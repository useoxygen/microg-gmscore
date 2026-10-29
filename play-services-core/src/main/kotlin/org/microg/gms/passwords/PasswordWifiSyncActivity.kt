/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.Manifest
import org.microg.gms.cyclon.Cyclon
import android.accounts.Account
import android.accounts.AccountManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.microg.gms.cyclon.*

/** Internal owner surface: a known stable account and fresh foreground owner grant are required. */
class PasswordWifiSyncActivity : PasswordOwnerActivity() {
    private lateinit var account: Account
    private lateinit var userId: String
    private var unlocked by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var enabled by mutableStateOf(false)
    private var access by mutableStateOf(PasswordWifiAccess.IMAGE_GRANT_REQUIRED)
    private val available get() = access == PasswordWifiAccess.AVAILABLE
    private var pending by mutableStateOf(0)
    private var conflicts by mutableStateOf(0)
    private var uncertain by mutableStateOf(0)
    private var prompt by mutableStateOf<Int?>(null)
    private var message by mutableStateOf<Int?>(null)
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // A permission result only refreshes readiness. Account sync still needs its separate opt-in.
        refreshAccessIfOwned()
    }
    private val locationSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshAccessIfOwned()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT < 26) { finish(); return }
        val name = intent.getStringExtra("account")
        val id = intent.getStringExtra("google_user_id")
        if (name.isNullOrBlank() || id.isNullOrBlank()) { finish(); return }
        account = Account(name, "com.google"); userId = id
        if (!validAccount()) { finish(); return }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setContent { CyclonMaterialTheme(glass = true) {
            PasswordOwnerPage(stringResource(R.string.passwords_wifi_title), account.name, onBack = ::finish) {
                if (!unlocked) { PasswordOwnerLocked(hasSecureScreenLock, onUnlock = ::authenticateOwner); return@PasswordOwnerPage }
                CyclonNote(stringResource(R.string.passwords_wifi_description))
                if (!available) {
                    PasswordOwnerText(stringResource(when (access) {
                        PasswordWifiAccess.LOCATION_PERMISSION_REQUIRED -> R.string.passwords_wifi_location_permission
                        PasswordWifiAccess.LOCATION_DISABLED -> R.string.passwords_wifi_location_disabled
                        PasswordWifiAccess.APP_OP_DENIED -> R.string.passwords_wifi_location_restricted
                        else -> R.string.passwords_wifi_unavailable
                    }))
                    if (access in setOf(PasswordWifiAccess.LOCATION_PERMISSION_REQUIRED,
                            PasswordWifiAccess.LOCATION_DISABLED, PasswordWifiAccess.APP_OP_DENIED)) PasswordOwnerActions {
                        CyclonGlassButton(stringResource(when (access) {
                            PasswordWifiAccess.LOCATION_PERMISSION_REQUIRED -> R.string.passwords_wifi_allow_location
                            PasswordWifiAccess.LOCATION_DISABLED -> R.string.passwords_wifi_open_location
                            else -> R.string.passwords_wifi_open_permissions
                        }), enabled = !busy, onClick = ::requestAccess)
                        if (access == PasswordWifiAccess.LOCATION_PERMISSION_REQUIRED) CyclonTextAction(
                            stringResource(R.string.passwords_wifi_open_permissions), enabled = !busy, onClick = ::openAppPermissions)
                    }
                }
                CyclonSectionLabel(stringResource(R.string.passwords_section_sync))
                CyclonGroup {
                    CyclonSwitchRow(stringResource(R.string.passwords_wifi_enable), stringResource(R.string.passwords_wifi_state, pending, conflicts),
                        enabled, enabled = !busy && (available || enabled), onChange = { value ->
                            if (value) prompt = R.string.passwords_wifi_enable_warning else perform { it.setEnabled(false) }
                        })
                    CyclonRow(stringResource(R.string.passwords_refresh), onClick = { perform { it.synchronizeIfEnabled() } },
                        enabled = enabled && available && !busy)
                    if (uncertain > 0) CyclonRow(stringResource(R.string.passwords_retry_owner), onClick = { prompt = R.string.passwords_wifi_retry_warning },
                        enabled = !busy && enabled && available)
                    CyclonRow(stringResource(R.string.passwords_wifi_forget), onClick = { prompt = R.string.passwords_wifi_forget_warning }, enabled = !busy)
                }
                PasswordOwnerStatus(busy, message?.let { stringResource(it) })
            }
            prompt?.takeIf { unlocked }?.let { question -> AlertDialog(containerColor = Cyclon.glass.dialog, onDismissRequest = { prompt = null },
                title = { Text(stringResource(R.string.passwords_wifi_title)) },
                text = { PasswordDialogProtection(); PasswordDialogText(stringResource(question), scrolls = true) },
                confirmButton = { CyclonTextAction(stringResource(android.R.string.ok)) {
                    prompt = null
                    perform { helper -> when (question) {
                        R.string.passwords_wifi_enable_warning -> { helper.setEnabled(true); helper.synchronizeIfEnabled() }
                        R.string.passwords_wifi_retry_warning -> helper.synchronizeIfEnabled(ownerAuthorizesRetry = true)
                        R.string.passwords_wifi_forget_warning -> helper.pauseAndForget()
                    } }
                } },
                dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel)) { prompt = null } }) }
        } }
        authenticateOwner()
    }
    private fun validAccount(): Boolean {
        val accounts = AccountManager.get(this)
        return accounts.getAccountsByType("com.google").contains(account) && accounts.getUserData(account, "GoogleUserId") == userId
    }
    private fun helper(): PasswordWifiSync {
        if (!validAccount()) throw VaultLockedException()
        val repo = PasswordRepository(this, account)
        if (!validAccount()) throw VaultLockedException()
        return PasswordWifiSync(this, repo, userId)
    }
    private fun requestAccess() {
        try {
            requireOwner()
            if (!validAccount() || busy) return
            when (access) {
                // Android 12+ needs fine and coarse requested together for a precise-location choice.
                PasswordWifiAccess.LOCATION_PERMISSION_REQUIRED -> locationPermission.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                PasswordWifiAccess.LOCATION_DISABLED -> locationSettings.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                PasswordWifiAccess.APP_OP_DENIED -> locationSettings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)))
                else -> Unit
            }
        } catch (_: Exception) { message = R.string.passwords_wifi_access_changed }
    }
    private fun openAppPermissions() {
        try {
            requireOwner()
            if (validAccount() && !busy) locationSettings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)))
        } catch (_: Exception) { message = R.string.passwords_wifi_access_changed }
    }
    private fun refreshAccessIfOwned() {
        if (!unlocked || busy) return
        try { requireOwner(); if (validAccount()) perform() else onOwnerLocked() }
        catch (_: Exception) { onOwnerLocked() }
    }
    private fun perform(action: (PasswordWifiSync) -> Unit = {}) {
        requireOwner()
        if (busy) return
        busy = true; message = null
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val helper = helper()
                val failure = runCatching { action(helper) }.exceptionOrNull()
                Triple(helper.status(), helper.access(), failure)
            } }
            busy = false
            if (!unlocked) return@launch
            try { requireOwner(); if (!validAccount()) throw VaultLockedException() }
            catch (_: Exception) { onOwnerLocked(); return@launch }
            result.onSuccess { (state, admitted, failure) ->
                enabled = state.enabled; access = admitted; pending = state.pending; conflicts = state.conflicts; uncertain = state.indeterminate
                if (failure != null) message = if (failure is PasswordWifiUnavailableException)
                    R.string.passwords_wifi_access_changed else R.string.passwords_sync_failed
            }.onFailure {
                message = if (it is PasswordWifiUnavailableException) R.string.passwords_wifi_access_changed else R.string.passwords_sync_failed
            }
        }
    }
    override fun onOwnerAuthenticated() { unlocked = true; perform() }
    override fun onOwnerAuthenticationFailed() { onOwnerLocked() }
    override fun onOwnerLocked() { unlocked = false; prompt = null; message = null; access = PasswordWifiAccess.IMAGE_GRANT_REQUIRED; pending = 0; conflicts = 0; uncertain = 0 }
}
