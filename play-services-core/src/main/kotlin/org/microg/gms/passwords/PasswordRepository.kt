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

class PasswordRepository(context: Context, val account: Account, private val expectedVaultParent: String? = null) : ManagedPasswordVault {
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
            PasswordDeviceRegistration.requestPushConnection(context)
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

    // Capture and consume cached recovery keys under the same account lock as reset.
    // The pending-reset flag alone cannot fence an old in-memory copy after reset is acknowledged.
    override fun refresh(): SyncReceipt = LocalKeyManager.getInstance(context).withRecoveryAuthority(account.name, userId) {
        checkEnabled()
        val saved = store.locked { session -> session.load().let { it.customKey to it.trustedVaultKeys.map { raw -> raw.copyOf() } } }
        val registration = registration()
        val api = PasswordApi(authenticatedTransport())
        try { VaultSync(api, store, device = registration).synchronize { metadata ->
            checkSnapshotParent(metadata)
            unlock(metadata, saved.first, saved.second)
        }.also { checkAccount() } }
        finally { saved.first?.fill(0); saved.second.forEach { it.fill(0) } }
    }

    override fun initializeEmptyVault() {
        checkEnabled()
        val api = PasswordApi(authenticatedTransport())
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
            checkSnapshotParent(metadata)
            val keys = unlock(metadata, state.customKey, state.trustedVaultKeys)
            try {
                val visible = visibleRecords(state)
                visible.map { (tag, entity) ->
                    val data = PasswordResourceCodec.read(entity, keys)
                    val chrome = Proto.field(1, data.encode()) + (state.chromeLocalData[tag]?.let { Proto.field(3, it) } ?: byteArrayOf())
                    PasswordView(tag, data, chrome, PasswordAffiliations.cachedDisplayInfo(state.affiliationCache, data.realm)?.name)
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
            val keys = unlock(state.metadata ?: throw VaultLockedException(), state.customKey, state.trustedVaultKeys)
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

    /** Read only the authenticated server snapshot, not optimistic local edits. */
    override fun committed(tag: String): ByteArray? {
        checkEnabled()
        return store.locked { session ->
            val state = session.load()
            val raw = state.records.singleOrNull { Proto.decode(it).string(2) == tag } ?: return@locked null
            checkSnapshotParent(state.metadata ?: throw VaultLockedException())
            val keys = unlock(state.metadata ?: throw VaultLockedException(), state.customKey, state.trustedVaultKeys)
            try { Proto.field(1, PasswordResourceCodec.read(raw, keys).encode()) } finally { keys.destroy() }
        }
    }
    override fun pending(tag: String): Boolean { checkEnabled(); return store.locked { it.load().mutations.any { mutation -> mutation.tag == tag } } }

    override fun insertIfAbsent(chrome: ByteArray) = ownerChange(null, chrome, insert = true)
    override fun editIfUnchanged(original: ByteArray, replacement: ByteArray) = ownerChange(original, replacement)
    override fun deleteIfUnchanged(chrome: ByteArray) = ownerChange(chrome, null)
    private fun ownerChange(original: ByteArray?, replacement: ByteArray?, insert: Boolean = false) {
        checkEnabled()
        val before = original?.let { PasswordData.decode(Proto.decode(it).one(1)) }
        val after = replacement?.let { PasswordData.decode(Proto.decode(it).one(1)) }
        val tag = (before ?: after ?: throw ProtocolException()).tag()
        if (before != null && after != null && before.tag() != after.tag()) throw ConflictException()
        store.locked { session ->
            val state = session.load()
            if (state.mutations.any { it.tag == tag } || state.pendingMetadata != null) throw PendingMutationException()
            checkSnapshotParent(state.metadata ?: throw VaultLockedException())
            val keys = unlock(state.metadata ?: throw VaultLockedException(), state.customKey, state.trustedVaultKeys)
            try {
                val current = state.records.singleOrNull { Proto.decode(it).string(2) == tag }
                val currentChrome = current?.let { Proto.field(1, PasswordResourceCodec.read(it, keys).encode()) }
                if (insert && currentChrome != null) {
                    if (PasswordTransferEngine.samePassword(currentChrome, replacement!!)) return@locked
                    throw ConflictException()
                }
                if (!insert && (currentChrome == null || !PasswordTransferEngine.samePassword(currentChrome, original!!))) throw ConflictException()
                val desired = after?.let { PasswordResourceCodec.encrypt(it, keys, current) }
                localEngine().enqueue(session, when { after == null -> MutationKind.DELETE; current == null -> MutationKind.CREATE; else -> MutationKind.UPDATE }, tag, desired)
            } finally { keys.destroy() }
        }
        schedule()
    }

    override fun pendingChanges(): List<PasswordPendingChange> {
        checkEnabled()
        return store.locked { session ->
            val state = session.load()
            val keys = unlock(state.metadata ?: throw VaultLockedException(), state.customKey, state.trustedVaultKeys)
            try { state.mutations.map { operation ->
                val entity = operation.desired ?: state.records.singleOrNull { Proto.decode(it).string(2) == operation.tag }
                PasswordPendingChange(operation.id, operation.kind, operation.phase, entity?.let { PasswordResourceCodec.read(it, keys).realm }.orEmpty())
            } } finally { keys.destroy() }
        }
    }
    override fun dropChange(id: String) { checkEnabled(); store.locked { VaultRecovery.drop(it, id) } }
    override fun retryChange(id: String, acknowledgeUncertain: Boolean) {
        checkEnabled()
        refresh() // Reconcile before the owner explicitly authorizes a new attempt.
        store.locked { session ->
            val state = session.load()
            if (state.mutations.none { it.id == id }) return@locked // Previous effect was observed.
            val keys = unlock(state.metadata ?: throw VaultLockedException(), state.customKey, state.trustedVaultKeys)
            try { VaultRecovery.retry(session, id, keys, acknowledgeUncertain) } finally { keys.destroy() }
        }
        schedule()
    }
    override fun hasRemoteReset(): Boolean = store.locked { session ->
        val state = session.load()
        state.pendingMetadata?.let { pending -> state.metadata?.let { previous -> Proto.decode(pending).string(1) != Proto.decode(previous).string(1) } } == true
    }
    override fun acceptRemoteReset() { checkEnabled(); store.locked(VaultRecovery::acceptRemoteReset); schedule() }

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
        val registration = registration()
        val api = PasswordApi(authenticatedTransport())
        val parent = PasswordApi.checkedParent(Proto.decode(api.metadata()).string(1))
        return PreferenceSync(api, store, registration).synchronize(parent)
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

    fun needsTrustedVaultRecovery(): Boolean = store.locked { session ->
        val state = session.load()
        (state.pendingMetadata ?: state.metadata)?.let { Proto.decode(Proto.decode(it).one(3)).integer(2) == 5L } == true
    }
    /** A successful WebView result is insufficient: validate keys against this account's actual keybag before persisting. */
    fun acceptTrustedVaultKeys(keys: List<ByteArray>, scheduleRefresh: Boolean = true) {
        checkEnabled()
        checkTrustedVaultDomainAuthority()
        store.locked { VaultRecovery.acceptTrustedKeys(it, keys) }
        checkTrustedVaultDomainAuthority()
        if (scheduleRefresh) schedule()
    }
    /** Capture before opening recovery; a later WebView result cannot adopt another vault. */
    internal fun trustedVaultRecoveryParent(expectedUserId: String): String {
        checkTrustedVaultIdentity(expectedUserId)
        return store.locked { session ->
            checkTrustedVaultIdentity(expectedUserId)
            val metadata = VaultRecovery.trustedMetadata(session.load())
            checkSnapshotParent(metadata)
            PasswordApi.checkedParent(Proto.decode(metadata).string(1))
        }
    }
    internal fun acceptVersionedTrustedVaultKeys(keys: List<VersionedTrustedVaultKey>, expectedParent: String,
        expectedUserId: String, scheduleRefresh: Boolean = true, authorize: () -> Unit) {
        checkTrustedVaultIdentity(expectedUserId)
        checkTrustedVaultDomainAuthority()
        if (expectedVaultParent != null && expectedVaultParent != expectedParent) throw VaultResetException()
        store.locked { session -> VaultRecovery.acceptVersionedTrustedKeys(session, keys, expectedParent) {
            authorize(); checkTrustedVaultIdentity(expectedUserId); checkTrustedVaultDomainAuthority()
        } }
        if (scheduleRefresh) schedule()
    }
    /** No recovery, network fetch, guessed epoch, or enrollment is performed by this getter. */
    internal fun versionedTrustedVaultKeys(expectedParent: String, expectedUserId: String): List<VersionedTrustedVaultKey> {
        checkTrustedVaultIdentity(expectedUserId)
        checkTrustedVaultDomainAuthority()
        return store.locked { session ->
            checkTrustedVaultIdentity(expectedUserId)
            checkTrustedVaultDomainAuthority()
            val state = session.load()
            if (state.pendingMetadata != null) throw VaultResetException()
            val metadata = VaultRecovery.trustedMetadata(state)
            checkSnapshotParent(metadata)
            if (Proto.decode(metadata).string(1) != expectedParent) throw VaultResetException()
            val retained = state.versionedTrustedVaultKeys ?: throw VaultLockedException()
            if (retained.parent != expectedParent) throw VaultResetException()
            val keys = retained.keys
            val raw = ArrayList<ByteArray>()
            var returned = false
            try {
                keys.forEach { raw.add(it.material) }
                VaultKeys.unlock(metadata, trustedVaultKeys = raw).destroy()
                checkTrustedVaultIdentity(expectedUserId)
                checkTrustedVaultDomainAuthority()
                returned = true
                keys
            } finally { raw.forEach { it.fill(0) }; if (!returned) keys.forEach { it.destroy() } }
        }
    }
    private fun checkTrustedVaultIdentity(expectedUserId: String) {
        checkEnabled()
        if (userId != expectedUserId) throw VaultLockedException()
    }
    internal fun clearTrustedVaultRecovery(expectedParent: String?, expectedUserId: String, authorize: () -> Unit) {
        checkAccount()
        if (userId != expectedUserId) throw VaultLockedException()
        if (!LocalKeyManager.getInstance(context).hasPendingDomainReset(account.name, "chromesync")) throw PendingMutationException()
        if (expectedVaultParent != null && expectedParent != null && expectedVaultParent != expectedParent) throw VaultResetException()
        store.locked { session -> VaultRecovery.clearTrustedKeys(session, expectedParent) {
            authorize(); checkAccount()
            if (userId != expectedUserId || !LocalKeyManager.getInstance(context).hasPendingDomainReset(account.name, "chromesync"))
                throw VaultLockedException()
        } }
    }
    private fun checkTrustedVaultDomainAuthority() {
        if (LocalKeyManager.getInstance(context).hasPendingDomainReset(account.name, "chromesync")) throw VaultLockedException()
    }
    private fun unlock(metadata: ByteArray, customKey: ByteArray? = null, cachedTrustedKeys: List<ByteArray> = emptyList()): VaultKeys {
        val type = Proto.decode(Proto.decode(metadata).one(3)).integer(2)
        if (type == 5L) checkTrustedVaultDomainAuthority()
        return if (type == 5L) VaultKeys.unlockTrustedVault(metadata) { force ->
            checkEnabled(); checkTrustedVaultDomainAuthority()
            if (!force && cachedTrustedKeys.isNotEmpty()) cachedTrustedKeys.map { it.copyOf() } else runBlocking {
                LocalKeyManager.getInstance(context).getLocalKeysOrSync(context, account.name, "chromesync", "password-sync", forceRefresh = force,
                    validateLegacyKeys = { candidates ->
                        val raw = candidates.mapNotNull { it.keyMaterial?.toByteArray() }
                        try { runCatching { VaultKeys.unlock(metadata, trustedVaultKeys = raw).destroy() }.isSuccess }
                        finally { raw.forEach { it.fill(0) } }
                    })
                    .mapNotNull { it.keyMaterial?.toByteArray() }
            }
        }.let { keys ->
            try { checkTrustedVaultDomainAuthority(); keys }
            catch (failure: Exception) { keys.destroy(); throw failure }
        } else VaultKeys.unlock(metadata, customKey = customKey)
    }
    private fun registration(): DeviceTokenProvider {
        val info = PasswordDeviceRegistration.info(context, store.locked { it.load().deviceRegistration })
        val transport = authenticatedTransport(CHROMESYNC_SCOPE, "chromesyncentities-pa.googleapis.com")
        return DeviceRegistrationSync(transport, { info })
    }

    private fun authenticatedTransport(scope: String = CHROMESYNC_SCOPE,
        host: String = "chromesyncpasswords-pa.googleapis.com"): PasswordTransport {
        val auth = authManager(scope)
        val session = PasswordOAuthSession({ checkEnabled(); auth.requestAuthWithBackgroundResolution(false).auth }, auth::invalidateAuthToken)
        return GooglePasswordTransport({ checkEnabled(); session.token() }, "https://$host/".toHttpUrl(), rejectedToken = session::reject)
    }
    internal fun sharingTransport(): PasswordTransport = authenticatedTransport()
    internal fun sharingKeys(metadata: ByteArray, state: VaultState): VaultKeys = unlock(metadata, state.customKey, state.trustedVaultKeys)
    internal fun verifyWifiAccess() = checkEnabled()
    internal fun wifiDeviceRegistration(): DeviceTokenProvider = registration()
    private fun authManager(scope: String = CHROMESYNC_SCOPE) = AuthManager(context, account.name, Constants.GMS_PACKAGE_NAME,
        "oauth2:$scope").apply {
        isGmsApp = true
        setPackageSignature(Constants.GMS_PACKAGE_SIGNATURE_SHA1)
    }
    private fun checkAccount() {
        if (!accounts.getAccountsByType("com.google").contains(account) || accounts.getUserData(account, "GoogleUserId") != userId) throw VaultLockedException()
    }
    private fun checkSnapshotParent(metadata: ByteArray) {
        if (expectedVaultParent != null && Proto.decode(metadata).string(1) != expectedVaultParent) throw VaultResetException()
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

class PasswordView(val id: String, val data: PasswordData, val chromeBytes: ByteArray, val brandName: String? = null) {
    override fun toString(): String = "PasswordView[redacted]"
}
