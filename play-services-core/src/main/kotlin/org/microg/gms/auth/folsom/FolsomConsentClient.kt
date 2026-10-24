/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom

import android.accounts.Account
import android.accounts.AccountManager
import android.app.KeyguardManager
import android.app.backup.BackupManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.provider.Settings
import androidx.work.*
import com.squareup.wire.GrpcClient
import com.squareup.wire.GrpcException
import com.squareup.wire.GrpcStatus
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.microg.gms.auth.folsom.utils.*
import org.microg.gms.gcm.AuthHeaderInterceptor
import org.microg.gms.passwords.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class FolsomConsentOwnerStatus(val id: String?, val domain: String?, val value: Boolean?, val phase: FolsomConsentPhase?)
internal class FolsomConsentRecoveryRequired(val domain: String) : Exception("Recovery keys unavailable")

/** The admitted SDK caller delegates the same account-scoped authority as getKeyMaterial. */
internal class FolsomConsentClient(context: Context, private val account: Account, private val accountId: String,
    private val authorize: () -> Unit) {
    private val context = context.applicationContext
    private val local = LocalKeyManager.getInstance(this.context)
    private val stopped = AtomicBoolean(false)
    private val backups = CopyOnWriteArrayList<RecoverableVaultClient>()
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .callTimeout(60, TimeUnit.SECONDS).connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    fun cancel() { stopped.set(true); backups.forEach(RecoverableVaultClient::cancel); http.dispatcher.cancelAll() }
    private fun readOnlyVerify() {
        if (stopped.get() || Thread.currentThread().isInterrupted) throw VaultLockedException()
        authorize()
        val manager = AccountManager.get(context)
        if (account.type != "com.google" || accountId.isBlank() || !manager.getAccountsByType("com.google").contains(account) ||
            manager.getUserData(account, "GoogleUserId") != accountId ||
            FolsomConsentPolicy.coupled.any { local.hasPendingDomainReset(account.name, it) }) throw VaultLockedException()
    }
    private fun verify() {
        readOnlyVerify()
        if (android.os.Build.VERSION.SDK_INT < 23) throw VaultLockedException()
        val lock = context.getSystemService(KeyguardManager::class.java)
        if (!lock.isDeviceSecure || lock.isDeviceLocked || lock.isKeyguardLocked) throw VaultLockedException()
    }
    private fun service(check: () -> Unit = ::verify): SecurityDomainServiceClient {
        check(); val token = context.requestOauthToken(account.name); check()
        return GrpcClient.Builder().client(http.newBuilder().addInterceptor(AuthHeaderInterceptor(token)).build())
            .baseUrl(SECURITY_DOMAIN_BASE_URL).minMessageToCompress(Long.MAX_VALUE).build().create(SecurityDomainServiceClient::class)
    }
    private fun backup(domain: String) = RecoverableVaultClient(context, account, accountId, domain, null, ::verify, sdkMode = true).also(backups::add)
    private fun material(domain: String, check: () -> Unit = ::verify): List<DomainLifecycleKey> {
        check(); val result = ArrayList<DomainLifecycleKey>()
        try {
            local.getKeysForDomain(account.name, domain).forEach { key ->
                val raw = key.keyMaterial?.toByteArray() ?: throw VaultLockedException()
                try { result.add(DomainLifecycleKey(key.keyVersion?.takeIf { it > 0 } ?: throw VaultLockedException(), raw)) }
                finally { raw.fill(0) }
            }
            check(); return result
        } catch (failure: Throwable) { result.forEach(DomainLifecycleKey::close); throw failure }
    }
    private fun epoch(domain: String, id: String, check: () -> Unit = ::verify): Int {
        val actual = service(check).GetSecurityDomain().executeBlocking(GetSecurityDomainRequest(name = DomainLifecycleWire.name(domain), view = 2, requestId = id))
        check()
        if (actual.name != DomainLifecycleWire.name(domain)) throw ProtocolException()
        return actual.currentEpoch?.takeIf { it > 0 } ?: throw VaultLockedException()
    }
    private fun member(public: ByteArray, id: String): SecurityDomainMemberResponse? {
        val name = EnrollmentWire.memberName(public)
        val value = try { service().GetSecurityDomainMember().executeBlocking(GetSecurityDomainMemberRequest(name = name, view = 2,
            filterOptions = FilterOptions(includeDeleted = false), requestId = id)) }
        catch (failure: GrpcException) { if (failure.grpcStatus == GrpcStatus.NOT_FOUND) null else throw failure }
        verify()
        value?.let { if (it.name != name || it.publicKey?.toByteArray()?.contentEquals(public) != true || it.memberType != 2) throw ConflictException() }
        return value
    }
    /** anza.e: only the selected Google backup account's enabled, opted-in OS backup prevents leaving its LSKF member. */
    private fun backupEnabled(check: () -> Unit = ::verify): Boolean {
        check()
        val intent = Intent("com.google.android.gms.backup.BackupAccountManagerService").setPackage(context.packageName)
            .addCategory(Intent.CATEGORY_DEFAULT)
        val resolved = context.packageManager.resolveService(intent, 0)?.serviceInfo ?: return false
        if (resolved.applicationInfo.uid != Process.myUid()) throw SecurityException("Backup account unavailable")
        val ready = CountDownLatch(1); var binder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { binder = service; ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { binder = null; ready.countDown() }
        }
        val bound = context.bindService(intent.setComponent(ComponentName(resolved.packageName, resolved.name)), connection, Context.BIND_AUTO_CREATE)
        if (!bound) throw VaultLockedException()
        try {
            if (!ready.await(10, TimeUnit.SECONDS)) throw VaultLockedException()
            check(); val remote = binder ?: throw VaultLockedException()
            val request = Parcel.obtain(); val response = Parcel.obtain()
            val selected = try {
                request.writeInterfaceToken("com.google.android.gms.backup.IBackupAccountManagerService")
                if (!remote.transact(1, request, response, 0)) throw VaultLockedException()
                response.readException()
                if (response.readInt() == 0) null else Account.CREATOR.createFromParcel(response)
            } finally { request.recycle(); response.recycle() }
            check(); if (selected != account) return false
            val enabled = BackupManager::class.java.getMethod("isBackupEnabled").invoke(BackupManager(context)) as? Boolean ?: throw VaultLockedException()
            return enabled && Settings.Secure.getInt(context.contentResolver, "backup_encryption_opt_in_displayed", 0) == 1
        } finally { context.unbindService(connection) }
    }
    fun get(domain: String): Boolean {
        authorize()
        val snapshot = local.consentSnapshot(account.name, accountId)
        val actual = FolsomConsentPolicy.effective(snapshot, domain)
        // anza.h schedules propagation to an existing paired domain with no explicit true value.
        if (actual && domain in FolsomConsentPolicy.coupled) snapshot.domains.filter {
            it.key in FolsomConsentPolicy.coupled && it.value_?.consent != true
        }.forEach { FolsomConsentWorker.schedule(context, account, accountId, it.key!!) }
        authorize(); return actual
    }
    private fun status(state: FolsomConsentState): FolsomConsentOwnerStatus {
        val a = state.attempt
        if (a?.accountId?.let { it != accountId } == true) throw VaultLockedException()
        return FolsomConsentOwnerStatus(a?.id, a?.domain, a?.value, a?.phase)
    }
    fun status(): FolsomConsentOwnerStatus {
        authorize()
        return local.withFolsomConsent(account.name, accountId) { load, _ ->
            val bytes = load(); val state = try { FolsomConsentState.decode(bytes) } finally { bytes.fill(0) }
            try { status(state).also { authorize() } } finally { state.destroy() }
        }
    }
    fun set(domain: String, value: Boolean): Boolean = session { manager -> manager.set(domain, value).attempt?.phase == FolsomConsentPhase.COMMITTED }
    fun prepare(domain: String, value: Boolean): FolsomConsentOwnerStatus = session(preparationOnly = true) { status(it.prepare(domain, value)) }
    /** anqw returns SUCCESS without notifying when consent is already effective or the first local epoch is absent/zero. */
    fun needsConsentPrompt(domain: String): Boolean {
        readOnlyVerify(); DomainLifecycleWire.checkedDomain(domain)
        return local.withRecoveryAuthority(account.name, accountId) {
            readOnlyVerify()
            val snapshot = local.consentSnapshotUnderRecoveryLock(account.name, accountId)
            val keys = local.getKeysForDomain(account.name, domain)
            FolsomConsentPolicy.needsPrompt(snapshot, domain, keys.firstOrNull()?.keyVersion).also { readOnlyVerify() }
        }
    }
    fun reconcile(expectedAttemptId: String? = null): FolsomConsentOwnerStatus = session(expectedAttemptId) { status(it.reconcile()) }
    fun review(id: String): ByteArray = session(id) { it.review(id) }
    fun retry(id: String, reviewDigest: ByteArray): FolsomConsentOwnerStatus = session(id) { status(it.retry(id, reviewDigest)) }
    fun close(id: String): FolsomConsentOwnerStatus = session(id) { status(it.close(id)) }
    private fun <T> session(expectedId: String? = null, preparationOnly: Boolean = false, action: (FolsomConsentManager) -> T): T {
        fun verify() { if (preparationOnly) readOnlyVerify() else this@FolsomConsentClient.verify() }
        verify(); val opened = ArrayList<FolsomConsentState>()
        var acceptedId = expectedId; var acceptedClosed = false
        fun load(): FolsomConsentState = local.withFolsomConsent(account.name, accountId) { read, _ ->
            verify(); val bytes = read(); val state = try { FolsomConsentState.decode(bytes) } finally { bytes.fill(0) }
            if (expectedId != null && (if (acceptedClosed) state.attempt != null else state.attempt?.id != acceptedId)) { state.destroy(); throw ConflictException() }
            opened.add(state); state
        }
        fun save(before: FolsomConsentState, after: FolsomConsentState) {
            verify(); local.compareFolsomConsent(account.name, accountId, before, after, authorize = authorize); opened.add(after); verify()
            if (expectedId != null) {
                if (after.attempt == null && before.attempt?.id == acceptedId) acceptedClosed = true
                else if (acceptedClosed && before.attempt == null && after.attempt != null) { acceptedId = after.attempt.id; acceptedClosed = false }
            }
        }
        fun commit(before: FolsomConsentState, after: FolsomConsentState, domain: String, value: Boolean) {
            verify(); local.compareFolsomConsent(account.name, accountId, before, after, domain, value, authorize); opened.add(after); verify()
        }
        val remote = object : FolsomConsentRemote {
            override fun review(domain: String, value: Boolean, id: String): ByteArray {
                verify(); DomainLifecycleWire.checkedDomain(domain)
                val anchors = ArrayList<ByteArray>()
                for (target in listOf(domain) + FolsomConsentPolicy.coupled.filter { it != domain }) {
                    val cached = local.getKeysForDomain(account.name, target)
                    if (target == domain && value || cached.isNotEmpty()) runBlocking {
                        local.getLocalKeysOrSync(context, account.name, target, id, expectedUserId = accountId)
                    }
                    verify(); val keys = material(target, ::verify)
                    try {
                        if (keys.isEmpty()) { if (target == domain && value) throw FolsomConsentRecoveryRequired(domain); continue }
                        val current = epoch(target, id, ::verify)
                        if (keys.maxOf { it.epoch } != current) throw FolsomConsentRecoveryRequired(target)
                        anchors.add(Proto.field(1, target) + Proto.integer(2, current.toLong()) +
                            Proto.field(3, RecoverableVaultWire.keysDigest(keys)) + Proto.integer(4, local.recoveryGeneration(account.name, target, accountId)))
                    } finally { keys.forEach(DomainLifecycleKey::close) }
                }
                val snapshot = local.consentSnapshot(account.name, accountId)
                return Proto.field(1, accountId) + Proto.field(2, domain) + Proto.integer(3, if (value) 1 else 0) +
                    anchors.fold(byteArrayOf()) { out, a -> out + Proto.field(4, a) } + Proto.integer(5, if (backupEnabled(::verify)) 1 else 0) +
                    Proto.field(6, MessageDigest.getInstance("SHA-256").digest(snapshot.encode()))
            }
            override fun verify(review: ByteArray) {
                this@FolsomConsentClient.verify(); val p = Proto.decode(review)
                if (p.string(1) != accountId || p.bytes(4).size > 2) throw VaultLockedException()
                if (!MessageDigest.isEqual(p.one(6), MessageDigest.getInstance("SHA-256").digest(local.consentSnapshot(account.name, accountId).encode())))
                    throw ConflictException()
                for (raw in p.bytes(4)) {
                    val anchor = Proto.decode(raw); val target = DomainLifecycleWire.checkedDomain(anchor.string(1)); val keys = material(target)
                    try {
                        if (local.recoveryGeneration(account.name, target, accountId) != anchor.integer(4) ||
                            !MessageDigest.isEqual(anchor.one(3), RecoverableVaultWire.keysDigest(keys)) || epoch(target, UUID.randomUUID().toString()).toLong() != anchor.integer(2))
                            throw VaultResetException()
                    } finally { keys.forEach(DomainLifecycleKey::close) }
                }
                if ((p.integer(5) == 1L) != backupEnabled()) throw ConflictException()
            }
            override fun mutations(review: ByteArray, id: String): List<FolsomConsentMutation> {
                verify(review); val p = Proto.decode(review); val desired = p.integer(3) == 1L; val backupEnabled = p.integer(5) == 1L
                return p.bytes(4).flatMap { raw ->
                    val target = Proto.decode(raw).string(1)
                    backup(target).knownMemberPublicKeys().mapNotNull { public ->
                        val actual = member(public, id)
                        if (actual?.securityDomains?.none { it.name == DomainLifecycleWire.name(target) } != false) null
                        else if (!desired && !backupEnabled) FolsomConsentMutation(FolsomConsentMutationKind.LEAVE, target, FolsomConsentWire.leave(target, public, id))
                        else if (target == "chromesync") FolsomConsentMutation(FolsomConsentMutationKind.UPDATE, target,
                            FolsomConsentWire.update(target, public, backupEnabled, desired, id))
                        else null
                    }
                }
            }
            override fun synchronize(review: ByteArray, id: String): Boolean {
                verify(review); val p = Proto.decode(review)
                if (p.integer(3) != 1L) return true
                val complete = FolsomConsentLowerJournals.synchronize(p.bytes(4).map { Proto.decode(it).string(1) }) { target -> backup(target).syncConsent() }
                verify(review); return complete
            }
            override fun synchronized(review: ByteArray, id: String): Boolean {
                // Reconcile the actual lower journals. Their WAITING/PREPARED/confirmed-upload
                // stages may finish under this still-current consent; launched RPCs are never resent.
                return synchronize(review, id)
            }
            override fun invoke(kind: FolsomConsentMutationKind, request: ByteArray): ByteArray {
                return local.withRecoveryAuthority(account.name, accountId) {
                    this@FolsomConsentClient.verify()
                    val raw = local.consentJournalUnderRecoveryLock(account.name, accountId)
                    val state = try { FolsomConsentState.decode(raw) } finally { raw.fill(0) }
                    try {
                        val attempt = state.attempt ?: throw ConflictException()
                        if (attempt.accountId != accountId || attempt.mutations.count {
                            it.kind == kind && it.phase == FolsomConsentMutationPhase.LAUNCHED && it.request.contentEquals(request) } != 1) throw ConflictException()
                    } finally { state.destroy() }
                    val client = service()
                    when (kind) {
                    FolsomConsentMutationKind.LEAVE -> {
                        val body = LeaveSecurityDomainRequest.ADAPTER.decode(request)
                        if (!body.encode().contentEquals(request)) throw ProtocolException()
                        client.LeaveSecurityDomain().executeBlocking(body).encode()
                    }
                    FolsomConsentMutationKind.UPDATE -> {
                        val body = UpdateSecurityDomainMemberRequest.ADAPTER.decode(request)
                        if (!body.encode().contentEquals(request)) throw ProtocolException()
                        client.UpdateSecurityDomainMember().executeBlocking(body).encode()
                    }
                    }
                }
            }
            override fun applied(mutation: FolsomConsentMutation): Boolean {
                this@FolsomConsentClient.verify()
                val request = Proto.decode(mutation.request)
                val memberName = if (mutation.kind == FolsomConsentMutationKind.LEAVE) request.string(2) else Proto.decode(request.one(1)).string(1)
                val public = backup(mutation.domain).knownMemberPublicKeys().singleOrNull { EnrollmentWire.memberName(it) == memberName } ?: throw ConflictException()
                val actual = member(public, UUID.randomUUID().toString())
                val membership = actual?.securityDomains?.singleOrNull { it.name == DomainLifecycleWire.name(mutation.domain) }
                return if (mutation.kind == FolsomConsentMutationKind.LEAVE) membership == null
                else membership?.metadata?.encode()?.contentEquals(Proto.decode(Proto.decode(request.one(1)).bytes(3).single()).one(6)) == true
            }
        }
        return try { action(FolsomConsentManager(accountId, remote, ::load, ::save, ::commit, ::verify)).also { verify() } }
        finally { opened.forEach(FolsomConsentState::destroy) }
    }
}

