/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.app.KeyguardManager
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.auth.folsom.utils.LocalKeyManager
import java.security.MessageDigest

/** Explicit live acceptance only. The ordinary suite never selects accounts, calls providers, or mutates credentials. */
@RunWith(AndroidJUnit4::class)
class PasswordParityAcceptanceAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private fun runId(): String {
        check(android.os.Build.VERSION.SDK_INT >= 28 && android.app.Application.getProcessName() == "com.google.android.gms:ui")
        val value = arguments.getString("passwordAcceptanceRun") ?: error("Missing native acceptance run")
        PasswordAcceptanceAccounts.checked(value, "primary"); return value
    }
    private fun status(vararg values: Pair<String, Boolean>) = instrumentation.sendStatus(2, Bundle().apply {
        values.forEach { (name, value) -> putBoolean(name, value) }
    })
    @Test fun selectApprovedSyntheticAccountEntirelyOnPhone() {
        assumeTrue(arguments.getString("passwordAcceptance") == "cyc689-native-selection")
        val run = runId(); val role = arguments.getString("passwordAcceptanceRole") ?: "primary"
        try {
            LegacyPasskeyTicketStore.system(context)
            if (!PasswordAcceptanceAccounts.tryAutomatic(context, run, role)) {
                val (intent, reply) = PasswordAcceptanceAccounts.picker(context, run, role)
                instrumentation.runOnMainSync { context.startActivity(intent) }
                check(runBlocking { withTimeout(120_000) { reply.await() } })
            }
            PasswordAcceptanceAccounts.resolve(context, run, role)
            status("nativeSelectionReady" to true)
        } catch (_: Exception) { status("nativeSelectionReady" to false); throw AssertionError("Native synthetic account selection did not complete") }
    }

    /** Discovery only: cached keys cannot trigger the repository's automatic SDS key retrieval. */
    @Test fun readCachedSyntheticPeerInventoryWithoutProviderCalls() {
        assumeTrue(arguments.getString("passwordAcceptance") == "cyc689-local-peer-inventory")
        runId()
        try {
            check(com.google.android.gms.BuildConfig.DEBUG)
            LegacyPasskeyTicketStore.system(context)
            val candidates = PasswordAcceptanceAccounts.candidates(context)
            var readable = 0; var blocked = 0; var unreadable = 0
            val approved = HashSet<String>(); val sharing = HashSet<String>()
            candidates.forEach { selected ->
                try {
                    cachedPeer(selected)?.let { found ->
                        readable++
                        if (found.approved) {
                            approved.add(selected.accountId)
                            if (found.sharingKeys) sharing.add(selected.accountId)
                        }
                    }
                } catch (_: PendingMutationException) { blocked++ }
                catch (_: Exception) { unreadable++ }
            }
            LegacyPasskeyTicketStore.system(context)
            val lock = context.getSystemService(KeyguardManager::class.java)
            check(!lock.isDeviceLocked && !lock.isKeyguardLocked)
            candidates.forEach { PasswordAcceptanceAccounts.verify(context, it) }
            instrumentation.sendStatus(2, Bundle().apply {
                putBoolean("localPeerInventoryReadSucceeded", true)
                putInt("scannedStableAccountCandidateCount", candidates.size)
                putBoolean("localCandidateScanLimitReached", candidates.size == 20)
                putInt("locallyReadableEnabledVaultCount", readable)
                putInt("cachedApprovedSyntheticFixtureCount", approved.size)
                putInt("cachedApprovedSharingKeyFixtureCount", sharing.size)
                putInt("blockedCachedVaultCount", blocked)
                putInt("unreadableCachedVaultCount", unreadable)
                putBoolean("twoDistinctCachedSyntheticFixtures", approved.size >= 2)
            })
        } catch (_: Exception) {
            status("localPeerInventoryReadSucceeded" to false)
            throw AssertionError("Local synthetic peer inventory did not complete; no provider call was made")
        }
    }

    private class CachedPeer(val approved: Boolean, val sharingKeys: Boolean)
    private fun cachedPeer(selected: PasswordAcceptanceAccounts.Selected): CachedPeer? {
        val local = LocalKeyManager.getInstance(context)
        return local.withRecoveryAuthority(selected.account.name, selected.accountId) {
            fun verify() {
                LegacyPasskeyTicketStore.system(context)
                val lock = context.getSystemService(KeyguardManager::class.java)
                check(!lock.isDeviceLocked && !lock.isKeyguardLocked)
                PasswordAcceptanceAccounts.verify(context, selected)
                if (local.hasPendingDomainReset(selected.account.name, "chromesync") ||
                    local.hasPendingDomainReset(selected.account.name, "hw_protected")) throw PendingMutationException()
            }
            verify()
            val store = AndroidVaultStore(context, "google:" + selected.accountId, ::verify)
            if (!store.enabled) { verify(); return@withRecoveryAuthority null }
            store.locked { session ->
                val state = session.load()
                try {
                    if (state.pendingMetadata != null || state.mutations.isNotEmpty() || state.preferenceMutations.isNotEmpty() ||
                        state.ownerTransfers.isNotEmpty() || PasswordEncryptionState.fences(state.passwordEncryptionFlow)) throw PendingMutationException()
                    val metadata = state.metadata ?: throw VaultLockedException()
                    // This pure overload only uses metadata and already cached keys. Never use
                    // PasswordRepository.list/refresh, unlockTrustedVault, or getLocalKeysOrSync here.
                    val keys = VaultKeys.unlock(metadata, trustedVaultKeys = state.trustedVaultKeys, customKey = state.customKey)
                    try {
                        val tags = HashSet<String>()
                        var markers = 0
                        state.records.forEach { raw ->
                            val data = PasswordResourceCodec.read(raw, keys)
                            check(Proto.decode(raw).string(2) == data.tag() && tags.add(data.tag()))
                            if (data.realm == PasswordAcceptanceAccounts.REALM && data.username == PasswordAcceptanceAccounts.USERNAME &&
                                !data.blocked && data.password.isNotEmpty()) markers++
                        }
                        check(markers <= 1)
                        val hasSharingKeys = if (markers == 1) try {
                            PasswordSharingKeys.read(metadata, keys).also { it.destroy() }; true
                        } catch (_: VaultLockedException) { false } else false
                        verify()
                        CachedPeer(markers == 1, hasSharingKeys)
                    } finally { keys.destroy() }
                } finally {
                    state.customKey?.fill(0); state.trustedVaultKeys.forEach { it.fill(0) }
                    state.versionedTrustedVaultKeys?.destroy(); state.metadata?.fill(0); state.pendingMetadata?.fill(0)
                }
            }
        }
    }

    @Test fun readActualFamilyEligibilityAndNativeRecipientMatchingWithoutSending() {
        assumeTrue(arguments.getString("passwordAcceptance") == "cyc690-readiness")
        val run = runId()
        try {
            LegacyPasskeyTicketStore.system(context)
            val selected = PasswordAcceptanceAccounts.resolve(context, run, "primary")
            val repository = PasswordAcceptanceAccounts.repository(context, selected)
            check(repository.enabled && PasswordAcceptanceAccounts.approvedFixture(context, selected))
            val screen = PasswordSharing(context, repository) { PasswordAcceptanceAccounts.verify(context, selected) }.load()
            val installed = PasswordAcceptanceAccounts.candidates(context).filter { it.accountId != selected.accountId }
            val eligible = screen.recipients.filter { it.eligible }
            val present = installed.filter { account -> eligible.any { it.userId == account.accountId } }
            instrumentation.sendStatus(2, Bundle().apply {
                putBoolean("familyProviderReadSucceeded", true); putBoolean("familySharingKeysAvailable", screen.keysAvailable)
                putInt("familyEligibleRecipientCount", eligible.size); putInt("familyInstalledEligibleCount", present.size)
                putInt("familyApprovedInstalledCount", present.count { PasswordAcceptanceAccounts.approvedFixture(context, it) })
                putInt("familyPendingInvitationCount", screen.incoming.size)
            })
        } catch (_: Exception) {
            status("familyProviderReadSucceeded" to false)
            throw AssertionError("Native family readiness read failed; no invitation was sent")
        }
    }
    @Test fun readActualSyncedPasskeyAndRecoveryReadinessWithoutEnrollmentOrSigning() {
        assumeTrue(arguments.getString("passwordAcceptance") == "cyc691-readiness")
        val run = runId()
        try {
            LegacyPasskeyTicketStore.system(context)
            val selected = PasswordAcceptanceAccounts.resolve(context, run, "primary")
            val repository = PasswordAcceptanceAccounts.repository(context, selected)
            check(repository.enabled && PasswordAcceptanceAccounts.approvedFixture(context, selected))
            val parent = repository.store.locked { session ->
                val state = session.load(); check(state.pendingMetadata == null)
                PasswordApi.checkedParent(Proto.decode(state.metadata ?: throw VaultLockedException()).string(1))
            }
            val vault = SyncedPasskeyVault(context, selected.account, selected.accountId, parent)
            val entries = vault.list(refresh = true)
            PasswordAcceptanceAccounts.verify(context, selected)
            instrumentation.sendStatus(2, Bundle().apply {
                putBoolean("nativePasskeySnapshotReadable", true); putBoolean("nativePasskeyDomainKeysAvailable", vault.hasDomainKeys())
                putInt("nativeSyncedPasskeyCount", entries.size)
                putBoolean("secureScreenLockConfigured", context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
                putBoolean("recoverableVaultApiConfigured", com.google.android.gms.BuildConfig.RECOVERABLE_VAULT_API_KEY.isNotBlank())
                putBoolean("recoverKeystorePermissionGranted", context.checkSelfPermission("android.permission.RECOVER_KEYSTORE") == android.content.pm.PackageManager.PERMISSION_GRANTED)
                putBoolean("recoveryHiddenApiVisible", runCatching {
                    Class.forName("android.security.keystore.recovery.RecoveryController").getMethod("getInstance", android.content.Context::class.java)
                }.isSuccess)
            })
        } catch (_: Exception) {
            status("nativePasskeySnapshotReadable" to false)
            throw AssertionError("Native passkey readiness read failed; no enrollment or signature was requested")
        }
    }

    @Test fun realFamilySendAutomaticReceiveAndExactSyntheticCleanup() {
        assumeTrue(arguments.getString("passwordAcceptance") == "cyc690-family-roundtrip")
        val fixture = Family(runId())
        try { fixture.start(); fixture.receiveAndCleanup(); status("familyRoundTripAccepted" to true, "syntheticCleanupConfirmed" to true) }
        catch (_: Exception) {
            status("familyRoundTripAccepted" to false, "encryptedCheckpointRetained" to true)
            throw AssertionError("Family acceptance incomplete; native encrypted checkpoint retained; no automatic resend")
        }
    }
    @Test fun finishApprovedIncomingAndCleanupAfterInterruptionWithoutResending() {
        assumeTrue(arguments.getString("passwordAcceptance") == "cyc690-finish-receive-cleanup")
        try { Family(runId()).receiveAndCleanup(); status("syntheticCleanupConfirmed" to true) }
        catch (_: Exception) { status("syntheticCleanupConfirmed" to false); throw AssertionError("Family cleanup remains pending; no send was retried") }
    }
    private inner class Family(private val run: String) {
        private val primary = PasswordAcceptanceAccounts.resolve(context, run, "primary")
        private val family = PasswordAcceptanceAccounts.resolve(context, run, "family")
        private fun verify() {
            LegacyPasskeyTicketStore.system(context)
            check(primary.accountId != family.accountId)
            check(!context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            check(PasswordAcceptanceAccounts.resolve(context, run, "primary").accountId == primary.accountId)
            check(PasswordAcceptanceAccounts.resolve(context, run, "family").accountId == family.accountId)
        }
        private val checkpoint = PasswordAcceptanceStorage.store(context, "family/$run", ::verify)
        private fun load(): Proto = checkpoint.locked { s -> val raw = s.load(); try { Proto.decode(raw) } finally { raw.fill(0) } }
        private fun save(value: Proto) = checkpoint.locked { it.save(value.encode()) }
        private fun repository(selected: PasswordAcceptanceAccounts.Selected, expected: String? = null) =
            PasswordAcceptanceAccounts.repository(context, selected, expected).also { check(it.enabled) }
        private fun parent(repository: PasswordRepository) = repository.store.locked { session ->
            val state = session.load(); check(state.pendingMetadata == null && state.mutations.isEmpty())
            PasswordApi.checkedParent(Proto.decode(state.metadata ?: throw VaultLockedException()).string(1))
        }
        private fun baseline(values: List<PasswordView>): ByteArray = values.fold(byteArrayOf()) { out, item ->
            out + Proto.field(1, Proto.field(1, item.id) + Proto.field(2, SharingOperation.digest(item.data.encode())))
        }
        private fun unchanged(raw: ByteArray, values: List<PasswordView>) {
            val wanted = Proto.decode(raw).bytes(1).map(Proto::decode)
            check(wanted.size == values.size && wanted.all { saved -> values.singleOrNull { it.id == saved.string(1) }?.let {
                MessageDigest.isEqual(saved.one(2), SharingOperation.digest(it.data.encode()))
            } == true })
        }
        private fun api(repository: PasswordRepository) = PasswordSharingApi(repository.sharingTransport())
        private fun sharing(repository: PasswordRepository) = PasswordSharing(context, repository, ::verify)
        private fun noUnrelatedPending(repository: PasswordRepository) {
            check(repository.store.locked { it.load().mutations.isEmpty() })
            check(SharingJournal(repository.store).operations().none { !it.phase.terminal })
        }
        fun start() {
            verify(); check(load().encode().isEmpty())
            val initialSource = repository(primary); val initialReceiver = repository(family)
            noUnrelatedPending(initialSource); noUnrelatedPending(initialReceiver)
            val sourceParent = parent(initialSource); val destinationParent = parent(initialReceiver)
            val source = repository(primary, sourceParent); val receiver = repository(family, destinationParent)
            source.refresh(); receiver.refresh()
            check(parent(source) == sourceParent && parent(receiver) == destinationParent)
            val beforeSource = source.list(false); val beforeDestination = receiver.list(false)
            val approved = beforeSource.single { it.data.realm == PasswordAcceptanceAccounts.REALM && it.data.username == PasswordAcceptanceAccounts.USERNAME &&
                !it.data.blocked && it.data.password.isNotEmpty() }
            // A per-run native synthetic copy avoids changing the approved original or colliding with an earlier test.
            val data = approved.data.withIdentity(approved.data.origin, "cyc690-" + run).withDisplayName("Synthetic family acceptance")
            val chrome = Proto.field(1, data.encode())
            check(beforeSource.none { it.id == data.tag() } && beforeDestination.none { it.id == data.tag() })
            val sender = sharing(source).load()
            check(sender.keysAvailable)
            val recipient = sender.recipients.single { it.userId == family.accountId && it.eligible }
            check(api(receiver).incomingResources(destinationParent).isEmpty())
            val state = Proto.decode(Proto.field(1, sourceParent) + Proto.field(2, destinationParent) + Proto.field(3, chrome) +
                Proto.field(4, data.tag()) + Proto.field(5, baseline(beforeSource)) + Proto.field(6, baseline(beforeDestination)) +
                Proto.field(10, primary.accountId) + Proto.field(11, family.accountId) + Proto.integer(9, 1))
            save(state)
            save(state.replaceInteger(9, 2)) // Before local enqueue; its scheduled worker may start immediately.
            source.insertIfAbsent(chrome); source.refresh(); verify()
            check(!source.pending(data.tag()))
            val actual = source.list(false).single { it.id == data.tag() }
            check(SharingIncomingPolicy.sameIdentity(actual.data, data) && actual.data.password == data.password)
            save(state.replace(13, actual.chromeBytes).replaceInteger(9, 3))
            // Sending has its own durable launch journal. A lost reply is never automatically retried by this fixture.
            save(load().replaceInteger(9, 4))
            val id = sharing(source).send(actual.chromeBytes, recipient)
            check(SharingJournal(source.store).get(id).phase == SharingOperationPhase.COMPLETE)
            save(load().replace(8, id.toByteArray()).replaceInteger(9, 5))
            status("familySendAcknowledged" to true)
        }
        fun receiveAndCleanup() {
            verify(); var state = load()
            check((state.integer(9) ?: -1) in 1L..8L && state.string(10) == primary.accountId && state.string(11) == family.accountId)
            val source = repository(primary, state.string(1)); val receiver = repository(family, state.string(2))
            val tag = state.string(4)
            if ((state.integer(9) ?: -1) in 1L..3L) {
                // No send was admitted at these checkpoints. Never drop or retry a launched cloud mutation.
                check(SharingJournal(source.store).operations().none { it.kind == SharingOperationKind.SEND &&
                    it.parent == state.string(1) && it.source == tag + ":" + family.accountId })
                source.store.locked { PasswordAcceptanceCleanup.dropQueuedClone(it, tag, state.string(1)) }
                source.refresh(); receiver.refresh(); verify()
                PasswordAcceptanceCleanup.requireAbsent(receiver, tag)
                source.list(false).singleOrNull { it.id == tag }?.let { actual ->
                    check(MessageDigest.isEqual(actual.data.encode(), PasswordData.decode(Proto.decode(state.one(3)).one(1)).encode()))
                    source.deleteIfUnchanged(actual.chromeBytes); source.refresh()
                }
                PasswordAcceptanceCleanup.requireAbsent(source, tag); PasswordAcceptanceCleanup.requireAbsent(receiver, tag)
                unchanged(state.one(5), source.list(false)); unchanged(state.one(6), receiver.list(false))
                check(api(receiver).incomingResources(state.string(2)).isEmpty())
                checkpoint.locked { it.save(byteArrayOf()) }
                return
            }
            if (state.integer(9) == 4L) {
                state = PasswordAcceptanceCleanup.acknowledgedSend(state, SharingJournal(source.store).operations())
                save(state)
            }
            val id = state.string(8)
            check(SharingJournal(source.store).get(id).phase == SharingOperationPhase.COMPLETE)
            if (state.integer(9) == 5L) {
                val deadline = SystemClock.elapsedRealtime() + 60_000
                var imported: PasswordView? = null
                while (SystemClock.elapsedRealtime() < deadline) {
                    verify()
                    val incoming = api(receiver).incomingResources(state.string(2))
                    check(incoming.all { SharingInvitation.decode(it, state.string(2)).guid == id })
                    if (incoming.isNotEmpty()) sharing(receiver).processIncoming()
                    receiver.refresh(); verify()
                    val completed = SharingJournal(receiver.store).operations().any { it.kind == SharingOperationKind.AUTOMATIC &&
                        it.invitationGuid == id && it.phase == SharingOperationPhase.COMPLETE }
                    if (completed) { imported = receiver.list(false).single { it.id == tag }; break }
                    Thread.sleep(250)
                }
                val received = imported ?: throw VaultLockedException()
                val original = PasswordData.decode(Proto.decode(state.one(3)).one(1))
                check(SharingIncomingPolicy.sameIdentity(received.data, original) && received.data.password == original.password)
                check(!SharingIncomingPolicy.AUTO_APPROVE_SAME_SENDER_UPDATES)
                check(api(receiver).incomingResources(state.string(2)).isEmpty())
                state = state.replace(12, received.chromeBytes).replaceInteger(9, 6); save(state)
                status("familyNativeImportVerified" to true, "familyInvitationAcknowledged" to true)
            }
            fun remove(repository: PasswordRepository, expected: ByteArray) {
                verify(); repository.refresh()
                val actual = repository.list(false).singleOrNull { it.id == tag }
                if (actual == null) { PasswordAcceptanceCleanup.requireAbsent(repository, tag); return }
                check(MessageDigest.isEqual(actual.data.encode(), PasswordData.decode(Proto.decode(expected).one(1)).encode()))
                repository.deleteIfUnchanged(actual.chromeBytes); repository.refresh(); verify()
                PasswordAcceptanceCleanup.requireAbsent(repository, tag)
            }
            save(state.replaceInteger(9, 7))
            remove(receiver, state.one(12)); remove(source, state.one(13))
            source.refresh(); receiver.refresh(); verify()
            PasswordAcceptanceCleanup.requireAbsent(source, tag); PasswordAcceptanceCleanup.requireAbsent(receiver, tag)
            unchanged(state.one(5), source.list(false)); unchanged(state.one(6), receiver.list(false))
            check(api(receiver).incomingResources(state.string(2)).isEmpty())
            save(state.replaceInteger(9, 8))
            checkpoint.locked { it.save(byteArrayOf()) }
        }
    }
}
