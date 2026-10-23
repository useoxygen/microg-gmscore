/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.folsom.utils.*
import java.io.IOException
import java.math.BigInteger
import javax.crypto.spec.SecretKeySpec

/** Real upper/lower coordinators with one shared synthetic account journal, not a ready boolean as the lower oracle. */
class FolsomConsentLowerJournalTest {
    private val account = "synthetic-consent-gaia"
    private fun pair(scalar: Int) = ByteArray(31) + byteArrayOf(scalar.toByte()) + SECNamedCurves.getByName("secp256r1").g
        .multiply(BigInteger.valueOf(scalar.toLong())).normalize().getEncoded(false)
    private inner class Fixture {
        var ownerAllowed = true
        var snapshotReady = false
        var unknownUpload = false
        var lower = byteArrayOf()
        var upper = byteArrayOf()
        var uploads = 0
        var joins = 0
        var commits = 0
        var identities = 0
        var consent = false
        val targets = listOf("chromesync", "hw_protected")
        val vaults = ArrayList<ByteArray>()
        val members = HashMap<String, ByteArray>()
        fun verify() { if (!ownerAllowed) throw VaultLockedException() }
        fun lowerState() = RecoverableVaultState.decode(lower)
        fun upperState() = FolsomConsentState.decode(upper)
        fun sync(domain: String): RecoverableVaultStatus {
            val crypto = object : EnrollmentCrypto {
                override fun generatePair() = pair(if (domain == "chromesync") 1 else 2)
                override fun wrap(publicKey: ByteArray, material: ByteArray) = Proto.field(1, publicKey) + Proto.field(2, material)
                override fun unwrap(pair: ByteArray, wrapped: ByteArray): ByteArray {
                    val encoded = Proto.decode(wrapped)
                    if (!encoded.one(1).contentEquals(EnrollmentWire.publicKey(pair))) throw ConflictException()
                    return encoded.one(2)
                }
            }
            val os = object : RecoverableVaultPlatform {
                override fun prepare(alias: String, handle: ByteArray) { verify() }
                override fun snapshot(alias: String, handle: ByteArray) = if (!snapshotReady) null else RecoverableVaultSnapshot(
                    1, pair(3).copyOfRange(32, 97), 4, 10, handle.copyOf(),
                    RecoverableVaultWire.protection(1, 1, byteArrayOf(5), 0, byteArrayOf(6)), byteArrayOf(7), byteArrayOf(8))
                override fun wrap(alias: String, pair: ByteArray) = RecoverableVaultWire.wrapPair(SecretKeySpec(ByteArray(32) { 9 }, "AES"), pair)
                override fun synced(alias: String) { verify() }
            }
            val remote = object : RecoverableVaultRemote {
                override fun domain(domain: String, id: String) = Proto.field(1, DomainLifecycleWire.name(domain)) + Proto.integer(2, 3)
                override fun members(domain: String, id: String) = listOfNotNull(members[domain])
                override fun vaults(id: String) = vaults.toList()
                override fun update(request: ByteArray): ByteArray {
                    verify(); uploads++
                    assertEquals(RecoverableVaultPhase.VAULT_LAUNCHED, lowerState().attempt!!.phase)
                    if (unknownUpload) throw IOException("synthetic lost upload before known outcome")
                    val created = Proto.decode(request).one(3) + Proto.field(1, "vaults/synthetic-$uploads")
                    vaults.add(created); return created
                }
                override fun join(request: ByteArray): ByteArray {
                    verify(); joins++
                    assertEquals(RecoverableVaultPhase.JOIN_LAUNCHED, lowerState().attempt!!.phase)
                    val encoded = Proto.decode(request)
                    val membership = Proto.field(1, DomainLifecycleWire.name(domain)) +
                        encoded.bytes(3).fold(byteArrayOf()) { out, key -> out + Proto.field(3, key) }
                    members[domain] = Proto.decode(encoded.one(2)).replaceRepeated(3, listOf(membership)).encode()
                    return Proto.field(1, domain(domain, id = encoded.string(6)))
                }
            }
            val manager = RecoverableVaultManager(account, null, domain, 123, crypto, os, remote,
                { listOf(DomainLifecycleKey(3, ByteArray(32) { if (domain == "chromesync") 10 else 11 })) },
                ::lowerState, { lower = it.encode() }, ::verify,
                { RecoverableVaultWire.ALIAS_PREFIX + "1000/" + (++identities) }, { RecoverableVaultWire.handle(identities.toLong(), 12) })
            val actual = manager.syncConsent().attempt ?: throw ProtocolException()
            return RecoverableVaultStatus(actual.domain, actual.phase, actual.id, actual.phase == RecoverableVaultPhase.WAITING_FOR_SNAPSHOT)
        }
        private val remote = object : FolsomConsentRemote {
            override fun review(domain: String, value: Boolean, id: String) = Proto.field(1, account) + Proto.field(2, domain) + Proto.integer(3, if (value) 1 else 0)
            override fun mutations(review: ByteArray, id: String) = emptyList<FolsomConsentMutation>()
            override fun synchronize(review: ByteArray, id: String) = FolsomConsentLowerJournals.synchronize(targets, ::sync)
            override fun synchronized(review: ByteArray, id: String) = FolsomConsentLowerJournals.synchronize(targets, ::sync)
            override fun verify(review: ByteArray) { this@Fixture.verify(); assertEquals(account, Proto.decode(review).string(1)) }
            override fun invoke(kind: FolsomConsentMutationKind, request: ByteArray): ByteArray = throw AssertionError("No membership metadata mutation in this lower-journal fixture")
            override fun applied(mutation: FolsomConsentMutation) = false
        }
        fun manager() = FolsomConsentManager(account, remote, ::upperState, { before, after ->
            verify(); if (!before.encode().contentEquals(upperState().encode())) throw ConflictException(); upper = after.encode()
        }, { before, after, _, value ->
            verify(); if (!before.encode().contentEquals(upperState().encode())) throw ConflictException()
            consent = value; upper = after.encode(); commits++
        }, ::verify)
    }

