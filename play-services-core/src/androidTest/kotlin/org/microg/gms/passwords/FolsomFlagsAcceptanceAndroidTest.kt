/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class FolsomFlagsAcceptanceAndroidTest {
    private val run = "00000000-0000-0000-0000-000000000123"

    /** Explicit, cached-only discovery; absence of proof never opens an account picker or calls a provider. */
    @Test fun admitCachedSyntheticAccountForFlagsOnlyWithoutEnablingVault() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("passwordAcceptance") == "cyc687-cached-flags-selection")
        try {
            val selectedRun = arguments.getString("passwordAcceptanceRun") ?: throw SecurityException()
            FolsomFlagsAcceptanceAccount.admit(instrumentation.targetContext, selectedRun)
            instrumentation.sendStatus(2, Bundle().apply {
                putBoolean("flagsOnlyNativeSelectionReady", true)
                putBoolean("passwordVaultStateAndOptOutPreserved", true)
            })
        } catch (_: Exception) {
            instrumentation.sendStatus(2, Bundle().apply { putBoolean("flagsOnlyNativeSelectionReady", false) })
            throw AssertionError("Cached synthetic proof did not authorize flags-only selection; no account was enabled or provider called")
        }
    }

    @Test fun flagsReceiptCannotAuthorizePrimaryFamilyOrSurviveExpiryRebootOrAccountReplacement() {
        val raw = FolsomFlagsReceipt("synthetic-native-only", "synthetic-stable-id", 100, 4, run).encode()
        val receipt = FolsomFlagsReceipt.decode(raw)
        receipt.verify(run, 4, 200, "synthetic-native-only", "synthetic-stable-id")
        assertEquals("FolsomFlagsReceipt[redacted]", receipt.toString())
        assertThrows(Exception::class.java) { PasswordAcceptanceSelection.decode(raw) }
        for (role in listOf("primary", "family")) {
            val ordinary = PasswordAcceptanceSelection("synthetic-native-only", "synthetic-stable-id", 100, 4, run, role)
            assertThrows(Exception::class.java) { FolsomFlagsReceipt.decode(ordinary.encode()) }
        }
        for ((currentBoot, now) in listOf(5 to 200L, 4 to 99L, 4 to 1_800_100L)) {
            assertThrows(Exception::class.java) { receipt.verify(run, currentBoot, now, "synthetic-native-only", "synthetic-stable-id") }
        }
        assertThrows(Exception::class.java) { receipt.verify(run, 4, 200, "synthetic-native-only", "replacement-stable-id") }
        assertThrows(Exception::class.java) { receipt.verify("00000000-0000-0000-0000-000000000124", 4, 200, "synthetic-native-only", "synthetic-stable-id") }
        assertThrows(Exception::class.java) { FolsomFlagsReceipt.decode(raw + Proto.field(2, "another-id")) }
        assertThrows(Exception::class.java) { FolsomFlagsReceipt.decode(Proto.decode(raw).replace(7, "another-purpose".toByteArray()).encode()) }
        receipt.verify(run, 4, 1_800_099L, "synthetic-native-only", "synthetic-stable-id")
    }

    @Test fun selectionRefusesMissingAmbiguousUnreadableDuplicateAndTruncatedAccountProof() {
        fun account(index: Int) = PasswordAcceptanceAccounts.Selected(Account("synthetic-flags-$index", "com.google"), "synthetic-id-$index")
        val first = account(1); val second = account(2)
        assertSame(first, FolsomFlagsAcceptanceAccount.uniqueFixture(listOf(first, second)) { it === first })
        assertThrows(Exception::class.java) { FolsomFlagsAcceptanceAccount.uniqueFixture(emptyList()) { true } }
        assertThrows(Exception::class.java) { FolsomFlagsAcceptanceAccount.uniqueFixture(listOf(first, second)) { false } }
        assertThrows(Exception::class.java) { FolsomFlagsAcceptanceAccount.uniqueFixture(listOf(first, second)) { true } }
        assertThrows(Exception::class.java) {
            FolsomFlagsAcceptanceAccount.uniqueFixture(listOf(first, second)) { if (it === second) throw VaultLockedException() else true }
        }
        var reads = 0
        assertThrows(Exception::class.java) { FolsomFlagsAcceptanceAccount.uniqueFixture((1..20).map(::account)) { reads++; true } }
        assertEquals(0, reads)
        assertThrows(Exception::class.java) { FolsomFlagsAcceptanceAccount.uniqueFixture(listOf(first, account(1))) { reads++; true } }
        assertEquals(0, reads)
    }

    private class Fixture : AutoCloseable {
        private val dataKey = NigoriKey.derive("flags-only-synthetic-data-key".toByteArray())
        private val rawKey = "flags-only-synthetic-wrapping-key".toByteArray()
        private val wrapping = NigoriKey.derive(Base64.getEncoder().encode(rawKey))
        val metadata = Proto.field(1, "users/me/birthdays/flags-only-fixture") + Proto.field(2, rawKey) + Proto.field(3,
            Proto.field(1, dataKey.encrypt(Proto.field(2, dataKey.export()))) + Proto.integer(2, 2) +
                Proto.field(3, wrapping.encrypt(dataKey.export())))
        val marker = PasswordData.create(PasswordAcceptanceAccounts.REALM, PasswordAcceptanceAccounts.REALM,
            PasswordAcceptanceAccounts.USERNAME, "synthetic-not-an-account-password", 1234L)
        fun record(data: PasswordData): ByteArray {
            val keys = VaultKeys.unlock(metadata)
            return try { PasswordResourceCodec.encrypt(data, keys) } finally { keys.destroy() }
        }
        fun state() = VaultState(metadata = metadata.copyOf(), records = arrayListOf(record(marker)))
        override fun close() { dataKey.destroy(); wrapping.destroy(); rawKey.fill(0); metadata.fill(0) }
    }

    @Test fun cachedMarkerDecryptsOnlyExactSyntheticRealmAndPreservesAllInputBytes() {
        Fixture().use { fixture ->
            val state = fixture.state()
            // A valid unrelated header with unreadable ciphertext must remain opaque to admission.
            state.records.add(Proto.field(2, "unrelated-opaque-tag") + Proto.field(6, byteArrayOf(1, 2, 3)) +
                Proto.field(8, Proto.field(3, "https://unrelated.invalid/")))
            val before = state.encode()
            assertTrue(FolsomFlagsAcceptanceAccount.cachedMarker(state))
            assertArrayEquals(before, state.encode())
            state.records.removeAt(0)
            assertFalse(FolsomFlagsAcceptanceAccount.cachedMarker(state))
            assertFalse(FolsomFlagsAcceptanceAccount.cachedMarker(VaultState()))
        }
    }

    @Test fun mismatchedMarkerCiphertextAmbiguityAndPendingWritesCannotBecomeFlagsAuthority() {
        Fixture().use { fixture ->
            val wrongUser = fixture.marker.withIdentity(PasswordAcceptanceAccounts.REALM, "another-synthetic-user")
            assertFalse(FolsomFlagsAcceptanceAccount.cachedMarker(VaultState(metadata = fixture.metadata,
                records = arrayListOf(fixture.record(wrongUser)))))
            val ambiguous = fixture.state().also { it.records.add(fixture.record(wrongUser)) }
            assertThrows(Exception::class.java) { FolsomFlagsAcceptanceAccount.cachedMarker(ambiguous) }
            val unreadable = fixture.state().also { state ->
                state.records[0] = Proto.decode(state.records[0]).replace(6, byteArrayOf(1, 2, 3)).encode()
            }
            assertThrows(Exception::class.java) { FolsomFlagsAcceptanceAccount.cachedMarker(unreadable) }
            val wrongTag = fixture.state().also { state -> state.records[0] = Proto.decode(state.records[0]).replace(2, "wrong-tag".toByteArray()).encode() }
            assertThrows(Exception::class.java) { FolsomFlagsAcceptanceAccount.cachedMarker(wrongTag) }
            val pending = fixture.state().also { it.pendingMetadata = fixture.metadata.copyOf() }
            assertThrows(PendingMutationException::class.java) { FolsomFlagsAcceptanceAccount.cachedMarker(pending) }
            val queued = fixture.state().also { it.mutations.add(Mutation("fixture", MutationKind.DELETE, fixture.marker.tag(), null, null, null)) }
            assertThrows(PendingMutationException::class.java) { FolsomFlagsAcceptanceAccount.cachedMarker(queued) }
            val wifi = fixture.state().also { it.wifiSync = Proto.integer(1, 1) + Proto.integer(2, 0) +
                Proto.field(5, Mutation(run, MutationKind.DELETE, "synthetic-wifi-tag", null, null, null).encode()) }
            assertThrows(PendingMutationException::class.java) { FolsomFlagsAcceptanceAccount.cachedMarker(wifi) }
            val sharing = fixture.state().also { it.sharingJournal = Proto.integer(1, 1) + Proto.field(2,
                SharingOperation(run, SharingOperationKind.SEND, SharingOperationPhase.PREPARED,
                    "users/me/birthdays/flags-only-fixture", "synthetic-source", ByteArray(32), byteArrayOf()).encode()) }
            assertThrows(PendingMutationException::class.java) { FolsomFlagsAcceptanceAccount.cachedMarker(sharing) }
            val opened = fixture.state()
            PasswordEncryptionSnapshot("123", "users/me/birthdays/flags-only-fixture", fixture.metadata,
                opened.records, emptyList(), emptyList()).use { snapshot ->
                PasswordEncryptionState.prepare(run, PasswordEncryptionOperation.ENROLL, snapshot, 1234L).use { prepared ->
                    prepared.open().use { flow -> opened.passwordEncryptionFlow = flow.encode() }
                }
            }
            assertThrows(PendingMutationException::class.java) { FolsomFlagsAcceptanceAccount.cachedMarker(opened) }
        }
    }
}
