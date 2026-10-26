/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import okio.ByteString.Companion.toByteString
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.folsom.*
import org.microg.gms.auth.folsom.utils.*
import java.io.IOException
import java.math.BigInteger

/** Actual generated wire adapters and coordinator; native OS snapshot/admission stay separate acceptance. */
class FolsomConsentTest {
    private val owner = "synthetic-consent-gaia"
    private fun public(n: Int) = SECNamedCurves.getByName("secp256r1").g.multiply(BigInteger.valueOf(n.toLong())).normalize().getEncoded(false)
    private fun account(chrome: Boolean?, hardware: Boolean?) = AccountData(googleUserId = owner, domains = listOf(
        AccountData.DomainsEntry("chromesync", DomainData(consent = chrome, googleUserId = owner)),
        AccountData.DomainsEntry("hw_protected", DomainData(consent = hardware, googleUserId = owner))))
    @Test fun sourceOptionalConsentCouplingAndExplicitFalseOverrideAreDistinct() {
        assertFalse(FolsomConsentPolicy.effective(null, "chromesync"))
        for ((chrome, hardware) in listOf(null to null, false to false, true to false, false to true, true to true)) {
            val state = account(chrome, hardware); val expected = chrome == true || hardware == true
            assertEquals(expected, FolsomConsentPolicy.effective(state, "chromesync"))
            assertEquals(expected, FolsomConsentPolicy.effective(state, "hw_protected"))
            assertFalse(FolsomConsentPolicy.effective(state, "chromesync", "hw_protected", false))
            assertFalse(FolsomConsentPolicy.effective(state, "hw_protected", "chromesync", false))
            assertTrue(FolsomConsentPolicy.effective(state, "hw_protected", "chromesync", true))
        }
        val absent = AccountData.ADAPTER.decode(account(null, null).encode())
        val explicit = FolsomConsentPolicy.store(absent, owner, "chromesync", false)
        assertNull(absent.domains.single { it.key == "chromesync" }.value_!!.consent)
        assertEquals(false, AccountData.ADAPTER.decode(explicit.encode()).domains.single { it.key == "chromesync" }.value_!!.consent)
    }
    @Test fun sourcePersistsOnlySelectedFlagAndPreservesKeysPhysicalIdentityAndUnknownMetadata() {
        val key = Keys(keyVersion = 7, keyMaterial = ByteArray(32) { 4 }.toByteString())
        val domain = DomainData.ADAPTER.decode(DomainData(consent = true, keys = listOf(key), googleUserId = owner).encode() + Proto.field(99, "opaque-domain"))
        val physical = KeyPair(publicKey = public(2).toByteString(), privateKey = ByteArray(32) { 5 }.toByteString(), keyPairType = 1)
        val original = AccountData.ADAPTER.decode(AccountData(googleUserId = owner, accountKeyPairs = listOf(physical), domains = listOf(
            AccountData.DomainsEntry("chromesync", domain), AccountData.DomainsEntry("hw_protected", DomainData(consent = true, googleUserId = owner))))
            .encode() + Proto.field(100, "opaque-account"))
        val changed = FolsomConsentPolicy.store(original, owner, "chromesync", false)
        assertEquals(false, changed.domains.single { it.key == "chromesync" }.value_!!.consent)
        assertEquals(true, changed.domains.single { it.key == "hw_protected" }.value_!!.consent)
        assertTrue(FolsomConsentPolicy.effective(changed, "chromesync")) // anza getter OR differs from anwc's explicit false override.
        assertEquals(listOf(key), changed.domains.single { it.key == "chromesync" }.value_!!.keys)
        assertEquals(original.accountKeyPairs, changed.accountKeyPairs)
        assertEquals(original.unknownFields, changed.unknownFields)
        assertEquals(domain.unknownFields, changed.domains.single { it.key == "chromesync" }.value_!!.unknownFields)
        assertThrows(Exception::class.java) { FolsomConsentPolicy.store(original, "replacement-gaia", "chromesync", false) }
    }
    @Test fun independentSourceLeaveAndMembershipMetadataWireRoundTripsGeneratedSdkWithoutRotationMetadata() {
        val pub = public(3)
        val leave = FolsomConsentWire.leave("hw_protected", pub, "source-request")
        assertArrayEquals(leave, LeaveSecurityDomainRequest.ADAPTER.decode(leave).encode())
        assertEquals(DomainLifecycleWire.name("hw_protected"), Proto.decode(leave).string(1))
        assertEquals(EnrollmentWire.memberName(pub), Proto.decode(leave).string(2)); assertEquals("source-request", Proto.decode(leave).string(3))
        for (backup in listOf(false, true)) for (consent in listOf(false, true)) {
            val request = FolsomConsentWire.update("chromesync", pub, backup, consent, "source-request")
            assertArrayEquals(request, UpdateSecurityDomainMemberRequest.ADAPTER.decode(request).encode())
            val outer = Proto.decode(request); val member = Proto.decode(outer.one(1)); val membership = Proto.decode(member.one(3))
            assertEquals(EnrollmentWire.memberName(pub), member.string(1)); assertNull(member.optional(2))
            assertEquals(DomainLifecycleWire.name("chromesync"), membership.string(1))
            assertTrue(membership.bytes(3).isEmpty()); assertTrue(membership.bytes(4).isEmpty()); assertNull(membership.optional(5))
            val flags = Proto.decode(Proto.decode(membership.one(6)).one(1))
            assertEquals(if (backup) 1L else 0L, flags.integer(1)); assertEquals(if (consent) 1L else 0L, flags.integer(2))
            assertEquals("memberships.metadata.lockscreen_membership_metadata", Proto.decode(outer.one(2)).string(1))
            assertEquals("source-request", outer.string(3))
        }
        assertThrows(Exception::class.java) { FolsomConsentWire.update("hw_protected", pub, true, true, "id") }
    }
    /** Finite coordinator work oracle; native backup/consent branching has separate lower-journal coverage. */
    private inner class Fixture {
        var disk = FolsomConsentState().encode(); var stored = account(false, false)
        var actualOwner = owner; var epoch = 7; var resetPending = false
        var syncReady = true; var syncLost = false; var persistPreparedDeath = false; var beforeInvocationDeath = false
        var loseMutation = false; var loseReadback = false; var afterInvoke: () -> Unit = {}
        var commits = 0; var syncs = 0; var reviews = 0
        val calls = ArrayList<ByteArray>(); val applied = HashSet<String>()
        fun state() = FolsomConsentState.decode(disk)
        fun authorize() { if (actualOwner != owner || resetPending) throw VaultLockedException() }
        private fun key(raw: ByteArray) = java.util.Base64.getEncoder().encodeToString(raw)
        val remote = object : FolsomConsentRemote {
            override fun review(domain: String, value: Boolean, id: String): ByteArray { authorize(); reviews++
                return Proto.field(1, domain) + Proto.integer(2, if (value) 1 else 0) + Proto.integer(3, epoch.toLong()) + Proto.field(4, owner) }
            override fun mutations(review: ByteArray, id: String): List<FolsomConsentMutation> {
                val value = Proto.decode(review).integer(2) == 1L
                val leaves = if (value) emptyList() else listOf("chromesync", "hw_protected").mapIndexed { index, domain ->
                    FolsomConsentMutation(FolsomConsentMutationKind.LEAVE, domain, FolsomConsentWire.leave(domain, public(index + 3), id)) }
                return leaves + FolsomConsentMutation(FolsomConsentMutationKind.UPDATE, "chromesync", FolsomConsentWire.update("chromesync", public(2), true, value, id))
            }
            override fun synchronize(review: ByteArray, id: String): Boolean {
                assertEquals(FolsomConsentPhase.SYNC_LAUNCHED, state().attempt!!.phase); assertEquals(id, state().attempt!!.id)
                syncs++; if (syncLost) throw IOException("synthetic lower journal response lost"); return syncReady
            }
            override fun synchronized(review: ByteArray, id: String): Boolean = syncReady
            override fun verify(review: ByteArray) { authorize(); val p = Proto.decode(review)
                if (p.string(4) != actualOwner || p.integer(3) != epoch.toLong()) throw VaultResetException() }
            override fun invoke(kind: FolsomConsentMutationKind, request: ByteArray): ByteArray {
                assertEquals(FolsomConsentPhase.SYNCED, state().attempt!!.phase)
                val mutation = state().attempt!!.mutations.single { it.request.contentEquals(request) }
                assertEquals(FolsomConsentMutationPhase.LAUNCHED, mutation.phase)
                if (beforeInvocationDeath) throw IOException("synthetic crash immediately after durable launch")
                calls.add(request.copyOf()); applied.add(key(request)); afterInvoke()
                if (loseMutation) throw IOException("synthetic committed mutation response lost")
                return byteArrayOf()
            }
            override fun applied(mutation: FolsomConsentMutation): Boolean {
                if (loseReadback) throw PasswordRpcException(7, false)
                return key(mutation.request) in applied
            }
        }
        fun manager(bound: String = owner) = FolsomConsentManager(bound, remote, ::state,
            { before, after -> authorize(); assertArrayEquals(before.encode(), disk); disk = after.encode()
                if (persistPreparedDeath && after.attempt?.phase == FolsomConsentPhase.PREPARED) throw IOException("synthetic prepared save then death") },
            { before, after, domain, value ->
                authorize(); assertArrayEquals(before.encode(), disk)
                assertEquals(FolsomConsentPhase.COMMITTED, after.attempt!!.phase)
                assertTrue(after.attempt!!.mutations.all { it.phase == FolsomConsentMutationPhase.COMMITTED })
                stored = FolsomConsentPolicy.store(stored, owner, domain, value); disk = after.encode(); commits++
            }, ::authorize)
    }
    @Test fun trueConsentWaitsForRealLowerJournalThenMetadataReadbackBeforePersistingTrue() {
        val f = Fixture(); f.syncReady = false
        f.manager().set("chromesync", true)
        assertEquals(FolsomConsentPhase.SYNC_LAUNCHED, f.state().attempt!!.phase); assertEquals(1, f.syncs); assertTrue(f.calls.isEmpty()); assertEquals(0, f.commits)
        assertFalse(FolsomConsentPolicy.effective(f.stored, "chromesync"))
        f.manager().set("chromesync", true); assertEquals(1, f.syncs)
        f.syncReady = true; f.manager().reconcile(); assertEquals(FolsomConsentPhase.SYNCED, f.state().attempt!!.phase); assertTrue(f.calls.isEmpty())
        f.manager().set("chromesync", true)
        assertEquals(FolsomConsentPhase.COMMITTED, f.state().attempt!!.phase); assertEquals(1, f.calls.size); assertEquals(1, f.commits)
        assertTrue(FolsomConsentPolicy.effective(f.stored, "hw_protected"))
    }
    @Test fun finiteReviewedMutationGroupCompletesBeforeTheSelectedConsentFlagChanges() {
        val f = Fixture(); f.stored = account(true, false); f.manager().set("chromesync", false)
        assertEquals(3, f.calls.size); assertEquals(1, f.commits)
        val leaves = f.state().attempt!!.mutations.filter { it.kind == FolsomConsentMutationKind.LEAVE }
        assertEquals(setOf("chromesync", "hw_protected"), leaves.map { it.domain }.toSet())
        assertEquals(setOf(EnrollmentWire.memberName(public(3)), EnrollmentWire.memberName(public(4))), leaves.map { Proto.decode(it.request).string(2) }.toSet())
        assertTrue(leaves.none { Proto.decode(it.request).string(2) == EnrollmentWire.memberName(public(2)) })
        assertFalse(FolsomConsentPolicy.effective(f.stored, "chromesync"))
    }
    @Test fun unknownLeaveRetainsLaunchAndReopenReadsReceiptWithoutAutomaticallyDispatchingAnything() {
        val f = Fixture(); f.loseMutation = true
        assertThrows(IOException::class.java) { f.manager().set("chromesync", false) }
        assertEquals(FolsomConsentMutationPhase.LAUNCHED, f.state().attempt!!.mutations.first().phase); assertEquals(1, f.calls.size)
        val request = f.calls.single().copyOf(); f.manager().reconcile()
        assertEquals(FolsomConsentMutationPhase.COMMITTED, f.state().attempt!!.mutations.first().phase); assertEquals(1, f.calls.size); assertEquals(0, f.commits)
        f.loseMutation = false; f.manager().set("chromesync", false)
        assertEquals(3, f.calls.size); assertEquals(1, f.calls.count { it.contentEquals(request) }); assertEquals(1, f.commits)
    }
    @Test fun crashAfterLaunchBeforeRpcCannotReplayEvenWhenReadbackShowsAbsentEffect() {
        val f = Fixture(); f.beforeInvocationDeath = true
        assertThrows(IOException::class.java) { f.manager().set("chromesync", false) }
        val first = f.state().attempt!!.mutations.first().request.copyOf(); assertTrue(f.calls.isEmpty())
        f.manager().reconcile(); assertEquals(FolsomConsentMutationPhase.LAUNCHED, f.state().attempt!!.mutations.first().phase)
        f.beforeInvocationDeath = false; f.manager().set("chromesync", false)
        assertTrue(f.calls.none { it.contentEquals(first) }); assertEquals(0, f.commits)
    }
    @Test fun lowerSyncUnknownAndDefinitePostMutationReadFailureRemainFenced() {
        val sync = Fixture(); sync.syncLost = true
        assertThrows(IOException::class.java) { sync.manager().set("hw_protected", true) }; assertEquals(FolsomConsentPhase.SYNC_LAUNCHED, sync.state().attempt!!.phase)
        sync.manager().reconcile(); assertEquals(1, sync.syncs); assertTrue(sync.calls.isEmpty()); assertEquals(0, sync.commits)
        val read = Fixture(); read.loseReadback = true
        assertThrows(PasswordRpcException::class.java) { read.manager().set("chromesync", false) }
        assertEquals(FolsomConsentMutationPhase.LAUNCHED, read.state().attempt!!.mutations.first().phase); assertEquals(1, read.calls.size); assertEquals(0, read.commits)
        read.loseReadback = false; read.manager().reconcile(); assertEquals(1, read.calls.size)
    }
    @Test fun preparedProcessDeathMayExplicitlyResumeOneFirstLaunchWithoutAllocatingNewAttempt() {
        val f = Fixture(); f.persistPreparedDeath = true
        assertThrows(IOException::class.java) { f.manager().set("chromesync", true) }
        val id = f.state().attempt!!.id; assertEquals(FolsomConsentPhase.PREPARED, f.state().attempt!!.phase); assertEquals(0, f.syncs)
        f.persistPreparedDeath = false; f.manager().set("chromesync", true)
        assertEquals(id, f.state().attempt!!.id); assertEquals(1, f.syncs); assertEquals(1, f.commits)
    }
    @Test fun accountResetAndChangedReviewDuringIoCannotPersistConsentOrCommitReceipt() {
        for (fault in listOf("account", "epoch", "reset")) {
            val f = Fixture(); f.afterInvoke = { when (fault) { "account" -> f.actualOwner = "replacement-gaia"; "epoch" -> f.epoch++; else -> f.resetPending = true } }
            assertThrows(Exception::class.java) { f.manager().set("chromesync", true) }
            assertEquals(0, f.commits); assertFalse(FolsomConsentPolicy.effective(f.stored, "chromesync"))
            assertEquals(FolsomConsentMutationPhase.LAUNCHED, f.state().attempt!!.mutations.single().phase)
        }
        val f = Fixture(); f.syncReady = false; f.manager().set("chromesync", true)
        assertThrows(Exception::class.java) { f.manager("replacement-gaia").reconcile() }
        assertThrows(PendingMutationException::class.java) { f.manager().set("hw_protected", false) }
        assertEquals(1, f.syncs); assertTrue(f.calls.isEmpty())
    }
    @Test fun bindingAnUnrelatedJournalCannotAdoptUnboundLegacyConsentEvenWithoutKeys() {
        val legacy = AccountData(domains = listOf(AccountData.DomainsEntry("chromesync", DomainData(consent = true)),
            AccountData.DomainsEntry("hw_protected", DomainData(consent = true))))
        val bound = FolsomAccountProtection.bind(AccountData.ADAPTER.decode(legacy.encode()), owner)
        assertTrue(bound.domains.all { it.value_!!.consent == null })
        assertFalse(FolsomConsentPolicy.effective(bound, "chromesync"))
        val current = account(true, false)
        assertEquals(current, FolsomAccountProtection.bind(current, owner))
        val proven = legacy.copy(domains = listOf(AccountData.DomainsEntry("chromesync", DomainData(consent = true, googleUserId = owner))))
        assertTrue(FolsomConsentPolicy.effective(FolsomAccountProtection.bind(proven, owner), "hw_protected"))
    }
    @Test fun aFullReceiptHistoryCompactsWithoutBrickingLaterExplicitConsentChanges() {
        val f = Fixture(); val original = List(100) { index -> ByteArray(32) { index.toByte() } }
        f.disk = FolsomConsentState(history = original).encode()
        f.manager().set("chromesync", false); assertEquals(100, f.state().history.size)
        f.manager().set("chromesync", true)
        val compacted = f.state()
        assertEquals(FolsomConsentPhase.COMMITTED, compacted.attempt!!.phase); assertEquals(100, compacted.history.size)
        assertArrayEquals(java.security.MessageDigest.getInstance("SHA-256").digest(original[0] + original[1]), compacted.history.first())
        assertTrue(compacted.history.all { it.size == 32 })
        f.disk = FolsomConsentState.decode(f.disk).encode(); f.manager().set("chromesync", false)
        assertEquals(3, f.commits); assertEquals(100, f.state().history.size)
    }
    @Test fun explicitOwnerRetryUsesFreshReviewedScopeNewIdAndArchivedUnknownReceipt() {
        val f = Fixture(); f.beforeInvocationDeath = true
        assertThrows(IOException::class.java) { f.manager().set("chromesync", false) }
        val prior = f.state().attempt!!; val receipt = prior.encode(); val oldRequest = prior.mutations.first().request.copyOf()
        val review = f.manager().review(prior.id)
        f.beforeInvocationDeath = false
        val result = f.manager().retry(prior.id, review)
        assertEquals(FolsomConsentPhase.COMMITTED, result.attempt!!.phase); assertNotEquals(prior.id, result.attempt!!.id)
        assertTrue(result.history.any { it.contentEquals(java.security.MessageDigest.getInstance("SHA-256").digest(receipt)) })
        assertTrue(f.calls.none { it.contentEquals(oldRequest) }); assertEquals(1, f.commits)
        val changed = Fixture(); changed.beforeInvocationDeath = true
        assertThrows(IOException::class.java) { changed.manager().set("chromesync", false) }
        val id = changed.state().attempt!!.id; val reviewed = changed.manager().review(id); val before = changed.disk.copyOf()
        changed.epoch++
        assertThrows(Exception::class.java) { changed.manager().retry(id, reviewed) }
        assertArrayEquals(before, changed.disk); assertTrue(changed.calls.isEmpty())
    }
    @Test fun explicitClosurePreservesExistingConsentAndDoesNotDispatchAnRpc() {
        val f = Fixture(); f.stored = account(true, false); f.beforeInvocationDeath = true
        val existing = f.stored.encode()
        assertThrows(IOException::class.java) { f.manager().set("chromesync", false) }
        val pending = f.state().attempt!!
        val closed = f.manager().close(pending.id)
        assertNull(closed.attempt); assertEquals(1, closed.history.size); assertTrue(f.calls.isEmpty()); assertEquals(0, f.commits)
        assertArrayEquals(existing, f.stored.encode())
    }
    @Test fun preparingAnOwnerPromptPersistsOnlyReviewAndCannotGrantConsentOrStartLowerSetup() {
        val f = Fixture(); val before = f.stored.encode()
        val first = f.manager().prepare("chromesync", true)
        assertEquals(FolsomConsentPhase.PREPARED, first.attempt!!.phase)
        assertEquals(0, f.syncs); assertTrue(f.calls.isEmpty()); assertEquals(0, f.commits)
        assertArrayEquals(before, f.stored.encode()); assertTrue(first.attempt!!.mutations.isEmpty())
        val bytes = f.disk.copyOf(); val same = f.manager().prepare("chromesync", true)
        assertEquals(first.attempt!!.id, same.attempt!!.id); assertArrayEquals(bytes, f.disk); assertEquals(1, f.reviews)
        assertThrows(PendingMutationException::class.java) { f.manager().prepare("hw_protected", true) }
        assertArrayEquals(bytes, f.disk); assertEquals(0, f.syncs)
        f.manager().set("chromesync", true)
        assertEquals(first.attempt!!.id, f.state().attempt!!.id); assertEquals(1, f.syncs); assertEquals(1, f.commits)
    }
    @Test fun promptEarlySuccessUsesEffectiveCoupledConsentAndActualFirstRecoveredEpoch() {
        assertFalse(FolsomConsentPolicy.needsPrompt(account(true, false), "hw_protected", 7))
        assertFalse(FolsomConsentPolicy.needsPrompt(account(false, true), "chromesync", 7))
        assertFalse(FolsomConsentPolicy.needsPrompt(account(false, false), "chromesync", null))
        assertFalse(FolsomConsentPolicy.needsPrompt(account(false, false), "chromesync", 0))
        assertTrue(FolsomConsentPolicy.needsPrompt(account(false, false), "chromesync", 7))
    }
}
