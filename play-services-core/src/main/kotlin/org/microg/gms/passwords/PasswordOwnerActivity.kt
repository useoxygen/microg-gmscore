/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.R

/** Foreground-only access, using the screen lock when configured. Never persisted or transferred. */
abstract class PasswordOwnerActivity : AppCompatActivity() {
    private val access = PasswordOwnerAccess()
    private var foreground = false
    private var requested = false
    private var requestedFresh = false
    private var confirming = false
    private var confirmationResult: Boolean? = null
    private val keyguard get() = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
    protected val hasSecureScreenLock get() = Build.VERSION.SDK_INT >= 23 && keyguard.isDeviceSecure
    protected open val ownerAuthenticationTitle: String get() = getString(R.string.passwords_unlock)
    protected open val ownerAuthenticationDescription: String get() = getString(R.string.passwords_unlock_description)
    /** Owner vault screens open only on the phone's own screen, never on an assistant's private display. */
    protected open val ownerDisplayOnly: Boolean get() = true
    private fun onOwnerDisplay() = windowManager.defaultDisplay.displayId == Display.DEFAULT_DISPLAY
    private val deviceUnlocked get() = Build.VERSION.SDK_INT >= 23 && !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked
    private val confirmOwner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        confirming = false
        confirmationResult = result.resultCode == RESULT_OK
        if (foreground) completeConfirmation()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (ownerDisplayOnly && !onOwnerDisplay()) refuseOffOwnerDisplay()
    }
    private fun refuseOffOwnerDisplay() {
        if (isFinishing) return
        Toast.makeText(this, R.string.passwords_owner_display_only, Toast.LENGTH_LONG).show()
        finish()
    }
    protected fun authenticateOwner() = authenticate(fresh = false)
    /** Asks for the screen lock again even if already unlocked. Fails without a secure screen lock. */
    protected fun reconfirmOwner() = authenticate(fresh = true)
    private fun authenticate(fresh: Boolean) {
        if (Build.VERSION.SDK_INT < 23) { access.cancel(); onOwnerAuthenticationFailed(); return }
        if (ownerDisplayOnly && !onOwnerDisplay()) {
            access.cancel(); onOwnerAuthenticationFailed(); refuseOffOwnerDisplay(); return
        }
        // Provider activities request access in onCreate, after an OS picker selection.
        // Wait until the activity is actually resumed before accepting no-lock presence.
        if (!foreground) { requested = true; requestedFresh = requestedFresh || fresh; return }
        if (confirming) return
        if (!fresh && access.allows(hasSecureScreenLock, deviceUnlocked, foreground)) { onOwnerAuthenticated(); return }
        val secure = hasSecureScreenLock
        if (fresh && !secure) { onOwnerAuthenticationFailed(); return }
        access.begin(secure)
        if (!secure) {
            if (access.complete(false, hasSecureScreenLock, deviceUnlocked, foreground)) onOwnerAuthenticated()
            else onOwnerAuthenticationFailed()
            return
        }
        try {
            @Suppress("DEPRECATION")
            val prompt = keyguard.createConfirmDeviceCredentialIntent(
                ownerAuthenticationTitle, ownerAuthenticationDescription)
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
        if (ownerDisplayOnly && !onOwnerDisplay() || !access.allows(hasSecureScreenLock, deviceUnlocked, foreground)) throw VaultLockedException()
    }
    override fun onResume() {
        super.onResume()
        foreground = true
        val fresh = requestedFresh
        requestedFresh = false
        if (confirmationResult != null) { requested = false; completeConfirmation() }
        else if (requested) { requested = false; authenticate(fresh) }
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
