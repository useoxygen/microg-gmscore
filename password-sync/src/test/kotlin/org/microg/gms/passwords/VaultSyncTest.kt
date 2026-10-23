/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class VaultSyncTest {
    private class Store : VaultStore {
        var persisted = VaultState().encode()
        var beforeSave: ((VaultState) -> Unit)? = null
        override fun <T> locked(action: (VaultSession) -> T): T = synchronized(this) {
            action(object : VaultSession {
                override fun load() = VaultState.decode(persisted)
                override fun save(state: VaultState) { beforeSave?.invoke(state); persisted = state.encode() }
            })
        }
        fun state() = VaultState.decode(persisted)
    }
    private class Server : PasswordTransport {
        val parent = "users/me/birthdays/test"
        val dataKey = NigoriKey.derive("data-key".toByteArray())
        private val raw = "keystore-test-key".toByteArray()
        private val wrapping = NigoriKey.derive(Base64.getEncoder().encode(raw))
        val metadata = Proto.field(1, parent) + Proto.field(2, raw) + Proto.field(3,
            Proto.field(1, dataKey.encrypt(Proto.field(2, dataKey.export()))) + Proto.integer(2, 2) +
                Proto.field(3, wrapping.encrypt(dataKey.export())))
        val records = ArrayList<ByteArray>()
        var writes = 0
        var loseCreateResponse = false
        var loseDeleteResponse = false
        var failList = false
        var repeatPageToken = false
        private var version = 0
        fun entity(tag: String, secret: String, name: String = "$parent/passwords/$tag"): ByteArray =
            Proto.field(1, name) + Proto.field(2, tag) + Proto.field(5, Proto.integer(1, (++version).toLong())) +
                Proto.field(6, dataKey.encrypt(Proto.field(8, secret)))
        override fun invoke(method: PasswordMethod, request: ByteArray): ByteArray = when (method) {
            PasswordMethod.GET_METADATA -> metadata
            PasswordMethod.LIST -> {
                if (failList) throw PasswordRpcException(14, false)
                records.fold(byteArrayOf()) { out, record -> out + Proto.field(1, record) } +
                    if (repeatPageToken) Proto.field(2, "repeated") else byteArrayOf()
            }
            PasswordMethod.CREATE -> {
                writes++
                val incoming = Proto.decode(Proto.decode(request).one(2))
                if (records.any { Proto.decode(it).string(2) == incoming.string(2) }) throw PasswordRpcException(6, false)
                val created = incoming.replace(1, "$parent/passwords/${incoming.string(2)}".toByteArray())
                    .replace(5, Proto.integer(1, (++version).toLong())).encode()
                records.add(created)
                if (loseCreateResponse) throw PasswordRpcException(14, true)
                created
            }
            PasswordMethod.BATCH_UPDATE -> {
                writes++
                val incoming = Proto.decode(Proto.decode(Proto.decode(request).one(1)).one(2))
                val index = records.indexOfFirst { Proto.decode(it).string(1) == incoming.string(1) }
                if (index < 0) throw PasswordRpcException(5, false)
                if (!Proto.decode(records[index]).one(5).contentEquals(incoming.one(5))) throw PasswordRpcException(10, false)
                val updated = incoming.replace(5, Proto.integer(1, (++version).toLong())).encode()
                records[index] = updated
                Proto.field(1, updated)
            }
            PasswordMethod.DELETE -> {
                writes++
                val name = Proto.decode(request).string(1)
                records.removeAll { Proto.decode(it).string(1) == name }
                if (loseDeleteResponse) throw PasswordRpcException(14, true)
                byteArrayOf()
            }
            else -> throw AssertionError(method)
        }
    }
    private fun engine(server: Server, store: Store) = VaultSync(PasswordApi(server), store) { 1234L }

    @Test fun normalCreateUpdateDeleteWithDurableReadback() {
        val server = Server(); val store = Store(); val engine = engine(server, store)
        engine.synchronize { VaultKeys.unlock(it) }
        engine.enqueue(MutationKind.CREATE, "fixture", server.entity("fixture", "first"))
        assertEquals(1, engine.synchronize { VaultKeys.unlock(it) }.uploaded)
        val base = store.state().records.single()
        val updated = Proto.decode(base).replace(6, server.dataKey.encrypt(Proto.field(8, "second"))).encode()
        engine.enqueue(MutationKind.UPDATE, "fixture", updated)
        assertEquals(1, engine.synchronize { VaultKeys.unlock(it) }.uploaded)
        engine.enqueue(MutationKind.DELETE, "fixture")
        assertEquals(1, engine.synchronize { VaultKeys.unlock(it) }.uploaded)
        assertTrue(server.records.isEmpty()); assertTrue(store.state().records.isEmpty())
        assertEquals(3, server.writes)
    }

    @Test fun lostCreateResponseReconcilesInFreshEngineWithoutReplay() {
        val server = Server(); val store = Store(); val first = engine(server, store)
        first.synchronize { VaultKeys.unlock(it) }
        first.enqueue(MutationKind.CREATE, "fixture", server.entity("fixture", "secret"))
        server.loseCreateResponse = true
        assertThrows(PasswordRpcException::class.java) { first.synchronize { VaultKeys.unlock(it) } }
        assertEquals(MutationPhase.INDETERMINATE, store.state().mutations.single().phase)
        val fresh = engine(server, store)
        fresh.synchronize { VaultKeys.unlock(it) }
        assertEquals(1, server.writes)
        assertTrue(store.state().mutations.isEmpty())
        assertEquals(1, store.state().records.size)
    }

    @Test fun launchFenceSurvivesProcessDeathBeforeTransport() {
        val server = Server(); val store = Store(); val first = engine(server, store)
        first.synchronize { VaultKeys.unlock(it) }
        first.enqueue(MutationKind.CREATE, "fixture", server.entity("fixture", "secret"))
        store.beforeSave = { state ->
            if (state.mutations.any { it.phase == MutationPhase.LAUNCHED }) {
                store.persisted = state.encode()
                throw SimulatedDeath()
            }
        }
        assertThrows(SimulatedDeath::class.java) { first.synchronize { VaultKeys.unlock(it) } }
        store.beforeSave = null
        val result = engine(server, store).synchronize { VaultKeys.unlock(it) }
        assertEquals(0, server.writes)
        assertEquals(1, result.indeterminate)
    }

    @Test fun remoteEditPreventsStaleLocalOverwrite() {
        val server = Server(); val store = Store(); val engine = engine(server, store)
        server.records.add(server.entity("fixture", "first"))
        engine.synchronize { VaultKeys.unlock(it) }
        val local = Proto.decode(store.state().records.single()).replace(6, server.dataKey.encrypt(Proto.field(8, "local"))).encode()
        engine.enqueue(MutationKind.UPDATE, "fixture", local)
        server.records[0] = server.entity("fixture", "remote")
        val result = engine.synchronize { VaultKeys.unlock(it) }
        assertEquals(1, result.conflicts); assertEquals(0, server.writes)
    }

    @Test fun failedOrLoopingPaginationPreservesPreviousSnapshot() {
        val server = Server(); val store = Store(); val engine = engine(server, store)
        server.records.add(server.entity("fixture", "first"))
        engine.synchronize { VaultKeys.unlock(it) }
        val before = store.persisted.copyOf()
        server.failList = true
        assertThrows(PasswordRpcException::class.java) { engine.synchronize { VaultKeys.unlock(it) } }
        assertArrayEquals(before, store.persisted)
        server.failList = false; server.repeatPageToken = true
        assertThrows(ProtocolException::class.java) { engine.synchronize { VaultKeys.unlock(it) } }
        assertArrayEquals(before, store.persisted)
    }
    @Test fun offlineEditsCoalesceAndUnsentCreateCanBeCancelled() {
        val server = Server(); val store = Store(); val engine = engine(server, store)
        engine.synchronize { VaultKeys.unlock(it) }
        val first = engine.enqueue(MutationKind.CREATE, "fixture", server.entity("fixture", "first"))
        val second = engine.enqueue(MutationKind.CREATE, "fixture", server.entity("fixture", "second"))
        assertEquals(first, second)
        assertEquals(1, store.state().mutations.size)
        engine.enqueue(MutationKind.DELETE, "fixture")
        assertTrue(store.state().mutations.isEmpty())
        engine.synchronize { VaultKeys.unlock(it) }
        assertEquals(0, server.writes)
    }
    @Test fun lostDeleteResponseReconcilesWithoutReplaying() {
        val server = Server(); val store = Store(); val engine = engine(server, store)
        server.records.add(server.entity("fixture", "first"))
        engine.synchronize { VaultKeys.unlock(it) }
        engine.enqueue(MutationKind.DELETE, "fixture")
        server.loseDeleteResponse = true
        assertThrows(PasswordRpcException::class.java) { engine.synchronize { VaultKeys.unlock(it) } }
        engine(server, store).synchronize { VaultKeys.unlock(it) }
        assertTrue(store.state().mutations.isEmpty())
        assertEquals(1, server.writes)
    }
    @Test fun staleEnqueueCannotReplaceANewerBaseOrUncertainOperation() {
        val server = Server(); val store = Store(); val engine = engine(server, store)
        server.records.add(server.entity("fixture", "first"))
        engine.synchronize { VaultKeys.unlock(it) }
        val stale = store.state().records.single()
        server.records[0] = server.entity("fixture", "remote")
        engine.synchronize { VaultKeys.unlock(it) }
        assertThrows(ConflictException::class.java) { engine.enqueue(MutationKind.UPDATE, "fixture", stale) }
        engine.enqueue(MutationKind.UPDATE, "fixture", store.state().records.single())
        store.persisted = store.state().also { it.mutations[0] = it.mutations[0].phase(MutationPhase.INDETERMINATE) }.encode()
        assertThrows(PendingMutationException::class.java) { engine.enqueue(MutationKind.DELETE, "fixture") }
        assertEquals(0, server.writes)
    }
    private class SimulatedDeath : Error()
}
