/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

/** No OS session/server/secret input is used here. The fixture exercises the real coordinator and its atomic sink contract. */
class RecoveryRestoreTest {
    private val owner = "synthetic-recovery-gaia"
    private val parent = "users/me/birthdays/synthetic-restore"
    private fun hex(raw: String) = raw.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun pair(n: Int = 1) = ByteArray(31) + byteArrayOf(n.toByte()) + SECNamedCurves.getByName("secp256r1").g
        .multiply(BigInteger.valueOf(n.toLong())).normalize().getEncoded(false)
    private fun vault(domain: String = "chromesync", challenge: Int = 6): ByteArray {
        val parameters = Proto.field(1, pair(3).copyOfRange(32, 97)) + Proto.field(2, byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1)) +
            Proto.integer(3, 10) + Proto.field(4, RecoverableVaultWire.handle(4, 5))
        val metadata = RecoverableVaultWire.protection(1, 1, "source-fixture-salt".toByteArray(), 0, byteArrayOf(7))
        val member = Proto.field(1, pair().copyOfRange(32, 97)) + Proto.field(2, ByteArray(125) { 2 }) + Proto.field(3, byteArrayOf(3))
        return Proto.field(1, "vaults/synthetic") + Proto.field(2, parameters) + Proto.field(3, metadata) + Proto.field(4, byteArrayOf(4)) +
            Proto.field(5, Proto.field(1, RecoverableVaultWire.entry(domain)) + Proto.field(4, member)) +
            Proto.field(6, byteArrayOf(challenge.toByte())) + Proto.integer(7, 2)
    }
    private inner class Fixture {
        var raw = byteArrayOf()
        var selected = vault()
        var allowed = true
        var openCount = 0
        var recoverCount = 0
        var keyReads = 0
        var commits = 0
        var loseReply = false
        var result = 1
        var failImport = false
        var failKeys = false
        var failDomain: String? = null
        var failCommit = false
        var concurrentJournal = false
        var concurrentKeys = false
        var liveKeyGeneration = 1
        var returnedPair = pair()
        var afterOpen: () -> Unit = {}
        var sent: ByteArray? = null
        var osChallenge: ByteArray? = null
        var importedDomains: List<String> = emptyList()
        val platform = object : RecoveryRestorePlatform {
            override fun start(vault: RecoveryRestoreVault, secretHash: ByteArray): ByteArray {
                verify(); assertEquals(32, secretHash.size)
                osChallenge = vault.challenge.copyOf()
                return byteArrayOf(0x55, 0x66)
            }
            override fun recover(opened: ByteArray, vault: RecoveryRestoreVault): ByteArray {
                verify(); recoverCount++
                assertEquals(RecoveryRestorePhase.IMPORT_LAUNCHED, state().attempt!!.phase)
                if (failImport) throw VaultLockedException()
                return returnedPair.copyOf()
            }
            override fun recoverDomains(opened: ByteArray, vault: RecoveryRestoreVault, domains: List<String>): Map<String, ByteArray> {
                importedDomains = domains.toList()
                if (domains == listOf(vault.domain)) return mapOf(vault.domain to recover(opened, vault))
                verify(); recoverCount++
                assertEquals(RecoveryRestorePhase.IMPORT_LAUNCHED, state().attempt!!.phase)
                if (failImport) throw VaultLockedException()
                return domains.associateWith { if (it == "chromesync") returnedPair.copyOf() else pair(2) }
            }
            override fun close() = Unit
        }
        val remote = object : RecoveryRestoreRemote {
            override fun vaults() = listOf(selected)
            override fun open(request: ByteArray): ByteArray {
                verify(); openCount++; sent = request.copyOf()
                assertEquals(RecoveryRestorePhase.LAUNCHED, state().attempt!!.phase)
                afterOpen()
                if (loseReply) throw PasswordRpcException(14, true)
                return Proto.field(3, Proto.integer(1, 27)) + Proto.integer(1, result.toLong()) + if (result == 1) Proto.field(2,
                    Proto.decode(selected).replace(4, byteArrayOf(0x44)).encode()) else byteArrayOf()
            }
            override fun keys(domain: String, pair: ByteArray, id: String): List<DomainLifecycleKey> {
                verify(); keyReads++
                if (failKeys || domain == failDomain) throw VaultLockedException()
                if (domain !in setOf("chromesync", "hw_protected") ||
                    !pair.contentEquals(if (domain == "chromesync") returnedPair else pair(2))) throw ConflictException()
                return listOf(DomainLifecycleKey(3, ByteArray(32) { 4 }))
            }
        }
        val sink = object : RecoveryRestoreSink {
            override fun commit(before: RecoveryRestoreState, after: RecoveryRestoreState, keys: List<DomainLifecycleKey>) {
                commitDomains(before, after, mapOf("chromesync" to keys))
            }
            override fun commitDomains(before: RecoveryRestoreState, after: RecoveryRestoreState, keys: Map<String, List<DomainLifecycleKey>>) {
                verify()
                val generation = liveKeyGeneration
                if (concurrentJournal) raw = RecoveryRestoreState().encode()
                if (concurrentKeys) liveKeyGeneration++
                // Independently modeled native transaction: compare both account journal and prior key generation before replacing either.
                if (failCommit || !raw.contentEquals(before.encode()) || generation != liveKeyGeneration) throw ConflictException()
                assertEquals(RecoveryRestorePhase.RECOVERED, before.attempt!!.phase)
                keys.values.forEach { assertEquals(3, it.single().epoch) }
                verify(); commits++; raw = after.encode()
            }
        }
        fun verify() { if (!allowed) throw VaultLockedException() }
        fun state() = RecoveryRestoreState.decode(raw)
        fun save(value: RecoveryRestoreState) { verify(); raw = value.encode() }
        fun manager(account: String = owner, birthday: String? = parent, domain: String = "chromesync") = RecoveryRestoreManager(account, domain, birthday, 123,
            remote, platform, sink, ::state, ::save, ::verify)
        fun restore() = manager().restore(RecoveryRestoreVault(selected, "chromesync").digest, ByteArray(32) { 9 })
        fun sdkRestore(birthday: String? = parent) = manager(birthday = birthday).restore(RecoveryRestoreVault(selected, "chromesync").digest,
            ByteArray(32) { 9 }, allDomains = true)
        fun combined() {
            val hardware = Proto.decode(Proto.decode(vault("hw_protected")).one(5))
            val member = Proto.decode(hardware.one(4)).replace(1, pair(2).copyOfRange(32, 97)).encode()
            selected += Proto.field(5, hardware.replace(4, member).encode())
        }
    }
    @Test fun hashingMatchesIndependentPythonHashlibVectorsAndRejectsUnboundedOrUnknownCosts() {
        // Generated independently using Python hashlib.sha256 and hashlib.scrypt; no production helper as oracle.
        val sha = Proto.integer(4, 1) + Proto.field(5, "source-fixture-salt")
        assertArrayEquals(hex("6299dfe3f224eebdf707fbb4e87a9966d441ce7b3dc86f0f9bdae22a3eb56e59"),
            RecoveryRestoreWire.hash(sha, "1357".toByteArray()))
        val scrypt = Proto.integer(4, 3) + Proto.field(5, "NaCl") + Proto.integer(6, 16)
        assertArrayEquals(hex("f5dfb3972e7908b22410c5c5f3788907cdbd1a79971b12277502bd4a77e6d5d3"),
            RecoveryRestoreWire.hash(scrypt, "password".toByteArray()))
        for (n in listOf(0L, 1L, 3L, 131072L)) assertThrows(ProtocolException::class.java) {
            RecoveryRestoreWire.hash(Proto.decode(scrypt).replaceInteger(6, n).encode(), byteArrayOf(1)) }
        assertThrows(ProtocolException::class.java) { RecoveryRestoreWire.hash(Proto.integer(4, 2) + Proto.field(5, byteArrayOf()), byteArrayOf(1)) }
    }
    @Test fun actualSecretEncoderUsesInclusiveAscii127AndRejectsUnsupportedInputBeforeAnyGuess() {
        assertArrayEquals(byteArrayOf(32, 65, 127), RecoveryRestoreWire.secretBytes(2, charArrayOf(' ', 'A', '\u007f')))
        assertArrayEquals("1234".toByteArray(), RecoveryRestoreWire.secretBytes(1, "1234".toCharArray()))
        assertArrayEquals("1236".toByteArray(), RecoveryRestoreWire.secretBytes(3, "1236".toCharArray()))
        for (value in listOf("\u001f", "\u0080", "\u20ac", "")) assertThrows(ProtocolException::class.java) {
            RecoveryRestoreWire.secretBytes(2, value.toCharArray()) }
        assertThrows(ProtocolException::class.java) { RecoveryRestoreWire.secretBytes(1, "12ab".toCharArray()) }
        assertThrows(ProtocolException::class.java) { RecoveryRestoreWire.secretBytes(3, "1123".toCharArray()) }
    }
    @Test fun googlePinType3UsesExactSixDigitOrUnnormalizedUtf8ProducerWithoutLskfTruncation() {
        assertArrayEquals(hex("313233343536"), RecoveryRestoreWire.secretBytes(1, "123456".toCharArray(), 3))
        assertArrayEquals(hex("65cc8120f09f9491"), RecoveryRestoreWire.secretBytes(2, charArrayOf('e', '\u0301', ' ', '\ud83d', '\udd11'), 3))
        assertArrayEquals(hex("c3a9"), RecoveryRestoreWire.secretBytes(2, charArrayOf('\u00e9'), 3))
        assertEquals(1024, RecoveryRestoreWire.secretBytes(2, CharArray(512) { '\u00e9' }, 3).size)
        for (value in listOf("12345", "1234567", "12345a")) assertThrows(ProtocolException::class.java) {
            RecoveryRestoreWire.secretBytes(1, value.toCharArray(), 3) }
        for (value in listOf(charArrayOf('\ud800'), charArrayOf('\udc00'), CharArray(513) { '\u00e9' }))
            assertThrows(ProtocolException::class.java) { RecoveryRestoreWire.secretBytes(2, value, 3) }
        assertThrows(ProtocolException::class.java) { RecoveryRestoreWire.secretBytes(3, "1236".toCharArray(), 3) }
        val sourceVault = Proto.decode(vault("hw_protected"))
        val type3 = sourceVault.replace(2, Proto.decode(sourceVault.one(2)).replace(4, byteArrayOf(3) + ByteArray(16)).encode()).encode()
        assertEquals(3, RecoveryRestoreVault(type3, "hw_protected").handleType)
        val selected = RecoveryRestoreWire.selectSdk(listOf(type3), "chromesync", RecoveryRestoreVault(type3, "hw_protected").requestScopeDigest)
        assertEquals("hw_protected", selected.domain); assertEquals(listOf("hw_protected"), selected.domains())
        assertThrows(ConflictException::class.java) { RecoveryRestoreWire.selectSdk(listOf(type3), "chromesync", ByteArray(32) { 7 }) }
    }
    @Test fun openRequestUsesIndependentLiteralTagsAndOsClaimWithoutAnyRawHashOrCredentialField() {
        val selected = RecoveryRestoreVault(vault(), "chromesync")
        val request = RecoveryRestoreWire.request(selected, byteArrayOf(0x55, 0x66), "s", 123)
        assertArrayEquals(hex("121101040000000000000005000000000000001a025566220106280140034a05120173187b"), request)
        val p = Proto.decode(request)
        assertNull(p.optional(1)); assertNull(p.optional(6)); assertNull(p.optional(7))
        assertEquals(94, selected.verifierParams().size) // 65 + counter8 + attempts4 + handle17.
        assertEquals(4L, Proto.decode(RecoverableVaultWire.list("s", 123, source = 4)).integer(8))
        assertEquals(4L, Proto.decode(RecoveryRestoreWire.request(selected, byteArrayOf(1), "s", 123, 4)).integer(8))
    }
    @Test fun backupSelectionIgnoresOnlyRefreshedChallengeAndGuessCounterAndKeepsSdkScopeExact() {
        val first = RecoveryRestoreVault(vault(), "chromesync")
        val refreshed = RecoveryRestoreVault(Proto.decode(vault(challenge = 9)).replaceInteger(7, 3).encode(), "chromesync")
        assertArrayEquals(first.digest, refreshed.digest); assertArrayEquals(first.requestScopeDigest, refreshed.requestScopeDigest)
        assertEquals(8, first.remainingAttempts); assertEquals(7, refreshed.remainingAttempts)
        val changed = RecoveryRestoreVault(Proto.decode(vault()).replace(3,
            Proto.decode(first.metadata).replace(5, byteArrayOf(8)).encode()).encode(), "chromesync")
        assertFalse(first.digest.contentEquals(changed.digest)); assertFalse(first.requestScopeDigest.contentEquals(changed.requestScopeDigest))
        val changedParams = RecoveryRestoreVault(Proto.decode(vault()).replace(2,
            Proto.decode(first.parameters).replace(2, ByteArray(8) { 9 }).encode()).encode(), "chromesync")
        assertFalse(first.digest.contentEquals(changedParams.digest)); assertFalse(first.requestScopeDigest.contentEquals(changedParams.requestScopeDigest))
        for (invalid in listOf(-1L, 1001L)) assertThrows(ProtocolException::class.java) {
            RecoveryRestoreVault(Proto.decode(vault()).replace(2,
                Proto.decode(first.parameters).replaceInteger(3, invalid).encode()).encode(), "chromesync") }
    }
    @Test fun sdkPreservesItsOriginalChallengeAfterRefreshingEntriesWithoutChangingBackupScope() {
        val f = Fixture(); val original = RecoveryRestoreVault(f.selected, "chromesync")
        f.selected = vault(challenge = 9)
        f.manager().restore(original.digest, ByteArray(32) { 9 }, original.challenge)
        assertArrayEquals(original.challenge, f.osChallenge)
        assertArrayEquals(original.challenge, Proto.decode(f.sent!!).one(4))
        val persisted = RecoveryRestoreVault(f.state().attempt!!.vault, "chromesync")
        assertArrayEquals(original.digest, persisted.digest)
        assertArrayEquals(original.requestScopeDigest, persisted.requestScopeDigest)
        assertEquals(RecoveryRestorePhase.CONFIRMED, f.state().attempt!!.phase)
        for (bad in listOf(byteArrayOf(), ByteArray(65537))) {
            val g = Fixture()
            assertThrows(ProtocolException::class.java) { g.manager().restore(original.digest, ByteArray(32), bad) }
            assertNull(g.osChallenge); assertEquals(0, g.openCount); assertTrue(g.raw.isEmpty())
        }
    }
    @Test fun sdkResultMappingUsesSourceEnumOrderAndPreservesActualRetryMetadata() {
        val literalResults = listOf(1, 2, 3, 4, 5, 6, 7, 8, 6, 0)
        for (code in 1..10) assertEquals(literalResults[code - 1], RecoveryRestoreWire.sdkResult(code))
        assertThrows(ProtocolException::class.java) { RecoveryRestoreWire.sdkResult(11) }
        val literalReply = hex("08061a02081b") // COOL_OFF plus iqcp.field1=27, independently encoded wire tags.
        assertEquals(27L, RecoveryRestoreWire.retryAfter(literalReply))
        assertEquals(0L, RecoveryRestoreWire.retryAfter(hex("0802")))
        val f = Fixture(); f.result = 9; f.restore()
        assertEquals(6, RecoveryRestoreWire.sdkResult(f.state().attempt!!.resultCode!!))
        assertEquals(27L, RecoveryRestoreWire.retryAfter(f.state().attempt!!.response!!))
        assertEquals(0, f.recoverCount)
    }
    @Test fun realConsumerPersistsEveryBoundaryBeforeImportAndCommitsOnlyValidatedRecoveredKeys() {
        val f = Fixture(); f.restore()
        assertEquals(1, f.openCount); assertEquals(1, f.recoverCount); assertEquals(1, f.keyReads); assertEquals(1, f.commits)
        assertEquals(RecoveryRestorePhase.CONFIRMED, f.state().attempt!!.phase)
        assertArrayEquals(pair(), f.state().attempt!!.pair)
        assertArrayEquals(f.sent, f.state().attempt!!.request)
    }
    @Test fun lostOpenReplyIsNeverReplayedAcrossRestartFinishOrAnotherExplicitRestore() {
        val f = Fixture(); f.loseReply = true
        assertThrows(PasswordRpcException::class.java) { f.restore() }
        val previous = f.raw.copyOf()
        assertEquals(RecoveryRestorePhase.LAUNCHED, f.state().attempt!!.phase)
        assertThrows(PendingMutationException::class.java) { f.manager().finish() }
        assertThrows(PendingMutationException::class.java) { f.restore() }
        assertArrayEquals(previous, f.raw); assertEquals(1, f.openCount); assertEquals(0, f.recoverCount)
    }
    @Test fun invalidGuessLockedChallengeCoolOffAndUnknownResultsNeverImportKeys() {
        for (code in 2..11) {
            val f = Fixture(); f.result = code
            if (code == 11) assertThrows(ProtocolException::class.java) { f.restore() } else f.restore()
            assertEquals(1, f.openCount); assertEquals(0, f.recoverCount); assertEquals(0, f.commits)
            assertEquals(if (code == 11) RecoveryRestorePhase.LAUNCHED else RecoveryRestorePhase.REFUSED, f.state().attempt!!.phase)
        }
    }
    @Test fun osImportFailureRetainsItsOwnFenceWithoutPretendingTheEphemeralSessionCanRestart() {
        val f = Fixture(); f.failImport = true
        assertThrows(VaultLockedException::class.java) { f.restore() }
        assertEquals(RecoveryRestorePhase.IMPORT_LAUNCHED, f.state().attempt!!.phase)
        assertThrows(PendingMutationException::class.java) { f.manager().finish() }
        assertEquals(1, f.openCount); assertEquals(1, f.recoverCount); assertEquals(0, f.commits)
    }
    @Test fun recoveredPairSurvivesReopenAndReadOnlyFinishNeverConsumesAnotherGuess() {
        val f = Fixture(); f.failKeys = true
        assertThrows(VaultLockedException::class.java) { f.restore() }
        assertEquals(RecoveryRestorePhase.RECOVERED, f.state().attempt!!.phase)
        f.failKeys = false; f.manager().finish()
        assertEquals(RecoveryRestorePhase.CONFIRMED, f.state().attempt!!.phase)
        assertEquals(1, f.openCount); assertEquals(1, f.recoverCount); assertEquals(1, f.commits)
    }
    @Test fun ownerFinishChecksCapturedAttemptAndSdkScopeBeforeAnySavedBackupIsImported() {
        val f = Fixture(); f.failKeys = true
        assertThrows(VaultLockedException::class.java) { f.restore() }; f.failKeys = false
        val a = f.state().attempt!!; val scope = RecoveryRestoreVault(a.vault, a.domain).requestScopeDigest
        val reads = f.keyReads
        assertThrows(ConflictException::class.java) { f.manager().finish("another-attempt", scope) }
        assertThrows(ConflictException::class.java) { f.manager().finish(a.id, ByteArray(32) { 7 }) }
        assertEquals(reads, f.keyReads); assertEquals(0, f.commits)
        f.manager().finish(a.id, scope)
        assertEquals(1, f.commits); assertEquals(1, f.openCount)
    }
    @Test fun wrongRecoveredPairAccountBirthdayOrRevokedOwnerCannotPublishKeys() {
        val wrong = Fixture(); wrong.returnedPair = pair(2)
        assertThrows(ConflictException::class.java) { wrong.restore() }; assertEquals(0, wrong.commits)
        for (gate in listOf("account", "birthday", "owner")) {
            val f = Fixture(); f.failKeys = true
            assertThrows(VaultLockedException::class.java) { f.restore() }; f.failKeys = false
            if (gate == "owner") f.allowed = false
            assertThrows(Exception::class.java) { f.manager(if (gate == "account") "removed-and-readded" else owner,
                if (gate == "birthday") "users/me/birthdays/reset" else parent).finish() }
            assertEquals(0, f.commits); assertEquals(1, f.openCount)
        }
    }
    @Test fun cancellationAfterOpenDoesNotImportOrClaimACompletedRestore() {
        val f = Fixture(); f.afterOpen = { f.allowed = false }
        assertThrows(VaultLockedException::class.java) { f.restore() }
        assertEquals(RecoveryRestorePhase.LAUNCHED, f.state().attempt!!.phase)
        assertEquals(0, f.recoverCount); assertEquals(0, f.commits)
    }
    @Test fun concurrentJournalOrDomainKeysCannotBeOverwrittenByTheFinalAtomicSink() {
        for (journal in listOf(false, true)) {
            val f = Fixture(); f.concurrentJournal = journal; f.concurrentKeys = !journal
            assertThrows(ConflictException::class.java) { f.restore() }
            assertEquals(0, f.commits); assertEquals(1, f.openCount)
            if (!journal) assertEquals(RecoveryRestorePhase.RECOVERED, f.state().attempt!!.phase)
        }
    }
    @Test fun sdkCombinedBackupUsesOneGuessAndOneAtomicReceiptForEveryValidatedMemberDomain() {
        val f = Fixture(); f.combined(); f.sdkRestore(birthday = null) // Source SDK works before Services password opt-in/Nigori exists.
        val scope = RecoveryRestoreVault(f.selected, "chromesync").requestScopeDigest
        assertEquals("chromesync", RecoveryRestoreWire.selectSdk(listOf(f.selected), "chromesync", scope).domain)
        assertEquals("hw_protected", RecoveryRestoreWire.selectSdk(listOf(f.selected), "hw_protected", scope).domain)
        assertEquals(listOf("chromesync", "hw_protected"), f.importedDomains)
        assertEquals(1, f.openCount); assertEquals(1, f.recoverCount); assertEquals(2, f.keyReads); assertEquals(1, f.commits)
        val a = f.state().attempt!!
        assertEquals(RecoveryRestorePhase.CONFIRMED, a.phase); assertTrue(a.allDomains); assertNull(a.parent)
        assertEquals(setOf("chromesync", "hw_protected"), a.pairs.keys)
        assertArrayEquals(pair(), a.pairs.getValue("chromesync")); assertArrayEquals(pair(2), a.pairs.getValue("hw_protected"))
        assertEquals(2L, Proto.decode(f.raw).integer(1))
        assertThrows(ProtocolException::class.java) { RecoveryRestoreState.decode(Proto.decode(f.raw).replaceInteger(1, 1).encode()) }
        val explicit = Fixture(); explicit.combined(); explicit.restore()
        assertEquals(listOf("chromesync"), explicit.importedDomains); assertTrue(explicit.state().attempt!!.pairs.isEmpty())
    }
    @Test fun sdkChromeConnectionCanReallyRestoreAHardwareOnlyGooglePinBackupWithoutLocalPasswordState() {
        val f = Fixture(); f.returnedPair = pair(2)
        val p = Proto.decode(vault("hw_protected")); val entry = Proto.decode(p.one(5))
        f.selected = p.replace(2, Proto.decode(p.one(2)).replace(4, byteArrayOf(3) + ByteArray(16)).encode())
            .replace(5, entry.replace(4, Proto.decode(entry.one(4)).replace(1, pair(2).copyOfRange(32, 97)).encode()).encode()).encode()
        val actual = RecoveryRestoreVault(f.selected, "hw_protected")
        val choice = RecoveryRestoreWire.selectSdk(listOf(f.selected), "chromesync", actual.requestScopeDigest)
        f.manager(birthday = null, domain = choice.domain).restore(choice.digest, ByteArray(32) { 9 }, allDomains = true)
        assertEquals("hw_protected", f.state().attempt!!.domain)
        assertEquals(setOf("hw_protected"), f.state().attempt!!.pairs.keys)
        assertEquals(RecoveryRestorePhase.CONFIRMED, f.state().attempt!!.phase)
        assertEquals(1, f.openCount); assertEquals(1, f.recoverCount); assertEquals(1, f.commits)
    }
    @Test fun secondaryDomainProofFailureRetainsAllRecoveredPairsAndCannotPartiallyCommitOrGuessAgain() {
        val f = Fixture(); f.combined(); f.failDomain = "hw_protected"
        assertThrows(VaultLockedException::class.java) { f.sdkRestore() }
        assertEquals(RecoveryRestorePhase.RECOVERED, f.state().attempt!!.phase)
        assertEquals(2, f.state().attempt!!.pairs.size); assertEquals(0, f.commits); assertEquals(1, f.openCount)
        f.failDomain = null; f.manager().finish()
        assertEquals(1, f.commits); assertEquals(1, f.openCount); assertEquals(RecoveryRestorePhase.CONFIRMED, f.state().attempt!!.phase)
    }
    @Test fun newExplicitSdkCallArchivesOnlyProvenTerminalOutcomesAndNeverUnknownLaunches() {
        for (code in listOf(1, 2)) {
            val f = Fixture(); f.result = code; f.sdkRestore(); f.result = 1; f.sdkRestore()
            assertEquals(2, f.openCount); assertEquals(1, f.state().history.size)
            assertEquals(RecoveryRestorePhase.CONFIRMED, f.state().attempt!!.phase)
        }
        val f = Fixture(); f.loseReply = true
        assertThrows(PasswordRpcException::class.java) { f.sdkRestore() }
        val durable = f.raw.copyOf()
        assertThrows(PendingMutationException::class.java) { f.sdkRestore() }
        assertEquals(1, f.openCount); assertArrayEquals(durable, f.raw)
    }
    @Test fun resetScrubsAnUncertainCombinedLaunchForEitherAffectedDomainBeforePairRecovery() {
        val f = Fixture(); f.combined(); f.loseReply = true
        assertThrows(PasswordRpcException::class.java) { f.sdkRestore() }
        assertTrue(f.state().attempt!!.allDomains); assertTrue(f.state().attempt!!.pairs.isEmpty())
        for (domain in listOf("chromesync", "hw_protected")) {
            val cleared = RecoveryRestoreState.decode(RecoveryRestoreState.clearDomain(f.raw, domain))
            assertNull(cleared.attempt); assertEquals(1, cleared.resets.size)
        }
        val legacy = Fixture(); legacy.restore()
        val old = Proto.decode(legacy.raw).replaceInteger(1, 1).encode()
        assertEquals(RecoveryRestorePhase.CONFIRMED, RecoveryRestoreState.decode(old).attempt!!.phase)
    }
    @Test fun optionalOpenMetadataMayBeAbsentButWrongOrSubstitutedSelectedEntryIsRejected() {
        val selected = RecoveryRestoreVault(vault(), "chromesync")
        val minimal = Proto.field(4, byteArrayOf(1)) + Proto.field(5, selected.entry)
        assertArrayEquals(minimal, RecoveryRestoreWire.opened(Proto.integer(1, 1) + Proto.field(2, minimal), selected))
        val wrong = Proto.decode(minimal).replace(5, Proto.decode(selected.entry).replace(1, "foreign".toByteArray()).encode()).encode()
        assertThrows(ConflictException::class.java) { RecoveryRestoreWire.opened(Proto.integer(1, 1) + Proto.field(2, wrong), selected) }
        assertThrows(ConflictException::class.java) { RecoveryRestoreWire.opened(Proto.integer(1, 1) +
            Proto.field(2, minimal + Proto.field(1, "other-vault")), selected) }
    }
    @Test fun closureAndDomainResetScrubOnlyTheirAttemptWithoutReplayingOrDroppingOtherDomainProvenance() {
        val f = Fixture(); f.loseReply = true
        assertThrows(PasswordRpcException::class.java) { f.restore() }
        val original = f.state().attempt!!; f.manager().close(original.id)
        assertNull(f.state().attempt); assertEquals(1, f.state().history.size); assertEquals(1, f.openCount)
        val receipt = f.state().history.single(); assertEquals("chromesync", Proto.decode(receipt).string(1))
        assertEquals(32, Proto.decode(receipt).one(4).size)
        val otherReceipt = Proto.field(1, "hw_protected") + Proto.field(2, "other") + Proto.field(4, ByteArray(32))
        val full = RecoveryRestoreState(original, List(100) { if (it % 2 == 0) receipt else otherReceipt })
        val clear = RecoveryRestoreState.decode(RecoveryRestoreState.clearDomain(full.encode(), "chromesync"))
        assertNull(clear.attempt); assertEquals(50, clear.history.size); clear.history.forEach { assertArrayEquals(otherReceipt, it) }
        assertEquals(1, clear.resets.size)
        assertThrows(ProtocolException::class.java) { RecoveryRestoreState.decode(Proto.decode(full.encode()).replaceInteger(1, 3).encode()) }
    }
}
