/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.os.Bundle
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.microg.gms.cyclon.*
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
        setContent { CyclonMaterialTheme(glass = true) {
            GpmPinRenewalScreen(account.name, unlocked, hasSecureScreenLock, busy, failed, status, confirmation,
                onBack = { cancel(); finish() }, onUnlock = ::authenticateOwner, onAsk = { confirmation = it },
                onRefresh = { perform(GpmPinRenewalAction.REFRESH) }, onConfirm = { action -> confirmation = null; perform(action) })
        } }
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

/** What [GpmPinRenewalActivity] shows, from its state alone; the activity keeps every check and request. */
@Composable
internal fun GpmPinRenewalScreen(
    account: String, unlocked: Boolean, secure: Boolean, busy: Boolean, failed: Boolean, status: GpmPinRenewalStatus?,
    confirmation: GpmPinRenewalAction?, onBack: () -> Unit, onUnlock: () -> Unit, onAsk: (GpmPinRenewalAction?) -> Unit,
    onRefresh: () -> Unit, onConfirm: (GpmPinRenewalAction) -> Unit,
) {
    PasswordOwnerPage(stringResource(R.string.passwords_pin_renewal_title), account, onBack) {
        if (!unlocked) { PasswordOwnerLocked(secure, enabled = !busy, onUnlock = onUnlock); return@PasswordOwnerPage }
        CyclonNote(stringResource(R.string.passwords_pin_renewal_description))
        if (!secure) PasswordOwnerText(stringResource(R.string.passwords_recovery_enroll_lock_required))
        PasswordOwnerStatus(busy, if (failed) stringResource(R.string.passwords_pin_renewal_failed) else null)
        if (status?.current == true) PasswordOwnerText(stringResource(R.string.passwords_pin_renewal_current))
        status?.phase?.let { phase -> PasswordOwnerText(stringResource(when (phase) {
            GpmPinRenewalPhase.PREPARED -> R.string.passwords_recoverable_vault_prepared
            GpmPinRenewalPhase.VAULT_LAUNCHED, GpmPinRenewalPhase.JOIN_LAUNCHED -> R.string.passwords_recoverable_vault_uncertain
            GpmPinRenewalPhase.VAULT_CONFIRMED -> R.string.passwords_recoverable_vault_uploaded
            GpmPinRenewalPhase.CONFIRMED -> R.string.passwords_pin_renewal_complete
            GpmPinRenewalPhase.REVOKED -> R.string.passwords_pin_renewal_revoked
        })) }
        if (!secure) return@PasswordOwnerPage
        PasswordOwnerActions {
            if (status != null && (status.id == null || status.phase == GpmPinRenewalPhase.CONFIRMED))
                CyclonInkButton(stringResource(R.string.passwords_pin_renewal_start), enabled = !busy) { onAsk(GpmPinRenewalAction.PREPARE) }
            if (status?.phase in setOf(GpmPinRenewalPhase.PREPARED, GpmPinRenewalPhase.VAULT_CONFIRMED))
                CyclonInkButton(stringResource(R.string.passwords_recoverable_vault_continue), enabled = !busy) { onAsk(GpmPinRenewalAction.CONTINUE) }
            CyclonGlassButton(stringResource(R.string.passwords_refresh), enabled = !busy, onClick = onRefresh)
            if (status?.id != null) CyclonTextAction(stringResource(R.string.passwords_recovery_enroll_close_attempt), enabled = !busy) {
                onAsk(GpmPinRenewalAction.CLOSE)
            }
        }
    }
    confirmation?.takeIf { unlocked }?.let { action ->
        PasswordOwnerConfirm(stringResource(R.string.passwords_pin_renewal_title), account, listOf(stringResource(
            if (action == GpmPinRenewalAction.CLOSE) R.string.passwords_pin_renewal_close_confirm else R.string.passwords_pin_renewal_confirm)),
            confirmEnabled = !busy && secure, onConfirm = { onConfirm(action) }, onDismiss = { onAsk(null) })
    }
}
