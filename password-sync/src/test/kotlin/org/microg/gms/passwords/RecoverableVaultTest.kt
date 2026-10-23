/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Pure source-shaped policy/codec fixtures. Native OS, Google admission and provider acceptance remain separate. */
class RecoverableVaultTest {
    private val owner = "synthetic-gaia"
    private val parent = "users/me/birthdays/synthetic-recovery"
    private fun pair(n: Int): ByteArray = ByteArray(31) + byteArrayOf(n.toByte()) +
        SECNamedCurves.getByName("secp256r1").g.multiply(BigInteger.valueOf(n.toLong())).normalize().getEncoded(false)
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun snapshot(handle: ByteArray, recovery: Int = 5) = RecoverableVaultSnapshot(1, pair(7).copyOfRange(32, 97),
        0x0102030405060708, 10, handle, RecoverableVaultWire.protection(1, 2, byteArrayOf(7), 4096, byteArrayOf(8)),
        byteArrayOf(recovery.toByte()), byteArrayOf(6))
    private inner class Fixture(val domain: String = "chromesync") {
        var raw = byteArrayOf()
        var allowed = true
        var epoch = 3
        var snapshotReady = true
        var staleSnapshot = false
        var aliases = 0
        var prepares = 0
        var uploads = 0
        var joins = 0
        var synced = 0
        var loseUpload = false
        var applyUpload = true
        var loseJoin = false
        var applyJoin = true
        var wrongFinalDomain = false
        var readPresentMember = false
        val vaults = ArrayList<ByteArray>()
        val members = ArrayList<ByteArray>()
        val launched = ArrayList<Pair<RecoverableVaultPhase, ByteArray>>()
        val crypto = object : EnrollmentCrypto {
            override fun generatePair() = pair(if (domain == "chromesync") 1 else 2)
            // These deliberately transparent envelopes test coordinator authority, not SecureBox cryptography.
            override fun wrap(publicKey: ByteArray, material: ByteArray) = Proto.field(1, publicKey) + Proto.field(2, material)
            override fun unwrap(pair: ByteArray, wrapped: ByteArray): ByteArray {
                val p = Proto.decode(wrapped)
                if (!p.one(1).contentEquals(EnrollmentWire.publicKey(pair))) throw ProtocolException()
                return p.one(2)
            }
        }
        val platform = object : RecoverableVaultPlatform {
            override fun prepare(alias: String, handle: ByteArray) { verify(); prepares++ }
            override fun snapshot(alias: String, handle: ByteArray) = if (!snapshotReady) null else snapshot(handle, if (staleSnapshot) 9 else 5)
            override fun wrap(alias: String, pair: ByteArray) = RecoverableVaultWire.wrapPair(SecretKeySpec(ByteArray(32) { 3 }, "AES"), pair)
            override fun synced(alias: String) { verify(); synced++ }
        }
        val remote = object : RecoverableVaultRemote {
            override fun domain(domain: String, id: String) = Proto.field(1, DomainLifecycleWire.name(
                if (wrongFinalDomain && readPresentMember) "hw_protected" else domain)) + Proto.integer(2, epoch.toLong())
            override fun members(domain: String, id: String) = members.toList().also { readPresentMember = it.isNotEmpty() }
            override fun vaults(id: String) = vaults.toList()
            override fun update(request: ByteArray): ByteArray {
                verify(); uploads++
                assertEquals(RecoverableVaultPhase.VAULT_LAUNCHED, state().attempt!!.phase)
                launched.add(RecoverableVaultPhase.VAULT_LAUNCHED to request.copyOf())
                val vault = Proto.decode(request).one(3) + Proto.field(1, "vaults/synthetic-$uploads")
                if (applyUpload) { vaults.clear(); vaults.add(vault) }
                if (loseUpload) throw PasswordRpcException(14, true)
                return vault
            }
            override fun join(request: ByteArray): ByteArray {
                verify(); joins++
                assertEquals(RecoverableVaultPhase.JOIN_LAUNCHED, state().attempt!!.phase)
                launched.add(RecoverableVaultPhase.JOIN_LAUNCHED to request.copyOf())
                val p = Proto.decode(request)
                val membership = Proto.field(1, DomainLifecycleWire.name(domain)) +
                    p.bytes(3).fold(byteArrayOf()) { out, key -> out + Proto.field(3, key) }
                val member = Proto.decode(p.one(2)).replaceRepeated(3, listOf(membership)).encode()
                if (applyJoin) { members.clear(); members.add(member) }
                if (loseJoin) throw PasswordRpcException(14, true)
                return Proto.field(1, domain(domain, p.string(6)))
            }
        }
        fun verify() { if (!allowed) throw VaultLockedException() }
        fun state() = RecoverableVaultState.decode(raw)
        fun save(state: RecoverableVaultState) { raw = state.encode() }
        fun manager(boundOwner: String = owner, boundParent: String? = parent) = RecoverableVaultManager(boundOwner, boundParent,
            domain, 123, crypto, platform, remote, { listOf(DomainLifecycleKey(3, ByteArray(32) { 4 })) }, ::state, ::save, ::verify,
            { RecoverableVaultWire.ALIAS_PREFIX + "1000/" + (++aliases) }, { RecoverableVaultWire.handle(aliases.toLong(), 7) })
        fun prepare() { manager().prepare() }
        fun proceed() { manager().continueOnce(state().attempt!!.id) }
    }

