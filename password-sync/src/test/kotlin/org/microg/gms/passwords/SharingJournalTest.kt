/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SharingJournalTest {
    private val parent = "users/me/birthdays/fixture"
    private class Store : VaultStore {
        var bytes = VaultState().encode()
        override fun <T> locked(action: (VaultSession) -> T): T = action(object : VaultSession {
            override fun load() = VaultState.decode(bytes)
            override fun save(state: VaultState) { bytes = state.encode() }
        })
    }
    private fun operation(kind: SharingOperationKind = SharingOperationKind.SEND) = SharingOperation(UUID.randomUUID().toString(), kind,
        SharingOperationPhase.PREPARED, parent, "fixture-source", ByteArray(32) { 1 }, Proto.field(1, "synthetic-ciphertext"),
        if (kind == SharingOperationKind.ACCEPT) listOf(Proto.field(1,
            PasswordData.create("https://example.invalid/", "https://example.invalid/", "fixture", "synthetic", 1000).encode())) else emptyList())

    @Test fun uncertainSendIsFencedAcrossProcessReopeningAndCannotBeReplayedOrReset() {
        val store = Store(); val original = operation(); SharingJournal(store).prepare(original)
        var sends = 0
        val api = PasswordSharingApi(PasswordTransport { method, _ -> when (method) {
            PasswordMethod.GET_METADATA -> Proto.field(1, parent)
            PasswordMethod.CREATE_OUTGOING_SHARING -> { sends++; throw PasswordRpcException(14, true) }
            else -> error("Unexpected RPC")
        } })
        assertThrows(PasswordRpcException::class.java) { SharingSender(api, SharingJournal(store)).launch(original.id) }
        assertEquals(SharingOperationPhase.INDETERMINATE, SharingJournal(store).get(original.id).phase)
        assertThrows(PendingMutationException::class.java) { SharingSender(api, SharingJournal(store)).launch(original.id) }
        assertThrows(PendingMutationException::class.java) { SharingJournal(store).transition(original.id,
            setOf(SharingOperationPhase.INDETERMINATE), SharingOperationPhase.PREPARED) }
        assertEquals(1, sends)
    }

    @Test fun opaqueSuccessfulReceiptNeverClaimsRecipientAcceptance() {
        val store = Store(); val original = operation(); SharingJournal(store).prepare(original)
        val receipt = Proto.integer(1, 7)
        val api = PasswordSharingApi(PasswordTransport { method, _ -> when (method) {
            PasswordMethod.GET_METADATA -> Proto.field(1, parent)
            PasswordMethod.CREATE_OUTGOING_SHARING -> receipt
            else -> error("Unexpected RPC")
        } })
        SharingSender(api, SharingJournal(store)).launch(original.id)
        val reopened = SharingJournal(store).get(original.id)
        assertEquals(SharingOperationPhase.INDETERMINATE, reopened.phase); assertArrayEquals(receipt, reopened.receipt)
    }

    @Test fun onlyAbsenceOfTheCommitErrorFieldRetiresTheSendAsInThePinnedConsumer() {
        for (receipt in listOf(byteArrayOf(), Proto.integer(10, 1), Proto.integer(1, 0), Proto.integer(1, 1), Proto.field(1, byteArrayOf()))) {
            val store = Store(); val original = operation(); SharingJournal(store).prepare(original)
            var sends = 0
            val api = PasswordSharingApi(PasswordTransport { method, _ -> when (method) {
                PasswordMethod.GET_METADATA -> Proto.field(1, parent)
                PasswordMethod.CREATE_OUTGOING_SHARING -> { sends++; receipt }
                else -> error("Unexpected RPC")
            } })
            SharingSender(api, SharingJournal(store)).launch(original.id)
            val reopened = SharingJournal(store).get(original.id)
            val expected = if (receipt.isEmpty() || receipt.contentEquals(Proto.integer(10, 1))) SharingOperationPhase.COMPLETE
                else SharingOperationPhase.INDETERMINATE
            assertEquals(expected, reopened.phase); assertArrayEquals(receipt, reopened.receipt)
            assertThrows(PendingMutationException::class.java) { SharingSender(api, SharingJournal(store)).launch(original.id) }
            assertEquals(1, sends)
        }
    }

    @Test fun ownerResolutionPreservesTheUnknownAttemptAndRequiresAFreshExplicitSend() {
        val store = Store(); val original = operation(); val journal = SharingJournal(store)
        val receipt = Proto.integer(1, 0)
        journal.prepare(original)
        journal.transition(original.id, setOf(SharingOperationPhase.PREPARED), SharingOperationPhase.LAUNCHED)
        journal.transition(original.id, setOf(SharingOperationPhase.LAUNCHED), SharingOperationPhase.INDETERMINATE, receipt)
        assertThrows(PendingMutationException::class.java) { journal.prepare(operation()) }
        assertThrows(ConflictException::class.java) { journal.resolveUnknownSend(original.id, false, 1000) {} }
        assertThrows(VaultLockedException::class.java) { journal.resolveUnknownSend(original.id, true, 1000) { throw VaultLockedException() } }
        assertEquals(SharingOperationPhase.INDETERMINATE, journal.get(original.id).phase)
        journal.resolveUnknownSend(original.id, true, 1000) {}
        val reopened = SharingJournal(store).get(original.id)
        assertEquals(SharingOperationPhase.OWNER_RESOLVED, reopened.phase); assertEquals(1000L, reopened.ownerResolvedAtMillis)
        assertArrayEquals(original.request, reopened.request); assertArrayEquals(receipt, reopened.receipt)
        assertArrayEquals(original.sourceDigest, reopened.sourceDigest); assertEquals(parent, reopened.parent)
        var requests = 0
        val api = PasswordSharingApi(PasswordTransport { _, _ -> requests++; error("An acknowledged attempt must never be replayed") })
        assertThrows(PendingMutationException::class.java) { SharingSender(api, SharingJournal(store)).launch(original.id) }
        assertThrows(PendingMutationException::class.java) { journal.transition(original.id,
            setOf(SharingOperationPhase.OWNER_RESOLVED), SharingOperationPhase.PREPARED) }
        assertEquals(0, requests)
        val fresh = operation(); journal.prepare(fresh)
        assertEquals(SharingOperationPhase.PREPARED, journal.get(fresh.id).phase)
    }

    @Test fun aCrashAfterLaunchCanBeAcknowledgedButIncomingAndCompletedOperationsCannot() {
        val store = Store(); val journal = SharingJournal(store); val original = operation()
        journal.prepare(original); journal.transition(original.id, setOf(SharingOperationPhase.PREPARED), SharingOperationPhase.LAUNCHED)
        SharingJournal(store).resolveUnknownSend(original.id, true, 1000) {}
        val incoming = operation(SharingOperationKind.DISMISS); journal.prepare(incoming)
        assertThrows(PendingMutationException::class.java) { journal.resolveUnknownSend(incoming.id, true, 1000) {} }
        journal.transition(incoming.id, setOf(SharingOperationPhase.PREPARED), SharingOperationPhase.COMPLETE)
        assertThrows(PendingMutationException::class.java) { journal.resolveUnknownSend(incoming.id, true, 1000) {} }
        assertThrows(PendingMutationException::class.java) { journal.resolveUnknownSend(original.id, true, 1001) {} }
    }

    @Test fun aPreparedSendInterruptedBeforeLaunchCanBeClosedWithoutSendingIt() {
        val store = Store(); val journal = SharingJournal(store); val original = operation()
        journal.prepare(original)
        SharingJournal(store).resolveUnknownSend(original.id, true, 1000) {}
        assertEquals(SharingOperationPhase.OWNER_RESOLVED, SharingJournal(store).get(original.id).phase)
        journal.prepare(operation())
    }

    @Test fun changedVaultOrRevokedOwnerCannotLaunchAPreparedSend() {
        val store = Store(); val original = operation(); SharingJournal(store).prepare(original)
        var sends = 0
        val api = PasswordSharingApi(PasswordTransport { method, _ -> when (method) {
            PasswordMethod.GET_METADATA -> Proto.field(1, "users/me/birthdays/other")
            else -> { sends++; error("A changed vault must not receive an invitation") }
        } })
        assertThrows(VaultResetException::class.java) { SharingSender(api, SharingJournal(store)).launch(original.id) }
        assertThrows(VaultLockedException::class.java) { SharingSender(api, SharingJournal(store)).launch(original.id) { throw VaultLockedException() } }
        assertEquals(0, sends); assertEquals(SharingOperationPhase.PREPARED, SharingJournal(store).get(original.id).phase)
    }

    @Test fun definiteServerRefusalIsDistinctFromUncertaintyAndAccountJournalsStayIsolated() {
        val store = Store(); val original = operation(); SharingJournal(store).prepare(original)
        val api = PasswordSharingApi(PasswordTransport { method, _ -> if (method == PasswordMethod.GET_METADATA) Proto.field(1, parent)
            else throw PasswordRpcException(7, false) })
        assertThrows(PasswordRpcException::class.java) { SharingSender(api, SharingJournal(store)).launch(original.id) }
        assertEquals(SharingOperationPhase.REFUSED, SharingJournal(store).get(original.id).phase)
        assertTrue(SharingJournal(Store()).operations().isEmpty())
    }

    @Test fun acceptAndDismissCannotRunConcurrentOperationsForTheSameInvitation() {
        val store = Store(); val original = operation(SharingOperationKind.ACCEPT); SharingJournal(store).prepare(original)
        assertThrows(PendingMutationException::class.java) { SharingJournal(store).prepare(operation(SharingOperationKind.DISMISS)) }
        assertEquals(1, SharingJournal(store).operations().size)
    }

    @Test fun boundedTerminalReceiptRetentionNeverPrunesAnUnknownLaunch() {
        val store = Store(); val journal = SharingJournal(store); val unknown = operation()
        journal.prepare(unknown)
        journal.transition(unknown.id, setOf(SharingOperationPhase.PREPARED), SharingOperationPhase.LAUNCHED)
        journal.transition(unknown.id, setOf(SharingOperationPhase.LAUNCHED), SharingOperationPhase.INDETERMINATE)
        repeat(140) {
            val done = operation(SharingOperationKind.DISMISS)
            val otherSource = SharingOperation(done.id, done.kind, done.phase, done.parent, "completed-$it", done.sourceDigest, done.request)
            journal.prepare(otherSource)
            journal.transition(otherSource.id, setOf(SharingOperationPhase.PREPARED), SharingOperationPhase.COMPLETE)
        }
        assertEquals(SharingOperationPhase.INDETERMINATE, SharingJournal(store).get(unknown.id).phase)
        assertTrue(journal.operations().size <= 34)
    }
}
