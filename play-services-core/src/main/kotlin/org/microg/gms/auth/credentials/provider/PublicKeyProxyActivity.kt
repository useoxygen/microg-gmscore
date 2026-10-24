/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.credentials.provider

import android.content.Intent
import android.accounts.Account
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.MotionEvent
import android.app.KeyguardManager
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.provider.ProviderCreateCredentialRequest
import androidx.credentials.provider.ProviderGetCredentialRequest
import com.google.android.gms.fido.Fido.FIDO2_KEY_CREDENTIAL_EXTRA
import com.google.android.gms.fido.fido2.api.common.*
import org.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.microg.gms.auth.folsom.ui.GenericActivity
import org.microg.gms.passwords.rejectsPasswordTouch
import org.microg.gms.auth.credentials.buildFidoAuthenticateIntent
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_CREDENTIAL_ID
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.SOURCE_APP
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.SOURCE_BROWSER
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.TYPE_REGISTER
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.TYPE_SIGN

private const val TAG = "PublicKeyProxyActivity"
private const val REQUEST_CODE_FIDO = 1001

fun String?.isHttpsUrl(): Boolean = this?.startsWith("https://") == true

@RequiresApi(34)
class PublicKeyProxyActivity : CredentialProviderActivity() {
    private var passkeyRecovery: CompletableDeferred<Boolean>? = null
    private var passkeyOwner: CompletableDeferred<Boolean>? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        super.onCreate(savedInstanceState)
    }

    override fun onProviderGetCredentialRequest(request: ProviderGetCredentialRequest) {
        if (SyncedPasskeyProvider.get(this, request)) return
        val option = request.credentialOptions.firstOrNull() as? GetPublicKeyCredentialOption ?: throw IllegalArgumentException()

        val isBrowserRequest = request.callingAppInfo.origin.isHttpsUrl()

        val options = JSONObject(option.requestJson).parsePublicKeyCredentialRequestOptions()
        val credentialIdString = intent.getStringExtra(KEY_CREDENTIAL_ID)

        val (optionsBytes, source) = buildRequestOptions(options, isBrowserRequest, request.callingAppInfo.origin, option.clientDataHash)
        val fidoIntent = buildFidoAuthenticateIntent(source, optionsBytes, request.callingAppInfo.packageName, TYPE_SIGN, credentialIdString)
        startActivityForResult(fidoIntent, REQUEST_CODE_FIDO)
    }

    fun buildRequestOptions(
        baseOptions: PublicKeyCredentialRequestOptions, isBrowserRequest: Boolean, origin: String?, clientDataHash: ByteArray?
    ): Pair<ByteArray, String> = if (isBrowserRequest && origin != null) {
        BrowserPublicKeyCredentialRequestOptions.Builder().setPublicKeyCredentialRequestOptions(baseOptions).setOrigin(origin.toUri()).apply { clientDataHash?.let(::setClientDataHash) }.build()
            .serializeToBytes() to SOURCE_BROWSER
    } else {
        baseOptions.serializeToBytes() to SOURCE_APP
    }

    override fun onProviderCreateCredentialRequest(request: ProviderCreateCredentialRequest) {
        if (SyncedPasskeyProvider.create(this, request)) return
        val callingPackage = request.callingAppInfo.packageName
        val origin = request.callingAppInfo.origin
        val isBrowserRequest = origin.isHttpsUrl()
        val publicKeyRequest = request.callingRequest as CreatePublicKeyCredentialRequest

        Log.d(TAG, "Creating passkey for: $callingPackage, browser=$isBrowserRequest")

        val options = JSONObject(publicKeyRequest.requestJson).parsePublicKeyCredentialCreationOptions()


        val (optionsBytes, source) = buildCreationOptions(options, isBrowserRequest, origin, publicKeyRequest.clientDataHash)
        val fidoIntent = buildFidoAuthenticateIntent(source, optionsBytes, callingPackage, TYPE_REGISTER)

        startActivityForResult(fidoIntent, REQUEST_CODE_FIDO)
        Log.d(TAG, "Launched FIDO authenticator by PasskeyCreate")
    }

    fun buildCreationOptions(
        baseOptions: PublicKeyCredentialCreationOptions, isBrowserRequest: Boolean, origin: String?, clientDataHash: ByteArray?
    ): Pair<ByteArray, String> = if (isBrowserRequest && origin != null) {
        BrowserPublicKeyCredentialCreationOptions.Builder().setPublicKeyCredentialCreationOptions(baseOptions).setOrigin(origin.toUri()).apply { clientDataHash?.let(::setClientDataHash) }.build()
            .serializeToBytes() to SOURCE_BROWSER
    } else {
        baseOptions.serializeToBytes() to SOURCE_APP
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PASSKEY_RECOVERY) {
            passkeyRecovery?.complete(resultCode == RESULT_OK)
            return
        }
        if (requestCode == REQUEST_CODE_PASSKEY_OWNER) { passkeyOwner?.complete(resultCode == RESULT_OK); return }

        if (requestCode != REQUEST_CODE_FIDO) return
        if (resultCode != RESULT_OK || data == null) {
            Log.w(TAG, "FIDO activity canceled or failed")
            return finishWithException("Sign in canceled")
        }

        runCatching {
            val credentialBytes = data.getByteArrayExtra(FIDO2_KEY_CREDENTIAL_EXTRA)
                ?: return@runCatching finishWithException("No credential data in FIDO result")

            val publicKeyCredential = PublicKeyCredential.deserializeFromBytes(credentialBytes)

            (publicKeyCredential.response as? AuthenticatorErrorResponse)?.let { error ->
                return@runCatching finishWithException()
            }

            handleFidoSuccess(publicKeyCredential)
        }.onFailure { e ->
            finishWithException()
        }
    }

    private fun handleFidoSuccess(publicKeyCredential: PublicKeyCredential) = runCatching {
        when (val response = publicKeyCredential.response) {
            is AuthenticatorAttestationResponse -> {
                val responseJson = publicKeyCredential.toJson()
                finishWithSuccess(CreatePublicKeyCredentialResponse(responseJson))
            }
            is AuthenticatorAssertionResponse -> {
                val responseJson = publicKeyCredential.toJson()
                finishWithSuccess(GetCredentialResponse(androidx.credentials.PublicKeyCredential(responseJson)))
            }
            else -> {
                Log.e(TAG, "Unknown response type: ${response.javaClass.simpleName}")
                finishWithException()
            }
        }
    }.onFailure { e ->
        finishWithException()
    }
    internal suspend fun recoverSyncedPasskeys(account: Account, stableId: String): Boolean {
        if (passkeyRecovery != null) return false
        val result = CompletableDeferred<Boolean>()
        passkeyRecovery = result
        return try {
            startActivityForResult(Intent(this, GenericActivity::class.java)
                .putExtra(GenericActivity.EXTRA_ACCOUNT_NAME, account.name)
                .putExtra(GenericActivity.EXTRA_ACCOUNT_ID, stableId)
                .putExtra(GenericActivity.EXTRA_SECURITY_DOMAIN, "hw_protected")
                .putExtra(GenericActivity.EXTRA_SESSION_ID, java.util.UUID.randomUUID().toString())
                .putExtra(GenericActivity.EXTRA_OFFER_RESET, false), REQUEST_CODE_PASSKEY_RECOVERY)
            withTimeoutOrNull(120_000) { result.await() } == true
        } finally { passkeyRecovery = null }
    }
    internal suspend fun confirmSyncedPasskeyOwner(rp: String): Boolean {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (passkeyOwner != null || !keyguard.isDeviceSecure) return false
        val result = CompletableDeferred<Boolean>()
        passkeyOwner = result
        return try {
            @Suppress("DEPRECATION")
            val prompt = keyguard.createConfirmDeviceCredentialIntent(rp, getString(com.google.android.gms.R.string.passwords_unlock_description)) ?: return false
            startActivityForResult(prompt, REQUEST_CODE_PASSKEY_OWNER)
            withTimeoutOrNull(120_000) { result.await() } == true
        } finally { passkeyOwner = null }
    }
    internal suspend fun confirmAbandonSyncedPasskeyAttempt(): Boolean = suspendCancellableCoroutine { continuation ->
        val dialog = AlertDialog.Builder(this)
            .setTitle("Earlier passkey creation is unresolved")
            .setMessage("The previous attempt may have reached Google. Close that local attempt before creating another passkey? A passkey already stored by Google will be kept.")
            .setPositiveButton("Close earlier attempt") { _, _ -> if (continuation.isActive) continuation.resume(true) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> if (continuation.isActive) continuation.resume(false) }
            .setOnCancelListener { if (continuation.isActive) continuation.resume(false) }.create()
        continuation.invokeOnCancellation { dialog.dismiss() }
        dialog.show()
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = if (rejectsPasswordTouch(event.flags)) false else super.dispatchTouchEvent(event)
    override fun onDestroy() { passkeyRecovery?.cancel(); passkeyRecovery = null; passkeyOwner?.cancel(); passkeyOwner = null; super.onDestroy() }
    companion object {
        private const val REQUEST_CODE_PASSKEY_RECOVERY = 1002
        private const val REQUEST_CODE_PASSKEY_OWNER = 1003
    }
}
