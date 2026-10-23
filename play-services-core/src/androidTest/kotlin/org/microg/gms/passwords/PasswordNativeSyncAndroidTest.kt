/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.AccountManager
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkQuery
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.workDataOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.microg.gms.auth.AuthManager
import org.microg.gms.common.Constants
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Opt-in live check. The ordinary instrumentation suite never selects an account or performs RPCs. */
@RunWith(AndroidJUnit4::class)
class PasswordNativeSyncAndroidTest {
    @Test fun inspectInterruptedSyntheticFixtureJournalWithoutReportingContents() {
        assumeTrue(arguments.getString("passwordLiveMutations") == "cyc651-synthetic-only")
        val selected = arguments.getString("passwordLiveAccount")
        assumeTrue(!selected.isNullOrBlank())
        val account = AccountManager.get(context).getAccountsByType("com.google").single { it.name == selected }
        val repository = PasswordRepository(context, account)
        val state = repository.store.locked { it.load() }
        val checkpoint = AndroidVaultStore(context, "password-fixture-metadata-restore/" + account.name)
            .locked { it.load().chromeLocalData["original"] }
        val messages = Bundle().apply {
            putString("passwordFixtureJournal", state.mutations.joinToString(",") { "${it.kind}:${it.phase}" }.ifEmpty { "empty" })
            putInt("passwordFixturePreferenceJournalCount", state.preferenceMutations.size)
            putBoolean("passwordFixtureMetadataCheckpoint", checkpoint != null)
            putBoolean("passwordFixtureEnabled", repository.enabled)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, messages)
    }

    @Test fun actualServerPushRefreshesTheApprovedSyntheticLoginWithoutManualRefresh() {
        assumeTrue(arguments.getString("passwordLivePush") == "cyc654")
        assumeTrue(arguments.getString("passwordLiveMutations") == "cyc651-synthetic-only")
        withFixture { repository, original ->
            val registration = repository.store.locked { it.load().deviceRegistration } ?: error("Missing registration")
            assertTrue("Push delivery capability unavailable: messaging consent, check-in and registration are required",
                registration.info?.let { Proto.decode(it).optionalString(7) }?.isNotEmpty() == true)
            val checkpoint = AndroidVaultStore(context, "password-fixture-push-restore/" + repository.account.name)
            val remoteStore = AndroidVaultStore(context, "password-fixture-push-remote/" + repository.account.name)
            val restore = checkpoint.locked { session -> session.load().chromeLocalData["original"] ?: original.data.encode().also {
                session.save(VaultState(chromeLocalData = hashMapOf("original" to it)))
            } }
            val data = PasswordData.decode(restore)
            assertTrue("Restore input is outside the approved fixture", isFixture(data) && data.tag() == original.id)
            val auth = AuthManager(context, repository.account.name, Constants.GMS_PACKAGE_NAME,
                "oauth2:https://www.googleapis.com/auth/login_manager").apply {
                isGmsApp = true
                setPackageSignature(Constants.GMS_PACKAGE_SIGNATURE_SHA1)
            }
            val token = auth.requestAuthWithBackgroundResolution(false).auth?.takeIf { it.isNotEmpty() } ?: throw VaultLockedException()
            val transport = GooglePasswordTransport({
                assertTrue("Designated account was removed", AccountManager.get(context).getAccountsByType("com.google").contains(repository.account))
                token
            }, "https://chromesyncpasswords-pa.googleapis.com/".toHttpUrl())
            // This independent durable client does not register an additional device identity.
            // Its empty client token ensures the primary registered device is not the originator.
            val remote = VaultSync(PasswordApi(transport), remoteStore)
            fun refreshRemote() = remote.synchronize { VaultKeys.unlock(it) }
            fun enqueueExact(value: PasswordData) = remoteStore.locked { session ->
                val state = session.load()
                assertTrue("Remote mutation requires reconciliation; encrypted restore input retained", state.mutations.isEmpty())
                val current = state.records.single { Proto.decode(it).string(2) == original.id }
                val keys = VaultKeys.unlock(state.metadata!!)
                try { remote.enqueue(session, MutationKind.UPDATE, original.id, PasswordResourceCodec.encrypt(value, keys, current)) }
                finally { keys.destroy() }
            }
            val work = RemoteWorkManager.getInstance(context)
            val userId = AccountManager.get(context).getUserData(repository.account, "GoogleUserId") ?: throw VaultLockedException()
            val marker = PasswordRepository.hash("google:$userId")
            try {
                refreshRemote()
                work.cancelUniqueWork("password-periodic-$marker").get(30, TimeUnit.SECONDS)
                work.cancelUniqueWork("password-now-$marker").get(30, TimeUnit.SECONDS)
                val before = repository.store.locked { it.load().invalidations.directPasswordGeneration }
                val changed = data.withDisplayName("cyc654-remote-" + UUID.randomUUID())
                enqueueExact(changed)
                val sent = refreshRemote()
                assertTrue("Remote fixture update is unresolved", sent.conflicts == 0 && sent.indeterminate == 0)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
                var observed = false
                while (System.nanoTime() < deadline) {
                    val invalidation = repository.store.locked { it.load().invalidations }
                    // No manual refresh or schedule occurs after the remote edit. A genuine
                    // password-type push and an authenticated snapshot acknowledgement are required.
                    if (invalidation.directPasswordGeneration > before &&
                        invalidation.passwordsHandled >= invalidation.directPasswordGeneration &&
                        repository.list(false).single { it.id == original.id }.data.displayName == changed.displayName) {
                        observed = true; break
                    }
                    Thread.sleep(100)
                }
                assertTrue("Real server push refresh was not observed within the bounded delivery window", observed)
            } finally {
                try {
                    refreshRemote()
                    enqueueExact(data)
                    val restored = refreshRemote()
                    refreshRemote()
                    assertTrue("Remote restoration is unresolved", restored.conflicts == 0 && restored.indeterminate == 0)
                    repository.refresh()
                    assertTrue("Exact push fixture restoration is unverified",
                        same(restore, repository.list(false).single { it.id == original.id }.data.encode()))
                    checkpoint.clear()
                    remoteStore.clear()
                } finally { repository.setEnabled(true) }
            }
        }
    }

