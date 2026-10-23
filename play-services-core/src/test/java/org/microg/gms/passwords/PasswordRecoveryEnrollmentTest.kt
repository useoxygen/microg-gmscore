/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.folsom.utils.SecureBox
import org.microg.gms.auth.folsom.JoinSecurityDomainRequest
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Serialized production coordinator plus an independent JCA wire/crypto oracle; no Android/account/server access. */
class PasswordRecoveryEnrollmentTest {
    private val owner = "synthetic-stable-owner"
    private val parent = "users/me/birthdays/enrollment-fixture"
    private val domain = "users/me/securitydomains/chromesync"
    private val gx = hex("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296")
    private val public = byteArrayOf(4) + gx + hex("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")
    private val pair = ByteArray(32).also { it[31] = 1 } + public
    private val old = ByteArray(32) { (it + 1).toByte() }
    private val fresh = ByteArray(32) { (it + 65).toByte() }
    private val requestId = "00000000-0000-4000-8000-000000000001"
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hmac(key: ByteArray, input: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(input)
    }
    /** With recipient scalar 1, ECDH is the actual wire ephemeral point's x-coordinate. */
    private fun independentUnwrap(wire: ByteArray, aad: ByteArray = "V1 shared_key".toByteArray()): ByteArray {
        assertTrue(wire.size >= 95)
        assertArrayEquals(byteArrayOf(2, 0, 4), wire.copyOfRange(0, 3))
        val shared = wire.copyOfRange(3, 35)
        val prk = hmac("SECUREBOX".toByteArray() + byteArrayOf(2, 0), shared)
        val aes = hmac(prk, "P256 HKDF-SHA-256 AES-128-GCM".toByteArray() + byteArrayOf(1)).copyOf(16)
        return try { Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, wire.copyOfRange(67, 79)))
            updateAAD(aad); doFinal(wire.copyOfRange(79, wire.size))
        } } finally { shared.fill(0); prk.fill(0); aes.fill(0) }
    }
    private fun versioned() = listOf(VersionedTrustedVaultKey(7, old), VersionedTrustedVaultKey(8, fresh))
    private val crypto = object : EnrollmentCrypto {
        override fun generatePair() = pair.copyOf()
        override fun wrap(publicKey: ByteArray, material: ByteArray) = SecureBox.wrapKey(publicKey, material)
        override fun unwrap(pair: ByteArray, wrapped: ByteArray) = SecureBox.unwrapKey(pair, wrapped, "V1 shared_key".toByteArray())
    }
    private fun returnedMember(request: ByteArray): ByteArray {
        val value = Proto.decode(request)
        val entry = Proto.field(1, domain) + value.bytes(3).fold(byteArrayOf()) { out, key -> out + Proto.field(3, key) }
        return Proto.decode(value.one(2)).replaceRepeated(3, listOf(entry)).encode()
    }
    private inner class Fixture {
        var disk = EnrollmentState().encode()
        var actualOwner = owner
        var actualParent = parent
        var allowed = true
        var epoch = 8
        var domainName = domain
        var material = listOf(7 to old, 8 to fresh)
        val requests = ArrayList<ByteArray>()
        var members = emptyList<ByteArray>()
        var generated = 0
        var existing: ByteArray? = null
        var beforeSave: (EnrollmentState) -> Unit = {}
        var readingDomain: () -> Unit = {}
        var readingMembers: () -> Unit = {}
        var joining: (ByteArray) -> Unit = { request -> members = listOf(returnedMember(request)) }
        var response: () -> ByteArray = { Proto.field(1, domainResponse()) }
        fun state() = EnrollmentState.decode(disk)
        fun save(value: EnrollmentState) { disk = value.encode(); beforeSave(value) }
        fun domainResponse() = Proto.field(1, domainName) + Proto.integer(2, epoch.toLong())
        fun verify() { if (!allowed || actualOwner != owner || actualParent != parent) throw VaultLockedException() }
        fun manager(boundOwner: String = owner, boundParent: String = parent) = EnrollmentManager(boundOwner, boundParent, { verify(); material.map { VersionedTrustedVaultKey(it.first, it.second) } },
            object : EnrollmentCrypto {
                override fun generatePair(): ByteArray { generated++; return crypto.generatePair() }
                override fun wrap(publicKey: ByteArray, material: ByteArray) = crypto.wrap(publicKey, material)
                override fun unwrap(pair: ByteArray, wrapped: ByteArray) = crypto.unwrap(pair, wrapped)
            }, object : EnrollmentRemote {
                override fun domain(requestId: String): ByteArray { verify(); assertTrue(requestId.isNotBlank()); readingDomain(); return domainResponse() }
                override fun members(requestId: String): List<ByteArray> { verify(); assertTrue(requestId.isNotBlank()); readingMembers(); return members.map { it.copyOf() } }
                override fun join(request: ByteArray): ByteArray {
                    assertArrayEquals(pair, state().pair)
                    assertEquals(EnrollmentPhase.LAUNCHED, state().attempt!!.phase)
                    assertArrayEquals(request, state().attempt!!.request)
                    requests.add(request.copyOf()); joining(request)
                    return response()
                }
            }, ::state, ::save, ::verify, { existing?.copyOf() })
    }

    @Test fun physicalJoinBytesHaveTheSourceLayoutAndIndependentSecureBoxAndMembershipProofs() {
        val keys = versioned()
        try {
            val wire = EnrollmentWire.request(pair, keys, requestId, crypto)
            assertArrayEquals(wire, JoinSecurityDomainRequest.ADAPTER.decode(wire).encode())
            val request = Proto.decode(wire)
            assertEquals(domain, Proto.decode(request.one(1)).string(1))
            val member = Proto.decode(request.one(2))
            assertEquals("users/me/members/" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(public), member.string(1))
            assertArrayEquals(public, member.one(2)); assertEquals(1L, member.integer(4))
            val metadata = Proto.decode(member.one(6))
            assertEquals(1L, Proto.decode(metadata.one(3)).integer(1))
            assertNull(metadata.optional(4)) // Android metadata is the source oneof3, not a guessed oneof4.
            assertNull(request.optional(5)); assertEquals(requestId, request.string(6))
            assertEquals(0L, request.integer(4) ?: 0L) // Google default join option, not an epoch guessed from a key index.
            val wrapped = request.bytes(3).map(Proto::decode)
            assertEquals(listOf(7L, 8L), wrapped.map { it.integer(1) }.sortedBy { it })
            for (key in wrapped) {
                val expected = if (key.integer(1) == 7L) old else fresh
                assertArrayEquals(expected, independentUnwrap(key.one(2)))
                assertArrayEquals(hmac(expected, public), key.one(3))
                assertThrows(Exception::class.java) { independentUnwrap(key.one(2), byteArrayOf()) }
                val tampered = key.one(2).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                assertThrows(Exception::class.java) { independentUnwrap(tampered) }
            }
            val second = Proto.decode(EnrollmentWire.request(pair, keys, requestId, crypto)).bytes(3).map(Proto::decode)
            assertFalse(wrapped.first().one(2).contentEquals(second.first().one(2)))
            // Chrome Sync key material is not restricted to the passkey domain's 32-byte keys.
            val short = ByteArray(16) { (it + 17).toByte() }
            assertArrayEquals(short, independentUnwrap(SecureBox.wrapKey(public, short)))
        } finally { keys.forEach { it.destroy() } }
    }
    @Test fun memberReadbackMustBindExactPublicIdentityDomainEpochMaterialAndEveryMembershipProof() {
        val keys = versioned()
        try {
            val member = returnedMember(EnrollmentWire.request(pair, keys, requestId, crypto))
            EnrollmentWire.validateMember(member, pair, keys, 8, crypto)
            val original = Proto.decode(member)
            val entry = Proto.decode(original.bytes(3).single())
            val wrapped = entry.bytes(3)
            val first = Proto.decode(wrapped.first())
            fun changedKeys(first: ByteArray) = original.replaceRepeated(3, listOf(entry.replaceRepeated(3, listOf(first) + wrapped.drop(1)).encode())).encode()
            val malformed = listOf(
                original.replace(1, "users/me/members/another-member".toByteArray()).encode(),
                original.replace(2, public.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }).encode(),
                original.replaceInteger(4, 2).encode(),
                original.replaceRepeated(3, listOf(entry.replace(1, "users/me/securitydomains/hw_protected".toByteArray()).encode())).encode(),
                original.replaceRepeated(3, listOf(entry.encode(), entry.encode())).encode(),
                original.replaceRepeated(3, listOf(entry.replaceRepeated(3, wrapped + wrapped.first()).encode())).encode(),
                changedKeys(first.replace(3, ByteArray(32)).encode()),
                changedKeys(first.replace(2, first.one(2).apply { this[lastIndex] = (last().toInt() xor 1).toByte() }).encode()),
                original.replaceRepeated(3, listOf(entry.replaceRepeated(3, listOf(wrapped.first())).encode())).encode(),
                original.replaceRepeated(3, listOf(entry.replaceRepeated(3, listOf(wrapped.last())).encode())).encode())
            for (value in malformed) assertThrows(Exception::class.java) { EnrollmentWire.validateMember(value, pair, keys, 8, crypto) }
            assertThrows(Exception::class.java) { EnrollmentWire.validateMember(member, pair, keys, 9, crypto) }
            val other = listOf(VersionedTrustedVaultKey(7, fresh), VersionedTrustedVaultKey(8, old))
            try { assertThrows(Exception::class.java) { EnrollmentWire.validateMember(member, pair, other, 8, crypto) } }
            finally { other.forEach { it.destroy() } }
        } finally { keys.forEach { it.destroy() } }
    }
    @Test fun pairAndExactRequestAreDurableBeforeJoinAndFullReadbackMakesEnrollmentUsable() {
        val f = Fixture()
        f.manager().enroll()
        assertEquals(1, f.generated); assertEquals(1, f.requests.size)
        assertEquals(EnrollmentPhase.ENROLLED, f.state().attempt!!.phase)
        assertArrayEquals(pair, f.state().pair)
        val reopened = f.manager().reconcile()
        assertEquals(EnrollmentPhase.ENROLLED, reopened.attempt!!.phase)
        assertEquals(1, f.requests.size); assertEquals(1, f.generated)
    }
    @Test fun processDeathAfterPreparingPairAndRequestCannotAllocateAReplacementIdentityOrAutomaticallyJoinOnReopen() {
        val f = Fixture()
        f.beforeSave = { if (it.attempt?.phase == EnrollmentPhase.PREPARED) throw AssertionError("Synthetic process death") }
        assertThrows(AssertionError::class.java) { f.manager().enroll() }
        assertArrayEquals(pair, f.state().pair); assertTrue(f.requests.isEmpty()); assertEquals(1, f.generated)
        f.beforeSave = {}
        val pending = f.manager().reconcile().attempt!!
        assertEquals(EnrollmentPhase.PREPARED, pending.phase); assertTrue(f.requests.isEmpty())
        f.manager().retry(pending.id)
        assertEquals(1, f.generated); assertEquals(EnrollmentPhase.ENROLLED, f.state().attempt!!.phase)
    }
    @Test fun lostJoinResponseAndReopenNeverReplayUntilAnExplicitNewAttemptArchivesTheOldReceipt() {
        val f = Fixture()
        f.joining = { throw PasswordRpcException(503, true) }
        assertThrows(Exception::class.java) { f.manager().enroll() }
        val attempt = f.state().attempt!!
        assertEquals(EnrollmentPhase.INDETERMINATE, attempt.phase)
        assertEquals(EnrollmentPhase.INDETERMINATE, f.manager().reconcile().attempt!!.phase)
        assertEquals(1, f.requests.size)
        f.joining = { f.members = listOf(returnedMember(it)) }
        f.manager().retry(attempt.id)
        val retried = f.state().attempt!!
        assertArrayEquals(retried.request, JoinSecurityDomainRequest.ADAPTER.decode(retried.request).encode())
        assertNotEquals(attempt.id, retried.id); assertEquals(EnrollmentPhase.ENROLLED, retried.phase)
        val previous = EnrollmentAttempt.decode(f.state().history.single())
        assertEquals(attempt.id, previous.id); assertEquals(EnrollmentPhase.INDETERMINATE, previous.phase)
        assertEquals(2, f.requests.size); assertEquals(1, f.generated); assertArrayEquals(pair, f.state().pair)
    }
    @Test fun processDeathAfterRemoteCommitIsConfirmedByMembershipReadbackWithoutASecondJoin() {
        val f = Fixture()
        f.joining = { f.members = listOf(returnedMember(it)); throw AssertionError("Synthetic process death") }
        assertThrows(AssertionError::class.java) { f.manager().enroll() }
        assertEquals(EnrollmentPhase.LAUNCHED, f.state().attempt!!.phase)
        assertEquals(EnrollmentPhase.ENROLLED, f.manager().reconcile().attempt!!.phase)
        assertEquals(1, f.requests.size); assertEquals(1, f.generated)
    }
    @Test fun definiteRefusalStaysRefusedAcrossReadbackAndOnlyExplicitRetryMaySendAnotherJoin() {
        val f = Fixture()
        f.joining = { throw PasswordRpcException(403, false) }
        assertThrows(Exception::class.java) { f.manager().enroll() }
        val attempt = f.state().attempt!!
        assertEquals(EnrollmentPhase.REFUSED, attempt.phase)
        assertEquals(EnrollmentPhase.REFUSED, f.manager().reconcile().attempt!!.phase)
        assertEquals(1, f.requests.size)
        f.joining = { f.members = listOf(returnedMember(it)) }
        f.manager().retry(attempt.id)
        assertEquals(2, f.requests.size); assertEquals(EnrollmentPhase.ENROLLED, f.state().attempt!!.phase)
    }
    @Test fun ownerAccountAndBirthdayRevocationBeforeLaunchCannotSendAJoin() {
        for (change in 0..2) {
            val f = Fixture()
            f.beforeSave = { if (it.attempt?.phase == EnrollmentPhase.PREPARED) when (change) {
                0 -> f.allowed = false
                1 -> f.actualOwner = "same-name-new-stable-id"
                else -> f.actualParent = "users/me/birthdays/replaced"
            } }
            assertThrows(Exception::class.java) { f.manager().enroll() }
            assertTrue(f.requests.isEmpty())
            assertNotEquals(EnrollmentPhase.ENROLLED, f.state().attempt?.phase)
        }
    }
    @Test fun authorityRevocationWhileJoinReturnsCannotPersistOrReleaseAnEnrolledResult() {
        for (change in 0..2) {
            val f = Fixture()
            f.joining = { request ->
                f.members = listOf(returnedMember(request))
                when (change) { 0 -> f.allowed = false; 1 -> f.actualOwner = "same-name-new-stable-id"
                    else -> f.actualParent = "users/me/birthdays/replaced" }
            }
            assertThrows(Exception::class.java) { f.manager().enroll() }
            assertEquals(1, f.requests.size)
            assertTrue(f.state().attempt!!.phase in setOf(EnrollmentPhase.LAUNCHED, EnrollmentPhase.INDETERMINATE))
            assertThrows(Exception::class.java) { f.manager().reconcile() }
        }
    }
    @Test fun domainMismatchUnrecoveredCurrentEpochAndDuplicateRecoveredEpochCannotLaunch() {
        for (change in 0..3) {
            val f = Fixture()
            when (change) { 0 -> f.domainName = "users/me/securitydomains/hw_protected"; 1 -> f.epoch = 9
                2 -> f.material = listOf(7 to old, 7 to fresh); else -> f.material = emptyList() }
            assertThrows(Exception::class.java) { f.manager().enroll() }
            assertTrue(f.requests.isEmpty()); assertNotEquals(EnrollmentPhase.ENROLLED, f.state().attempt?.phase)
        }
    }
    @Test fun joinAcknowledgementWithoutExactMembershipAndEpochReadbackNeverClaimsEnrollment() {
        for (change in 0..2) {
            val f = Fixture()
            f.joining = { request -> when (change) {
                0 -> f.members = emptyList()
                1 -> f.members = listOf(Proto.decode(returnedMember(request)).replace(2, public.copyOf().apply { this[10] = 0 }).encode())
                else -> { f.members = listOf(returnedMember(request)); f.epoch = 9 }
            } }
            runCatching { f.manager().enroll() }
            assertNotEquals(EnrollmentPhase.ENROLLED, f.state().attempt?.phase)
            assertEquals(1, f.requests.size)
            runCatching { f.manager().reconcile() }
            assertEquals(1, f.requests.size); assertNotEquals(EnrollmentPhase.ENROLLED, f.state().attempt?.phase)
        }
    }
    @Test fun unknownClosureArchivesProvenanceAndKeepsTheSamePairForASeparateOwnerAttempt() {
        val f = Fixture()
        f.joining = { throw IOException("Synthetic response loss") }
        assertThrows(Exception::class.java) { f.manager().enroll() }
        val original = f.state().attempt!!
        f.manager().close(original.id)
        assertNull(f.state().attempt); assertArrayEquals(pair, f.state().pair)
        assertEquals(original.id, EnrollmentAttempt.decode(f.state().history.single()).id)
        assertEquals(1, f.requests.size)
        f.joining = { f.members = listOf(returnedMember(it)) }
        f.manager().enroll()
        assertEquals(2, f.requests.size); assertEquals(1, f.generated)
        assertNotEquals(original.id, f.state().attempt!!.id)
    }
    @Test fun anExistingPhysicalPairIsAdoptedDurablyAndNeverReplacedByGeneration() {
        val f = Fixture(); f.existing = pair.copyOf()
        f.manager().enroll()
        assertEquals(0, f.generated); assertArrayEquals(pair, f.state().pair)
        assertEquals(EnrollmentPhase.ENROLLED, f.state().attempt!!.phase)
    }
    @Test fun aFreshlyRemovedPreviouslyEnrolledMemberIsRevokedAndOnlyExplicitRetryCanRejoin() {
        val f = Fixture()
        f.manager().enroll()
        val original = f.state().attempt!!
        assertEquals(EnrollmentPhase.ENROLLED, original.phase)
        f.members = emptyList()
        assertEquals(EnrollmentPhase.REVOKED, f.manager().reconcile().attempt!!.phase)
        assertEquals(1, f.requests.size)
        f.manager().retry(original.id)
        assertEquals(EnrollmentPhase.ENROLLED, f.state().attempt!!.phase)
        assertNotEquals(original.id, f.state().attempt!!.id)
        assertEquals(2, f.requests.size); assertEquals(1, f.generated)
        assertArrayEquals(pair, f.state().pair)
        assertEquals(EnrollmentPhase.REVOKED, EnrollmentAttempt.decode(f.state().history.single()).phase)
    }
    @Test fun reopeningAnAttemptUnderAnotherAccountOrBirthdayCannotReadAdoptOrRetryIt() {
        val f = Fixture()
        f.joining = { throw PasswordRpcException(503, true) }
        assertThrows(Exception::class.java) { f.manager().enroll() }
        val id = f.state().attempt!!.id
        val before = f.disk.copyOf()
        assertThrows(VaultLockedException::class.java) { f.manager(boundOwner = "same-name-new-gaia").reconcile() }
        assertThrows(VaultLockedException::class.java) { f.manager(boundOwner = "same-name-new-gaia").retry(id) }
        assertThrows(VaultLockedException::class.java) { f.manager(boundOwner = "same-name-new-gaia").close(id) }
        assertThrows(VaultResetException::class.java) { f.manager(boundParent = "users/me/birthdays/replaced").reconcile() }
        assertThrows(VaultResetException::class.java) { f.manager(boundParent = "users/me/birthdays/replaced").retry(id) }
        // Explicit owner closure of the previous birthday may archive it, but it cannot cause a network effect.
        assertArrayEquals(before, f.disk); assertEquals(1, f.requests.size)
    }
    @Test fun aPersistedAttemptCannotAdoptAnotherRequestIdEvenWhenItsPublicKeyAndMembershipProofsMatch() {
        val f = Fixture()
        f.joining = { throw PasswordRpcException(503, true) }
        assertThrows(Exception::class.java) { f.manager().enroll() }
        val state = f.state(); val old = state.attempt!!
        val changed = Proto.decode(old.request).replace(6, requestId.toByteArray()).encode()
        assertNotEquals(requestId, old.id)
        f.save(EnrollmentState(state.pair, EnrollmentAttempt(old.id, old.accountId, old.parent, old.epoch, changed, old.phase), state.history))
        val before = f.disk.copyOf()
        assertThrows(Exception::class.java) { f.manager().reconcile() }
        assertThrows(Exception::class.java) { f.manager().retry(old.id) }
        assertArrayEquals(before, f.disk); assertEquals(1, f.requests.size)
    }
    @Test fun recoveredEpochRebindingOrLossCannotRewriteOrReplayAnUncertainEnrollment() {
        for (change in 0..1) {
            val f = Fixture()
            f.joining = { throw PasswordRpcException(503, true) }
            assertThrows(Exception::class.java) { f.manager().enroll() }
            val original = f.state().attempt!!; val before = f.disk.copyOf()
            f.material = if (change == 0) listOf(7 to old, 8 to old) else listOf(8 to fresh)
            assertThrows(Exception::class.java) { f.manager().reconcile() }
            assertThrows(Exception::class.java) { f.manager().retry(original.id) }
            assertArrayEquals(before, f.disk); assertEquals(1, f.requests.size)
            assertEquals(1, f.generated); assertArrayEquals(pair, f.state().pair)
        }
    }
    @Test fun aDefiniteReadFailureAfterJoinReturnedCannotReclassifyItsCommittedEffectAsARefusedJoin() {
        for (failDomain in listOf(true, false)) {
            val f = Fixture()
            val fail = { if (f.requests.isNotEmpty()) throw PasswordRpcException(403, false) }
            if (failDomain) f.readingDomain = fail else f.readingMembers = fail
            assertThrows(PasswordRpcException::class.java) { f.manager().enroll() }
            assertEquals(EnrollmentPhase.INDETERMINATE, f.state().attempt!!.phase)
            assertEquals(1, f.requests.size)
            f.readingDomain = {}; f.readingMembers = {}
            assertEquals(EnrollmentPhase.ENROLLED, f.manager().reconcile().attempt!!.phase)
            assertEquals(1, f.requests.size); assertEquals(1, f.generated)
        }
    }
}
