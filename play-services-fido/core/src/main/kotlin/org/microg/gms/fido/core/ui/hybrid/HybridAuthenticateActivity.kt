/**
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.fido.core.ui.hybrid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.fido.Fido.FIDO2_KEY_CREDENTIAL_EXTRA
import com.google.android.gms.fido.fido2.api.common.AttestationConveyancePreference
import com.google.android.gms.fido.fido2.api.common.AuthenticatorAssertionResponse
import com.google.android.gms.fido.fido2.api.common.AuthenticatorAttestationResponse
import com.google.android.gms.fido.fido2.api.common.AuthenticatorSelectionCriteria
import com.google.android.gms.fido.fido2.api.common.BrowserPublicKeyCredentialCreationOptions
import com.google.android.gms.fido.fido2.api.common.BrowserPublicKeyCredentialRequestOptions
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredentialCreationOptions
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredentialDescriptor
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredentialRequestOptions
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredentialUserEntity
import com.google.android.gms.fido.fido2.api.common.UserVerificationRequirement
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.microg.gms.fido.core.AccountPasskeyBackendProvider
import org.microg.gms.fido.core.hybrid.HybridAccountSessions
import org.microg.gms.fido.core.hybrid.HybridPrf
import org.microg.gms.fido.core.hybrid.controller.HybridAuthenticatorController
import org.microg.gms.fido.core.hybrid.model.QrCodeData
import org.microg.gms.fido.core.protocol.AttestationObject
import org.microg.gms.fido.core.protocol.msgs.AuthenticatorGetAssertionRequest
import org.microg.gms.fido.core.protocol.msgs.AuthenticatorGetAssertionResponse
import org.microg.gms.fido.core.protocol.msgs.AuthenticatorGetInfoRequest
import org.microg.gms.fido.core.protocol.msgs.AuthenticatorGetInfoResponse
import org.microg.gms.fido.core.protocol.msgs.AuthenticatorMakeCredentialRequest
import org.microg.gms.fido.core.protocol.msgs.AuthenticatorMakeCredentialResponse
import org.microg.gms.fido.core.ui.AuthenticatorActivity
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resume

private const val TAG = "HybridAuthenticate"
private const val REQUEST_BLUETOOTH_PERMISSIONS = 1001

@RequiresApi(23)
class HybridAuthenticateActivity : AppCompatActivity() {
    private var bottomSheet: HybridReceiverBottomSheetFragment? = null
    private var qrCodeData: QrCodeData? = null

    private var hybridAuthenticatorController: HybridAuthenticatorController? = null

    private lateinit var waitingLauncher: ActivityResultLauncher<Intent>
    private var waitingLauncherContinuation: CancellableContinuation<ActivityResult>? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fidoUrl = intent.dataString
        if (fidoUrl == null || !fidoUrl.startsWith(QrCodeData.PREFIX_FIDO, ignoreCase = true)) {
            Log.w(TAG, "Invalid FIDO URL")
            finishWithError("Invalid FIDO URL")
            return
        }
        qrCodeData = QrCodeData.parse(fidoUrl)
        if (qrCodeData == null) {
            Log.w(TAG, "Failed to parse QR code data")
            finishWithError("Failed to parse QR code data")
            return
        }
        waitingLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val pending = waitingLauncherContinuation
            waitingLauncherContinuation = null
            if (pending?.isActive == true) pending.resume(it)
        }

        val isRegistration = qrCodeData?.flowIdentifier == "mc"

        var bottomSheetFragment = supportFragmentManager.findFragmentByTag(HybridReceiverBottomSheetFragment.TAG)
        if (bottomSheetFragment == null) {
            bottomSheetFragment = HybridReceiverBottomSheetFragment.newInstance(isRegistration)
            bottomSheetFragment.show(supportFragmentManager, HybridReceiverBottomSheetFragment.TAG)
        }

        (bottomSheetFragment as HybridReceiverBottomSheetFragment).also { bottomSheet = it }.setOnContinueListener {
            Log.d(TAG, "User confirmed - starting connection flow")
            // Update UI to CONNECTING state
            bottomSheetFragment.showConnecting()

            if (!hasBluetoothPermissions()) {
                Log.d(TAG, "Bluetooth permissions not granted, requesting...")
                requestBluetoothPermissions()
                return@setOnContinueListener
            }

            startHybridConnectionFlow()
        }

        bottomSheet?.setOnCancelListener {
            Log.d(TAG, "User canceled")
            finishWithError("User canceled")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    override fun onDestroy() {
        super.onDestroy()
        waitingLauncherContinuation?.cancel(); waitingLauncherContinuation = null
        hybridAuthenticatorController?.release()
        hybridAuthenticatorController = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startHybridConnectionFlow() {
        lifecycleScope.launchWhenCreated {
            hybridAuthenticatorController = hybridAuthenticatorController ?: HybridAuthenticatorController(this@HybridAuthenticateActivity,
                (application as? AccountPasskeyBackendProvider)?.accountPasskeyBackend?.supportsHybridPrf(this@HybridAuthenticateActivity) == true)
            try {
                hybridAuthenticatorController?.startAuth(qrCodeData!!, handleAuthenticator = {
                    withContext(Dispatchers.Main.immediate) { runCatching {
                        when (it) {
                            is AuthenticatorMakeCredentialRequest -> handleMakeCredential(it)
                            is AuthenticatorGetAssertionRequest -> handleGetAssertion(it)
                            is AuthenticatorGetInfoRequest -> handleGetInfo(it)
                            else -> null
                        }
                    }.getOrNull() }
                }, completed = { success ->
                    runOnUiThread { if (success) finishWithSuccess() else finishWithError("auth error") }
                })
            } catch (e: Exception) {
                Log.w(TAG, "Hybrid connection failed")
                bottomSheet?.showError()
            } finally {
                hybridAuthenticatorController?.release()
            }
        }
    }

    private suspend fun handleMakeCredential(request: AuthenticatorMakeCredentialRequest): AuthenticatorMakeCredentialResponse {
        val publicKeyCredentialCreationOptions = PublicKeyCredentialCreationOptions.Builder()
            .setRp(request.rp)
            .setUser(request.user)
            .setParameters(request.pubKeyCredParams)
            .setChallenge(request.clientDataHash)
            .setAttestationConveyancePreference(AttestationConveyancePreference.NONE)
            .setAuthenticatorSelection(
                AuthenticatorSelectionCriteria.Builder()
                    .setRequireResidentKey(request.options?.residentKey ?: false)
                    .setRequireUserVerification(request.options?.userVerification?.takeIf { it }?.let { UserVerificationRequirement.REQUIRED })
                    .build()
            )
            .setExcludeList(request.excludeList)
            .setAuthenticationExtensions(HybridPrf.parse(request.extensions, true))
            .build()

        val browserOptions = BrowserPublicKeyCredentialCreationOptions.Builder()
            .setPublicKeyCredentialCreationOptions(publicKeyCredentialCreationOptions)
            .setOrigin(HybridAccountSessions.CONTEXT.toUri())
            .setClientDataHash(request.clientDataHash).build()

        val intent = Intent(this, AuthenticatorActivity::class.java)
            .putExtra(AuthenticatorActivity.KEY_SOURCE, AuthenticatorActivity.SOURCE_HYBRID)
            .putExtra(AuthenticatorActivity.KEY_TYPE, AuthenticatorActivity.TYPE_REGISTER)
            .putExtra(AuthenticatorActivity.KEY_OPTIONS, browserOptions.serializeToBytes())

        val result = launchBound(intent, browserOptions.serializeToBytes(), request.rp.id, request.clientDataHash, true)

        val resultBytes = result.data?.getByteArrayExtra(FIDO2_KEY_CREDENTIAL_EXTRA) ?: throw RuntimeException("No result")
        val publicKeyCredential = PublicKeyCredential.deserializeFromBytes(resultBytes)
        val response = publicKeyCredential.response as? AuthenticatorAttestationResponse? ?: throw RuntimeException("Invalid result")
        val attestationObject = AttestationObject.decode(response.attestationObject)

        return AuthenticatorMakeCredentialResponse(
            authData = attestationObject.authData,
            fmt = attestationObject.fmt,
            attStmt = attestationObject.attStmt,
            unsignedExtensionOutputs = HybridPrf.output(publicKeyCredential.clientExtensionResults?.prfOutputs, true)
        )
    }

    private suspend fun handleGetAssertion(request: AuthenticatorGetAssertionRequest): AuthenticatorGetAssertionResponse {
        val publicKeyCredentialRequestOptions = PublicKeyCredentialRequestOptions.Builder()
            .setRpId(request.rpId)
            .setChallenge(request.clientDataHash)
            .setAllowList(request.allowList)
            .setAuthenticationExtensions(HybridPrf.parse(request.extensions, false))
            .setRequireUserVerification(request.options?.userVerification?.takeIf { it }?.let { UserVerificationRequirement.REQUIRED })
            .build()

        val browserOptions = BrowserPublicKeyCredentialRequestOptions.Builder()
            .setPublicKeyCredentialRequestOptions(publicKeyCredentialRequestOptions)
            .setOrigin(HybridAccountSessions.CONTEXT.toUri())
            .setClientDataHash(request.clientDataHash).build()

        val intent = Intent(this, AuthenticatorActivity::class.java)
            .putExtra(AuthenticatorActivity.KEY_SOURCE, AuthenticatorActivity.SOURCE_HYBRID)
            .putExtra(AuthenticatorActivity.KEY_TYPE, AuthenticatorActivity.TYPE_SIGN)
            .putExtra(AuthenticatorActivity.KEY_OPTIONS, browserOptions.serializeToBytes())

        val result = launchBound(intent, browserOptions.serializeToBytes(), request.rpId, request.clientDataHash, false)

        val resultBytes = result.data?.getByteArrayExtra(FIDO2_KEY_CREDENTIAL_EXTRA) ?: throw RuntimeException("No result")
        val publicKeyCredential = PublicKeyCredential.deserializeFromBytes(resultBytes)
        val response = publicKeyCredential.response as? AuthenticatorAssertionResponse? ?: throw RuntimeException("Invalid result")
        val userEntity = result.data?.getStringExtra(AuthenticatorActivity.KEY_USER_JSON)?.let { PublicKeyCredentialUserEntity.parseJson(it) }?:
            response.userHandle?.let { PublicKeyCredentialUserEntity(it,  "", null, "") }

        return AuthenticatorGetAssertionResponse(
            credential = PublicKeyCredentialDescriptor("public-key", response.keyHandle, null),
            authData = response.authenticatorData,
            signature = response.signature,
            user = if (request.allowList.isEmpty()) userEntity ?: throw SecurityException("Missing user handle") else null,
            numberOfCredentials = null,
            unsignedExtensionOutputs = HybridPrf.output(publicKeyCredential.clientExtensionResults?.prfOutputs, false),
            userSelected = if (request.allowList.isEmpty()) true else null
        )
    }

    private suspend fun launchBound(intent: Intent, raw: ByteArray, rp: String, hash: ByteArray, create: Boolean): ActivityResult {
        val controller = hybridAuthenticatorController ?: throw SecurityException("Hybrid session expired")
        val token = controller.issueAccountRequest(raw, rp, hash, create)
        intent.putExtra(HybridAccountSessions.KEY_REQUEST, token)
        try {
            val result = suspendCancellableCoroutine<ActivityResult> { continuation ->
                check(waitingLauncherContinuation == null)
                waitingLauncherContinuation = continuation
                continuation.invokeOnCancellation { if (waitingLauncherContinuation === continuation) waitingLauncherContinuation = null }
                waitingLauncher.launch(intent)
            }
            HybridAccountSessions.requireLive(token, raw, rp, hash, create)
            check(result.resultCode == RESULT_OK)
            return result
        } finally { controller.completeAccountRequest(token) }
    }

    private fun handleGetInfo(request: AuthenticatorGetInfoRequest): AuthenticatorGetInfoResponse {
        return AuthenticatorGetInfoResponse(
            versions = arrayListOf("FIDO_2_0", "FIDO_2_1"),
            extensions = if ((application as? AccountPasskeyBackendProvider)?.accountPasskeyBackend?.supportsHybridPrf(this) == true) listOf("prf") else emptyList(),
            aaguid = ByteArray(16),
            options = AuthenticatorGetInfoResponse.Companion.Options(
                residentKey = true,
                userPresence = true,
                userVerification = true,
                platformDevice = true
            )
        )
    }

    private fun hasBluetoothPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBluetoothPermissions() {
        ActivityCompat.requestPermissions(
            this, arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
            ), REQUEST_BLUETOOTH_PERMISSIONS
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                Log.d(TAG, "Bluetooth permissions granted")
                // Resume flow with pending QR code data
                startHybridConnectionFlow()
            } else {
                Log.e(TAG, "Bluetooth permissions denied")
                finishWithError("Bluetooth permissions required for cross-device authentication")
            }
        }
    }

    private fun finishWithSuccess() {
        Log.d(TAG, "Finishing with success")
        bottomSheet?.dismiss()
        bottomSheet = null
        setResult(RESULT_OK)
        finish()
    }

    private fun finishWithError(message: String) {
        hybridAuthenticatorController?.release()
        Log.d(TAG, "Finishing with error: $message")
        bottomSheet?.dismiss()
        bottomSheet = null
        setResult(RESULT_CANCELED, Intent().apply {
            putExtra("error", message)
        })
        finish()
    }
}