    @Test fun handleAndProtectionUseIndependentExactFieldAndLittleEndianLiterals() {
        assertArrayEquals(hex("0108070605040302011817161514131211"),
            RecoverableVaultWire.handle(0x0102030405060708, 0x1112131415161718))
        assertArrayEquals(hex("08031003180320032a01013080403a0102"),
            RecoverableVaultWire.protection(3, 2, byteArrayOf(1), 8192, byteArrayOf(2)))
        val list = Proto.decode(RecoverableVaultWire.list("session", 123, "next"))
        assertEquals(50L, list.integer(1)); assertEquals("next", list.string(2)); assertNull(list.optional(3))
        assertArrayEquals(hex("120773657373696f6e187b"), list.one(7))
        assertThrows(ProtocolException::class.java) { RecoverableVaultWire.protection(0, 2, byteArrayOf(1), 1, byteArrayOf(2)) }
    }
    @Test fun sdkConsentConfiguresARealWaitingSnapshotWithoutRequiringALocalPasswordVaultAndLaterCompletes() {
        val f = Fixture(); f.snapshotReady = false
        val waiting = f.manager(boundParent = null).syncConsent()
        assertEquals(RecoverableVaultPhase.WAITING_FOR_SNAPSHOT, waiting.attempt!!.phase)
        assertNotNull(waiting.pair); assertNotNull(waiting.alias); assertNotNull(waiting.handle)
        assertTrue(f.prepares > 0); assertEquals(0, f.uploads); assertEquals(0, f.joins)
        f.snapshotReady = true; f.manager(boundParent = null).syncConsent()
        assertEquals(RecoverableVaultPhase.CONFIRMED, f.state().attempt!!.phase)
        assertEquals(1, f.uploads); assertEquals(1, f.joins)
        f.manager(boundParent = null).syncConsent()
        assertEquals(1, f.uploads); assertEquals(1, f.joins)
    }
    @Test fun sdkConsentNeverReplaysUnknownUploadsOrJoins() {
        for (upload in listOf(true, false)) {
            val f = Fixture(); f.loseUpload = upload; f.applyUpload = !upload
            f.loseJoin = !upload; f.applyJoin = upload
            assertThrows(PasswordRpcException::class.java) { f.manager(boundParent = null).syncConsent() }
            val raw = f.raw.copyOf(); val uploads = f.uploads; val joins = f.joins
            f.manager(boundParent = null).syncConsent()
            assertArrayEquals(raw, f.raw); assertEquals(uploads, f.uploads); assertEquals(joins, f.joins)
        }
    }
    @Test fun sdkConsentDoesNotOverwriteAnotherDomainsPendingSetupOrClaimItAsTheRequestedDomain() {
        val chrome = Fixture(); chrome.snapshotReady = false; chrome.manager(boundParent = null).syncConsent()
        val hardware = Fixture("hw_protected"); hardware.raw = chrome.raw.copyOf()
        val retained = hardware.manager(boundParent = null).syncConsent()
        assertEquals("chromesync", retained.attempt!!.domain); assertNull(retained.hardwarePair)
        assertArrayEquals(chrome.raw, hardware.raw); assertEquals(0, hardware.prepares); assertEquals(0, hardware.uploads)
    }
    @Test fun sdkConsentRetainsMemberIdentitiesAcrossCompletedDomainsAndExplicitReenableAfterLeave() {
        val chrome = Fixture(); chrome.manager(boundParent = null).syncConsent()
        val originalPair = chrome.state().pair!!.copyOf()
        chrome.members.clear() // A proven external Leave removed this exact member; no RPC is replayed by the fixture.
        chrome.manager(boundParent = null).syncConsent()
        assertArrayEquals(originalPair, chrome.state().pair); assertEquals(2, chrome.joins)
        val hardware = Fixture("hw_protected"); hardware.raw = chrome.raw.copyOf(); hardware.aliases = chrome.aliases
        hardware.manager(boundParent = null).syncConsent()
        assertEquals("hw_protected", hardware.state().attempt!!.domain)
        assertEquals(RecoverableVaultPhase.CONFIRMED, hardware.state().attempt!!.phase)
        assertArrayEquals(originalPair, hardware.state().pair); assertArrayEquals(pair(2), hardware.state().hardwarePair)
        assertEquals(2, Proto.decode(Proto.decode(hardware.state().attempt!!.update!!).one(3)).bytes(5).size)
    }
    @Test fun fullCombinedPairIsWrappedByActualAesGcmWithNoAadRatherThanOnlyItsScalar() {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"); val pair = pair(1)
        val encrypted = RecoverableVaultWire.wrapPair(key, pair)
        assertEquals(125, encrypted.size)
        val independent = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            doFinal(encrypted, 12, encrypted.size - 12)
        }
        assertArrayEquals(pair, independent); assertEquals(97, independent.size)
        assertThrows(ProtocolException::class.java) { RecoverableVaultWire.wrapPair(key, pair.copyOfRange(0, 32)) }
    }
    @Test fun eachLocalLskfIdentityHasItsOwnPairButBothUseWireTypeTwoWithoutInventedMetadata() {
        for (domain in listOf("chromesync", "hw_protected")) {
            val f = Fixture(domain); f.prepare()
            val request = Proto.decode(f.state().attempt!!.join)
            val member = Proto.decode(request.one(2))
            assertEquals(2L, member.integer(4)); assertNull(member.optional(6))
            assertEquals(DomainLifecycleWire.name(domain), Proto.decode(request.one(1)).string(1))
            assertEquals(3L, Proto.decode(request.bytes(3).single()).integer(1))
            assertArrayEquals(EnrollmentWire.publicKey(pair(if (domain == "chromesync") 1 else 2)), member.one(2))
        }
    }
    @Test fun missingActualSnapshotRetainsStablePreparationWithoutAnyRemoteMutation() {
        val f = Fixture(); f.snapshotReady = false; f.prepare()
        val old = f.state(); assertEquals(RecoverableVaultPhase.WAITING_FOR_SNAPSHOT, old.attempt!!.phase)
        assertNull(old.attempt.update); assertEquals(0, f.uploads); assertEquals(0, f.joins)
        f.proceed()
        assertEquals(old.alias, f.state().alias); assertArrayEquals(old.handle, f.state().handle)
        assertArrayEquals(old.pair, f.state().pair); assertEquals(0, f.uploads)
        f.snapshotReady = true; f.proceed(); assertEquals(RecoverableVaultPhase.CONFIRMED, f.state().attempt!!.phase)
    }
    @Test fun preparationDoesNotDispatchAndCompletionRequiresActualVaultAndMemberReadback() {
        val f = Fixture(); f.prepare()
        assertEquals(RecoverableVaultPhase.PREPARED, f.state().attempt!!.phase)
        assertEquals(0, f.uploads); assertEquals(0, f.joins)
        val prepared = f.state().attempt!!
        f.proceed()
        assertEquals(RecoverableVaultPhase.CONFIRMED, f.state().attempt!!.phase)
        assertEquals(1, f.uploads); assertEquals(1, f.joins); assertTrue(f.synced > 0)
        assertArrayEquals(prepared.update, f.launched[0].second); assertArrayEquals(prepared.join, f.launched[1].second)
        f.proceed(); assertEquals(1, f.uploads); assertEquals(1, f.joins)
    }
    @Test fun lostUploadReplyReconcilesActualEffectAndNeverRepeatsTheLaunchedUpload() {
        val f = Fixture(); f.prepare(); f.loseUpload = true
        assertThrows(PasswordRpcException::class.java) { f.proceed() }
        assertEquals(RecoverableVaultPhase.VAULT_LAUNCHED, f.state().attempt!!.phase)
        f.manager().reconcile()
        assertEquals(RecoverableVaultPhase.VAULT_CONFIRMED, f.state().attempt!!.phase)
        f.proceed(); assertEquals(1, f.uploads); assertEquals(1, f.joins)
        assertEquals(RecoverableVaultPhase.CONFIRMED, f.state().attempt!!.phase)
    }
    @Test fun unappliedUnknownUploadNeverReplaysEvenAfterReopenOrExplicitContinue() {
        val f = Fixture(); f.prepare(); f.loseUpload = true; f.applyUpload = false
        assertThrows(PasswordRpcException::class.java) { f.proceed() }
        val request = f.state().attempt!!.update!!.copyOf()
        f.manager().reconcile()
        assertThrows(PendingMutationException::class.java) { f.proceed() }
        assertEquals(1, f.uploads); assertEquals(0, f.joins)
        assertArrayEquals(request, f.state().attempt!!.update)
    }
    @Test fun uploadAcknowledgementWithoutPersistenceCannotEnrollTheMember() {
        val f = Fixture(); f.prepare(); f.applyUpload = false
        assertThrows(PendingMutationException::class.java) { f.proceed() }
        assertEquals(RecoverableVaultPhase.VAULT_LAUNCHED, f.state().attempt!!.phase)
        assertEquals(1, f.uploads); assertEquals(0, f.joins)
    }
    @Test fun lostJoinReplyConfirmsOnlyObservedMemberAndUnknownMissingMemberNeverReplays() {
        for (applied in listOf(false, true)) {
            val f = Fixture(); f.prepare(); f.loseJoin = true; f.applyJoin = applied
            assertThrows(PasswordRpcException::class.java) { f.proceed() }
            f.manager().reconcile()
            assertEquals(if (applied) RecoverableVaultPhase.CONFIRMED else RecoverableVaultPhase.JOIN_LAUNCHED, f.state().attempt!!.phase)
            if (!applied) assertThrows(PendingMutationException::class.java) { f.proceed() }
            assertEquals(1, f.uploads); assertEquals(1, f.joins)
        }
    }
    @Test fun ownerAccountBirthdayAndEpochChangesCannotLaunchPreparedRequests() {
        for (gate in listOf("owner", "account", "birthday", "epoch")) {
            val f = Fixture(); f.prepare(); val id = f.state().attempt!!.id
            when (gate) { "owner" -> f.allowed = false; "epoch" -> f.epoch = 4 }
            assertThrows(Exception::class.java) {
                f.manager(if (gate == "account") "replacement" else owner,
                    if (gate == "birthday") "users/me/birthdays/reset" else parent).continueOnce(id)
            }
            assertEquals(0, f.uploads); assertEquals(0, f.joins)
        }
    }
    @Test fun changedAndroidProtectionSnapshotCannotUploadPreviouslyPreparedCiphertext() {
        val f = Fixture(); f.prepare(); f.staleSnapshot = true
        assertThrows(ConflictException::class.java) { f.proceed() }
        assertEquals(0, f.uploads); assertEquals(0, f.joins)
    }
    @Test fun finalDomainReadWithAnotherNameAndTheSameEpochCannotReleaseConfirmation() {
        val f = Fixture(); f.prepare(); f.wrongFinalDomain = true
        assertThrows(VaultResetException::class.java) { f.proceed() }
        assertEquals(1, f.uploads); assertEquals(1, f.joins)
        assertEquals(RecoverableVaultPhase.JOIN_LAUNCHED, f.state().attempt!!.phase)
        assertEquals(0, f.synced)
    }
    @Test fun foreignVaultEntryOnTheSameHandleCannotBeOverwritten() {
        val f = Fixture(); f.snapshotReady = false; f.prepare()
        val state = f.state()
        val foreign = Proto.field(2, Proto.field(4, state.handle!!)) + Proto.field(5,
            Proto.field(1, RecoverableVaultWire.ENTRY) + Proto.field(4, Proto.field(1, EnrollmentWire.publicKey(pair(9)))))
        f.vaults.add(foreign); f.snapshotReady = true
        assertThrows(ConflictException::class.java) { f.proceed() }
        assertEquals(0, f.uploads); assertEquals(0, f.joins)
        assertArrayEquals(foreign, f.vaults.single())
    }
    @Test fun secondDomainBackupPreservesDistinctPairEntriesInOneActualSnapshotVault() {
        val chrome = Fixture(); chrome.prepare(); chrome.proceed()
        val first = chrome.state(); chrome.manager().close(first.attempt!!.id)
        val hardware = Fixture("hw_protected"); hardware.raw = chrome.raw.copyOf(); hardware.aliases = chrome.aliases
        hardware.prepare()
        val state = hardware.state(); assertArrayEquals(first.pair, state.pair)
        assertFalse(state.pair!!.contentEquals(state.hardwarePair!!))
        assertFalse(first.handle!!.contentEquals(state.handle!!)) // New explicitly authorized backup handle.
        val entries = Proto.decode(Proto.decode(state.attempt!!.update!!).one(3)).bytes(5).map(Proto::decode)
        assertEquals(setOf(RecoverableVaultWire.ENTRY, RecoverableVaultWire.HARDWARE_ENTRY), entries.map { it.string(1) }.toSet())
        entries.forEach { entry ->
            val domain = if (entry.string(1) == RecoverableVaultWire.ENTRY) "chromesync" else "hw_protected"
            assertArrayEquals(EnrollmentWire.publicKey(state.pairFor(domain)!!), Proto.decode(entry.one(4)).one(1))
            assertEquals(125, Proto.decode(entry.one(4)).one(2).size)
        }
        hardware.proceed(); assertEquals(RecoverableVaultPhase.CONFIRMED, hardware.state().attempt!!.phase)
    }
    @Test fun confirmedRemovalIsRevokedAndCannotAutomaticallyReenroll() {
        val f = Fixture(); f.prepare(); f.proceed(); f.members.clear()
        f.manager().reconcile()
        assertEquals(RecoverableVaultPhase.REVOKED, f.state().attempt!!.phase)
        assertThrows(ConflictException::class.java) { f.proceed() }
        assertEquals(1, f.uploads); assertEquals(1, f.joins)
    }
    @Test fun resetScrubsOnlyAffectedDomainRequestsAtFullHistoryCapacityAndKeepsOtherDomainExactBytes() {
        val chrome = Fixture(); chrome.prepare()
        val affected = chrome.state().attempt!!
        val hardware = Fixture("hw_protected"); hardware.prepare(); val other = hardware.state().attempt!!
        val state = RecoverableVaultState(pair(1), chrome.state().alias, chrome.state().handle, affected,
            List(100) { if (it % 2 == 0) affected.encode() else other.encode() }, hardwarePair = pair(2))
        val cleared = RecoverableVaultState.decode(RecoverableVaultState.clearDomain(state.encode(), "chromesync"))
        assertNull(cleared.attempt); assertArrayEquals(state.pair, cleared.pair); assertArrayEquals(state.hardwarePair, cleared.hardwarePair)
        assertEquals(50, cleared.history.size); cleared.history.forEach { assertArrayEquals(other.encode(), it) }
        assertEquals(1, cleared.resetReceipts.size); assertEquals(32, Proto.decode(cleared.resetReceipts.single()).one(2).size)
        val unrelated = state.withAttempt(other)
        val kept = RecoverableVaultState.decode(RecoverableVaultState.clearDomain(unrelated.encode(), "chromesync"))
        assertArrayEquals(other.encode(), kept.attempt!!.encode())
        assertArrayEquals(other.update, kept.attempt.update)
    }
    @Test fun ownerClosureRetainsUnknownExactProvenanceAndCodecRefusesCorruptAuthority() {
        val f = Fixture(); f.prepare(); f.loseUpload = true; f.applyUpload = false
        assertThrows(PasswordRpcException::class.java) { f.proceed() }
        val old = f.state(); f.manager().close(old.attempt!!.id)
        assertNull(f.state().attempt); assertArrayEquals(old.attempt.encode(), f.state().history.single())
        assertArrayEquals(old.pair, f.state().pair)
        assertThrows(ProtocolException::class.java) { RecoverableVaultState.decode(Proto.decode(old.encode()).replaceInteger(1, 2).encode()) }
        assertThrows(ProtocolException::class.java) { RecoverableVaultState.decode(Proto.decode(old.encode()).replace(2, ByteArray(97)).encode()) }
        assertThrows(ProtocolException::class.java) { RecoverableVaultAttempt.decode(Proto.decode(old.attempt.encode()).replace(6, byteArrayOf()).encode()) }
    }
}
