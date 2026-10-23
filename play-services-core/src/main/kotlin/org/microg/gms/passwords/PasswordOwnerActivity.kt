/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.app.KeyguardManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.R

/** A foreground-only owner grant. It is neither persisted nor transferable between activities. */
abstract class PasswordOwnerActivity : AppCompatActivity() {
    private var authenticated = false
    private val confirmOwner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            authenticated = true
            onOwnerAuthenticated()
        } else onOwnerAuthenticationFailed()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    protected fun authenticateOwner() {
        if (authenticated) { onOwnerAuthenticated(); return }
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        @Suppress("DEPRECATION")
        val prompt = if (keyguard.isDeviceSecure) keyguard.createConfirmDeviceCredentialIntent(
            getString(R.string.passwords_unlock), getString(R.string.passwords_unlock_description)) else null
        if (prompt == null) onOwnerAuthenticationFailed() else confirmOwner.launch(prompt)
    }
    protected fun requireOwner() { if (!authenticated) throw VaultLockedException() }
    override fun onStop() {
        authenticated = false
        onOwnerLocked()
        super.onStop()
    }
    protected abstract fun onOwnerAuthenticated()
    protected abstract fun onOwnerAuthenticationFailed()
    protected abstract fun onOwnerLocked()
}
