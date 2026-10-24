/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.folsom.JoinSecurityDomainRequest
import org.microg.gms.auth.folsom.RotateSharedKeyRequest
import org.microg.gms.auth.folsom.ResetSecurityDomainRequest
import org.microg.gms.auth.folsom.utils.SecureBox
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Actual production journal and SecureBox, with independent wire/AEAD oracles and a serialized fake server. */
class DomainLifecycleTest {
    private val owner = "synthetic-lifecycle-owner"
    private val domain = "chromesync"
    private val domainName = DomainLifecycleWire.name(domain)
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val public = byteArrayOf(4) + hex("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296") +
        hex("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")
    private val pair = ByteArray(32).also { it[31] = 1 } + public
    private val old = ByteArray(32) { (it + 1).toByte() }
    private val fresh = ByteArray(32) { (it + 65).toByte() }
    private fun hmac(key: ByteArray, value: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(value)
    }
    private fun independentShared(secret: ByteArray, aad: ByteArray, wire: ByteArray): ByteArray {
        assertArrayEquals(byteArrayOf(2, 0), wire.copyOfRange(0, 2))
        val prk = hmac("SECUREBOX".toByteArray() + byteArrayOf(2, 0), secret)
        val aes = hmac(prk, "SHARED HKDF-SHA-256 AES-128-GCM".toByteArray() + byteArrayOf(1)).copyOf(16)
        return try { Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, wire.copyOfRange(2, 14)))
            updateAAD(aad); doFinal(wire.copyOfRange(14, wire.size))
        } } finally { prk.fill(0); aes.fill(0) }
    }
    /** For the public scalar-one fixture, ECDH equals the actual ephemeral point's x-coordinate. */
    private fun independentUnwrap(wire: ByteArray): ByteArray {
        assertArrayEquals(byteArrayOf(2, 0, 4), wire.copyOfRange(0, 3))
        val shared = wire.copyOfRange(3, 35)
        val prk = hmac("SECUREBOX".toByteArray() + byteArrayOf(2, 0), shared)
        val aes = hmac(prk, "P256 HKDF-SHA-256 AES-128-GCM".toByteArray() + byteArrayOf(1)).copyOf(16)
        return try { Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, wire.copyOfRange(67, 79)))
            updateAAD("V1 shared_key".toByteArray()); doFinal(wire.copyOfRange(79, wire.size))
        } } finally { shared.fill(0); prk.fill(0); aes.fill(0) }
    }
    private fun initialMember() = Proto.field(1, EnrollmentWire.memberName(public)) + Proto.field(2, public) +
        Proto.field(3, Proto.field(1, domainName) + Proto.field(3, Proto.integer(1, 7) +
            Proto.field(2, SecureBox.wrapKey(public, old)) + Proto.field(3, hmac(old, public)))) + Proto.integer(4, 1)
    private inner class Fixture(existingDomain: Boolean = true) {
        var disk = DomainLifecycleState(pair.copyOf()).encode()
        var actualOwner = owner
        var allowed = true
        var epoch = if (existingDomain) 7 else 0
        var remoteDomain = domainName
        var members = if (existingDomain) listOf(initialMember()) else emptyList()
        var recipients = members.map { it.copyOf() }
        var generatedPairs = 0
        var generatedKeys = 0
        var existing: ByteArray? = pair.copyOf()
        var saveHook: (DomainLifecycleState) -> Unit = {}
        var readHook: () -> Unit = {}
        var invokeHook: (DomainLifecycleKind, ByteArray) -> Unit = { kind, request -> apply(kind, request) }
        val requests = ArrayList<Pair<DomainLifecycleKind, ByteArray>>()
        val crypto = object : DomainLifecycleCrypto {
            override fun generatePair(): ByteArray { generatedPairs++; return pair.copyOf() }
            override fun generateSharedKey(): ByteArray { generatedKeys++; return fresh.copyOf() }
            override fun wrap(publicKey: ByteArray, material: ByteArray) = SecureBox.wrapKey(publicKey, material)
            override fun unwrap(pair: ByteArray, wrapped: ByteArray) = SecureBox.unwrapKey(pair, wrapped, "V1 shared_key".toByteArray())
            override fun rotationProof(previous: ByteArray, next: ByteArray) = SecureBox.wrapShared(previous, next, byteArrayOf(0))
        }
        fun state() = DomainLifecycleState.decode(disk)
        fun verify() { if (!allowed || actualOwner != owner) throw VaultLockedException() }
        fun snapshot() = DomainLifecycleSnapshot(if (epoch == 0) null else Proto.field(1, remoteDomain) + Proto.integer(2, epoch.toLong()),
            members.map { it.copyOf() }, recipients.map { it.copyOf() })
        fun manager(boundOwner: String = owner, boundDomain: String = domain) = DomainLifecycleManager(boundOwner, boundDomain, crypto,
            object : DomainLifecycleRemote {
                override fun snapshot(domain: String, requestId: String): DomainLifecycleSnapshot {
                    assertEquals(boundDomain, domain); assertTrue(requestId.isNotBlank()); readHook(); return this@Fixture.snapshot()
                }
                override fun invoke(kind: DomainLifecycleKind, request: ByteArray): ByteArray {
                    assertEquals(DomainLifecyclePhase.LAUNCHED, state().attempt!!.phase)
                    assertArrayEquals(request, state().attempt!!.request)
                    requests.add(kind to request.copyOf()); invokeHook(kind, request)
                    return if (kind == DomainLifecycleKind.ROTATE) Proto.integer(1, epoch.toLong()) else byteArrayOf()
                }
            }, ::state, { value -> verify(); disk = value.encode(); saveHook(value) },
            { verify(); listOf(DomainLifecycleKey(7, old)) }, ::verify, { existing?.copyOf() })
        fun prepare(kind: DomainLifecycleKind) { val manager = manager(); manager.prepare(kind, manager.review().digest()) }
        fun apply(kind: DomainLifecycleKind, request: ByteArray) {
            val p = Proto.decode(request)
            when (kind) {
                DomainLifecycleKind.CREATE, DomainLifecycleKind.ENROLL, DomainLifecycleKind.ADD_MEMBER -> {
                    if (kind == DomainLifecycleKind.CREATE) epoch = 9
                    val keys = p.bytes(3).map { raw ->
                        val key = Proto.decode(raw)
                        if ((key.integer(1) ?: 0) == 0L) key.replaceInteger(1, epoch.toLong()).encode() else raw
                    }
                    val entry = Proto.field(1, domainName) + keys.fold(byteArrayOf()) { out, key -> out + Proto.field(3, key) }
                    val member = Proto.decode(p.one(2)).replaceRepeated(3, listOf(entry)).encode()
                    members = members.filter { Proto.decode(it).string(1) != Proto.decode(member).string(1) } + listOf(member)
                    recipients = members.map { it.copyOf() }
                }
                DomainLifecycleKind.ROTATE -> {
                    epoch++
                    members = members.map { raw ->
                        val member = Proto.decode(raw)
                        val sent = p.bytes(3).map(Proto::decode).single { it.string(1) == member.string(1) }
                        val entry = Proto.decode(member.bytes(3).single())
                        val key = Proto.integer(1, epoch.toLong()) + Proto.field(2, sent.one(2)) + Proto.field(3, sent.one(3))
                        member.replaceRepeated(3, listOf(entry.replaceRepeated(3, entry.bytes(3) + listOf(key)).encode())).encode()
                    }
                    recipients = members.map { it.copyOf() }
                }
                DomainLifecycleKind.RESET -> { epoch = 0; members = emptyList(); recipients = emptyList() }
            }
        }
    }

    @Test fun sourceCreateWireHasZeroEpochAndOnlyPositiveAuthenticatedMemberReadbackCommits() {
        val f = Fixture(false)
        f.disk = DomainLifecycleState().encode(); f.existing = null
        f.prepare(DomainLifecycleKind.CREATE)
        val saved = f.state(); val attempt = saved.attempt!!
        val request = f.requests.single().second; val p = Proto.decode(request)
        assertArrayEquals(request, JoinSecurityDomainRequest.ADAPTER.decode(request).encode())
        assertEquals(domainName, Proto.decode(p.one(1)).string(1)); assertNull(Proto.decode(p.one(1)).integer(2))
        val key = Proto.decode(p.bytes(3).single())
        assertEquals(0L, key.integer(1) ?: 0L); assertArrayEquals(fresh, independentUnwrap(key.one(2)))
        assertArrayEquals(hmac(fresh, public), key.one(3)); assertNull(p.optional(5))
        assertEquals(DomainLifecyclePhase.COMMITTED, attempt.phase); assertEquals(9, attempt.resultEpoch)
        assertEquals(0, DomainLifecycleKey.decode(attempt.proposedKey!!).use { it.epoch })
        assertArrayEquals(pair, saved.pair); assertEquals(1, f.generatedPairs); assertEquals(1, f.generatedKeys)
        assertEquals(DomainLifecyclePhase.COMMITTED, f.manager().reconcile().attempt!!.phase)
        assertEquals(1, f.requests.size)
    }
    @Test fun rotationProofUsesSerializedOldKeyAndNewKeyAadRatherThanRawMaterials() {
        val f = Fixture(); f.prepare(DomainLifecycleKind.ROTATE)
        val request = f.requests.single().second
        assertArrayEquals(request, RotateSharedKeyRequest.ADAPTER.decode(request).encode())
        val p = Proto.decode(request); val oldEncoded = DomainLifecycleKey(7, old).use { it.encode() }
        val proposed = DomainLifecycleKey(0, fresh).use { it.encode() }
        assertEquals(domainName, p.string(1)); assertEquals(7L, p.integer(2))
        assertArrayEquals(byteArrayOf(0), independentShared(oldEncoded, proposed, p.one(4)))
        assertThrows(Exception::class.java) { independentShared(old, proposed, p.one(4)) }
        assertThrows(Exception::class.java) { independentShared(oldEncoded, fresh, p.one(4)) }
        val sent = Proto.decode(p.bytes(3).single())
        assertEquals(EnrollmentWire.memberName(public), sent.string(1))
        assertArrayEquals(fresh, independentUnwrap(sent.one(2))); assertArrayEquals(hmac(fresh, public), sent.one(3))
        assertEquals(8, f.state().attempt!!.resultEpoch); assertEquals(DomainLifecyclePhase.COMMITTED, f.state().attempt!!.phase)
    }
    @Test fun processDeathAfterDurableLaunchCannotReplayOrReplaceIdentityOnReopen() {
        val f = Fixture(false)
        f.saveHook = { if (it.attempt?.phase == DomainLifecyclePhase.LAUNCHED) throw AssertionError("Synthetic process death") }
        assertThrows(AssertionError::class.java) { f.prepare(DomainLifecycleKind.CREATE) }
        val launched = f.state().attempt!!
        assertEquals(DomainLifecyclePhase.LAUNCHED, launched.phase); assertTrue(f.requests.isEmpty())
        f.saveHook = {}
        assertEquals(DomainLifecyclePhase.INDETERMINATE, f.manager().reconcile().attempt!!.phase)
        assertTrue(f.requests.isEmpty()); assertEquals(0, f.generatedPairs); assertEquals(1, f.generatedKeys)
        assertArrayEquals(pair, f.state().pair); assertArrayEquals(launched.request, f.state().attempt!!.request)
    }
    @Test fun lostMutationResponseResolvesOnlyByReadbackWithoutSecondNetworkMutation() {
        val f = Fixture()
        f.invokeHook = { kind, request -> f.apply(kind, request); throw IOException("Synthetic lost response") }
        assertThrows(Exception::class.java) { f.prepare(DomainLifecycleKind.ROTATE) }
        assertEquals(DomainLifecyclePhase.INDETERMINATE, f.state().attempt!!.phase)
        val resolved = f.manager().reconcile()
        assertEquals(DomainLifecyclePhase.COMMITTED, resolved.attempt!!.phase); assertEquals(8, resolved.attempt!!.resultEpoch)
        assertEquals(1, f.requests.size)
    }
    @Test fun successfulInvokeThenDefiniteReadRefusalRemainsUnknownUntilIndependentReadback() {
        val f = Fixture()
        f.readHook = { if (f.requests.isNotEmpty()) throw PasswordRpcException(7, false) }
        assertThrows(Exception::class.java) { f.prepare(DomainLifecycleKind.ROTATE) }
        assertEquals(DomainLifecyclePhase.INDETERMINATE, f.state().attempt!!.phase)
        f.readHook = {}
        assertEquals(DomainLifecyclePhase.COMMITTED, f.manager().reconcile().attempt!!.phase)
        assertEquals(1, f.requests.size)
    }
    @Test fun explicitRetryArchivesOriginalUnknownAndReusesSamePairProposalAndWrappedBytesWithNewId() {
        val f = Fixture(false)
        f.invokeHook = { _, _ -> throw PasswordRpcException(503, true) }
        assertThrows(Exception::class.java) { f.prepare(DomainLifecycleKind.CREATE) }
        val oldAttempt = f.state().attempt!!
        f.invokeHook = { kind, request -> f.apply(kind, request) }
        val manager = f.manager(); manager.retry(oldAttempt.id, manager.review().digest())
        val state = f.state(); val next = state.attempt!!
        assertNotEquals(oldAttempt.id, next.id); assertEquals(DomainLifecyclePhase.COMMITTED, next.phase)
        assertArrayEquals(oldAttempt.proposedKey, next.proposedKey); assertArrayEquals(pair, state.pair)
        assertArrayEquals(Proto.decode(oldAttempt.request).replace(6, null).encode(), Proto.decode(next.request).replace(6, null).encode())
        assertArrayEquals(next.request, JoinSecurityDomainRequest.ADAPTER.decode(next.request).encode())
        val receipt = DomainLifecycleAttempt.decode(state.history.single())
        assertEquals(oldAttempt.id, receipt.id); assertEquals(DomainLifecyclePhase.INDETERMINATE, receipt.phase)
        assertEquals(2, f.requests.size); assertEquals(1, f.generatedKeys); assertEquals(0, f.generatedPairs)
    }
    @Test fun reviewDigestAndRecipientMembershipChangesCannotExpandOwnerConsent() {
        val f = Fixture(); val manager = f.manager(); val reviewed = manager.review().digest()
        f.epoch = 8
        assertThrows(ConflictException::class.java) { manager.prepare(DomainLifecycleKind.RESET, reviewed) }
        assertTrue(f.requests.isEmpty())
        f.epoch = 7; f.recipients = listOf(Proto.decode(f.members.single()).replaceInteger(4, 2).encode())
        assertThrows(ConflictException::class.java) { manager.review() }
        assertTrue(f.requests.isEmpty())
        f.recipients = f.members; f.remoteDomain = DomainLifecycleWire.name("hw_protected")
        assertThrows(ProtocolException::class.java) { manager.review() }
    }
    @Test fun accountRevocationDuringInvokeKeepsDurableLaunchAndCannotReleaseOrCommitSuccess() {
        val f = Fixture()
        f.invokeHook = { kind, request -> f.apply(kind, request); f.actualOwner = "removed-and-readded-account" }
        assertThrows(VaultLockedException::class.java) { f.prepare(DomainLifecycleKind.ROTATE) }
        assertEquals(DomainLifecyclePhase.LAUNCHED, f.state().attempt!!.phase)
        assertThrows(VaultLockedException::class.java) { f.manager().reconcile() }
        f.actualOwner = owner
        assertThrows(VaultLockedException::class.java) { f.manager(boundOwner = "another-account").reconcile() }
        assertThrows(VaultLockedException::class.java) { f.manager(boundDomain = "hw_protected").reconcile() }
        assertEquals(1, f.requests.size)
    }
    @Test fun confirmedResetRedactsRecoveryHistoryAndStaysFencedUntilNativeCacheClearAcknowledgement() {
        val f = Fixture()
        val history = Proto.field(1, old) + Proto.field(2, f.members.single())
        f.disk = DomainLifecycleState(pair.copyOf(), history = listOf(history)).encode()
        f.prepare(DomainLifecycleKind.RESET)
        val state = f.state(); val reset = state.attempt!!
        assertArrayEquals(reset.request, ResetSecurityDomainRequest.ADAPTER.decode(reset.request).encode())
        assertEquals(DomainLifecyclePhase.COMMITTED, reset.phase); assertFalse(reset.authorityCleared)
        assertTrue(reset.baseline.isEmpty()); assertNotNull(reset.baselineDigest)
        assertTrue(reset.knownKeys.isEmpty()); assertNull(reset.proposedKey)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(history), Proto.decode(state.history.single()).one(1))
        assertThrows(ConflictException::class.java) { f.manager().close(reset.id) }
        assertThrows(PendingMutationException::class.java) { f.prepare(DomainLifecycleKind.CREATE) }
        f.manager().acknowledgeResetAuthorityCleared(reset.id)
        assertTrue(f.state().attempt!!.authorityCleared)
        f.manager().close(reset.id)
        assertNull(f.state().attempt); assertArrayEquals(pair, f.state().pair)
        f.prepare(DomainLifecycleKind.CREATE)
        assertEquals(DomainLifecyclePhase.COMMITTED, f.state().attempt!!.phase); assertEquals(0, f.generatedPairs)
    }
    @Test fun unknownResetCannotCloseAndMissingDomainAloneCannotProveAllMembersDeleted() {
        val f = Fixture()
        f.invokeHook = { _, _ -> throw IOException("Synthetic unknown reset") }
        assertThrows(Exception::class.java) { f.prepare(DomainLifecycleKind.RESET) }
        val reset = f.state().attempt!!
        assertThrows(ConflictException::class.java) { f.manager().close(reset.id) }
        assertThrows(ConflictException::class.java) { f.manager().acknowledgeResetAuthorityCleared(reset.id) }
        f.epoch = 0
        assertEquals(DomainLifecyclePhase.INDETERMINATE, f.manager().reconcile().attempt!!.phase)
        f.members = emptyList(); f.recipients = emptyList()
        assertEquals(DomainLifecyclePhase.COMMITTED, f.manager().reconcile().attempt!!.phase)
        assertEquals(1, f.requests.size)
    }
    @Test fun wrongReadbackKeyOrMembershipProofCannotCommitEvenWhenServerEpochIncreases() {
        val f = Fixture()
        f.invokeHook = { kind, request ->
            f.apply(kind, request)
            f.members = f.members.map { raw ->
                val member = Proto.decode(raw); val entry = Proto.decode(member.bytes(3).single())
                val keys = entry.bytes(3).map { encoded ->
                    val key = Proto.decode(encoded)
                    if (key.integer(1) == 8L) key.replace(3, ByteArray(32)).encode() else encoded
                }
                member.replaceRepeated(3, listOf(entry.replaceRepeated(3, keys).encode())).encode()
            }
            f.recipients = f.members
        }
        assertThrows(Exception::class.java) { f.prepare(DomainLifecycleKind.ROTATE) }
        assertEquals(DomainLifecyclePhase.INDETERMINATE, f.state().attempt!!.phase)
        assertThrows(Exception::class.java) { f.manager().reconcile() }
        assertEquals(1, f.requests.size)
    }
    @Test fun addingReviewedExternalMemberUsesExactPositiveEpochAndReadbackOfSentEnvelope() {
        val f = Fixture()
        val externalPair = SecureBox.generateKeyPairBytes()
        try {
            val external = EnrollmentWire.publicKey(externalPair)
            val manager = f.manager()
            manager.prepare(DomainLifecycleKind.ADD_MEMBER, manager.review().digest(), external, 6)
            val request = f.requests.single().second
            assertArrayEquals(request, JoinSecurityDomainRequest.ADAPTER.decode(request).encode())
            val p = Proto.decode(request); val member = Proto.decode(p.one(2))
            assertArrayEquals(external, member.one(2)); assertEquals(EnrollmentWire.memberName(external), member.string(1))
            assertNull(member.integer(4)); assertNull(member.optional(6)); assertEquals(6L, p.integer(4))
            val key = Proto.decode(p.bytes(3).single())
            assertEquals(7L, key.integer(1))
            assertArrayEquals(old, SecureBox.unwrapKey(externalPair, key.one(2), "V1 shared_key".toByteArray()))
            assertArrayEquals(hmac(old, external), key.one(3))
            assertEquals(DomainLifecyclePhase.COMMITTED, f.state().attempt!!.phase)
            assertEquals(7, f.state().attempt!!.resultEpoch); assertArrayEquals(pair, f.state().pair)
        } finally { externalPair.fill(0) }
    }
    @Test fun durableResetCommitFollowedBySaveAcknowledgementFailureCannotRestoreOldAuthorityOrReplay() {
        val f = Fixture()
        val oldReceipt = Proto.field(1, old) + Proto.field(2, f.members.single())
        f.disk = DomainLifecycleState(pair.copyOf(), history = listOf(oldReceipt)).encode()
        f.saveHook = { if (it.attempt?.phase == DomainLifecyclePhase.COMMITTED) throw IOException("Synthetic acknowledgement lost after atomic commit") }
        assertThrows(IOException::class.java) { f.prepare(DomainLifecycleKind.RESET) }
        val confirmed = f.state().attempt!!
        assertEquals(DomainLifecyclePhase.COMMITTED, confirmed.phase); assertFalse(confirmed.authorityCleared)
        assertTrue(confirmed.baseline.isEmpty()); assertTrue(confirmed.knownKeys.isEmpty()); assertNull(confirmed.proposedKey)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(oldReceipt), Proto.decode(f.state().history.single()).one(1))
        f.saveHook = {}; f.readHook = { throw IOException("A confirmed operation requires no additional reconciliation RPC") }
        assertEquals(DomainLifecyclePhase.COMMITTED, f.manager().reconcile().attempt!!.phase)
        assertEquals(1, f.requests.size)
        assertThrows(ConflictException::class.java) { f.manager().close(confirmed.id) }
    }
    @Test fun neverDispatchedPreparedResetCanBeExplicitlyClosedAfterBaselineChangesWithoutPurgingAuthority() {
        val f = Fixture(); val originalMembers = f.members.map { it.copyOf() }
        f.saveHook = { if (it.attempt?.phase == DomainLifecyclePhase.PREPARED) throw AssertionError("Synthetic death before dispatch") }
        assertThrows(AssertionError::class.java) { f.prepare(DomainLifecycleKind.RESET) }
        val prepared = f.state().attempt!!
        assertEquals(DomainLifecyclePhase.PREPARED, prepared.phase); assertTrue(f.requests.isEmpty())
        f.saveHook = {}; f.epoch = 8
        f.manager().close(prepared.id)
        assertNull(f.state().attempt); assertArrayEquals(prepared.encode(), f.state().history.single())
        assertEquals(1, originalMembers.size); assertArrayEquals(originalMembers.single(), f.members.single())
        assertTrue(f.requests.isEmpty()); assertEquals(0, f.generatedPairs); assertEquals(0, f.generatedKeys)
    }
}
