/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.authzen.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Build.VERSION.SDK_INT
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import com.google.android.gms.R
import org.microg.gms.auth.authzen.OwnerAuthResult

internal enum class OwnerAuthAvailability { AVAILABLE, NO_SECURE_LOCK, UNSUPPORTED }

/**
 * Fresh proof that the owner is present, for exactly one approval attempt. A recently unlocked phone is not proof,
 * and neither is dismissing the keyguard: every Yes asks again.
 */
internal interface OwnerAuthGate {
    fun availability(): OwnerAuthAvailability

    /** Calls [onResult] once, on the main thread. [cancel] ends a running prompt as [OwnerAuthResult.CANCELED]. */
    fun authenticate(activity: Activity, onResult: (OwnerAuthResult) -> Unit)

    fun cancel()
}

/**
 * The system BiometricPrompt with `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` (Android 11+): a class 3 biometric or the
 * screen lock PIN, pattern or password. Older Android versions cannot combine them, so Yes is unavailable there.
 */
internal class SystemOwnerAuthGate(private val context: Context) : OwnerAuthGate {
    private var signal: CancellationSignal? = null

    override fun availability(): OwnerAuthAvailability {
        if (SDK_INT < 30) return OwnerAuthAvailability.UNSUPPORTED
        if (!context.getSystemService(KeyguardManager::class.java).isDeviceSecure) return OwnerAuthAvailability.NO_SECURE_LOCK
        return when (context.getSystemService(BiometricManager::class.java).canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> OwnerAuthAvailability.AVAILABLE
            else -> OwnerAuthAvailability.NO_SECURE_LOCK
        }
    }

    override fun authenticate(activity: Activity, onResult: (OwnerAuthResult) -> Unit) {
        if (SDK_INT < 30) return onResult(OwnerAuthResult.FAILED)
        prompt(activity, onResult)
    }

    @RequiresApi(30)
    private fun prompt(activity: Activity, onResult: (OwnerAuthResult) -> Unit) {
        var delivered = false
        fun deliver(result: OwnerAuthResult) {
            if (delivered) return
            delivered = true
            signal = null
            onResult(result)
        }
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(activity.getString(R.string.cyclon_authzen_auth_title))
            .setSubtitle(activity.getString(R.string.cyclon_authzen_auth_subtitle))
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .setConfirmationRequired(true)
            .build()
        val cancellation = CancellationSignal().also { signal = it }
        prompt.authenticate(cancellation, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = deliver(OwnerAuthResult.SUCCESS)

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = deliver(
                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED || errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) OwnerAuthResult.CANCELED
                else OwnerAuthResult.FAILED
            )
            // onAuthenticationFailed is a single rejected attempt; the prompt stays open, so it is not a result.
        })
    }

    override fun cancel() {
        signal?.cancel()
        signal = null
    }
}
