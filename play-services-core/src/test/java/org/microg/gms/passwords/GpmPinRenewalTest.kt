/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.folsom.JoinSecurityDomainRequest
import org.microg.gms.auth.folsom.vault.VaultUpdateRequest
import org.microg.gms.auth.folsom.utils.SecureBox
import java.io.IOException
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.ECPoint
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Production state machine + source-shaped actual SecureBox and generated protocol adapters, entirely host-local. */
class GpmPinRenewalTest {
    private val owner = "synthetic-pin-owner"
    private fun hex(raw: String) = raw.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val gx = hex("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296")
    private val gy = hex("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")
    private val public = byteArrayOf(4) + gx + gy
    private val pair = ByteArray(32).also { it[31] = 1 } + public
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val hash = ByteArray(32) { (it + 65).toByte() }
    private val claim = ByteArray(32) { (it + 101).toByte() }
    private val spki: ByteArray get() {
        val params = AlgorithmParameters.getInstance("EC").run { init(ECGenParameterSpec("secp256r1")); getParameterSpec(ECParameterSpec::class.java) }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, gx), BigInteger(1, gy)), params)).encoded
    }
    private val crypto = object : GpmPinRenewalCrypto by GpmPinCryptography { override fun pair() = pair.copyOf() }
    private fun keys() = listOf(DomainLifecycleKey(7, key))
    private fun cbor(serial: Int = 1, pinHash: ByteArray = hash) = byteArrayOf(0xa7.toByte(), 1, 0x58, 0x20) + pinHash +
        byteArrayOf(2, 0, 3, 0x58, 0x20) + claim + byteArrayOf(4, 0x48) + ByteArray(8) +
        byteArrayOf(5, 0x51, 3) + ByteArray(16) + byteArrayOf(6, serial.toByte(), 7, 0x58, spki.size.toByte()) + spki
    private fun member(plain: ByteArray = cbor(), claimed: ByteArray = claim): ByteArray {
        val wrapped = Proto.field(1, crypto.sealMetadata(plain, key)) + Proto.field(2, claimed) + Proto.integer(4, 1) +
            Proto.integer(6, 4096) + Proto.field(7, ByteArray(16) { 3 }) + Proto.field(91, "opaque-wrapped-pin")
        val metadata = Proto.field(5, Proto.field(2, wrapped) + Proto.integer(3, 1) + Proto.field(92, "opaque-pin-metadata"))
        return Proto.field(1, EnrollmentWire.memberName(public)) + Proto.field(2, public) + Proto.integer(4, 5) + Proto.field(6, metadata)
    }
    private inner class Fixture {
        var disk = GpmPinRenewalState().encode()
        var currentOwner = owner
        var allowed = true
        var epoch = 7
        var domainName = DomainLifecycleWire.name(GpmPinRenewalWire.DOMAIN)
        var members = listOf(member())
        var vaults = emptyList<ByteArray>()
        var saving: (GpmPinRenewalState) -> Unit = {}
        var reading: () -> Unit = {}
        var listing: () -> Unit = {}
        var upload: (ByteArray) -> Unit = { request -> vaults = listOf(Proto.field(1, "vaults/synthetic") + Proto.decode(request).one(3)) }
        var joining: (ByteArray) -> Unit = { request -> applyJoin(request) }
        val uploads = ArrayList<ByteArray>(); val joins = ArrayList<ByteArray>()
        fun state() = GpmPinRenewalState.decode(disk)
        fun verify() { if (!allowed || currentOwner != owner) throw VaultLockedException() }
        fun applyJoin(request: ByteArray) {
            val p = Proto.decode(request)
            val entry = Proto.field(1, DomainLifecycleWire.name(GpmPinRenewalWire.DOMAIN)) +
                p.bytes(3).fold(byteArrayOf()) { out, key -> out + Proto.field(3, key) }
            members = listOf(Proto.decode(p.one(2)).replaceRepeated(3, listOf(entry)).encode())
        }
        fun manager(bound: String = owner) = GpmPinRenewalManager(bound, 261136035, crypto, object : GpmPinRenewalRemote {
            override fun domain(id: String): ByteArray { reading(); return Proto.field(1, domainName) + Proto.integer(2, epoch.toLong()) }
            override fun members(id: String): List<ByteArray> { reading(); return members.map { it.copyOf() } }
            override fun vaults(id: String): List<ByteArray> { reading(); listing(); return vaults.map { it.copyOf() } }
            override fun update(request: ByteArray): ByteArray {
                assertEquals(GpmPinRenewalPhase.VAULT_LAUNCHED, state().attempt!!.phase)
                assertArrayEquals(request, state().attempt!!.update); uploads.add(request.copyOf()); upload(request); return byteArrayOf()
            }
            override fun join(request: ByteArray): ByteArray {
                assertEquals(GpmPinRenewalPhase.JOIN_LAUNCHED, state().attempt!!.phase)
                assertArrayEquals(request, state().attempt!!.join); joins.add(request.copyOf()); joining(request); return byteArrayOf()
            }
        }, { verify(); keys() }, ::state, { value -> verify(); disk = value.encode(); saving(value) }, ::verify)
        fun prepare(serial: Long = 2) = manager().prepare(GpmPinCertificate(serial, spki, byteArrayOf(1, 2, 3)))
    }
    private fun aes(key: ByteArray, raw: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        doFinal(raw.copyOfRange(12, raw.size))
    }
    @Test fun actualRenewalWireAndNestedCryptoMatchGmsParametersFullPairAndPreviousMemberBinding() {
        val f = Fixture(); assertTrue(f.prepare())
        val attempt = f.state().attempt!!
        assertArrayEquals(attempt.update, VaultUpdateRequest.ADAPTER.decode(attempt.update).encode())
        assertArrayEquals(attempt.join, JoinSecurityDomainRequest.ADAPTER.decode(attempt.join).encode())
        val update = Proto.decode(attempt.update); val vault = Proto.decode(update.one(3)); val params = Proto.decode(vault.one(2))
        assertArrayEquals(spki, params.one(1)); assertEquals(8, params.one(2).size); assertEquals(10L, params.integer(3))
        assertEquals(17, params.one(4).size); assertEquals(3.toByte(), params.one(4)[0])
        val protection = Proto.decode(vault.one(3))
        assertEquals(1L, protection.integer(1)); assertEquals(3L, protection.integer(4)); assertEquals(4096L, protection.integer(6))
        val thmPrivate = SecureBox.deserializePrivateKey(pair)
        try {
            val inner = SecureBox.decrypt(thmPrivate, hash, "V1 THM_encrypted_recovery_key".toByteArray() + params.encode(), vault.one(4))
            val recovery = SecureBox.decrypt(null, hash, "V1 locally_encrypted_recovery_key".toByteArray(), inner)
            val entry = Proto.decode(Proto.decode(vault.bytes(5).single()).one(4))
            val app = SecureBox.decrypt(null, recovery, "V1 encrypted_application_key".toByteArray(), entry.one(3))
            assertEquals(32, recovery.size); assertEquals(32, app.size); assertEquals(125, entry.one(2).size)
            assertArrayEquals(pair, aes(app, entry.one(2))); assertArrayEquals(public, entry.one(1))
            assertThrows(Exception::class.java) { SecureBox.decrypt(thmPrivate, hash, "V1 THM_encrypted_recovery_key".toByteArray(), vault.one(4)) }
            assertThrows(Exception::class.java) { SecureBox.decrypt(thmPrivate, key, "V1 THM_encrypted_recovery_key".toByteArray() + params.encode(), vault.one(4)) }
        } finally { runCatching { thmPrivate.destroy() } }
        val join = Proto.decode(attempt.join); val next = join.one(2)
        assertEquals(5L, Proto.decode(next).integer(4)); assertArrayEquals(public, join.one(5))
        assertArrayEquals(pair, attempt.pair)
        val material = keys()
        try { GpmPinRenewalWire.open(next, material, crypto).use { pin -> assertArrayEquals(hash, pin.hash); assertEquals(2L, pin.serial)
            assertEquals("opaque-wrapped-pin", Proto.decode(pin.wrapped).string(91)) } }
        finally { material.forEach(DomainLifecycleKey::close) }
    }
    @Test fun metadataEncryptionHasIndependentHkdfAesOracleAndWrongDomainKeyCannotOpenPin() {
        val raw = crypto.sealMetadata(cbor(), key)
        fun hmac(k: ByteArray, p: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(k, "HmacSHA256")); doFinal(p) }
        val prk = hmac(ByteArray(32), key)
        val derived = hmac(prk, "KeychainApplicationKey:chrome:GPM PIN data wrapping key".toByteArray() + byteArrayOf(1))
        assertArrayEquals(cbor(), aes(derived, raw))
        assertThrows(Exception::class.java) { crypto.openMetadata(raw, ByteArray(32) { 9 }) }
        val keys = listOf(DomainLifecycleKey(6, ByteArray(32) { 9 }), DomainLifecycleKey(7, key))
        try { GpmPinRenewalWire.open(member(), keys, crypto).use { assertArrayEquals(hash, it.hash) } }
        finally { keys.forEach(DomainLifecycleKey::close); prk.fill(0); derived.fill(0) }
    }
    @Test fun missingMalformedOrAmbiguousPinCannotBecomeNewPinEnrollment() {
        val f = Fixture()
        for (bad in listOf(emptyList(), listOf(Proto.decode(member()).replaceInteger(4, 7).encode()), listOf(member(), member()))) {
            f.members = bad
            assertThrows(Exception::class.java) { f.prepare() }; assertNull(f.state().attempt)
        }
        f.members = listOf(member(claimed = ByteArray(32)))
        assertThrows(ProtocolException::class.java) { f.prepare() }
        f.members = listOf(member(plain = byteArrayOf(0xa1.toByte(), 1, 0x41, 0)))
        assertThrows(ProtocolException::class.java) { f.prepare() }
        assertTrue(f.uploads.isEmpty()); assertTrue(f.joins.isEmpty())
    }
    @Test fun currentOrNewerSignedPinSerialDoesNotGenerateOrLaunchAnotherRenewal() {
        val f = Fixture()
        assertFalse(f.prepare(1)); assertNull(f.state().attempt); assertTrue(f.uploads.isEmpty()); assertTrue(f.joins.isEmpty())
    }
    @Test fun bothMutationsPersistLaunchFirstAndOnlyMatchingVaultAndMemberReadbackConfirms() {
        val f = Fixture(); assertTrue(f.prepare())
        val id = f.state().attempt!!.id
        assertTrue(f.uploads.isEmpty()); assertTrue(f.joins.isEmpty())
        f.manager().continueOnce(id)
        assertEquals(GpmPinRenewalPhase.CONFIRMED, f.state().attempt!!.phase)
        assertEquals(1, f.uploads.size); assertEquals(1, f.joins.size)
        f.manager().reconcile(); f.manager().continueOnce(id)
        assertEquals(1, f.uploads.size); assertEquals(1, f.joins.size)
    }
    @Test fun lostVaultResponseReopenCanReadConfirmButCannotRepeatUploadOrAutomaticallyJoin() {
        val f = Fixture(); f.prepare()
        f.upload = { request -> f.vaults = listOf(Proto.field(1, "vaults/synthetic") + Proto.decode(request).one(3)); throw IOException("Synthetic lost upload response") }
        val id = f.state().attempt!!.id
        assertThrows(IOException::class.java) { f.manager().continueOnce(id) }
        assertEquals(GpmPinRenewalPhase.VAULT_LAUNCHED, f.state().attempt!!.phase)
        assertEquals(GpmPinRenewalPhase.VAULT_CONFIRMED, f.manager().reconcile().attempt!!.phase)
        assertEquals(1, f.uploads.size); assertTrue(f.joins.isEmpty())
        f.manager().continueOnce(id)
        assertEquals(GpmPinRenewalPhase.CONFIRMED, f.state().attempt!!.phase); assertEquals(1, f.uploads.size); assertEquals(1, f.joins.size)
    }
    @Test fun unknownUploadWithoutMatchingRemoteVaultCannotBeReplayedOrAdvanceToJoin() {
        val f = Fixture(); f.prepare(); val id = f.state().attempt!!.id
        f.upload = { throw PasswordRpcException(503, true) }
        assertThrows(Exception::class.java) { f.manager().continueOnce(id) }
        f.manager().reconcile(); f.manager().continueOnce(id)
        assertEquals(GpmPinRenewalPhase.VAULT_LAUNCHED, f.state().attempt!!.phase)
        assertEquals(1, f.uploads.size); assertTrue(f.joins.isEmpty())
    }
    @Test fun lostJoinResponseAndPostCommitReadFailureResolveWithoutSecondMutation() {
        val f = Fixture(); f.prepare(); val id = f.state().attempt!!.id
        f.joining = { request -> f.applyJoin(request); throw IOException("Synthetic lost join response") }
        assertThrows(IOException::class.java) { f.manager().continueOnce(id) }
        assertEquals(GpmPinRenewalPhase.JOIN_LAUNCHED, f.state().attempt!!.phase)
        assertEquals(GpmPinRenewalPhase.CONFIRMED, f.manager().reconcile().attempt!!.phase)
        assertEquals(1, f.uploads.size); assertEquals(1, f.joins.size)
        val g = Fixture(); g.prepare(); val other = g.state().attempt!!.id
        g.reading = { if (g.joins.isNotEmpty()) throw PasswordRpcException(7, false) }
        assertThrows(Exception::class.java) { g.manager().continueOnce(other) }
        assertEquals(GpmPinRenewalPhase.JOIN_LAUNCHED, g.state().attempt!!.phase)
        g.reading = {}
        assertEquals(GpmPinRenewalPhase.CONFIRMED, g.manager().reconcile().attempt!!.phase)
        assertEquals(1, g.uploads.size); assertEquals(1, g.joins.size)
    }
    @Test fun epochAccountAndChangedExistingPinInvalidatePreparedConsentBeforeAnyUpload() {
        val f = Fixture(); f.prepare(); val id = f.state().attempt!!.id
        f.epoch = 8
        assertThrows(VaultResetException::class.java) { f.manager().continueOnce(id) }
        f.epoch = 7
        assertThrows(VaultLockedException::class.java) { f.manager("other-owner").continueOnce(id) }
        f.members = listOf(Proto.decode(f.members.single()).replace(97, byteArrayOf(1)).encode())
        assertThrows(ConflictException::class.java) { f.manager().continueOnce(id) }
        assertTrue(f.uploads.isEmpty()); assertTrue(f.joins.isEmpty())
    }
    @Test fun ownerRevocationDuringUploadRetainsLaunchAndNeverReleasesSuccessOrStartsJoin() {
        val f = Fixture(); f.prepare(); val id = f.state().attempt!!.id
        f.upload = { request -> f.vaults = listOf(Proto.field(1, "vaults/synthetic") + Proto.decode(request).one(3)); f.currentOwner = "replacement" }
        assertThrows(VaultLockedException::class.java) { f.manager().continueOnce(id) }
        assertEquals(GpmPinRenewalPhase.VAULT_LAUNCHED, f.state().attempt!!.phase); assertTrue(f.joins.isEmpty())
    }
    @Test fun resetRedactionRemovesAllPinAndWrappedAuthorityWhileKeepingFiniteReceiptHashes() {
        val f = Fixture(); f.prepare()
        val original = f.disk.copyOf(); val attempt = f.state().attempt!!.encode()
        val untouched = GpmPinRenewalState.clearDomain(original, "chromesync")
        assertArrayEquals(original, untouched)
        val cleared = GpmPinRenewalState.decode(GpmPinRenewalState.clearDomain(original, "hw_protected"))
        assertNull(cleared.attempt); assertEquals(1, cleared.history.size)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(attempt), Proto.decode(cleared.history.single()).one(1))
        assertEquals(GpmPinRenewalPhase.PREPARED, GpmPinRenewalState.decode(original).attempt!!.phase)
    }
    @Test fun completeMissingVaultOrMemberReadbackRevokesPriorConfirmationAndOwnerClosureAllowsFreshAttempt() {
        for (missingVault in listOf(true, false)) {
            val f = Fixture(); f.prepare(); val id = f.state().attempt!!.id
            f.manager().continueOnce(id)
            val confirmed = f.state().attempt!!.encode()
            if (missingVault) f.vaults = emptyList() else f.members = emptyList()
            assertEquals(GpmPinRenewalPhase.REVOKED, f.manager().reconcile().attempt!!.phase)
            f.manager().continueOnce(id)
            assertEquals(1, f.uploads.size); assertEquals(1, f.joins.size)
            val revoked = f.state().attempt!!.encode()
            f.manager().close(id)
            assertNull(f.state().attempt); assertArrayEquals(revoked, f.state().history.single())
            assertFalse(confirmed.contentEquals(revoked))
            f.members = listOf(member())
            assertTrue(f.prepare(3)); assertNotEquals(id, f.state().attempt!!.id)
            assertEquals(GpmPinRenewalPhase.PREPARED, f.state().attempt!!.phase)
            assertEquals(1, f.uploads.size); assertEquals(1, f.joins.size)
        }
    }
    @Test fun explicitClosureArchivesUncertainLaunchExactlyWithoutAnyRetryOrDelete() {
        val f = Fixture(); f.prepare(); val id = f.state().attempt!!.id
        f.upload = { throw IOException("Synthetic unknown upload") }
        assertThrows(IOException::class.java) { f.manager().continueOnce(id) }
        val launched = f.state().attempt!!.encode()
        assertThrows(ConflictException::class.java) { f.manager("different-owner").close(id) }
        f.manager().close(id)
        assertNull(f.state().attempt); assertArrayEquals(launched, f.state().history.single())
        assertEquals(1, f.uploads.size); assertTrue(f.joins.isEmpty())
        assertTrue(f.prepare(3)); assertNotEquals(id, f.state().attempt!!.id)
        assertArrayEquals(launched, f.state().history.single())
        assertEquals(1, f.uploads.size); assertTrue(f.joins.isEmpty())
    }
    @Test fun vaultReadCannotReleaseConfirmationFromMemberOrEpochSnapshotsTakenBeforeThatIo() {
        val f = Fixture(); f.prepare(); val id = f.state().attempt!!.id
        f.listing = { f.epoch = 8 }
        assertThrows(VaultResetException::class.java) { f.manager().continueOnce(id) }
        assertEquals(GpmPinRenewalPhase.VAULT_LAUNCHED, f.state().attempt!!.phase); assertTrue(f.joins.isEmpty())
        val g = Fixture(); g.prepare(); val other = g.state().attempt!!.id
        g.listing = { g.members = emptyList() }
        assertThrows(VaultLockedException::class.java) { g.manager().continueOnce(other) }
        assertNotEquals(GpmPinRenewalPhase.CONFIRMED, g.state().attempt!!.phase); assertTrue(g.joins.isEmpty())
        val h = Fixture(); h.prepare(); val done = h.state().attempt!!.id; h.manager().continueOnce(done)
        h.listing = { h.epoch = 8 }
        assertThrows(VaultResetException::class.java) { h.manager().reconcile() }
        assertEquals(GpmPinRenewalPhase.REVOKED, h.state().attempt!!.phase)
    }
    @Test fun resettingMaximumValidHistoryAndAnActiveAttemptRemainsDecodableAndRetainsLatestReceiptHash() {
        val f = Fixture(); f.prepare(); val attempt = f.state().attempt!!
        val original = GpmPinRenewalState(attempt, List(100) { Proto.integer(1, it.toLong()) }).encode()
        val cleared = GpmPinRenewalState.decode(GpmPinRenewalState.clearDomain(original, "hw_protected"))
        assertNull(cleared.attempt); assertEquals(100, cleared.history.size)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(attempt.encode()), Proto.decode(cleared.history.last()).one(1))
    }
}