    @Test fun nativeDeviceRegistrationAndHeartbeatPreserveCredentialData() {
        assumeTrue(arguments.getString("passwordLiveDeviceRegistration") == "cyc654")
        withFixture { repository, _ ->
            val registered = repository.store.locked { it.load().deviceRegistration } ?: error("Device registration was not persisted")
            assertTrue("Server registration is incomplete", registered.token?.isNotEmpty() == true && !registered.cacheGuid.isNullOrEmpty())
            assertTrue("Registration nonce must survive reopen", registered.nonce.size == 16)
            repository.store.locked { session ->
                val state = session.load()
                val current = state.deviceRegistration!!
                // Expire only the local heartbeat timestamp. Never restore an obsolete server token.
                state.deviceRegistration = DeviceRegistration(current.parent, current.nonce, current.token, current.cacheGuid, current.info, 0)
                session.save(state)
            }
            repository.refreshPreferences()
            val updated = PasswordRepository(context, repository.account).store.locked { it.load().deviceRegistration!! }
            assertTrue("Native UpdateDeviceInfo was not acknowledged", updated.updatedMillis > 0 && updated.token?.isNotEmpty() == true)
            assertTrue("Heartbeat changed the server device identity", updated.cacheGuid == registered.cacheGuid && same(updated.nonce, registered.nonce))
        }
    }

