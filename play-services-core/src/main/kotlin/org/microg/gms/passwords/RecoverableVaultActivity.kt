/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import org.microg.gms.auth.folsom.FolsomAndroidAdmission
import org.microg.gms.auth.folsom.FolsomCallerAdmission
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Explicit, account-bound screen-lock recovery consent. No lock-screen secret enters this activity. */
class RecoverableVaultActivity : PasswordOwnerActivity() {
    private lateinit var account: Account
    private lateinit var accountId: String
    private lateinit var domain: String
    private var parent: String? = null
    private val allowed = AtomicBoolean(false)
    private val generation = AtomicLong()
    @Volatile private var client: RecoverableVaultClient? = null
    @Volatile private var cancellation: AtomicBoolean? = null
    private var unlocked by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var status by mutableStateOf<RecoverableVaultStatus?>(null)
    private var failed by mutableStateOf(false)
    private var confirmation by mutableStateOf<RecoverableVaultAction?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        if (android.os.Build.VERSION.SDK_INT < 28) { finish(); return }
        val name = intent.getStringExtra("account")
        val id = intent.getStringExtra("google_user_id")
        val selected = intent.getStringExtra("domain")
        if (name.isNullOrBlank() || id.isNullOrBlank() || selected !in setOf("chromesync", "hw_protected")) { finish(); return }
        account = Account(name, "com.google"); accountId = id; domain = selected!!
        parent = intent.getStringExtra("password_parent")
        if (!validAccount()) { finish(); return }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setContent { CyclonMaterialTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.passwords_recoverable_vault_title), style = MaterialTheme.typography.headlineSmall)
                if (!unlocked) Button(onClick = ::authenticateOwner, enabled = !busy) { Text(stringResource(R.string.passwords_unlock)) }
                else {
                    Text(account.name)
                    Text(stringResource(if (domain == "chromesync") R.string.passwords_recoverable_vault_passwords
                        else R.string.passwords_recoverable_vault_passkeys))
                    Text(stringResource(R.string.passwords_recoverable_vault_description))
                    if (!hasSecureScreenLock) Text(stringResource(R.string.passwords_recovery_enroll_lock_required))
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (failed) Text(stringResource(R.string.passwords_recoverable_vault_failed))
                    status?.phase?.let { phase -> Text(stringResource(phaseText(phase))) }
                    if (hasSecureScreenLock) {
                        OutlinedButton(onClick = { perform(RecoverableVaultAction.REFRESH) }, enabled = !busy) {
                            Text(stringResource(R.string.passwords_refresh))
                        }
                        if (status != null && status?.attemptId == null) Button(onClick = {
                            confirmation = RecoverableVaultAction.PREPARE
                        }, enabled = !busy) { Text(stringResource(R.string.passwords_recoverable_vault_enable)) }
                        if (status?.attemptId != null && status?.complete != true && status?.phase != RecoverableVaultPhase.REVOKED) Button(onClick = {
                            confirmation = RecoverableVaultAction.CONTINUE
                        }, enabled = !busy) { Text(stringResource(R.string.passwords_recoverable_vault_continue)) }
                        if (status?.attemptId != null) TextButton(onClick = { confirmation = RecoverableVaultAction.CLOSE }, enabled = !busy) {
                            Text(stringResource(R.string.passwords_recovery_enroll_close_attempt))
                        }
                    }
                }
                TextButton(onClick = { cancel(); finish() }) { Text(stringResource(android.R.string.cancel)) }
            }
            confirmation?.takeIf { unlocked }?.let { action ->
                AlertDialog(onDismissRequest = { confirmation = null },
                    title = { Text(stringResource(R.string.passwords_recoverable_vault_title)) },
                    text = { PasswordDialogProtection(); Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(account.name)
                        Text(stringResource(when (action) {
                            RecoverableVaultAction.PREPARE -> R.string.passwords_recoverable_vault_confirm
                            RecoverableVaultAction.CONTINUE -> R.string.passwords_recoverable_vault_continue_confirm
                            else -> R.string.passwords_recoverable_vault_close_confirm
                        }))
                    } }, confirmButton = { TextButton(onClick = {
                        confirmation = null; perform(action)
                    }, enabled = !busy && hasSecureScreenLock) { Text(stringResource(android.R.string.ok)) } },
                    dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(android.R.string.cancel)) } })
            }
        } } }
        authenticateOwner()
    }
    private fun validAccount(): Boolean {
        val manager = AccountManager.get(this)
        if (!manager.getAccountsByType("com.google").contains(account) || manager.getUserData(account, "GoogleUserId") != accountId) return false
        return try {
            intent.getStringExtra("lifecycle_caller")?.let { caller ->
                val identity = FolsomAndroidAdmission.identity(this, caller) ?: throw VaultLockedException()
                if (identity.uid != intent.getIntExtra("lifecycle_uid", -1) || identity.certificate != intent.getStringExtra("lifecycle_signer") ||
                    !FolsomCallerAdmission.allowed(identity, domain, Process.myUid())) throw VaultLockedException()
                val now = SystemClock.elapsedRealtime()
                val expires = intent.getLongExtra("lifecycle_expires", -1)
                if (expires < now || expires - now > 120_000 || intent.getIntExtra("lifecycle_boot", -1) !=
                    Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -2)) throw VaultLockedException()
            }
            true
        } catch (_: Exception) { false }
    }
    private fun perform(action: RecoverableVaultAction) {
        try { requireOwner(); if (!allowed.get() || !validAccount() || !hasSecureScreenLock) throw VaultLockedException() }
        catch (_: Exception) { failed = true; return }
        if (busy) return
        busy = true; failed = false
        val grant = generation.get()
        val stopped = AtomicBoolean(false)
        cancellation = stopped
        val attempt = status?.attemptId
        val verify = {
            if (stopped.get() || !allowed.get() || generation.get() != grant || !validAccount() || !hasSecureScreenLock)
                throw VaultLockedException()
            requireOwner()
        }
        lifecycleScope.launch {
            try {
                var problem = false
                val result = withContext(Dispatchers.IO) {
                    verify()
                    val current = RecoverableVaultClient(this@RecoverableVaultActivity, account, accountId, domain, parent, verify)
                    if (stopped.get() || cancellation !== stopped) { current.cancel(); throw VaultLockedException() }
                    client = current
                    try { current.perform(action, attempt) }
                    catch (_: Exception) { problem = true; verify(); current.status() }
                }
                verify()
                if (cancellation === stopped) {
                    status = result; failed = problem
                    if (!problem && result.complete && intent.getBooleanExtra("lifecycle_return_on_confirmed", false)) {
                        verify(); setResult(RESULT_OK); finish()
                    }
                }
            } catch (_: Exception) {
                if (allowed.get() && generation.get() == grant && cancellation === stopped) failed = true
            } finally {
                if (cancellation === stopped) { busy = false; client = null; cancellation = null }
            }
        }
    }
    private fun phaseText(phase: RecoverableVaultPhase): Int = when (phase) {
        RecoverableVaultPhase.WAITING_FOR_SNAPSHOT -> R.string.passwords_recoverable_vault_waiting
        RecoverableVaultPhase.PREPARED -> R.string.passwords_recoverable_vault_prepared
        RecoverableVaultPhase.VAULT_LAUNCHED, RecoverableVaultPhase.JOIN_LAUNCHED -> R.string.passwords_recoverable_vault_uncertain
        RecoverableVaultPhase.VAULT_CONFIRMED -> R.string.passwords_recoverable_vault_uploaded
        RecoverableVaultPhase.CONFIRMED -> R.string.passwords_recoverable_vault_complete
        RecoverableVaultPhase.REVOKED -> R.string.passwords_recoverable_vault_revoked
    }
    private fun cancel() { cancellation?.set(true); client?.cancel() }
    override fun onOwnerAuthenticated() {
        generation.incrementAndGet(); allowed.set(true); unlocked = true
        if (hasSecureScreenLock) perform(RecoverableVaultAction.REFRESH)
    }
    override fun onOwnerAuthenticationFailed() { finish() }
    override fun onPause() { allowed.set(false); generation.incrementAndGet(); cancel(); super.onPause() }
    override fun onResume() {
        super.onResume()
        if (unlocked) allowed.set(runCatching { requireOwner(); true }.getOrDefault(false))
    }
    override fun onOwnerLocked() {
        allowed.set(false); generation.incrementAndGet(); cancel()
        client = null; cancellation = null; busy = false; unlocked = false; status = null; confirmation = null
    }
}
