/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class PreferenceSyncTest {
    private class Store : VaultStore {
        var bytes = VaultState().encode()
        override fun <T> locked(action: (VaultSession) -> T): T = action(object : VaultSession {
            override fun load() = VaultState.decode(bytes)
            override fun save(state: VaultState) { bytes = state.encode() }
        })
        fun state() = VaultState.decode(bytes)
    }
    private class Server : PasswordTransport {
        val parent = "users/me/birthdays/preference-fixture"
        val records = ArrayList<ByteArray>()
        var writes = 0
        var loseResponse = false
        var repeatToken = false
        var malformedPage = false
        var version = 0L
        fun entity(name: String, value: String): ByteArray = Proto.decode(PriorityPreference.create(name, value).encode())
            .replace(1, "$parent/priority_preferences/${PriorityPreference.tagFor(name)}".toByteArray())
            .replace(3, Proto.integer(1, ++version)).encode()
        override fun invoke(method: PasswordMethod, request: ByteArray): ByteArray {
            val body = Proto.decode(request)
            return when (method) {
                PasswordMethod.LIST_PREFERENCES -> {
                    assertEquals(parent, body.string(1)); assertNull(body.optional(3))
                    if (body.optional(4) != null) assertArrayEquals("page".toByteArray(), body.one(4))
                    if (malformedPage) Proto.field(1, Proto.field(4, "broken")) else
                        records.fold(byteArrayOf()) { all, record -> all + Proto.field(1, record) } +
                            (if (repeatToken) Proto.field(2, "page") else byteArrayOf())
                }
                PasswordMethod.CREATE_PREFERENCE, PasswordMethod.UPDATE_PREFERENCE -> {
                    val incoming = PriorityPreference.decode(body.one(2))
                    assertNull(body.optional(3)); assertNotNull(body.optional(4))
                    val old = records.indexOfFirst { PriorityPreference.decode(it).tag == incoming.tag }
                    if (method == PasswordMethod.CREATE_PREFERENCE) {
                        assertEquals(parent, body.string(1))
                        if (old >= 0) throw PasswordRpcException(6, false)
                    } else {
                        assertEquals(incoming.id, body.string(1))
                        if (old < 0 || !PriorityPreference.decode(records[old]).version!!.contentEquals(incoming.version!!))
                            throw PasswordRpcException(10, false)
                    }
                    val result = Proto.decode(incoming.encode()).replace(1,
                        "$parent/priority_preferences/${incoming.tag}".toByteArray()).replace(3, Proto.integer(1, ++version)).encode()
                    if (old < 0) records.add(result) else records[old] = result
                    writes++
                    if (loseResponse) throw PasswordRpcException(14, true)
                    result
                }
                else -> throw AssertionError("Unexpected preference RPC")
            }
        }
    }
    private fun engine(server: Server, store: Store) = PreferenceSync(PasswordApi(server), store)

    @Test fun createUpdateAndOfflineCoalescingUseThePriorityPreferenceWireLayout() {
        val server = Server(); val store = Store(); val sync = engine(server, store)
        sync.synchronize(server.parent)
        val first = sync.enqueue("credentials_enable_service", "true")
        assertEquals(first, sync.enqueue("credentials_enable_service", "false"))
        assertEquals("false", sync.value("credentials_enable_service"))
        assertEquals(1, sync.synchronize(server.parent).uploaded)
        sync.enqueue("credentials_enable_service", "true")
        assertEquals(1, sync.synchronize(server.parent).uploaded)
        assertEquals("true", engine(server, store).value("credentials_enable_service"))
        assertEquals(2, server.writes)
        assertTrue(store.state().mutations.isEmpty())
    }

    @Test fun lostCreateAndUpdateResponsesReconcileWithoutReplayingEitherEffect() {
        val server = Server(); val store = Store(); var sync = engine(server, store)
        sync.synchronize(server.parent)
        for (value in listOf("true", "false")) {
            sync.enqueue("credentials_enable_service", value)
            server.loseResponse = true
            assertThrows(PasswordRpcException::class.java) { sync.synchronize(server.parent) }
            assertEquals(MutationPhase.INDETERMINATE, store.state().preferenceMutations.single().phase)
            val writes = server.writes
            sync = engine(server, store)
            sync.synchronize(server.parent)
            assertEquals(writes, server.writes)
            assertTrue(store.state().preferenceMutations.isEmpty())
            assertEquals(value, sync.value("credentials_enable_service"))
        }
    }

    @Test fun concurrentRemoteEditRetainsTheLocalIntentAsAConflict() {
        val server = Server(); val store = Store(); val sync = engine(server, store)
        server.records.add(server.entity("credentials_enable_service", "true"))
        sync.synchronize(server.parent)
        sync.enqueue("credentials_enable_service", "false")
        server.records[0] = server.entity("credentials_enable_service", "null")
        assertEquals(1, sync.synchronize(server.parent).conflicts)
        assertEquals(0, server.writes)
        assertEquals("null", sync.value("credentials_enable_service"))
        assertEquals("false", PriorityPreference.decode(store.state().preferenceMutations.single().desired!!).value)
    }

    @Test fun incompleteSnapshotNeverReplacesPreviouslyAuthenticatedSettings() {
        val server = Server(); val store = Store(); val sync = engine(server, store)
        server.records.add(server.entity("credentials_enable_service", "true"))
        sync.synchronize(server.parent)
        val before = store.bytes.copyOf()
        server.repeatToken = true
        assertThrows(ProtocolException::class.java) { sync.synchronize(server.parent) }
        assertArrayEquals(before, store.bytes)
        server.repeatToken = false; server.malformedPage = true
        assertThrows(ProtocolException::class.java) { sync.synchronize(server.parent) }
        assertArrayEquals(before, store.bytes)
    }

    @Test fun oldVaultsMigrateAndNewJournalStateCarriesAnOldWriterFence() {
        val old = Proto.integer(1, 1) + Proto.integer(5, 123L)
        assertEquals(123L, VaultState.decode(old).lastSyncMillis)
        val server = Server(); val store = Store(); val sync = engine(server, store)
        sync.synchronize(server.parent)
        sync.enqueue("credentials_enable_service", "true")
        assertEquals(2L, Proto.decode(store.bytes).integer(1))
        val reopened = VaultState.decode(store.bytes)
        assertEquals(1, reopened.preferenceMutations.size)
        assertEquals("true", engine(server, store).value("credentials_enable_service"))
        assertThrows(ProtocolException::class.java) { VaultState.decode(Proto.integer(1, 3)) }
    }

    @Test fun unknownFieldsSurviveValueEditsAndAChangedVaultCannotReceiveOldQueuedSettings() {
        val source = Proto.decode(PriorityPreference.create("fixture", "true").encode()).replace(500, byteArrayOf(4, 5)).encode()
        assertArrayEquals(byteArrayOf(4, 5), Proto.decode(PriorityPreference.decode(source).withValue("false").encode()).one(500))
        val server = Server(); val store = Store(); val sync = engine(server, store)
        sync.synchronize(server.parent)
        sync.enqueue("fixture", "true")
        val before = store.bytes.copyOf()
        assertThrows(VaultResetException::class.java) { sync.synchronize("users/me/birthdays/replacement") }
        assertArrayEquals(before, store.bytes)
        assertEquals(0, server.writes)
    }
}