    @Test fun secondaryProcessSchedulesPasswordWorkerToCompletion() {
        val selected = arguments.getString("passwordLiveAccount")
        assumeTrue(!selected.isNullOrBlank())
        val account = AccountManager.get(context).getAccountsByType("com.google").single { it.name == selected }
        val repository = PasswordRepository(context, account)
        assertTrue("Refusing existing pending writes", repository.store.locked {
            val state = it.load(); state.mutations.isEmpty() && state.preferenceMutations.isEmpty()
        })
        val manager = RemoteWorkManager.getInstance(context)
        val workName = "password-fixture-background-" + UUID.randomUUID()
        val request = OneTimeWorkRequestBuilder<PasswordSyncWorker>()
            .setInputData(workDataOf("account" to selected))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        val wasEnabled = repository.enabled
        try {
            repository.setEnabled(true)
            manager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request).get(30, TimeUnit.SECONDS)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            var state: WorkInfo.State? = null
            while (System.nanoTime() < deadline) {
                state = manager.getWorkInfos(WorkQuery.Builder.fromIds(listOf(request.id)).build())
                    .get(10, TimeUnit.SECONDS).singleOrNull()?.state
                if (state?.isFinished == true) break
                Thread.sleep(100)
            }
            assertTrue("Scheduled password worker did not complete successfully", state == WorkInfo.State.SUCCEEDED)
        } finally {
            manager.cancelUniqueWork(workName).get(30, TimeUnit.SECONDS)
            if (!wasEnabled) repository.setEnabled(false)
        }
    }

    @Test fun nativePriorityPreferenceSnapshotAndCacheReopen() {
        val selected = arguments.getString("passwordLiveAccount")
        assumeTrue(!selected.isNullOrBlank())
        val account = AccountManager.get(context).getAccountsByType("com.google").single { it.name == selected }
        val repository = PasswordRepository(context, account)
        assertTrue("Refusing an existing mutation journal", repository.store.locked {
            val state = it.load(); state.mutations.isEmpty() && state.preferenceMutations.isEmpty()
        })
        val wasEnabled = repository.enabled
        try {
            repository.setEnabled(true)
            val receipt = repository.refreshPreferences()
            val saved = repository.store.locked { it.load() }
            assertTrue("Preference snapshot was not persisted", saved.preferenceParent != null && saved.preferences.size == receipt.downloaded)
            val reopened = PasswordRepository(context, account)
            for (raw in saved.preferences) {
                val preference = PriorityPreference.decode(raw)
                assertTrue("Preference cache changed after reopen", reopened.getPreference(preference.name) == preference.value)
            }
            assertNull(reopened.getPreference("cyc654.synthetic.missing." + UUID.randomUUID()))
        } finally { if (!wasEnabled) repository.setEnabled(false) }
    }

    @Test fun nativeAccountRefreshAndEncryptedCacheReopen() = withFixture { repository, original ->
        val reopened = PasswordRepository(context, repository.account).list(false).single { it.id == original.id }
        assertTrue("Native cache changed the fixture", same(original.chromeBytes, reopened.chromeBytes))
    }

    @Test fun nativeUpdateAndRestoreApprovedSyntheticLogin() {
        assumeTrue(arguments.getString("passwordLiveMutations") == "cyc651-synthetic-only")
        withFixture { repository, original ->
            // Persist the restore input before launching any effect. It contains synthetic data only,
            // remains encrypted on the phone, and survives an interrupted instrumentation process.
            val checkpoint = AndroidVaultStore(context, "password-fixture-restore/" + repository.account.name)
            val saved = checkpoint.locked { it.load().chromeLocalData["original"] }
            val restore = saved ?: original.chromeBytes.also { bytes ->
                checkpoint.locked { session -> session.save(VaultState(chromeLocalData = hashMapOf("original" to bytes))) }
            }
            val data = PasswordData.decode(Proto.decode(restore).one(1))
            assertTrue("Restore input is outside the approved fixture", isFixture(data) && data.tag() == original.id)
            try {
                val changed = data.withPassword("synthetic-" + UUID.randomUUID(), System.currentTimeMillis())
                repository.upsert(Proto.field(1, changed.encode()))
                val receipt = repository.refresh()
                assertTrue("Mutation needs owner reconciliation", receipt.conflicts == 0 && receipt.indeterminate == 0)
                val readback = PasswordRepository(context, repository.account).list(false).single { it.id == original.id }
                assertTrue("Native update did not read back", same(changed.encode(), readback.data.encode()))
            } finally {
                repository.upsert(restore)
                val receipt = repository.refresh()
                val restored = repository.list(false).single { it.id == data.tag() }
                assertTrue("Synthetic fixture restoration is unverified", receipt.conflicts == 0 && receipt.indeterminate == 0 &&
                    same(data.encode(), restored.data.encode()))
                checkpoint.clear()
            }
        }
    }

    @Test fun nativeDeleteRecreateAndRestoreTheSingleApprovedSyntheticLogin() {
        assumeTrue(arguments.getString("passwordLiveMutations") == "cyc651-synthetic-only")
        withFixture { repository, original ->
            val checkpoint = AndroidVaultStore(context, "password-fixture-recreate/" + repository.account.name)
            val saved = checkpoint.locked { it.load().chromeLocalData["original"] }
            val restore = saved ?: original.chromeBytes.also { bytes ->
                checkpoint.locked { session -> session.save(VaultState(chromeLocalData = hashMapOf("original" to bytes))) }
            }
            val data = PasswordData.decode(Proto.decode(restore).one(1))
            assertTrue("Restore input is outside the approved fixture", isFixture(data) && data.tag() == original.id)
            try {
                repository.delete(original.chromeBytes)
                val deleted = repository.refresh()
                assertTrue("Delete needs reconciliation", deleted.conflicts == 0 && deleted.indeterminate == 0)
                repository.refresh()
                assertTrue("Synthetic delete was not visible remotely", repository.list(false).none { it.id == original.id })
            } finally {
                // Reconcile a lost response before deciding whether re-creation is necessary.
                repository.refresh()
                assertTrue("Uncertain delete retained; encrypted restoration input preserved",
                    repository.store.locked { it.load().mutations.isEmpty() })
                val current = repository.list(false).singleOrNull { it.id == original.id }
                if (current == null || !same(current.data.encode(), data.encode())) {
                    repository.upsert(restore)
                    val recreated = repository.refresh()
                    assertTrue("Re-creation needs reconciliation", recreated.conflicts == 0 && recreated.indeterminate == 0)
                }
                repository.refresh()
                val restored = repository.list(false).single { it.id == original.id }
                assertTrue("Exact synthetic payload restoration is unverified", same(data.encode(), restored.data.encode()))
                checkpoint.clear()
            }
        }
    }

    private fun withFixture(block: (PasswordRepository, PasswordView) -> Unit) {
        val selected = arguments.getString("passwordLiveAccount")
        assumeTrue(!selected.isNullOrBlank())
        val account = AccountManager.get(context).getAccountsByType("com.google").single { it.name == selected }
        val repository = PasswordRepository(context, account)
        assertTrue("Refusing an existing mutation journal", repository.store.locked { it.load().mutations.isEmpty() })
        val wasEnabled = repository.enabled
        try {
            repository.setEnabled(true)
            repository.refresh()
            val entries = repository.list(false)
            val original = entries.single { isFixture(it.data) }
            assertTrue("Fixture has no password", original.data.password.isNotEmpty())
            // Compare decrypted bytes, never emit passwords, realms or other account records on failure.
            val untouched = entries.filter { it.id != original.id }.associate { it.id to it.data.encode() }
            try { block(repository, original) } finally {
                repository.refresh()
                val after = repository.list(false).filter { it.id != original.id }.associate { it.id to it.data.encode() }
                assertTrue("An unrelated record changed", untouched.keys == after.keys && untouched.all { (id, data) -> same(data, after.getValue(id)) })
            }
        } finally { if (!wasEnabled) repository.setEnabled(false) }
    }

    private fun isFixture(data: PasswordData) = data.realm == "https://cyc651.example.invalid/" &&
        data.username == "cyc651-synthetic-user" && !data.blocked
    private fun same(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
}
