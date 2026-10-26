/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import okio.ByteString.Companion.toByteString
import org.microg.gms.auth.folsom.AccountData
import org.microg.gms.auth.folsom.DomainData
import org.microg.gms.auth.folsom.Keys
import org.microg.gms.auth.folsom.utils.FolsomAccountProtection

class FolsomRootBindingTest {
    private val owner = "synthetic-current-owner"
    private fun key(version: Int) = Keys.ADAPTER.decode(Proto.integer(2, version.toLong()) +
        Proto.field(10, ByteArray(32) { version.toByte() }) + Proto.field(91, "opaque-key-$version"))
    private fun entry(name: String, value: DomainData) = AccountData.DomainsEntry.ADAPTER.decode(
        Proto.field(1, name) + Proto.field(2, value.encode()) + Proto.field(92, "opaque-entry"))
    @Test fun rootBindingQuarantinesUnboundDomainKeysWithoutLosingLegacyOrderOrUnknownMetadata() {
        val previousLegacy = key(1); val first = key(2); val second = key(3)
        val unbound = DomainData.ADAPTER.decode(DomainData(keys = listOf(first, second), legacyKeys = listOf(previousLegacy)).encode() +
            Proto.field(93, "opaque-domain"))
        val bound = DomainData(keys = listOf(key(7)), googleUserId = owner)
        val original = AccountData.ADAPTER.decode(AccountData(domains = listOf(entry("chromesync", unbound), entry("hw_protected", bound))).encode() +
            Proto.field(94, "opaque-account"))
        val result = FolsomAccountProtection.bind(original, owner)
        assertEquals(owner, result.googleUserId)
        val changedEntry = result.domains.first(); val changed = changedEntry.value_!!
        assertTrue(changed.keys.isEmpty())
        assertEquals(listOf(previousLegacy, first, second), changed.legacyKeys)
        assertNull(changed.googleUserId)
        assertEquals(unbound.unknownFields, changed.unknownFields)
        assertEquals(original.domains.first().unknownFields, changedEntry.unknownFields)
        assertEquals(original.unknownFields, result.unknownFields)
        assertEquals(original.domains.last(), result.domains.last())
        assertTrue(FolsomAccountProtection.canRead(result.googleUserId, bound.googleUserId, owner, false))
    }
    @Test fun journalRootBindingAndSerializedReopenCannotMakeAnotherDomainsQuarantinedBytesReadable() {
        val unbound = DomainData(keys = listOf(key(7)))
        val wrong = DomainData(keys = listOf(key(8)), googleUserId = "removed-owner")
        val raw = AccountData(domains = listOf(entry("chromesync", unbound), entry("hw_protected", wrong)),
            chromesyncEnrollment = byteArrayOf(1, 2, 3).toByteString(), recoverableVault = byteArrayOf(4, 5, 6).toByteString())
        val reopened = AccountData.ADAPTER.decode(FolsomAccountProtection.bind(raw, owner).encode())
        val quarantined = reopened.domains.first().value_!!
        // LocalKeyManager reads only keys; merely creating either account journal cannot promote legacyKeys.
        assertTrue(quarantined.keys.isEmpty()); assertEquals(listOf(key(7)), quarantined.legacyKeys)
        assertEquals(raw.chromesyncEnrollment, reopened.chromesyncEnrollment)
        assertEquals(raw.recoverableVault, reopened.recoverableVault)
        val otherDomain = reopened.domains.last().value_!!
        assertEquals(wrong, otherDomain)
        assertFalse(FolsomAccountProtection.canRead(reopened.googleUserId, otherDomain.googleUserId, owner, true))
        assertEquals(reopened, FolsomAccountProtection.bind(reopened, owner))
    }
    @Test fun existingRootIdentityCannotBeReboundByAReplacementAccountOrBlankDiscovery() {
        val original = AccountData(googleUserId = owner, domains = listOf(entry("chromesync", DomainData(keys = listOf(key(7)), googleUserId = owner))))
        assertSame(original, FolsomAccountProtection.bind(original, owner))
        assertThrows(IllegalStateException::class.java) { FolsomAccountProtection.bind(original, "replacement-owner") }
        assertThrows(IllegalArgumentException::class.java) { FolsomAccountProtection.bind(AccountData(), "") }
        assertThrows(IllegalArgumentException::class.java) { FolsomAccountProtection.bind(AccountData(), " ") }
    }
    @Test fun confirmedResetEnrollmentRedactionRetainsIdentityAndHashesEveryOldWrappedKeyReceipt() {
        val public = byteArrayOf(4) + hex("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296") +
            hex("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")
        val pair = ByteArray(32).also { it[31] = 1 } + public
        val id = "00000000-0000-4000-8000-000000000001"
        val request = Proto.field(1, Proto.field(1, EnrollmentWire.DOMAIN)) + Proto.field(3, "opaque-old-wrapped-keys") + Proto.field(6, id)
        val attempt = EnrollmentAttempt(id, owner, "users/me/birthdays/reset-test", 7, request, EnrollmentPhase.ENROLLED)
        val historical = Proto.field(1, "old-key-bearing-attempt")
        val before = EnrollmentState(pair, attempt, listOf(historical)).encode()
        val reopened = EnrollmentState.decode(FolsomAccountProtection.redactEnrollment(before))
        assertArrayEquals(pair, reopened.pair); assertNull(reopened.attempt); assertEquals(2, reopened.history.size)
        for ((receipt, expected) in reopened.history.zip(listOf(historical, attempt.encode())))
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected), Proto.decode(receipt).one(1))
        assertArrayEquals(before, EnrollmentState(pair, attempt, listOf(historical)).encode())
        assertArrayEquals(pair, EnrollmentState.decode(reopened.encode()).pair)
    }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
