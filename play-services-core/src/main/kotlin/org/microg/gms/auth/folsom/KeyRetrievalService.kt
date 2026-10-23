/*
 * SPDX-FileCopyrightText: 2023 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.folsom

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.Parcel
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
import org.microg.gms.AbstractGmsServiceBroker
import org.microg.gms.auth.folsom.utils.LocalKeyManager
import org.microg.gms.auth.folsom.utils.FolsomDomainPolicy
import org.microg.gms.common.GmsService
import org.microg.gms.common.PackageUtils
import org.microg.gms.utils.warnOnTransactionIssues
import java.util.UUID
import java.util.EnumSet

private const val TAG = "KeyRetrievalService"

private const val KEY_SECURITY_DOMAIN = "SECURITY_DOMAIN"
private const val KEY_SESSION_ID = "SESSION_ID"
private const val KEY_OFFER_RESET = "OFFER_RESET"

internal val FOLSOM_SUPPORTED_FEATURES = arrayOf(
    Feature("key_retrieval", 2L),
    Feature("get_domain_state", 1),
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
        callback?.onResult(Status.INTERNAL_ERROR, false)
    }

    override fun getConsent(callback: IKeyRetrievalConsentCallback?, accountName: String?, metadata: ApiMetadata?) {
        callback?.onResult(Status.INTERNAL_ERROR, false)
    }

    override fun getSyncStatus(callback: IKeyRetrievalSyncStatusCallback?, accountName: String?, metadata: ApiMetadata?) {
        callback?.onResult(Status.INTERNAL_ERROR, false)
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
        callback?.onResult(Status.INTERNAL_ERROR, emptyArray<String>())
    }

    override fun startRecoveryOperation(callback: IRecoveryResultCallback?, metadata: ApiMetadata?, request: RecoveryRequest?) {
        callback?.onResult(Status.INTERNAL_ERROR, RecoveryResult())
    }

    override fun listVaultsOperation(callback: IByteArrayListCallback?, accountName: String?, metadata: ApiMetadata?) {
        callback?.onResult(Status.INTERNAL_ERROR, emptyList<ByteArray>())
    }

    override fun generateOpenVaultRequestOperation(callback: IByteArrayCallback?, request: RecoveryRequest?, metadata: ApiMetadata?) {
        callback?.onResult(Status.INTERNAL_ERROR, byteArrayOf(), ApiMetadata.DEFAULT)
    }

    override fun joinSecurityDomain(callback: IStatusCallback?, accountName: String?, bytes: ByteArray?, type: Int, metadata: ApiMetadata?) {
        callback?.onResult(Status.INTERNAL_ERROR)
    }

    override fun resetSecurityDomain(callback: IStatusCallback?, accountName: String?, metadata: ApiMetadata?) {
        callback?.onResult(Status.INTERNAL_ERROR)
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
        val status = context.buildKeyRetrievalStatus(accountName, domainId, type, sessionId, offerReset) {
            Status(CommonStatusCodes.SUCCESS, "UX flow PendingIntent retrieved.", it)
        }
        checkCurrent(fence)
        callback?.onResult(status)
    }

    override fun promptForLskfConsent(callback: IKeyRetrievalCallback?, accountName: String?, metadata: ApiMetadata?) {
        callback?.onResult(Status.INTERNAL_ERROR)
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
