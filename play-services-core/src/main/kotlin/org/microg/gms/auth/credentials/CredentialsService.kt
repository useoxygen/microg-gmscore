/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.credentials

import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Parcel
import com.google.android.gms.auth.api.credentials.CredentialRequest
import com.google.android.gms.auth.api.credentials.Credential
import com.google.android.gms.auth.api.credentials.internal.*
import com.google.android.gms.common.Feature
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import org.microg.gms.BaseService
import org.microg.gms.common.GmsService
import org.microg.gms.common.PackageUtils
import org.microg.gms.passwords.LegacyPasswordRequests
import org.microg.gms.utils.warnOnTransactionIssues

private const val TAG = "CredentialService"

// These features belong to the separate identity services which import this inventory.
val FEATURES = arrayOf(
    Feature("auth_api_credentials_begin_sign_in", 9), Feature("auth_api_credentials_sign_out", 2),
    Feature("auth_api_credentials_authorize", 1), Feature("auth_api_credentials_revoke_access", 1),
    Feature("auth_api_credentials_save_password", 4), Feature("auth_api_credentials_get_sign_in_intent", 6),
    Feature("auth_api_credentials_save_account_linking_token", 3), Feature("auth_api_credentials_get_phone_number_hint_intent", 3),
    Feature("auth_api_credentials_verify_with_google", 1)
)

class CredentialsService : BaseService(TAG, GmsService.AUTH_CREDENTIALS) {
    override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
        val packageName = PackageUtils.getAndCheckCallingPackage(this, request.packageName) ?: throw SecurityException()
        callback.onPostInitCompleteWithConnectionInfo(CommonStatusCodes.SUCCESS,
            CredentialsServiceImpl(this, packageName, Binder.getCallingUid()).asBinder(), ConnectionInfo())
    }
}

/** Every access is resolved in protected owner UI; request and credential values are never logged. */
class CredentialsServiceImpl(private val context: Context, private val packageName: String, private val uid: Int) : ICredentialsService.Stub() {
    private fun checkCaller() {
        if (Binder.getCallingUid() != uid || PackageUtils.getAndCheckCallingPackage(context, packageName) != packageName) throw SecurityException()
    }
    private fun resolution(mode: Int, credential: Credential? = null): Status {
        checkCaller()
        if (Build.VERSION.SDK_INT < 28) return Status(CommonStatusCodes.API_NOT_CONNECTED)
        return try { Status(CommonStatusCodes.RESOLUTION_REQUIRED, null,
            LegacyPasswordRequests(context).issue(packageName, uid, mode, credential)) }
        catch (_: Exception) { Status.INTERNAL_ERROR }
    }
    override fun request(callbacks: ICredentialsCallbacks, request: CredentialRequest) {
        checkCaller()
        callbacks.onStatusAndCredential(if (request.isPasswordLoginSupported) resolution(LegacyPasswordRequests.GET)
            else Status(CommonStatusCodes.API_NOT_CONNECTED), null)
    }
    override fun save(callbacks: ICredentialsCallbacks, request: SaveRequest) {
        checkCaller()
        callbacks.onStatus(if (request.credential == null) Status.INTERNAL_ERROR else resolution(LegacyPasswordRequests.SAVE, request.credential))
    }
    override fun delete(callbacks: ICredentialsCallbacks, request: DeleteRequest) {
        checkCaller()
        callbacks.onStatus(if (request.credential == null) Status.INTERNAL_ERROR else resolution(LegacyPasswordRequests.DELETE, request.credential))
    }
    override fun disableAutoSignIn(callbacks: ICredentialsCallbacks) {
        checkCaller()
        // This implementation always requires an explicit owner selection.
        callbacks.onStatus(Status.SUCCESS)
    }
    override fun generatePassword(callbacks: ICredentialsCallbacks, request: GeneratePasswordRequest) {
        checkCaller()
        callbacks.onStatus(Status(CommonStatusCodes.API_NOT_CONNECTED))
    }
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
        warnOnTransactionIssues(code, reply, flags, TAG) { super.onTransact(code, data, reply, flags) }
}
