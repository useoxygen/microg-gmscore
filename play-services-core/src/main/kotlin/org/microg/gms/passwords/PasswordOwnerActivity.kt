/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.R

/** Foreground-only access, using the screen lock when configured. Never persisted or transferred. */
abstract class PasswordOwnerActivity : AppCompatActivity() {
    private val access = PasswordOwnerAccess()
    private var foreground = false
    private var requested = false
    private var confirming = false
    private var confirmationResult: Boolean? = null
    private val keyguard get() = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
    protected val hasSecureScreenLock get() = Build.VERSION.SDK_INT >= 23 && keyguard.isDeviceSecure
    private val deviceUnlocked get() = Build.VERSION.SDK_INT >= 23 && !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked
    private val confirmOwner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        confirming = false
        confirmationResult = result.resultCode == RESULT_OK
        if (foreground) completeConfirmation()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    protected fun authenticateOwner() {
        if (Build.VERSION.SDK_INT < 23) { access.cancel(); onOwnerAuthenticationFailed(); return }
        // Provider activities request access in onCreate, after an OS picker selection.
        // Wait until the activity is actually resumed before accepting no-lock presence.
        if (!foreground) { requested = true; return }
        if (confirming) return
        if (access.allows(hasSecureScreenLock, deviceUnlocked, foreground)) { onOwnerAuthenticated(); return }
        val secure = hasSecureScreenLock
        access.begin(secure)
        if (!secure) {
            if (access.complete(false, hasSecureScreenLock, deviceUnlocked, foreground)) onOwnerAuthenticated()
            else onOwnerAuthenticationFailed()
            return
        }
        try {
            @Suppress("DEPRECATION")
            val prompt = keyguard.createConfirmDeviceCredentialIntent(
                getString(R.string.passwords_unlock), getString(R.string.passwords_unlock_description))
            if (prompt == null) { access.cancel(); onOwnerAuthenticationFailed(); return }
            confirming = true
            confirmOwner.launch(prompt)
        } catch (_: Exception) {
            confirming = false; access.cancel(); onOwnerAuthenticationFailed()
        }
    }
    private fun completeConfirmation() {
        val confirmed = confirmationResult ?: return
        confirmationResult = null
        if (access.complete(confirmed, hasSecureScreenLock, deviceUnlocked, foreground)) onOwnerAuthenticated()
        else onOwnerAuthenticationFailed()
    }
    protected fun requireOwner() {
        if (!access.allows(hasSecureScreenLock, deviceUnlocked, foreground)) throw VaultLockedException()
    }
    override fun onResume() {
        super.onResume()
        foreground = true
        if (confirmationResult != null) { requested = false; completeConfirmation() }
        else if (requested) { requested = false; authenticateOwner() }
    }
    override fun onPause() {
        foreground = false
        super.onPause()
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (rejectsPasswordTouch(event.flags)) return false
        return super.dispatchTouchEvent(event)
    }
    override fun onStop() {
        foreground = false
        if (confirmationResult != null) {
            confirmationResult = null
            access.cancel()
        } else access.stopped()
        onOwnerLocked()
        super.onStop()
    }
    protected abstract fun onOwnerAuthenticated()
    protected abstract fun onOwnerAuthenticationFailed()
    protected abstract fun onOwnerLocked()
}
