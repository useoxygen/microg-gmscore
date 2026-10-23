/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.microg.gms.auth.AuthManager
import org.microg.gms.auth.folsom.utils.LocalKeyManager
import org.microg.gms.common.Constants
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class PasswordRepository(context: Context, val account: Account) : PasswordVault {
    private val context = context.applicationContext
    private val accounts = AccountManager.get(this.context)
    private val userId: String
    val store: AndroidVaultStore
    private val marker: String

    init {
        if (account.type != "com.google" || !accounts.getAccountsByType("com.google").contains(account)) throw VaultLockedException()
        userId = accounts.getUserData(account, "GoogleUserId")?.takeIf { it.isNotBlank() } ?: throw VaultLockedException()
        marker = hash("google:$userId")
        store = AndroidVaultStore(this.context, "google:$userId", ::checkAccount)
        store.registerAccount()
    }

    override val enabled: Boolean get() = store.enabled
    override fun setEnabled(value: Boolean) {
        checkAccount()
        store.setEnabled(value)
        if (value) {
            val auth = authManager()
            auth.setPermitted(true)
            authManager(CHROMESYNC_SCOPE).setPermitted(true)
            RemoteWorkManager.getInstance(context).enqueueUniquePeriodicWork("password-periodic-$marker", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PasswordSyncWorker>(1, TimeUnit.HOURS).setInputData(workDataOf("account" to account.name))
                    .setConstraints(network()).build()).get(30, TimeUnit.SECONDS)
            schedule()
        } else {
            RemoteWorkManager.getInstance(context).cancelUniqueWork("password-periodic-$marker").get(30, TimeUnit.SECONDS)
            RemoteWorkManager.getInstance(context).cancelUniqueWork("password-now-$marker").get(30, TimeUnit.SECONDS)
        }
    }

    fun schedule() {
        if (!enabled) return
        RemoteWorkManager.getInstance(context).enqueueUniqueWork("password-now-$marker", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<PasswordSyncWorker>().setInputData(workDataOf("account" to account.name))
                .setConstraints(network()).setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
            .get(30, TimeUnit.SECONDS)
    }

    override fun refresh(): SyncReceipt {
        checkEnabled()
        val auth = authManager()
        val token = auth.requestAuthWithBackgroundResolution(false).auth?.takeIf { it.isNotBlank() } ?: throw VaultLockedException()
        val savedKey = store.locked { it.load().customKey }
        val registration = registration()
        val api = PasswordApi(GooglePasswordTransport({ checkEnabled(); token }, "https://chromesyncpasswords-pa.googleapis.com/".toHttpUrl()))
        return try {
            VaultSync(api, store, device = registration).synchronize { metadata -> unlock(metadata, savedKey) }.also { checkAccount() }
        } catch (failure: PasswordRpcException) {
            if (failure.code == 16) auth.invalidateAuthToken(token)
            throw failure
        }
    }

    override fun initializeEmptyVault() {
        checkEnabled()
        val token = authManager().requestAuthWithBackgroundResolution(false).auth ?: throw VaultLockedException()
        val api = PasswordApi(GooglePasswordTransport({ token }, "https://chromesyncpasswords-pa.googleapis.com/".toHttpUrl()))
        try { api.metadata(); return } catch (failure: PasswordRpcException) { if (failure.code != 5) throw failure }
        // Called only from the owner's explicit enable/create UI, never from background reads.
        val metadata = api.initialize()
        store.locked { session -> val state = session.load(); state.metadata = metadata; session.save(state) }
    }

    override fun list(ensureInitialSync: Boolean): List<PasswordView> {
        checkEnabled()
        if (ensureInitialSync && store.locked { it.load().metadata == null }) refresh()
        return store.locked { session ->
            val state = session.load()
            val metadata = state.metadata ?: throw VaultLockedException()
            val keys = unlock(metadata, state.customKey)
            try {
                val visible = visibleRecords(state)
                visible.map { (tag, entity) ->
                    val data = PasswordResourceCodec.read(entity, keys)
                    val chrome = Proto.field(1, data.encode()) + (state.chromeLocalData[tag]?.let { Proto.field(3, it) } ?: byteArrayOf())
                    PasswordView(tag, data, chrome)
                }
            } finally { keys.destroy() }
        }
    }

    override fun upsert(chromePassword: ByteArray) = upsert(chromePassword, false)
    override fun upsertAutomatic(chromePassword: ByteArray) = upsert(chromePassword, true)
    private fun upsert(chromePassword: ByteArray, automatic: Boolean) {
        checkEnabled()
        val wrapper = Proto.decode(chromePassword)
        val data = PasswordData.decode(wrapper.one(1))
        val local = wrapper.optional(3)
        if (local != null && Proto.decode(local).optional(2) != null) throw ProtocolException()
        val tag = data.tag()
        store.locked { session ->
            val state = session.load()
            val keys = unlock(state.metadata ?: throw VaultLockedException(), state.customKey)
            try {
                if (automatic) for (raw in visibleRecords(state).values) {
                    val existing = PasswordResourceCodec.read(raw, keys)
                    if (existing.blocked && existing.realm == data.realm) throw PasswordSaveBlockedException()
                }
                val base = state.records.singleOrNull { Proto.decode(it).string(2) == tag }
                val visible = visibleRecords(state)[tag]
                val merged = visible?.let { PasswordResourceCodec.read(it, keys).mergeFrom(data) } ?: data
                val desired = PasswordResourceCodec.encrypt(merged, keys, base ?: visible)
                // Buffer the enqueue save so the local Chrome metadata commits with the mutation.
                val transaction = object : VaultSession {
                    override fun load() = state
                    override fun save(updated: VaultState) {
                        if (local != null) updated.chromeLocalData[tag] = local
                        session.save(updated)
                    }
                }
                localEngine().enqueue(transaction, if (base == null) MutationKind.CREATE else MutationKind.UPDATE, tag, desired)
            } finally { keys.destroy() }
        }
        schedule()
    }

    private fun visibleRecords(state: VaultState): Map<String, ByteArray> {
        val visible = state.records.associateBy { Proto.decode(it).string(2) }.toMutableMap()
        state.mutations.filter { it.phase != MutationPhase.CONFLICT }.forEach { pending ->
            if (pending.kind == MutationKind.DELETE) visible.remove(pending.tag)
            else pending.desired?.let { visible[pending.tag] = it }
        }
        return visible
    }

    override fun delete(chromePassword: ByteArray) {
        checkEnabled()
        val data = PasswordData.decode(Proto.decode(chromePassword).one(1))
        localEngine().enqueue(MutationKind.DELETE, data.tag())
        schedule()
    }

    override fun setCustomKey(encryption: ByteArray, mac: ByteArray) {
        checkEnabled()
        val candidate = NigoriKey(encryption, mac)
        val exported = candidate.export()
        candidate.destroy()
        try {
            store.locked { session ->
                val state = session.load()
                VaultKeys.unlock(state.pendingMetadata ?: state.metadata ?: throw VaultLockedException(), customKey = exported).destroy()
                state.customKey = exported.copyOf()
                session.save(state)
            }
        } finally { exported.fill(0) }
        schedule()
    }

    override fun unlockPassphrase(password: ByteArray) {
        checkEnabled()
        store.locked { session ->
            val state = session.load()
            val metadata = state.pendingMetadata ?: state.metadata ?: throw VaultLockedException()
            val key = VaultKeys.keyForPassphrase(metadata, password)
            try {
                val exported = key.export()
                try {
                    VaultKeys.unlock(metadata, customKey = exported).destroy()
                    state.customKey = exported.copyOf()
                    session.save(state)
                } finally { exported.fill(0) }
            } finally { key.destroy() }
        }
        schedule()
    }

    /** Settings never require Nigori keys; they remain usable while password recovery is pending. */
    fun refreshPreferences(): SyncReceipt {
        checkEnabled()
        val auth = authManager()
        val token = auth.requestAuthWithBackgroundResolution(false).auth?.takeIf { it.isNotBlank() } ?: throw VaultLockedException()
        val registration = registration()
        val api = PasswordApi(GooglePasswordTransport({ checkEnabled(); token }, "https://chromesyncpasswords-pa.googleapis.com/".toHttpUrl()))
        return try {
            val parent = PasswordApi.checkedParent(Proto.decode(api.metadata()).string(1))
            PreferenceSync(api, store, registration).synchronize(parent)
        } catch (failure: PasswordRpcException) {
            if (failure.code == 16) auth.invalidateAuthToken(token)
            throw failure
        }
    }

    override fun getPreference(name: String): String? {
        checkEnabled()
        if (store.locked { it.load().preferenceParent == null }) refreshPreferences()
        return localPreferences().value(name)
    }
    override fun setPreference(name: String, value: String) {
        checkEnabled()
        if (store.locked { it.load().preferenceParent == null }) refreshPreferences()
        localPreferences().enqueue(name, value)
        schedule()
    }

    private fun unlock(metadata: ByteArray, customKey: ByteArray? = null): VaultKeys {
        val type = Proto.decode(Proto.decode(metadata).one(3)).integer(2)
        return if (type == 5L) VaultKeys.unlockTrustedVault(metadata) { force ->
            checkEnabled()
            runBlocking {
                LocalKeyManager.getInstance(context).getLocalKeysOrSync(context, account.name, "chromesync", "password-sync", forceRefresh = force)
                    .mapNotNull { it.keyMaterial?.toByteArray() }
            }
        } else VaultKeys.unlock(metadata, customKey = customKey)
    }
    private fun registration(): DeviceTokenProvider {
        val info = PasswordDeviceRegistration.info(context, store.locked { it.load().deviceRegistration })
        val auth = authManager(CHROMESYNC_SCOPE)
        var token: String? = null
        val transport = GooglePasswordTransport({
            checkEnabled()
            token ?: auth.requestAuthWithBackgroundResolution(false).auth?.takeIf { it.isNotBlank() }
                ?.also { token = it } ?: throw VaultLockedException()
        }, "https://chromesyncentities-pa.googleapis.com/".toHttpUrl())
        val registration = DeviceRegistrationSync(transport, { info })
        return DeviceTokenProvider { session, state, parent ->
            try { registration.token(session, state, parent) }
            catch (failure: PasswordRpcException) {
                if (failure.code == 16) token?.let(auth::invalidateAuthToken)
                throw failure
            }
        }
    }
    private fun authManager(scope: String = "https://www.googleapis.com/auth/login_manager") = AuthManager(context, account.name, Constants.GMS_PACKAGE_NAME,
        "oauth2:$scope").apply {
        isGmsApp = true
        setPackageSignature(Constants.GMS_PACKAGE_SIGNATURE_SHA1)
    }
    private fun checkAccount() {
        if (!accounts.getAccountsByType("com.google").contains(account) || accounts.getUserData(account, "GoogleUserId") != userId) throw VaultLockedException()
    }
    private fun checkEnabled() { checkAccount(); if (!enabled) throw VaultLockedException() }
    private fun localEngine() = VaultSync(PasswordApi(PasswordTransport { _, _ -> error("enqueue is local") }), store)
    private fun localPreferences() = PreferenceSync(PasswordApi(PasswordTransport { _, _ -> error("enqueue is local") }), store)
    override fun toString(): String = "PasswordRepository[redacted]"
    companion object {
        private const val CHROMESYNC_SCOPE = "https://www.googleapis.com/auth/chromesync"
        internal fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }
}

class PasswordView(val id: String, val data: PasswordData, val chromeBytes: ByteArray) {
    override fun toString(): String = "PasswordView[redacted]"
}