    @Test fun realSnapshotArrivingBetweenSdkCallsUnblocksBothDomainsThroughTheirExistingJournals() {
        val f = Fixture(); f.manager().set("chromesync", true)
        assertEquals(FolsomConsentPhase.SYNC_LAUNCHED, f.upperState().attempt!!.phase)
        assertEquals("chromesync", f.lowerState().attempt!!.domain)
        assertEquals(RecoverableVaultPhase.WAITING_FOR_SNAPSHOT, f.lowerState().attempt!!.phase)
        assertFalse(f.consent); assertEquals(0, f.uploads); assertEquals(0, f.joins)
        val oldId = f.upperState().attempt!!.id
        f.snapshotReady = true; f.manager().set("chromesync", true)
        assertEquals(oldId, f.upperState().attempt!!.id); assertEquals(FolsomConsentPhase.COMMITTED, f.upperState().attempt!!.phase)
        assertTrue(f.consent); assertEquals(1, f.commits); assertEquals(2, f.uploads); assertEquals(2, f.joins)
        assertEquals(setOf("chromesync", "hw_protected"), f.members.keys)
        assertNotNull(f.lowerState().pair); assertNotNull(f.lowerState().hardwarePair)
    }
    @Test fun unknownLowerUploadRemainsFencedWhenUpperSdkConsentIsReopened() {
        val f = Fixture(); f.snapshotReady = true; f.unknownUpload = true
        assertThrows(IOException::class.java) { f.manager().set("chromesync", true) }
        assertEquals(FolsomConsentPhase.SYNC_LAUNCHED, f.upperState().attempt!!.phase)
        assertEquals(RecoverableVaultPhase.VAULT_LAUNCHED, f.lowerState().attempt!!.phase)
        val durable = f.lower.copyOf(); f.unknownUpload = false; f.manager().set("chromesync", true)
        assertArrayEquals(durable, f.lower); assertEquals(1, f.uploads); assertEquals(0, f.joins)
        assertEquals(0, f.commits); assertFalse(f.consent)
    }
    @Test fun revokingOwnerBeforeTheReadySnapshotPreventsAllLaterDispatchAndConsentCommit() {
        val f = Fixture(); f.manager().set("chromesync", true)
        val before = f.lower.copyOf(); f.snapshotReady = true; f.ownerAllowed = false
        assertThrows(VaultLockedException::class.java) { f.manager().set("chromesync", true) }
        assertArrayEquals(before, f.lower); assertEquals(0, f.uploads); assertEquals(0, f.joins); assertEquals(0, f.commits)
    }
    @Test fun anotherPendingDomainOutsideTheReviewedTargetSetIsNeverAdvanced() {
        val invoked = ArrayList<String>()
        val ready = FolsomConsentLowerJournals.synchronize(listOf("chromesync")) { target ->
            invoked.add(target); RecoverableVaultStatus("hw_protected", RecoverableVaultPhase.WAITING_FOR_SNAPSHOT, "other", true)
        }
        assertFalse(ready); assertEquals(listOf("chromesync"), invoked)
    }
}
