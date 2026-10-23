/*
 * SPDX-FileCopyrightText: 2023 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.folsom

import android.content.Context
import android.content.Intent
import android.accounts.Account
import android.app.KeyguardManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.Parcel
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.PendingIntentCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.folsom.ProductKey
import com.google.android.gms.auth.folsom.RecoveryRequest
import com.google.android.gms.auth.folsom.RecoveryResult
import com.google.android.gms.auth.folsom.SecurityDomainMember
import com.google.android.gms.auth.folsom.SharedKey
import com.google.android.gms.auth.folsom.internal.IBooleanCallback
import com.google.android.gms.auth.folsom.internal.IByteArrayCallback
import com.google.android.gms.auth.folsom.internal.IByteArrayListCallback
import com.google.android.gms.auth.folsom.internal.IKeyRetrievalCallback
import com.google.android.gms.auth.folsom.internal.IKeyRetrievalConsentCallback
import com.google.android.gms.auth.folsom.internal.IKeyRetrievalService
import com.google.android.gms.auth.folsom.internal.IKeyRetrievalSyncStatusCallback
import com.google.android.gms.auth.folsom.internal.IProductKeyCallback
import com.google.android.gms.auth.folsom.internal.IRecoveryResultCallback
import com.google.android.gms.auth.folsom.internal.ISecurityDomainMembersCallback
import com.google.android.gms.auth.folsom.internal.ISharedKeyCallback
import com.google.android.gms.auth.folsom.internal.IStringListCallback
import com.google.android.gms.common.Feature
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.ApiMetadata
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.api.internal.IStatusCallback
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import com.google.android.gms.R
import org.microg.gms.AbstractGmsServiceBroker
import org.microg.gms.auth.folsom.utils.LocalKeyManager
import org.microg.gms.auth.folsom.utils.FolsomDomainPolicy
import org.microg.gms.auth.folsom.utils.SecureBox
import org.microg.gms.auth.folsom.ui.SecurityDomainLifecycleActivity
import org.microg.gms.auth.folsom.ui.FolsomConsentActivity
import org.microg.gms.auth.folsom.utils.FolsomConsentPhase
import org.microg.gms.passwords.DomainLifecycleKind
import org.microg.gms.passwords.RecoveryRestoreClient
import org.microg.gms.passwords.RecoveryRestoreFailure
import org.microg.gms.passwords.RecoverableVaultTransport
import org.microg.gms.common.GmsService
import org.microg.gms.common.PackageUtils
import org.microg.gms.utils.warnOnTransactionIssues
import java.security.MessageDigest
import java.util.UUID
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val TAG = "KeyRetrievalService"

private const val KEY_SECURITY_DOMAIN = "SECURITY_DOMAIN"
private const val KEY_SESSION_ID = "SESSION_ID"
private const val KEY_OFFER_RESET = "OFFER_RESET"
private const val LSKF_CHANNEL = "folsom_screen_lock_recovery"

internal val FOLSOM_SUPPORTED_FEATURES = arrayOf(
    Feature("key_retrieval", 2L),
    Feature("get_domain_state", 1),
    Feature("reset_security_domain", 2L),
    Feature("lskf_consent", 1L),
    Feature("start_recovery", 3L),
    Feature("generate_open_vault_request", 1L),
    Feature("list_recovered_security_domains", 1L),
)

class KeyRetrievalService : LifecycleService() {
    // BaseService logs the complete GetServiceRequest, which may contain an account.
    // Retain its broker contract without putting recovery request metadata in logs.
    private val broker = object : AbstractGmsServiceBroker(EnumSet.of(GmsService.FOLSOM)) {
        override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
            this@KeyRetrievalService.handleServiceRequest(callback, request, service)
        }
    }
    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return broker.asBinder()
    }

    fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
        val packageName = PackageUtils.getAndCheckCallingPackage(this, request.packageName)
        val bundle = request.extras
        val securityDomain = bundle?.getString(KEY_SECURITY_DOMAIN)
        if (securityDomain.isNullOrEmpty() || !FolsomDomainPolicy.valid(securityDomain)) {
            Log.w(TAG, "Security domain is not set")
            callback.onPostInitComplete(ERROR_CODE_SECURITY_DOMAIN_NOT_SET, null, null)
            return
        }
        val admission = try {
            val caller = packageName ?: throw SecurityException()
            val identity = FolsomAndroidAdmission.identity(this, caller) ?: throw SecurityException()
            FolsomCallerAdmission(identity, securityDomain, Process.myUid()).also {
                it.checkTransaction(Binder.getCallingUid(), FolsomAndroidAdmission.identity(this, caller))
            }
        } catch (_: Exception) {
            callback.onPostInitComplete(ConnectionResult.API_DISABLED_FOR_CONNECTION, null, null)
            return
        }
        val selectedAccount = try { request.account?.let {
            if (it.type != "com.google") throw SecurityException()
            FolsomAndroidAdmission.account(this, it.name)
        } } catch (_: Exception) {
            callback.onPostInitComplete(ConnectionResult.API_DISABLED_FOR_CONNECTION, null, null)
            return
        }
        val sessionId = bundle.getString(KEY_SESSION_ID, UUID.randomUUID().toString())
        val offerReset = bundle.getBoolean(KEY_OFFER_RESET, false)
        callback.onPostInitCompleteWithConnectionInfo(
            CommonStatusCodes.SUCCESS,
            KeyRetrievalServiceImpl(this, lifecycle, securityDomain, sessionId, offerReset, packageName!!, admission, selectedAccount),
            ConnectionInfo().apply { features = FOLSOM_SUPPORTED_FEATURES }
        )
    }
}

internal class KeyRetrievalServiceImpl(
    private val context: Context,
    override val lifecycle: Lifecycle,
    private val domainId: String,
    private val sessionId: String,
    private val offerReset: Boolean,
    private val callerPackage: String,
    private val admission: FolsomCallerAdmission,
    private val selectedAccount: FolsomAccountFence?
) : IKeyRetrievalService.Stub(), LifecycleOwner {
    private fun checkCurrent(fence: FolsomAccountFence? = selectedAccount) {
        admission.checkCurrent(FolsomAndroidAdmission.identity(context, callerPackage))
        fence?.check()
    }
    private fun accountFence(name: String): FolsomAccountFence {
        admission.checkTransaction(Binder.getCallingUid(), FolsomAndroidAdmission.identity(context, callerPackage))
        if (selectedAccount != null && name != selectedAccount.name) throw SecurityException("Recovery account differs from connection")
        return (selectedAccount ?: FolsomAndroidAdmission.account(context, name)).also { checkCurrent(it) }
    }

    override fun setConsent(callback: IKeyRetrievalConsentCallback?, accountName: String?, force: Boolean, metadata: ApiMetadata?) {
        if (callback == null) return
        delegatedRead(accountName, callback.asBinder(), { fence, authorize, cancellation ->
            val client = FolsomConsentClient(context, Account(fence.name, "com.google"), fence.userId, authorize)
            cancellation.set(client::cancel)
            client.set(domainId, force)
            val current = client.status()
            if (current.phase == FolsomConsentPhase.COMMITTED) Status.SUCCESS else consentResolution(fence, current)
        }) { status, result ->
            callback.onResult(if (status.isSuccess) result ?: Status.INTERNAL_ERROR else status, false)
        }
    }

    override fun getConsent(callback: IKeyRetrievalConsentCallback?, accountName: String?, metadata: ApiMetadata?) {
        if (callback == null) return
        delegatedRead(accountName, callback.asBinder(), { fence, authorize, cancellation ->
            val client = FolsomConsentClient(context, Account(fence.name, "com.google"), fence.userId, authorize)
            cancellation.set(client::cancel); client.get(domainId)
        }) { status, actual -> callback.onResult(status, actual ?: false) }
    }

    override fun getSyncStatus(callback: IKeyRetrievalSyncStatusCallback?, accountName: String?, metadata: ApiMetadata?) {
        if (callback == null) return
        delegatedRead(accountName, callback.asBinder(), { fence, authorize, _ ->
            FolsomSyncStatus(context, fence.name, fence.userId, domainId, authorize).read()
        }) { status, actual -> callback.onResult(status, actual ?: 0) }
    }

    private fun consentResolution(fence: FolsomAccountFence, current: FolsomConsentOwnerStatus): Status {
        val id = current.id ?: throw IllegalStateException("Recovery consent unavailable")
        val domain = current.domain ?: throw IllegalStateException("Recovery consent unavailable")
        val value = current.value ?: throw IllegalStateException("Recovery consent unavailable")
        checkCurrent(fence)
        val identity = FolsomAndroidAdmission.identity(context, callerPackage) ?: throw SecurityException()
        val intent = Intent(context, FolsomConsentActivity::class.java).apply {
            data = android.net.Uri.Builder().scheme("microg-folsom").authority("consent").appendPath(UUID.randomUUID().toString()).build()
            putExtra("account", fence.name); putExtra("google_user_id", fence.userId); putExtra("domain", domain)
            putExtra("consent_expected_id", id); putExtra("consent_expected_value", value)
            putExtra("lifecycle_caller", callerPackage); putExtra("lifecycle_uid", identity.uid); putExtra("lifecycle_signer", identity.certificate)
            putExtra("lifecycle_expires", SystemClock.elapsedRealtime() + 120_000L)
            putExtra("lifecycle_boot", Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -2))
        }
        val pending = PendingIntentCompat.getActivity(context, 0, intent, PendingIntent.FLAG_ONE_SHOT, false) ?: throw SecurityException()
        checkCurrent(fence)
        return Status(38500, "Review recovery on this phone", pending)
    }

    override fun markLocalKeysAsStale(callback: IKeyRetrievalCallback?, accountName: String?, metadata: ApiMetadata?) {
        if (accountName.isNullOrEmpty()) {
            callback?.onResult(Status.INTERNAL_ERROR)
            return
        }
        val fence = accountFence(accountName)
        lifecycleScope.launchWhenStarted {
            try {
                checkCurrent(fence)
                val localKeyManager = LocalKeyManager.getInstance(context)
                localKeyManager.markKeysStale(accountName, domainId, expectedUserId = fence.userId)
                checkCurrent(fence)
                callback?.onResult(Status.SUCCESS)
            } catch (e: Exception) {
                Log.e(TAG, "markLocalKeysAsStale failed")
                callback?.onResult(Status(CommonStatusCodes.INTERNAL_ERROR))
            }
        }
    }

    override fun getKeyMaterial(callback: ISharedKeyCallback?, accountName: String?, metadata: ApiMetadata?) {
        if (accountName.isNullOrEmpty()) {
            Log.w(TAG, "getKeyMaterial: accountName is null or empty")
            callback?.onResult(Status(CommonStatusCodes.DEVELOPER_ERROR), emptyArray<SharedKey>())
            return
        }
        val fence = accountFence(accountName)
        fun errorResult(domainStatus: DomainStatus) {
            checkCurrent(fence)
            val errorStatus = context.buildKeyRetrievalStatus(accountName, domainId, 1, sessionId, offerReset) {
                val statusMessage = when (domainStatus) {
                    DomainStatus.NO_KEYS -> "Empty domain"
                    DomainStatus.NOT_RECOVERABLE -> "Domain is not retrievable"
                    else -> "No shared keys available"
                }
                Status(CommonStatusCodes.SIGN_IN_REQUIRED, statusMessage, it)
            }
            checkCurrent(fence)
            callback?.onResult(errorStatus, emptyArray<SharedKey>())
        }
        lifecycleScope.launchWhenStarted {
            try {
                checkCurrent(fence)
                val localKeyManager = LocalKeyManager.getInstance(context)
                val domainStatus = localKeyManager.getDomainStatus(accountName, domainId)

                val localKeys = localKeyManager.getLocalKeysOrSync(context, accountName, domainId, sessionId, expectedUserId = fence.userId)
                checkCurrent(fence)
                if (localKeys.isEmpty()) {
                    Log.w(TAG, "getKeyMaterial: no keys available")
                    return@launchWhenStarted errorResult(domainStatus)
                }

                val validKeys = localKeys.filter { it.keyVersion != 0 }
                if (validKeys.isEmpty() && localKeys.any { it.keyVersion == 0 }) {
                    Log.w(TAG, "getKeyMaterial: only invalid keys (version=0) available")
                    return@launchWhenStarted errorResult(domainStatus)
                }

                val sharedKeys = validKeys.mapNotNull { key ->
                    val keyMaterial = key.keyMaterial?.toByteArray()
                    if (keyMaterial != null && keyMaterial.isNotEmpty()) {
                        SharedKey(key.keyVersion ?: 0, keyMaterial)
                    } else {
                        null
                    }
                }.toTypedArray()

                if (sharedKeys.isEmpty()) {
                    Log.w(TAG, "getKeyMaterial: no valid key material")
                    return@launchWhenStarted errorResult(domainStatus)
                }

                try {
                    checkCurrent(fence)
                    callback?.onResult(Status.SUCCESS, sharedKeys)
                } finally { if (!admission.sameUid) sharedKeys.forEach { it.keyMaterial?.fill(0) } }
            } catch (e: Exception) {
                Log.e(TAG, "getKeyMaterial failed")
                callback?.onResult(Status(ERROR_CODE_NO_KEYS, "No shared keys available"), emptyArray<SharedKey>())
            }
        }
    }

    override fun setKeyMaterial(callback: IKeyRetrievalCallback?, accountName: String?, keys: Array<out SharedKey?>?, metadata: ApiMetadata?) {
        callback?.onResult(Status.INTERNAL_ERROR)
    }

    override fun getRecoveredSecurityDomains(callback: IStringListCallback?, accountName: String?, metadata: ApiMetadata?) {
        if (callback == null) return
        delegatedRead(accountName, callback.asBinder(), { fence, authorize, _ ->
            authorize()
            LocalKeyManager.getInstance(context).recoveredDomainIds(fence.name, fence.userId, domainId).toTypedArray()
                .also { authorize() }
        }) { status, domains -> callback.onResult(status, domains ?: emptyArray()) }
    }

    override fun startRecoveryOperation(callback: IRecoveryResultCallback?, metadata: ApiMetadata?, request: RecoveryRequest?) {
        if (callback == null) return
        sdkRecovery(callback.asBinder(), request, false) { status, result, _ -> callback.onResult(status, result) }
    }

    override fun listVaultsOperation(callback: IByteArrayListCallback?, accountName: String?, metadata: ApiMetadata?) {
        if (callback == null) return
        delegatedRead(accountName, callback.asBinder(), { fence, authorize, cancel ->
            if (Build.VERSION.SDK_INT < 28) throw IllegalStateException("Vault service unavailable")
            val transport = RecoverableVaultTransport(context, Account(fence.name, "com.google"), fence.userId, authorize)
            cancel.set(transport::cancel); authorize()
            // anqu passes hgtt.a(6) ==4. Return actual serialized backups, never decrypted app keys.
            transport.list(sessionId, 4).also { authorize() }
        }) { status, vaults ->
            try { callback.onResult(status, if (admission.sameUid) vaults?.map { it.copyOf() } ?: emptyList() else vaults ?: emptyList()) }
            finally { vaults?.forEach { it.fill(0) } }
        }
    }

    override fun generateOpenVaultRequestOperation(callback: IByteArrayCallback?, request: RecoveryRequest?, metadata: ApiMetadata?) {
        if (callback == null) return
        sdkRecovery(callback.asBinder(), request, true) { status, _, bytes -> callback.onResult(status, bytes, ApiMetadata.DEFAULT) }
    }

    /** Read-only SDK operations retain the same package/signer/Gaia boundary across asynchronous I/O. */
    private fun <T> delegatedRead(accountName: String?, callbackBinder: IBinder,
        action: (FolsomAccountFence, () -> Unit, AtomicReference<(() -> Unit)?>) -> T, respond: (Status, T?) -> Unit) {
        if (accountName.isNullOrBlank()) { respond(Status(CommonStatusCodes.DEVELOPER_ERROR), null); return }
        val fence = try { accountFence(accountName) } catch (_: Exception) { respond(Status.INTERNAL_ERROR, null); return }
        val stopped = AtomicBoolean(false); val completed = AtomicBoolean(false)
        val cancellation = AtomicReference<(() -> Unit)?>(null); val job = AtomicReference<Job?>(null)
        val deadline = SystemClock.elapsedRealtime() + 120_000L; val handler = Handler(Looper.getMainLooper())
        fun cancel() { stopped.set(true); cancellation.getAndSet(null)?.invoke(); job.get()?.cancel() }
        fun deliver(status: Status, value: T? = null) {
            checkCurrent(fence)
            if (completed.compareAndSet(false, true)) respond(status, value)
        }
        val death = IBinder.DeathRecipient { cancel() }
        try { callbackBinder.linkToDeath(death, 0) } catch (_: Exception) { return }
        val timeout = Runnable { try { deliver(Status(CommonStatusCodes.TIMEOUT)) } catch (_: Exception) { }
            finally { cancel() } }
        handler.postDelayed(timeout, 120_000L)
        val running = lifecycleScope.launch(Dispatchers.IO) {
            val operation = currentCoroutineContext()[Job] ?: return@launch
            fun authorize() {
                operation.ensureActive()
                if (stopped.get() || SystemClock.elapsedRealtime() >= deadline) throw SecurityException("Recovery call expired")
                checkCurrent(fence)
            }
            try { authorize(); val value = action(fence, ::authorize, cancellation); authorize(); deliver(Status.SUCCESS, value) }
            catch (failure: Exception) { if (!stopped.get()) try {
                val status = if (failure is FolsomConsentRecoveryRequired) context.buildKeyRetrievalStatus(fence.name, failure.domain, 1, sessionId, offerReset) {
                    Status(38500, "No shared keys available", it)
                } else Status.INTERNAL_ERROR
                deliver(status)
            } catch (_: Exception) { } }
        }
        job.set(running); if (stopped.get()) running.cancel()
        running.invokeOnCompletion {
            stopped.set(true); cancellation.getAndSet(null)?.invoke(); handler.removeCallbacks(timeout)
            try { callbackBinder.unlinkToDeath(death, 0) } catch (_: Exception) { }
        }
    }

    /** The source SDK call delegates its admitted caller's owner authority and completes once. */
    private fun sdkRecovery(callbackBinder: IBinder, request: RecoveryRequest?, generate: Boolean,
        respond: (Status, RecoveryResult, ByteArray) -> Unit) {
        val input = try {
            if (domainId !in setOf("chromesync", "hw_protected")) throw IllegalArgumentException()
            FolsomRecoveryInput(request)
        } catch (_: Exception) {
            respond(Status(CommonStatusCodes.DEVELOPER_ERROR), RecoveryResult(13, 0), byteArrayOf()); return
        }
        val fence = try { accountFence(input.accountName) } catch (_: Exception) {
            input.close(); respond(Status.INTERNAL_ERROR, RecoveryResult(), byteArrayOf()); return
        }
        if (Build.VERSION.SDK_INT < 28) {
            input.close(); checkCurrent(fence)
            respond(if (generate) Status.INTERNAL_ERROR else Status.SUCCESS, RecoveryResult(16, 0), byteArrayOf()); return
        }
        val stopped = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val client = AtomicReference<RecoveryRestoreClient?>(null)
        val job = AtomicReference<Job?>(null)
        val deadline = SystemClock.elapsedRealtime() + 120_000L
        val handler = Handler(Looper.getMainLooper())
        fun cancel() {
            stopped.set(true); input.close(); client.get()?.cancel(); job.get()?.cancel()
        }
        fun deliver(status: Status, result: RecoveryResult = RecoveryResult(), bytes: ByteArray = byteArrayOf()) {
            // Mark delivery before invoking Binder: a callback transport failure never causes another completion.
            checkCurrent(fence)
            if (completed.compareAndSet(false, true)) respond(status, result, if (admission.sameUid) bytes.copyOf() else bytes)
        }
        val death = IBinder.DeathRecipient { cancel() }
        try { callbackBinder.linkToDeath(death, 0) } catch (_: Exception) { input.close(); return }
        val timeout = Runnable {
            try { deliver(Status(CommonStatusCodes.TIMEOUT)) } catch (_: Exception) { /* No result to a revoked caller. */ }
            finally { cancel() }
        }
        handler.postDelayed(timeout, 120_000L)
        val running = lifecycleScope.launch(Dispatchers.IO) {
            val operation = currentCoroutineContext()[Job] ?: return@launch
            fun authorize() {
                operation.ensureActive()
                if (stopped.get() || SystemClock.elapsedRealtime() >= deadline) throw SecurityException("Recovery call expired")
                checkCurrent(fence)
                val lock = context.getSystemService(KeyguardManager::class.java)
                if (!lock.isDeviceSecure || lock.isDeviceLocked || lock.isKeyguardLocked) throw SecurityException("Recovery device locked")
            }
            var generated: ByteArray? = null
            try {
                authorize()
                val native = RecoveryRestoreClient(context, Account(fence.name, "com.google"), fence.userId, domainId,
                    authorize = ::authorize, sdkMode = true)
                client.set(native); authorize()
                if (generate) {
                    // anqi/source4 creates a validation claim and closes the OS session. No OpenVault or import.
                    generated = native.generateSdkHashed(input.scopeDigest, input.ownedHash(), input.challenge)
                    authorize()
                    val local = LocalKeyManager.getInstance(context)
                    if (local.hasPendingDomainReset(fence.name, "chromesync") || local.hasPendingDomainReset(fence.name, "hw_protected"))
                        throw SecurityException("Recovery reset pending")
                    authorize(); deliver(Status.SUCCESS, bytes = generated ?: throw IllegalStateException())
                } else {
                    // anrb/source3 returns success only after every recovered supported domain was really imported.
                    val outcome = native.restoreSdkHashed(input.scopeDigest, input.ownedHash(), input.challenge)
                    val result = outcome.sdkResultCode ?: throw IllegalStateException("Recovery not confirmed")
                    if (result == 1 && !outcome.complete) throw IllegalStateException("Recovery not confirmed")
                    authorize()
                    val local = LocalKeyManager.getInstance(context)
                    if (local.hasPendingDomainReset(fence.name, "chromesync") || local.hasPendingDomainReset(fence.name, "hw_protected"))
                        throw SecurityException("Recovery reset pending")
                    authorize(); deliver(Status.SUCCESS, RecoveryResult(result, outcome.retryAfter))
                }
            } catch (failure: Exception) {
                if (!stopped.get()) try {
                    if (!generate && failure is RecoveryRestoreFailure && failure.sdkResult == 9) deliver(Status.SUCCESS, RecoveryResult(9, 0))
                    else deliver(Status.INTERNAL_ERROR)
                } catch (_: Exception) { /* A revoked/dead caller receives no result. */ }
            } finally { generated?.fill(0); client.getAndSet(null)?.cancel() }
        }
        job.set(running)
        if (stopped.get()) running.cancel()
        running.invokeOnCompletion {
            stopped.set(true); input.close(); client.getAndSet(null)?.cancel()
            handler.removeCallbacks(timeout)
            try { callbackBinder.unlinkToDeath(death, 0) } catch (_: Exception) { /* Already dead. */ }
        }
    }

    override fun joinSecurityDomain(callback: IStatusCallback?, accountName: String?, bytes: ByteArray?, type: Int, metadata: ApiMetadata?) {
        if (accountName.isNullOrBlank() || bytes == null || type !in 0..6 || domainId !in setOf("chromesync", "hw_protected")) {
            callback?.onResult(Status(CommonStatusCodes.DEVELOPER_ERROR)); return
        }
        val fence = accountFence(accountName)
        try {
            // The Binder supplies only the recipient's public key. Sharing the account's
            // recovery material requires a fresh protected owner decision for this exact key.
            SecureBox.deserializePublicKey(bytes)
            callback?.onResult(lifecycleResolution(fence, DomainLifecycleKind.ADD_MEMBER, bytes, type))
        } catch (_: Exception) { callback?.onResult(Status.INTERNAL_ERROR) }
    }

    override fun resetSecurityDomain(callback: IStatusCallback?, accountName: String?, metadata: ApiMetadata?) {
        if (accountName.isNullOrBlank() || domainId !in setOf("chromesync", "hw_protected")) {
            callback?.onResult(Status(CommonStatusCodes.DEVELOPER_ERROR)); return
        }
        val fence = accountFence(accountName)
        try { callback?.onResult(lifecycleResolution(fence, DomainLifecycleKind.RESET)) }
        catch (_: Exception) { callback?.onResult(Status.INTERNAL_ERROR) }
    }

    private fun lifecycleResolution(fence: FolsomAccountFence, operation: DomainLifecycleKind,
        publicKey: ByteArray? = null, joinOptions: Int = 0): Status {
        checkCurrent(fence)
        val identity = FolsomAndroidAdmission.identity(context, callerPackage) ?: throw SecurityException()
        val intent = Intent(context, SecurityDomainLifecycleActivity::class.java).apply {
            data = android.net.Uri.Builder().scheme("microg-folsom").authority("lifecycle")
                .appendPath(UUID.randomUUID().toString()).build()
            putExtra("account", fence.name); putExtra("google_user_id", fence.userId)
            putExtra("security_domain", domainId); putExtra("lifecycle_action", operation.name)
            putExtra("external_public", publicKey?.copyOf()); putExtra("join_options", joinOptions)
            putExtra("lifecycle_caller", callerPackage); putExtra("lifecycle_uid", identity.uid)
            putExtra("lifecycle_signer", identity.certificate)
            putExtra("lifecycle_expires", SystemClock.elapsedRealtime() + 120_000)
            putExtra("lifecycle_boot", Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -2))
        }
        val resolution = PendingIntentCompat.getActivity(context, 0, intent, PendingIntent.FLAG_ONE_SHOT, false)
            ?: throw SecurityException("Recovery approval unavailable")
        checkCurrent(fence)
        return Status(CommonStatusCodes.SIGN_IN_REQUIRED, "Confirm this recovery operation on this phone", resolution)
    }

    override fun listSecurityDomainMembers(callback: ISecurityDomainMembersCallback?, accountName: String?, metadata: ApiMetadata?) {
        if (accountName.isNullOrEmpty()) {
            Log.w(TAG, "listSecurityDomainMembers: accountName is null or empty")
            callback?.onResult(Status.INTERNAL_ERROR, emptyArray<SecurityDomainMember>(), metadata ?: ApiMetadata.DEFAULT)
            return
        }
        val fence = accountFence(accountName)
        lifecycleScope.launchWhenStarted {
            try {
                checkCurrent(fence)
                val response = runCatching {
                    loadSecurityDomainMembers(context, accountName, sessionId, domainId)
                }.onFailure {
                    Log.w(TAG, "listSecurityDomainMembers: load error")
                }.getOrNull()

                checkCurrent(fence)
                if (response == null) {
                    // Transport failure is not authenticated evidence that recovery keys disappeared.
                    callback?.onResult(Status.INTERNAL_ERROR, emptyArray<SecurityDomainMember>(), ApiMetadata.DEFAULT)
                    return@launchWhenStarted
                }

                val memberList = response.members.map { member ->
                    SecurityDomainMember(
                        member.memberType ?: 0, member.memberMetadata?.encode() ?: byteArrayOf()
                    )
                }.toTypedArray()

                checkCurrent(fence)
                callback?.onResult(Status.SUCCESS, memberList, ApiMetadata.DEFAULT)
            } catch (e: Exception) {
                Log.e(TAG, "listSecurityDomainMembers failed")
                callback?.onResult(Status.INTERNAL_ERROR, emptyArray<SecurityDomainMember>(), ApiMetadata.DEFAULT)
            }
        }
    }

    override fun getDomainState(callback: IByteArrayCallback?, accountName: String?, metadata: ApiMetadata?) {
        if (accountName.isNullOrEmpty()) {
            Log.w(TAG, "getDomainState: accountName is null or empty")
            callback?.onResult(Status.INTERNAL_ERROR, byteArrayOf(), ApiMetadata.DEFAULT)
            return
        }
        val fence = accountFence(accountName)
        lifecycleScope.launchWhenStarted {
            try {
                checkCurrent(fence)
                val localKeyManager = LocalKeyManager.getInstance(context)
                try {
                    localKeyManager.updateLastFetchTimestamp(accountName, domainId, 0L)
                } catch (e: Exception) {
                    Log.w(TAG, "getDomainState: failed to reset timestamp, continuing")
                }
                var serverState: GetSecurityDomainResponse? = null
                var membersResponse: ListSecurityDomainMembersResponse? = null
                try {
                    serverState = getSecurityDomain(context, accountName, sessionId, domainId)
                    membersResponse = loadSecurityDomainMembers(context, accountName, sessionId, domainId)
                } catch (e: Exception) {
                    Log.e(TAG, "getDomainState: failed to sync domain state")
                }
                checkCurrent(fence)
                val computedState = localKeyManager.computeDomainStatus(accountName, domainId, serverState, membersResponse).also {
                    localKeyManager.setDomainStatus(accountName, domainId, it)
                    localKeyManager.updateLastFetchTimestamp(accountName, domainId, System.currentTimeMillis())
                }
                val responseBytes = serializeDomainStateResponse(computedState.code)
                checkCurrent(fence)
                callback?.onResult(Status.SUCCESS, responseBytes, ApiMetadata.DEFAULT)
            } catch (e: Exception) {
                Log.e(TAG, "getDomainState failed")
                val errorResponse = serializeDomainStateResponse(DomainStatus.UNKNOWN_ERROR.code)
                callback?.onResult(Status.INTERNAL_ERROR, errorResponse, ApiMetadata.DEFAULT)
            }
        }
    }

    override fun startUxFlow(callback: IKeyRetrievalCallback?, accountName: String?, type: Int, metadata: ApiMetadata?) {
        if (accountName.isNullOrEmpty()) {
            callback?.onResult(Status(CommonStatusCodes.DEVELOPER_ERROR))
            return
        }
        val fence = accountFence(accountName)
        // Pinned anrc / GenericChimeraActivity.k: operation5 is the LSKF consent UX.
        if (type == 5) {
            if (callback == null) return
            delegatedRead(accountName, callback.asBinder(), { current, authorize, cancellation ->
                val client = FolsomConsentClient(context, Account(current.name, "com.google"), current.userId, authorize)
                cancellation.set(client::cancel)
                val resolution = consentResolution(current, client.prepare(domainId, true)).resolution ?: throw SecurityException()
                Status(CommonStatusCodes.SUCCESS, "UX flow PendingIntent retrieved.", resolution)
            }) { status, result -> callback.onResult(if (status.isSuccess) result ?: Status.INTERNAL_ERROR else status) }
            return
        }
        val status = context.buildKeyRetrievalStatus(accountName, domainId, type, sessionId, offerReset) {
            Status(CommonStatusCodes.SUCCESS, "UX flow PendingIntent retrieved.", it)
        }
        checkCurrent(fence)
        callback?.onResult(status)
    }

    override fun promptForLskfConsent(callback: IKeyRetrievalCallback?, accountName: String?, metadata: ApiMetadata?) {
        if (callback == null) return
        delegatedRead(accountName, callback.asBinder(), { fence, authorize, cancellation ->
            val client = FolsomConsentClient(context, Account(fence.name, "com.google"), fence.userId, authorize)
            cancellation.set(client::cancel)
            // anqw checks stored coupled consent and locally readable positive-epoch keys first.
            // No owner notification or backup setup is needed for either early-success case.
            if (!client.needsConsentPrompt(domainId) ||
                !LocalKeyManager.getInstance(context).consentNotificationEligible(fence.name, fence.userId, domainId)) Status.SUCCESS
            else postConsentNotification(fence, consentResolution(fence, client.prepare(domainId, true)), authorize)
        }) { status, result -> callback.onResult(if (status.isSuccess) result ?: Status.INTERNAL_ERROR else status) }
    }

    private fun postConsentNotification(fence: FolsomAccountFence, fallback: Status, authorize: () -> Unit): Status {
        authorize(); checkCurrent(fence)
        val resolution = fallback.resolution ?: throw SecurityException("Recovery approval unavailable")
        val notifications = NotificationManagerCompat.from(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val permission = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(LSKF_CHANNEL,
            context.getString(R.string.passwords_recoverable_vault_title), NotificationManager.IMPORTANCE_DEFAULT))
        val channelEnabled = Build.VERSION.SDK_INT < 26 || manager.getNotificationChannel(LSKF_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        if (!permission || !notifications.areNotificationsEnabled() || !channelEnabled) {
            checkCurrent(fence); return fallback
        }
        val notification = NotificationCompat.Builder(context, LSKF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(context.getString(R.string.passwords_recoverable_vault_title))
            .setContentText(context.getString(R.string.passwords_recoverable_vault_confirm))
            .setContentIntent(resolution).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setLocalOnly(true).setTimeoutAfter(120_000).build()
        val tag = "folsom-lskf-" + MessageDigest.getInstance("SHA-256")
            .digest((fence.userId + ":" + domainId).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        authorize(); checkCurrent(fence)
        try { notifications.notify(tag, 1, notification) }
        catch (_: SecurityException) { authorize(); checkCurrent(fence); return fallback }
        LocalKeyManager.getInstance(context).recordConsentNotificationPosted(fence.name, fence.userId, domainId, authorize = authorize)
        authorize(); checkCurrent(fence)
        // SUCCESS means the owner notification was posted; the PREPARED attempt has not granted consent.
        return Status.SUCCESS
    }

    override fun canSilentlyAddGaiaPassword(callback: IBooleanCallback?, accountName: String?, metadata: ApiMetadata?) {
        callback?.onResult(Status.INTERNAL_ERROR, false)
    }

    override fun addGaiaPasswordMember(callback: IStatusCallback?, accountName: String?, metadata: ApiMetadata?) {
        callback?.onResult(Status.INTERNAL_ERROR)
    }

    override fun getProductDetails(callback: IByteArrayCallback?, accountName: String?, metadata: ApiMetadata?) {
        callback?.onResult(Status.INTERNAL_ERROR, byteArrayOf(), ApiMetadata.DEFAULT)
    }

    override fun getProductKeysOperation(callback: IProductKeyCallback?, accountName: String?, accountName2: String?, metadata: ApiMetadata?) {
        callback?.onResult(Status.INTERNAL_ERROR, emptyArray<ProductKey>(), metadata)
    }

    override fun createPrfMemberOperation(callback: IStatusCallback?, accountName: String?, bytes: ByteArray?, bytes2: ByteArray?, metadata: ApiMetadata?) {
        callback?.onResult(Status.INTERNAL_ERROR)
    }

    override fun addRecoveryContactToDependentKeychainOperation(callback: IStatusCallback?, accountName: String?, accountName2: String?, metadata: ApiMetadata?) {
        callback?.onResult(Status.INTERNAL_ERROR)
    }

    override fun createRetrievalPacketOperation(callback: IStatusCallback?, accountName: String?, accountName2: String?, bytes: ByteArray?, metadata: ApiMetadata?) {
        callback?.onResult(Status.INTERNAL_ERROR)
    }

    override fun setClaimantKeyOperation(callback: IStatusCallback?, accountName: String?, bytes: ByteArray?, bytes2: ByteArray?, metadata: ApiMetadata?) {
        callback?.onResult(Status.INTERNAL_ERROR)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        admission.checkTransaction(Binder.getCallingUid(), FolsomAndroidAdmission.identity(context, callerPackage))
        selectedAccount?.check()
        return warnOnTransactionIssues(code, reply, flags, TAG) { super.onTransact(code, data, reply, flags) }
    }
}
