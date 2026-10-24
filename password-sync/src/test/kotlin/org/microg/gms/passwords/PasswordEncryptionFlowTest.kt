/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class PasswordEncryptionFlowTest {
    private val account = "12345678901234567890"
    private val parent = "users/me/birthdays/source-fixture"
    private fun snapshot(passwords: List<ByteArray> = listOf(byteArrayOf(0x0a, 1, 0x61)),
        wifi: List<ByteArray> = emptyList(), passkeys: List<ByteArray> = emptyList()) =
        PasswordEncryptionSnapshot(account, parent, Proto.field(1, parent) + Proto.field(3, byteArrayOf(0x10, 2)), passwords, wifi, passkeys)
    private fun prepared(op: PasswordEncryptionOperation = PasswordEncryptionOperation.ENROLL) = snapshot().use {
        PasswordEncryptionState.prepare("owner-operation-1", op, it, 1_700_000_000_000)
    }
    private inline fun rejects(action: () -> Unit) { try { action(); fail("Expected rejection") } catch (_: ProtocolException) {} }

    @Test fun snapshotIsBoundToAllThreeRawCollectionsAndAccountMetadata() {
        snapshot().use { first ->
            snapshot(wifi = listOf(byteArrayOf(0x10, 1))).use { changed -> assertFalse(first.digest().contentEquals(changed.digest())) }
            snapshot(passkeys = listOf(byteArrayOf(0x10, 1))).use { changed -> assertFalse(first.digest().contentEquals(changed.digest())) }
            snapshot(passwords = listOf(byteArrayOf(0x0a, 1, 0x62))).use { changed -> assertFalse(first.digest().contentEquals(changed.digest())) }
            rejects { PasswordEncryptionSnapshot(account, parent, Proto.field(1, "users/me/birthdays/other"), emptyList(), emptyList(), emptyList()) }
        }
    }
    @Test fun pageOrderDoesNotChangeBaselineButMultiplicityDoes() {
        snapshot(listOf(byteArrayOf(2), byteArrayOf(1))).use { first ->
            snapshot(listOf(byteArrayOf(1), byteArrayOf(2))).use { reordered -> assertArrayEquals(first.digest(), reordered.digest()) }
            snapshot(listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(2))).use { duplicate -> assertFalse(first.digest().contentEquals(duplicate.digest())) }
        }
    }
    @Test fun inputAndReturnedSnapshotArraysCannotChangeAuthoritativeBaseline() {
        val meta = Proto.field(1, parent); val row = byteArrayOf(7)
        PasswordEncryptionSnapshot(account, parent, meta, listOf(row), emptyList(), emptyList()).use { value ->
            val original = value.digest(); meta.fill(0); row.fill(0); value.metadata.fill(0); value.passwords[0].fill(0)
            assertArrayEquals(original, value.digest())
            PasswordEncryptionSnapshot.decode(value.encode()).use { decoded -> assertArrayEquals(original, decoded.digest()) }
        }
    }
    @Test fun futureOrAmbiguousSnapshotFormatRefusesRatherThanDroppingBinding() {
        snapshot().use { before ->
            rejects { PasswordEncryptionSnapshot.decode(before.encode() + Proto.field(1, account)) }
            rejects { PasswordEncryptionSnapshot.decode(before.encode() + Proto.integer(15, 1)) }
        }
    }
    @Test fun oversizedCollectionRefusesBeforeReadingOrCopyingAnyRow() {
        val unbounded = object : AbstractList<ByteArray>() {
            override val size: Int get() = PasswordEncryptionSnapshot.MAX_RECORDS + 1
            override fun get(index: Int): ByteArray = throw AssertionError("Oversized collection must not be read or copied")
        }
        rejects { PasswordEncryptionSnapshot(account, parent, Proto.field(1, parent), unbounded, emptyList(), emptyList()) }
    }
    @Test fun openedStateIsDurableAndNeverReopensAfterRestart() {
        prepared().use { ready -> ready.open().use { opened ->
            assertFalse(PasswordEncryptionState.fences(ready.encode())); assertTrue(PasswordEncryptionState.fences(opened.encode()))
            PasswordEncryptionState.decode(opened.encode())!!.use { restored ->
                try { restored.open(); fail("Opened Google flow must not be reloaded") } catch (_: PendingMutationException) {}
                restored.closeView().use { unknown ->
                    assertEquals(PasswordEncryptionPhase.UNKNOWN, unknown.phase)
                    assertTrue(PasswordEncryptionState.fences(unknown.encode()))
                    assertNotNull(unknown.before)
                }
            }
        } }
    }
    @Test fun closingBeforeAnyWebLoadIsDefiniteAndScrubsBaseline() {
        prepared().use { ready -> ready.closeView().use { closed ->
            assertEquals(PasswordEncryptionPhase.CLOSED, closed.phase); assertNull(closed.before)
            assertTrue(closed.deliveredKeys.isEmpty()); assertFalse(PasswordEncryptionState.fences(closed.encode()))
            assertArrayEquals(ready.baselineDigest, closed.baselineDigest)
        } }
    }
    @Test fun relayDeliveryIsOnlyStagedAndDoesNotClearFence() {
        val key = VersionedTrustedVaultKey(7, ByteArray(32) { (it + 1).toByte() })
        try { prepared().use { ready -> ready.open().use { opened -> opened.stage(listOf(key)).use { staged ->
            assertTrue(PasswordEncryptionState.fences(staged.encode()))
            val copy = staged.deliveredKeys; try { copy.single().destroy() } finally { copy.forEach(VersionedTrustedVaultKey::destroy) }
            val actual = staged.deliveredKeys
            try { assertEquals(7, actual.single().epoch); assertEquals(32, actual.single().material.size) }
            finally { actual.forEach(VersionedTrustedVaultKey::destroy) }
        } } } } finally { key.destroy() }
    }
    @Test fun conflictingRelayCannotReplacePreviouslyStagedEpochMaterial() {
        val first = VersionedTrustedVaultKey(7, ByteArray(32) { 1 }); val other = VersionedTrustedVaultKey(7, ByteArray(32) { 2 })
        try { prepared().use { ready -> ready.open().use { opened -> opened.stage(listOf(first)).use { staged ->
            try { staged.stage(listOf(other)); fail("Conflicting epoch must not replace pending material") } catch (_: ConflictException) {}
            staged.stage(listOf(first)).use { repeated -> assertTrue(PasswordEncryptionState.fences(repeated.encode())) }
        } } } } finally { first.destroy(); other.destroy() }
    }
    @Test fun resetFlowNeverAcceptsEncryptionKeys() {
        val key = VersionedTrustedVaultKey(7, ByteArray(32) { 1 })
        try { prepared(PasswordEncryptionOperation.RESET_PASSKEYS).use { ready -> ready.open().use { opened ->
            try { opened.stage(listOf(key)); fail("Reset cannot import keys") } catch (_: ConflictException) {}
        } } } finally { key.destroy() }
    }
    @Test fun confirmedReceiptScrubsKeysAndFullBaselineButRetainsOperationBinding() {
        val key = VersionedTrustedVaultKey(7, ByteArray(32) { 1 })
        try { prepared().use { ready -> ready.open().use { opened -> opened.stage(listOf(key)).use { staged -> staged.confirmed().use { done ->
            assertEquals(opened.id, done.id); assertEquals(opened.operation, done.operation); assertEquals(account, done.accountId)
            assertNull(done.before); assertTrue(done.deliveredKeys.isEmpty()); assertFalse(PasswordEncryptionState.fences(done.encode()))
            assertArrayEquals(opened.baselineDigest, done.baselineDigest)
        } } } } } finally { key.destroy() }
    }
    @Test fun ownerClosureRetainsUnknownProvenanceWithoutClaimingRemoteSuccess() {
        prepared().use { ready -> ready.open().use { opened -> opened.abandoned().use { closed ->
            assertEquals(PasswordEncryptionPhase.CLOSED, closed.phase); assertTrue(closed.acknowledgedUnknown)
            assertFalse(PasswordEncryptionState.fences(closed.encode())); assertNull(closed.before)
            PasswordEncryptionState.decode(closed.encode())!!.use { decoded ->
                assertTrue(decoded.acknowledgedUnknown); assertEquals(opened.id, decoded.id); assertArrayEquals(opened.baselineDigest, decoded.baselineDigest)
            }
        } } }
    }
    @Test fun corruptOrFutureJournalCannotRemoveFenceByFallingBackToEmpty() {
        prepared().use { ready -> ready.open().use { opened ->
            rejects { PasswordEncryptionState.fences(Proto.decode(opened.encode()).replaceInteger(1, 2).encode()) }
            rejects { PasswordEncryptionState.fences(Proto.decode(opened.encode()).replaceInteger(4, Int.MAX_VALUE.toLong()).encode()) }
            rejects { PasswordEncryptionState.fences(Proto.decode(opened.encode()).replace(9, ByteArray(32)).encode()) }
            rejects { PasswordEncryptionState.fences(opened.encode() + Proto.integer(99, 1)) }
        } }
    }
    @Test fun operationsStartAtIntroAndInlineConsentIsNotPretended() {
        assertEquals("https://passwords.google.com/embedded/encryption/enroll/intro?ilcp=false", PasswordEncryptionWebPolicy.startUrl(PasswordEncryptionOperation.ENROLL))
        assertEquals("https://passwords.google.com/embedded/encryption/reset/intro", PasswordEncryptionWebPolicy.startUrl(PasswordEncryptionOperation.CLEAR_PASSWORDS))
        assertEquals("https://passwords.google.com/embedded/passkeys/reset/intro", PasswordEncryptionWebPolicy.startUrl(PasswordEncryptionOperation.RESET_PASSKEYS))
    }
    @Test fun navigationNeverCrossesToAnotherOperationOrLookalikeOrigin() {
        val op = PasswordEncryptionOperation.ENROLL
        assertTrue(PasswordEncryptionWebPolicy.allowedNavigation("https://passwords.google.com/u/0/embedded/encryption/enroll/run", op))
        for (url in listOf("https://passwords.google.com.evil.test/embedded/encryption/enroll/run", "https://passwords.google.com@evil.test/embedded/encryption/enroll/run",
            "http://passwords.google.com/embedded/encryption/enroll/run", "https://passwords.google.com:444/embedded/encryption/enroll/run",
            "https://passwords.google.com/embedded/encryption/reset/run", "https://passwords.google.com/embedded/passkeys/reset/run",
            "https://passwords.google.com/embedded/encryption/enroll/%72un", "file:///embedded/encryption/enroll/run"))
            assertFalse(url, PasswordEncryptionWebPolicy.allowedNavigation(url, op))
    }
    @Test fun pageDoneIsFiniteAndBelongsToSelectedOperation() {
        val op = PasswordEncryptionOperation.CLEAR_PASSWORDS
        assertTrue(PasswordEncryptionWebPolicy.terminal("https://passwords.google.com/embedded/encryption/reset/done?rvt=opaque", op))
        assertFalse(PasswordEncryptionWebPolicy.terminal("https://accounts.google.com/embedded/encryption/reset/done", op))
        assertFalse(PasswordEncryptionWebPolicy.terminal("https://passwords.google.com/embedded/passkeys/reset/done", op))
        assertFalse(PasswordEncryptionWebPolicy.terminal("https://passwords.google.com/embedded/encryption/reset/run?continue=/done", op))
    }
    @Test fun relayNeedsExactSourceFrameAndActivePasswordEnrollment() {
        val op = PasswordEncryptionOperation.ENROLL; val top = "https://passwords.google.com/embedded/encryption/enroll/run"
        val relay = "https://accounts.google.com/encryption/unlock/datarelay?origin=https%3A%2F%2Fpasswords.google.com"
        assertTrue(PasswordEncryptionWebPolicy.acceptsRelayMessage(PasswordEncryptionWebPolicy.ACCOUNTS_ORIGIN, false, top, relay, op))
        assertFalse(PasswordEncryptionWebPolicy.acceptsRelayMessage(PasswordEncryptionWebPolicy.ACCOUNTS_ORIGIN, true, top, relay, op))
        assertFalse(PasswordEncryptionWebPolicy.acceptsRelayMessage(PasswordEncryptionWebPolicy.ACCOUNTS_ORIGIN, false, "https://accounts.google.com/", relay, op))
        assertFalse(PasswordEncryptionWebPolicy.acceptsRelayMessage("https://accounts.google.com.evil.test", false, top, relay, op))
        assertFalse(PasswordEncryptionWebPolicy.acceptsRelayMessage(PasswordEncryptionWebPolicy.ACCOUNTS_ORIGIN, false, top, relay.replace("datarelay", "other"), op))
        assertFalse(PasswordEncryptionWebPolicy.acceptsRelayMessage(PasswordEncryptionWebPolicy.ACCOUNTS_ORIGIN, false, top, relay+"&origin=https%3A%2F%2Fevil.test", op))
        assertFalse(PasswordEncryptionWebPolicy.acceptsRelayMessage(PasswordEncryptionWebPolicy.ACCOUNTS_ORIGIN, false, top, relay, PasswordEncryptionOperation.CLEAR_PASSWORDS))
    }
    @Test fun secondAccountCannotClearOrReplaceTheActiveOrRetiringCookieProfile() {
        val lease = PasswordEncryptionSessionLease()
        assertTrue(lease.acquire("account-A")); assertFalse(lease.acquire("account-B")); assertTrue(lease.owns("account-A"))
        assertFalse(lease.retire("account-B")); lease.cleared("account-B"); assertFalse(lease.acquire("account-B"))
        assertTrue(lease.retire("account-A")); assertFalse(lease.owns("account-A")); assertFalse(lease.acquire("account-B"))
        lease.cleared("account-A"); assertTrue(lease.acquire("account-B"))
        lease.cleared("account-A"); assertTrue(lease.owns("account-B"))
    }
    @Test fun missingOrLateCookieCleanupCallbackFailsClosedUntilProcessRestart() {
        val lease = PasswordEncryptionSessionLease()
        assertTrue(lease.acquire("A")); assertTrue(lease.retire("A")); lease.cleanupTimedOut("A")
        lease.cleared("A"); assertFalse(lease.acquire("B")); assertFalse(lease.owns("A"))
        assertTrue(PasswordEncryptionSessionLease().acquire("new-process"))
    }
}