/** anza.h's coupled-consent propagation uses prior exact-account stored consent, never an absent/default opt-in. */
class FolsomConsentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val name = inputData.getString("account") ?: return Result.failure()
        val id = inputData.getString("gaia") ?: return Result.failure()
        val domain = inputData.getString("domain") ?: return Result.failure()
        if (domain !in FolsomConsentPolicy.coupled) return Result.failure()
        val manager = AccountManager.get(applicationContext)
        val account = manager.getAccountsByType("com.google").singleOrNull { it.name == name } ?: return Result.success()
        val local = LocalKeyManager.getInstance(applicationContext)
        fun authorize() {
            if (isStopped || manager.getUserData(account, "GoogleUserId") != id ||
                !FolsomConsentPolicy.effective(local.consentSnapshot(name, id), domain)) throw VaultLockedException()
        }
        // Do not use this callback while LocalKM's short store lock is held: capture only owner/account authority.
        fun ownerOnly() { if (isStopped || manager.getUserData(account, "GoogleUserId") != id) throw VaultLockedException() }
        val client = FolsomConsentClient(applicationContext, account, id, ::ownerOnly)
        return try { authorize(); if (client.set(domain, true)) Result.success() else Result.retry() }
        catch (_: Exception) { Result.retry() } finally { client.cancel() }
    }
    companion object {
        internal fun schedule(context: Context, account: Account, id: String, domain: String) {
            val key = MessageDigest.getInstance("SHA-256").digest((id + ":" + domain).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            val work = OneTimeWorkRequestBuilder<FolsomConsentWorker>().setInputData(workDataOf("account" to account.name, "gaia" to id, "domain" to domain))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("folsom-consent-$key", ExistingWorkPolicy.KEEP, work)
        }
    }
}
