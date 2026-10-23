/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SharingIncomingConsumerTest {
    private val parent = "users/me/birthdays/fixture"
    private val name = "$parent/incomingInvitations/fixture"
    private val guid = UUID.randomUUID().toString()
    private class Store : VaultStore {
        var bytes = VaultState().encode()
        override fun <T> locked(action: (VaultSession) -> T): T = action(object : VaultSession {
            override fun load() = VaultState.decode(bytes)
            override fun save(state: VaultState) { bytes = state.encode() }
        })
    }
    private class Destination : PasswordTransferEndpoint {
        val records = HashMap<String, ByteArray>()
        var inserts = 0; var pending = false; var loseInsert = false
        var generation = "fixture"
        private fun checkGeneration() { if (generation != "fixture") throw VaultResetException() }
        override fun refresh(): SyncReceipt { checkGeneration(); return SyncReceipt(records.size, 0, 0, 0) }
        override fun committed(tag: String) = records[tag]
        override fun pending(tag: String) = pending
        override fun insertIfAbsent(chrome: ByteArray) {
            checkGeneration()
            val tag = PasswordData.decode(Proto.decode(chrome).one(1)).tag()
            if (records[tag] != null) throw ConflictException()
            records[tag] = chrome.copyOf(); inserts++
            if (loseInsert) { loseInsert = false; throw PasswordRpcException(14, true) }
        }
        override fun deleteIfUnchanged(chrome: ByteArray) { error("Receiving must never delete a password") }
    }
    private fun password(secret: String = "synthetic") = Proto.field(1,
        PasswordData.create("https://example.invalid/", "https://example.invalid/", "fixture", secret, 1000).encode())
    private fun resource(): ByteArray {
        val sender = Proto.field(1, "fixture-sender") + Proto.field(3, Proto.integer(1, 1) +
            Proto.field(2, SharingCrypto.publicKey(ByteArray(32) { 1 })))
        return Proto.field(1, name) + Proto.field(5, Proto.field(1, guid) + Proto.field(2, sender) +
            Proto.field(3, ByteArray(48) { 2 }) + Proto.integer(6, 1))
    }
    private fun prepare(store: Store, kind: SharingOperationKind = SharingOperationKind.ACCEPT): String {
        val operation = SharingOperation(UUID.randomUUID().toString(), kind, SharingOperationPhase.PREPARED, parent, name,
            SharingOperation.digest(resource()), Proto.field(3, byteArrayOf(9)), if (kind == SharingOperationKind.ACCEPT) listOf(password()) else emptyList())
        return SharingJournal(store).prepare(operation)
    }

    @Test fun deleteFollowsVerifiedImportAndAnUncertainDeleteReconcilesWithoutReimporting() {
        val store = Store(); val destination = Destination(); val id = prepare(store)
        var deleted = false; var deletes = 0
        val api = PasswordSharingApi(PasswordTransport { method, request -> when (method) {
            PasswordMethod.GET_METADATA -> Proto.field(1, parent)
            PasswordMethod.LIST_INCOMING_SHARING -> if (deleted) byteArrayOf() else Proto.field(1, resource())
            PasswordMethod.DELETE_INCOMING_SHARING -> {
                assertEquals(1, destination.records.size); assertEquals(name, Proto.decode(request).string(1))
                deletes++; deleted = true; throw PasswordRpcException(14, true)
            }
            else -> error("Unexpected RPC")
        } })
        assertThrows(PasswordRpcException::class.java) { SharingIncomingConsumer(api, SharingJournal(store), destination).resume(id) }
        assertEquals(SharingOperationPhase.INDETERMINATE, SharingJournal(store).get(id).phase)
        SharingIncomingConsumer(api, SharingJournal(store), destination).resume(id)
        assertEquals(1, destination.inserts); assertEquals(1, deletes)
        assertEquals(SharingOperationPhase.COMPLETE, SharingJournal(store).get(id).phase)
    }

    @Test fun anUncertainImportNeverAcknowledgesTheInvitationUntilCommittedCopyIsObserved() {
        val store = Store(); val destination = Destination(); val id = prepare(store); destination.loseInsert = true
        var deletes = 0; var deleted = false
        val api = PasswordSharingApi(PasswordTransport { method, _ -> when (method) {
            PasswordMethod.GET_METADATA -> Proto.field(1, parent)
            PasswordMethod.LIST_INCOMING_SHARING -> if (deleted) byteArrayOf() else Proto.field(1, resource())
            PasswordMethod.DELETE_INCOMING_SHARING -> { deletes++; deleted = true; byteArrayOf() }
            else -> error("Unexpected RPC")
        } })
        assertThrows(PasswordRpcException::class.java) { SharingIncomingConsumer(api, SharingJournal(store), destination).resume(id) }
        assertEquals(0, deletes)
        SharingIncomingConsumer(api, SharingJournal(store), destination).resume(id)
        assertEquals(1, destination.inserts); assertEquals(1, deletes)
    }

    @Test fun conflictingOrPendingDestinationNeverOverwritesOrDeletesTheIncomingSource() {
        for (pending in listOf(false, true)) {
            val store = Store(); val destination = Destination(); val id = prepare(store)
            destination.pending = pending
            if (!pending) destination.records[PasswordData.decode(Proto.decode(password()).one(1)).tag()] = password("different")
            var deletions = 0
            val api = PasswordSharingApi(PasswordTransport { method, _ -> when (method) {
                PasswordMethod.GET_METADATA -> Proto.field(1, parent)
                PasswordMethod.DELETE_INCOMING_SHARING -> { deletions++; error("Unsafe acknowledgement") }
                else -> Proto.field(1, resource())
            } })
            assertThrows(Exception::class.java) { SharingIncomingConsumer(api, SharingJournal(store), destination).resume(id) }
            assertEquals(0, deletions); assertEquals(0, destination.inserts)
        }
    }

    @Test fun ownerDismissDoesNotImportAndChangedInvitationOrVaultCannotBeAcknowledged() {
        val store = Store(); val destination = Destination(); val id = prepare(store, SharingOperationKind.DISMISS)
        var deletes = 0; var deleted = false
        val api = PasswordSharingApi(PasswordTransport { method, _ -> when (method) {
            PasswordMethod.GET_METADATA -> Proto.field(1, parent)
            PasswordMethod.LIST_INCOMING_SHARING -> if (deleted) byteArrayOf() else Proto.field(1, resource())
            PasswordMethod.DELETE_INCOMING_SHARING -> { deletes++; deleted = true; byteArrayOf() }
            else -> error("Unexpected RPC")
        } })
        SharingIncomingConsumer(api, SharingJournal(store), destination).resume(id)
        assertEquals(0, destination.inserts); assertEquals(1, deletes)
        val resetStore = Store(); val resetId = prepare(resetStore)
        val resetApi = PasswordSharingApi(PasswordTransport { _, _ -> Proto.field(1, "users/me/birthdays/reset") })
        assertThrows(VaultResetException::class.java) { SharingIncomingConsumer(resetApi, SharingJournal(resetStore), destination).resume(resetId) }
        assertEquals(0, destination.inserts)
    }

    @Test fun vaultResetBetweenMetadataReadAndAtomicImportCannotReceiveOldInvitationData() {
        val store = Store(); val destination = Destination(); val id = prepare(store)
        var deletes = 0
        val api = PasswordSharingApi(PasswordTransport { method, _ -> when (method) {
            PasswordMethod.GET_METADATA -> { destination.generation = "reset"; Proto.field(1, parent) }
            PasswordMethod.DELETE_INCOMING_SHARING -> { deletes++; error("Old invitation must remain") }
            else -> Proto.field(1, resource())
        } })
        assertThrows(VaultResetException::class.java) { SharingIncomingConsumer(api, SharingJournal(store), destination).resume(id) }
        assertEquals(0, destination.inserts); assertEquals(0, deletes)
        assertEquals(SharingOperationPhase.PREPARED, SharingJournal(store).get(id).phase)
    }
}
