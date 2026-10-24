/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PasswordAcceptanceCleanupTest {
    private val parent = "users/me/birthdays/acceptance-test"
    private class Store : VaultStore {
        var raw = VaultState().encode()
        fun state() = VaultState.decode(raw)
        override fun <T> locked(action: (VaultSession) -> T): T = action(object : VaultSession {
            override fun load() = state()
            override fun save(state: VaultState) { raw = state.encode() }
        })
    }
    @Test fun accountReplacementDuringActualRepositoryCaptureCannotReleaseTheCapturedObject() {
        var current = "selected-gaia"; var released = false
        assertThrows(IllegalStateException::class.java) {
            PasswordAcceptanceSelection.capture({ check(current == "selected-gaia") }) {
                current = "replacement-gaia"; Any()
            }.also { released = true }
        }
        assertFalse(released)
    }
    @Test fun realUnknownDeleteRetainsCommittedRecordUntilAuthenticatedAbsenceWithoutASecondDelete() {
        val key = NigoriKey.derive("fixture-key".toByteArray())
        val rawKey = "fixture-keystore-key".toByteArray()
        val wrapper = NigoriKey.derive(Base64.getEncoder().encode(rawKey))
        val metadata = Proto.field(1, parent) + Proto.field(2, rawKey) + Proto.field(3,
            Proto.field(1, key.encrypt(Proto.field(2, key.export()))) + Proto.integer(2, 2) + Proto.field(3, wrapper.encrypt(key.export())))
        val data = PasswordData.create("https://example.invalid/", "https://example.invalid/", "fixture", "synthetic", 1234)
        val tag = data.tag()
        val record = Proto.field(1, "$parent/passwords/fixture") + Proto.field(2, tag) + Proto.field(5, Proto.integer(1, 1)) + Proto.field(6, key.encrypt(data.encode()))
        var exists = true; var deletes = 0
        val transport = PasswordTransport { method, _ -> when (method) {
            PasswordMethod.GET_METADATA -> metadata
            PasswordMethod.LIST -> if (exists) Proto.field(1, record) else byteArrayOf()
            PasswordMethod.DELETE -> { deletes++; throw PasswordRpcException(14, true) }
            else -> throw AssertionError("Unexpected fixture RPC")
        } }
        val store = Store(); val sync = VaultSync(PasswordApi(transport), store)
        sync.synchronize { VaultKeys.unlock(it) }; sync.enqueue(MutationKind.DELETE, tag)
        assertThrows(PasswordRpcException::class.java) { sync.synchronize { VaultKeys.unlock(it) } }
        assertEquals(MutationPhase.INDETERMINATE, store.state().mutations.single().phase)
        assertEquals(1, store.state().records.size) // The optimistic DELETE does not prove server absence.
        val endpoint = object : PasswordTransferEndpoint {
            override fun refresh() = sync.synchronize { VaultKeys.unlock(it) }
            override fun pending(tag: String) = store.state().mutations.any { it.tag == tag }
            override fun committed(tag: String) = store.state().records.singleOrNull { Proto.decode(it).string(2) == tag }
            override fun insertIfAbsent(chrome: ByteArray) = throw AssertionError()
            override fun deleteIfUnchanged(chrome: ByteArray) = throw AssertionError()
        }
        assertThrows(IllegalStateException::class.java) { PasswordAcceptanceCleanup.requireAbsent(endpoint, tag) }
        endpoint.refresh()
        assertThrows(IllegalStateException::class.java) { PasswordAcceptanceCleanup.requireAbsent(endpoint, tag) }
        assertEquals(1, deletes)
        exists = false; endpoint.refresh()
        PasswordAcceptanceCleanup.requireAbsent(endpoint, tag)
        assertEquals(1, deletes)
    }
    @Test fun preSendCleanupDropsOnlyADurablyQueuedCloneAndRetainsLaunchedEvidenceAcrossReopen() {
        val store = Store(); store.raw = VaultState(metadata = Proto.field(1, parent)).encode()
        val sync = VaultSync(PasswordApi(PasswordTransport { _, _ -> throw AssertionError("No RPC allowed") }), store)
        val desired = Proto.field(2, "fixture")
        sync.enqueue(MutationKind.CREATE, "fixture", desired)
        store.locked { PasswordAcceptanceCleanup.dropQueuedClone(it, "fixture", parent) }
        assertTrue(store.state().mutations.isEmpty())
        val id = sync.enqueue(MutationKind.CREATE, "fixture", desired)
        store.locked { session -> val state = session.load(); state.mutations[0] = state.mutations[0].phase(MutationPhase.LAUNCHED); session.save(state) }
        assertThrows(IllegalStateException::class.java) { store.locked { PasswordAcceptanceCleanup.dropQueuedClone(it, "fixture", parent) } }
        assertEquals(id, store.state().mutations.single().id); assertEquals(MutationPhase.LAUNCHED, store.state().mutations.single().phase)
        assertThrows(IllegalStateException::class.java) { store.locked { PasswordAcceptanceCleanup.dropQueuedClone(it, "fixture", "users/me/birthdays/other") } }
    }
    @Test fun stageFourRecoveryRequiresOneExactlyMatchingAcknowledgedSendAndNeverDispatches() {
        val original = byteArrayOf(1, 2, 3); val tag = "fixture-tag"; val recipient = "synthetic-recipient"
        val state = Proto.decode(Proto.field(1, parent) + Proto.field(4, tag) + Proto.field(11, recipient) + Proto.field(13, original) + Proto.integer(9, 4))
        val operation = SharingOperation("00000000-0000-0000-0000-000000000001", SharingOperationKind.SEND, SharingOperationPhase.COMPLETE,
            parent, "$tag:$recipient", SharingOperation.digest(original), byteArrayOf())
        val recovered = PasswordAcceptanceCleanup.acknowledgedSend(state, listOf(operation))
        assertEquals(operation.id, recovered.string(8)); assertEquals(5L, recovered.integer(9))
        assertThrows(PendingMutationException::class.java) { PasswordAcceptanceCleanup.acknowledgedSend(state, emptyList()) }
        assertThrows(PendingMutationException::class.java) { PasswordAcceptanceCleanup.acknowledgedSend(state, listOf(operation.withPhase(SharingOperationPhase.INDETERMINATE))) }
        assertThrows(PendingMutationException::class.java) { PasswordAcceptanceCleanup.acknowledgedSend(state, listOf(operation, operation)) }
        assertThrows(PendingMutationException::class.java) { PasswordAcceptanceCleanup.acknowledgedSend(state.replace(13, byteArrayOf(4)), listOf(operation)) }
        assertThrows(PendingMutationException::class.java) { PasswordAcceptanceCleanup.acknowledgedSend(state.replace(11, "different".toByteArray()), listOf(operation)) }
    }
}
