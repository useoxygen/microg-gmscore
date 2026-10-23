/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.os.Bundle
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.microg.gms.cyclon.CyclonMaterialTheme
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Private native owner screen. No PIN input, hash, key, or vault request is exposed through the result. */
class GpmPinRenewalActivity : PasswordOwnerActivity() {
    private lateinit var account: Account
    private lateinit var accountId: String
    private val allowed = AtomicBoolean(false)
    private val generation = AtomicLong()
    @Volatile private var client: GpmPinRenewalClient? = null
    @Volatile private var cancellation: AtomicBoolean? = null
    private var unlocked by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var status by mutableStateOf<GpmPinRenewalStatus?>(null)
    private var failed by mutableStateOf(false)
    private var confirmation by mutableStateOf<GpmPinRenewalAction?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT < 28) { finish(); return }
        val name = intent.getStringExtra("account"); val id = intent.getStringExtra("google_user_id")
        if (name.isNullOrBlank() || id.isNullOrBlank()) { finish(); return }
        account = Account(name, "com.google"); accountId = id
        if (!validAccount()) { finish(); return }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setContent { CyclonMaterialTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.passwords_pin_renewal_title), style = MaterialTheme.typography.headlineSmall)
                if (!unlocked) Button(onClick = ::authenticateOwner, enabled = !busy) { Text(stringResource(R.string.passwords_unlock)) }
                else {
                    Text(account.name); Text(stringResource(R.string.passwords_pin_renewal_description))
                    if (!hasSecureScreenLock) Text(stringResource(R.string.passwords_recovery_enroll_lock_required))
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (failed) Text(stringResource(R.string.passwords_pin_renewal_failed))
                    if (status?.current == true) Text(stringResource(R.string.passwords_pin_renewal_current))
                    status?.phase?.let { phase -> Text(stringResource(when (phase) {
                        GpmPinRenewalPhase.PREPARED -> R.string.passwords_recoverable_vault_prepared
                        GpmPinRenewalPhase.VAULT_LAUNCHED, GpmPinRenewalPhase.JOIN_LAUNCHED -> R.string.passwords_recoverable_vault_uncertain
                        GpmPinRenewalPhase.VAULT_CONFIRMED -> R.string.passwords_recoverable_vault_uploaded
                        GpmPinRenewalPhase.CONFIRMED -> R.string.passwords_pin_renewal_complete
                        GpmPinRenewalPhase.REVOKED -> R.string.passwords_pin_renewal_revoked
                    })) }
                    if (hasSecureScreenLock) {
                        OutlinedButton(onClick = { perform(GpmPinRenewalAction.REFRESH) }, enabled = !busy) { Text(stringResource(R.string.passwords_refresh)) }
                        if (status != null && (status?.id == null || status?.phase == GpmPinRenewalPhase.CONFIRMED)) Button(onClick = {
                            confirmation = GpmPinRenewalAction.PREPARE
                        }, enabled = !busy) { Text(stringResource(R.string.passwords_pin_renewal_start)) }
                        if (status?.phase in setOf(GpmPinRenewalPhase.PREPARED, GpmPinRenewalPhase.VAULT_CONFIRMED)) Button(onClick = {
                            confirmation = GpmPinRenewalAction.CONTINUE
                        }, enabled = !busy) { Text(stringResource(R.string.passwords_recoverable_vault_continue)) }
                        if (status?.id != null) TextButton(onClick = { confirmation = GpmPinRenewalAction.CLOSE }, enabled = !busy) {
                            Text(stringResource(R.string.passwords_recovery_enroll_close_attempt))
                        }
                    }
                }
                TextButton(onClick = { cancel(); finish() }) { Text(stringResource(android.R.string.cancel)) }
            }
            confirmation?.takeIf { unlocked }?.let { action -> AlertDialog(onDismissRequest = { confirmation = null },
                title = { Text(stringResource(R.string.passwords_pin_renewal_title)) },
                text = { PasswordDialogProtection(); Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(account.name); Text(stringResource(if (action == GpmPinRenewalAction.CLOSE)
                        R.string.passwords_pin_renewal_close_confirm else R.string.passwords_pin_renewal_confirm))
                } }, confirmButton = { TextButton(onClick = { confirmation = null; perform(action) }, enabled = !busy && hasSecureScreenLock) {
                    Text(stringResource(android.R.string.ok))
                } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(android.R.string.cancel)) } }) }
        } } }
        authenticateOwner()
    }
    private fun validAccount(): Boolean = AccountManager.get(this).let {
        it.getAccountsByType("com.google").contains(account) && it.getUserData(account, "GoogleUserId") == accountId
    }
    private fun perform(action: GpmPinRenewalAction) {
        try { requireOwner(); if (!allowed.get() || !validAccount() || !hasSecureScreenLock) throw VaultLockedException() }
        catch (_: Exception) { failed = true; return }
        if (busy) return
        busy = true; failed = false
        val stopped = AtomicBoolean(false); val grant = generation.get(); val selectedId = status?.id
        cancellation = stopped
        val verify = {
            if (stopped.get() || !allowed.get() || generation.get() != grant || !validAccount() || !hasSecureScreenLock) throw VaultLockedException()
            requireOwner()
        }
        lifecycleScope.launch {
            try {
                var problem = false
                val result = withContext(Dispatchers.IO) {
                    verify(); val current = GpmPinRenewalClient(this@GpmPinRenewalActivity, account, accountId, verify)
                    if (stopped.get() || cancellation !== stopped) { current.cancel(); throw VaultLockedException() }
                    client = current
                    try { current.perform(action, selectedId) } catch (_: Exception) { problem = true; verify(); current.status() }
                }
                verify()
                if (cancellation === stopped) { status = result; failed = problem }
            } catch (_: Exception) { if (allowed.get() && generation.get() == grant && cancellation === stopped) failed = true }
            finally { if (cancellation === stopped) { busy = false; client = null; cancellation = null } }
        }
    }
    private fun cancel() { cancellation?.set(true); client?.cancel() }
    override fun onOwnerAuthenticated() { generation.incrementAndGet(); allowed.set(true); unlocked = true
        if (hasSecureScreenLock) perform(GpmPinRenewalAction.REFRESH) }
    override fun onOwnerAuthenticationFailed() { finish() }
    override fun onPause() { allowed.set(false); generation.incrementAndGet(); cancel(); super.onPause() }
    override fun onResume() { super.onResume(); if (unlocked) allowed.set(runCatching { requireOwner(); true }.getOrDefault(false)) }
    override fun onOwnerLocked() { allowed.set(false); generation.incrementAndGet(); cancel(); client = null; cancellation = null
        unlocked = false; busy = false; status = null; failed = false; confirmation = null }
}
