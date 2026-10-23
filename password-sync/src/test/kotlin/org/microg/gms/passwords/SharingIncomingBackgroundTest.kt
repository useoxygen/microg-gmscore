/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SharingIncomingBackgroundTest {
    private val parent = "users/me/birthdays/fixture"
    private class Store : VaultStore {
        var bytes = VaultState().encode()
        override fun <T> locked(action: (VaultSession) -> T): T = action(object : VaultSession {
            override fun load() = VaultState.decode(bytes)
            override fun save(state: VaultState) { bytes = state.encode() }
        })
    }
    private class Destination : PasswordTransferEndpoint {
        val records = HashMap<String, ByteArray>()
        var inserts = 0; var reads = 0; var failRead = false; var failWrite = false; var loseWrite = false; var pending = false
        var generation = "fixture"; var beforeInsert: () -> Unit = {}; var checkAccess: () -> Unit = {}
        var storedForm: (ByteArray) -> ByteArray = { it.copyOf() }
        private fun check() { checkAccess(); if (generation != "fixture") throw VaultResetException() }
        override fun refresh(): SyncReceipt { check(); reads++; if (failRead) throw PasswordRpcException(14, false); return SyncReceipt(records.size, 0, 0, 0) }
        override fun committed(tag: String): ByteArray? { check(); return records[tag] }
        override fun pending(tag: String): Boolean { check(); return pending }
        override fun insertIfAbsent(chrome: ByteArray) {
            beforeInsert(); check()
            if (failWrite) throw PasswordRpcException(14, false)
            val tag = PasswordData.decode(Proto.decode(chrome).one(1)).tag()
            if (records.containsKey(tag) || pending) throw PendingMutationException()
            records[tag] = storedForm(chrome); inserts++
            if (loseWrite) { loseWrite = false; throw PasswordRpcException(14, true) }
        }
        override fun deleteIfUnchanged(chrome: ByteArray) { error("Receiving must never delete a password") }
    }
    private inner class Fixture {
        val store = Store(); val journal = SharingJournal(store); val destination = Destination()
        val senderPrivate = ByteArray(32) { (it + 1).toByte() }; val receiverPrivate = ByteArray(32) { (it + 33).toByte() }
        val resources = LinkedHashMap<String, ByteArray>()
        var deletes = 0; var notifications = 0; var loseDelete = false; var retainOnLostDelete = false; var notificationFails = false
        var authorized = true; var metadataParent = parent; var beforeDelete: () -> Unit = {}
        private val wrapping = NigoriKey(ByteArray(16) { 1 }, ByteArray(16) { 2 })
        private val bag = Proto.field(2, wrapping.export()) + Proto.field(3, Proto.integer(1, 7) + Proto.field(2, receiverPrivate))
        val metadata = Proto.field(1, parent) + Proto.field(3, Proto.field(1, wrapping.encrypt(bag)) + Proto.integer(2, 4))
        private val vaultKeys = VaultKeys.unlock(metadata, customKey = wrapping.export())
        val keys = PasswordSharingKeys.read(metadata, vaultKeys)
        init { vaultKeys.destroy(); wrapping.destroy(); destination.checkAccess = ::check }
        fun check() { if (!authorized) throw VaultLockedException() }
        val api = PasswordSharingApi(PasswordTransport { method, request ->
            check()
            when (method) {
                PasswordMethod.GET_METADATA -> if (metadataParent == parent) metadata else Proto.field(1, metadataParent)
                PasswordMethod.LIST_INCOMING_SHARING -> resources.values.fold(byteArrayOf()) { out, raw -> out + Proto.field(1, raw) }
                PasswordMethod.DELETE_INCOMING_SHARING -> {
                    beforeDelete(); deletes++
                    if (!loseDelete || !retainOnLostDelete) resources.remove(Proto.decode(request).string(1))
                    if (loseDelete) { loseDelete = false; throw PasswordRpcException(14, true) }
                    byteArrayOf()
                }
                else -> error("Unexpected RPC: $method")
            }
        })
        fun group(secret: String = "secret", invalidSibling: Boolean = false): ByteArray {
            val element = Proto.integer(2, 0) + Proto.field(3, "https://example.invalid/") + Proto.field(4, "https://example.invalid/login")
            return Proto.field(2, Proto.field(1, "fixture") + Proto.field(2, secret) + Proto.field(3, element) +
                if (invalidSibling) Proto.field(3, Proto.decode(element).replace(4, byteArrayOf()).encode()) else byteArrayOf())
        }
        fun add(plaintext: ByteArray = group(), version: Long = 7, corrupt: Boolean = false): SharingInvitation {
            val name = "$parent/incomingInvitations/${resources.size}"
            val sender = Proto.field(1, "sender") + Proto.field(2, Proto.field(1, "sender@example.invalid") +
                Proto.field(2, "Fixture sender") + Proto.field(3, "https://images.invalid/avatar")) +
                Proto.field(3, Proto.integer(1, 7) + Proto.field(2, SharingCrypto.publicKey(senderPrivate)))
            val ciphertext = SharingCrypto.seal(plaintext, senderPrivate, SharingCrypto.publicKey(receiverPrivate))
            if (corrupt) ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
            val raw = Proto.field(1, name) + Proto.field(5, Proto.field(1, UUID.randomUUID().toString()) + Proto.field(2, sender) +
                Proto.field(3, ciphertext) + Proto.integer(6, version))
            resources[name] = raw
            return SharingInvitation.decode(raw, parent)
        }
        fun decode(invitation: SharingInvitation) = invitation.passwordBatch(keys, 2000)
        fun run(decoder: (SharingInvitation) -> SharingPasswordBatch = ::decode): Int =
            SharingIncomingBackground(api, journal, destination, ::check) { operation ->
                assertEquals(SharingOperationPhase.COMPLETE, operation.phase)
                notifications++; if (notificationFails) throw IllegalStateException("Notification unavailable")
            }.synchronize(parent, byteArrayOf(9), decoder)
    }
    private fun fixture(action: (Fixture) -> Unit) { val value = Fixture(); try { action(value) } finally { value.keys.destroy() } }

    @Test fun aNewShareImportsAutomaticallyAndOnlyThenAcknowledgesAndNotifies() = fixture { f ->
        f.add(f.group(invalidSibling = true))
        f.beforeDelete = {
            assertEquals(1, f.destination.records.size)
            assertEquals(SharingOperationPhase.LAUNCHED, f.journal.operations().single().phase)
            assertEquals(0, f.notifications)
        }
        assertEquals(1, f.run()); assertEquals(1, f.deletes); assertEquals(1, f.notifications); assertTrue(f.resources.isEmpty())
        val operation = SharingJournal(f.store).operations().single()
        assertEquals(SharingOperationKind.AUTOMATIC, operation.kind); assertEquals(1, operation.invalidPasswordCount); assertNotNull(operation.invitationGuid)
        assertEquals(SharingOperationPhase.COMPLETE, operation.phase)
        val imported = Proto.decode(Proto.decode(f.destination.records.values.single()).one(1))
        assertEquals("https://images.invalid/avatar", imported.string(27)); assertEquals(0L, imported.integer(26))
    }

    @Test fun samePasswordWithDifferentMetadataIsConsumedWithoutOverwritingOrNotifying() = fixture { f ->
        val incoming = f.decode(f.add()).passwords.single()
        val existing = Proto.field(1, Proto.decode(incoming.encode()).replaceInteger(13, 3).replace(24, "Other metadata".toByteArray()).encode())
        f.destination.records[incoming.tag()] = existing
        assertEquals(0, f.run()); assertEquals(1, f.deletes); assertEquals(0, f.destination.inserts); assertEquals(0, f.notifications)
        assertArrayEquals(existing, f.destination.records[incoming.tag()])
    }

    @Test fun manualSameSenderAndDifferentSenderConflictsAreAllIgnoredAndConsumedUnderTheDefault() {
        for (kind in listOf("manual", "same-sender", "different-sender")) fixture { f ->
            val incoming = f.decode(f.add()).passwords.single()
            var existing = Proto.decode(incoming.withPassword("existing-secret", 1000).encode())
            if (kind == "manual") existing = existing.replaceInteger(13, 3)
            if (kind == "different-sender") existing = existing.replace(23, "other@example.invalid".toByteArray())
            val chrome = Proto.field(1, existing.encode()); f.destination.records[incoming.tag()] = chrome
            assertEquals(0, f.run()); assertEquals(1, f.deletes); assertEquals(0, f.destination.inserts); assertEquals(0, f.notifications)
            assertArrayEquals(chrome, f.destination.records[incoming.tag()])
        }
    }

    @Test fun invalidOnlyAndEmptyGroupsAreConsumedOnlyAfterSuccessfulAuthenticatedStoreRead() {
        for (empty in listOf(false, true)) fixture { f ->
            f.add(if (empty) byteArrayOf() else f.group(secret = "a".repeat(1025)))
            assertEquals(0, f.run()); assertTrue(f.destination.reads > 0); assertEquals(1, f.deletes)
            assertEquals(if (empty) 0 else 1, SharingJournal(f.store).operations().single().invalidPasswordCount)
            assertEquals(0, f.notifications)
        }
        fixture { f ->
            f.add(f.group(secret = "a".repeat(1025))); f.destination.failRead = true
            assertThrows(PasswordRpcException::class.java) { f.run() }
            assertEquals(0, f.deletes); assertEquals(1, f.resources.size)
            assertEquals(SharingOperationPhase.PREPARED, SharingJournal(f.store).operations().single().phase)
        }
    }

    @Test fun missingVersionCorruptCiphertextAndMalformedPlaintextNeverAcknowledge() {
        for (kind in listOf("missing-key", "bad-auth", "malformed")) fixture { f ->
            f.add(if (kind == "malformed") byteArrayOf(0x80.toByte()) else f.group(), version = if (kind == "missing-key") 8 else 7, corrupt = kind == "bad-auth")
            assertThrows(Exception::class.java) { f.run() }
            assertEquals(0, f.deletes); assertEquals(0, f.destination.inserts); assertEquals(1, f.resources.size)
            assertTrue(f.journal.operations().isEmpty())
        }
    }

    @Test fun failedOrPendingImportsKeepTheInvitationAndTheirDurableCheckpoint() {
        for (pending in listOf(false, true)) fixture { f ->
            f.add(); f.destination.pending = pending; f.destination.failWrite = !pending
            assertThrows(Exception::class.java) { f.run() }
            assertEquals(0, f.deletes); assertEquals(0, f.destination.inserts); assertEquals(1, f.resources.size)
            assertEquals(SharingOperationPhase.PREPARED, SharingJournal(f.store).operations().single().phase)
        }
    }

    @Test fun lostImportOutcomeIsReconciledByCommittedReadWithoutAnotherInsert() = fixture { f ->
        f.add(); f.destination.loseWrite = true
        assertThrows(PasswordRpcException::class.java) { f.run() }
        assertEquals(0, f.deletes); assertEquals(1, f.destination.inserts)
        assertEquals(0, f.run()); assertEquals(1, f.deletes); assertEquals(1, f.destination.inserts)
    }

    @Test fun uncertainAcknowledgementStillPresentNeverReplaysEvenAfterReopening() = fixture { f ->
        f.add(); f.loseDelete = true; f.retainOnLostDelete = true
        assertThrows(PasswordRpcException::class.java) { f.run() }
        assertEquals(SharingOperationPhase.INDETERMINATE, SharingJournal(f.store).operations().single().phase)
        assertThrows(PendingMutationException::class.java) { f.run() }
        assertEquals(1, f.deletes); assertEquals(1, f.destination.inserts); assertEquals(0, f.notifications)
        val operation = f.journal.operations().single()
        assertThrows(PendingMutationException::class.java) { f.journal.transition(operation.id,
            setOf(SharingOperationPhase.INDETERMINATE), SharingOperationPhase.IMPORTED) }
    }

    @Test fun uncertainAcknowledgementAbsentReconcilesWithoutAnotherDeleteAndNotifiesOnce() = fixture { f ->
        f.add(); f.loseDelete = true
        assertThrows(PasswordRpcException::class.java) { f.run() }
        assertEquals(1, f.run()); assertEquals(1, f.deletes); assertEquals(1, f.notifications)
        assertEquals(SharingOperationPhase.COMPLETE, SharingJournal(f.store).operations().single().phase)
        assertEquals(0, f.run()); assertEquals(1, f.notifications)
    }

    @Test fun notificationFailureCannotRetainOrReplayTheCompletedReceive() = fixture { f ->
        f.add(); f.notificationFails = true
        assertEquals(1, f.run()); assertEquals(1, f.notifications); assertEquals(1, f.deletes)
        assertEquals(SharingOperationPhase.COMPLETE, SharingJournal(f.store).operations().single().phase)
        assertEquals(0, f.run()); assertEquals(1, f.notifications); assertEquals(1, f.destination.inserts)
    }

    @Test fun reorderedServerProtobufStillVerifiesEveryImportedField() = fixture { f ->
        f.add()
        f.destination.storedForm = { raw ->
            val data = Proto.decode(Proto.decode(raw).one(1))
            Proto.field(1, data.replaceInteger(1, data.integer(1) ?: 0).encode() + Proto.field(99, "server-metadata"))
        }
        assertEquals(1, f.run()); assertEquals(1, f.deletes); assertEquals(1, f.notifications)
    }

    @Test fun anInvitationRemovedElsewhereDoesNotImportItsOldPreparedPlaintext() = fixture { f ->
        f.add(); f.destination.failRead = true
        assertThrows(PasswordRpcException::class.java) { f.run() }
        f.destination.failRead = false; f.resources.clear()
        assertEquals(0, f.run()); assertEquals(0, f.destination.inserts); assertEquals(0, f.deletes)
        assertEquals(SharingOperationPhase.COMPLETE, SharingJournal(f.store).operations().single().phase)
    }

    @Test fun resetOrAccountRemovalAtAtomicImportCannotReceiveOrAcknowledgeOldData() {
        for (reset in listOf(false, true)) fixture { f ->
            f.add(); f.destination.beforeInsert = { if (reset) f.destination.generation = "reset" else f.authorized = false }
            assertThrows(Exception::class.java) { f.run() }
            assertEquals(0, f.deletes); assertEquals(0, f.destination.inserts); assertEquals(1, f.resources.size)
        }
    }

    @Test fun aPreparedReceiveCannotUseItsPlaintextCheckpointWhenTheKeyIsNoLongerAvailable() = fixture { f ->
        f.add(); f.destination.failRead = true
        assertThrows(PasswordRpcException::class.java) { f.run() }
        f.destination.failRead = false
        assertThrows(VaultLockedException::class.java) { f.run { throw VaultLockedException() } }
        assertEquals(0, f.deletes); assertEquals(0, f.destination.inserts)
        f.metadataParent = "users/me/birthdays/reset"
        assertThrows(VaultResetException::class.java) { f.run() }
        assertEquals(0, f.deletes)
    }
}
