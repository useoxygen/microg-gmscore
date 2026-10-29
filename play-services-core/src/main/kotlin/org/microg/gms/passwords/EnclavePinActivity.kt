/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.os.Bundle
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.microg.gms.cyclon.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Private native PIN input. Neither PINs nor account proof tokens enter saved state, Intents or results. */
class EnclavePinActivity : PasswordOwnerActivity() {
    private lateinit var account: Account
    private lateinit var accountId: String
    private val allowed = AtomicBoolean(false)
    private val generation = AtomicLong()
    @Volatile private var client: EnclavePinClient? = null
    @Volatile private var cancellation: AtomicBoolean? = null
    @Volatile private var activePin: CharArray? = null
    private var unlocked by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private var status by mutableStateOf<EnclavePinStatus?>(null)
    private var pin by mutableStateOf(CharArray(0))
    private var repeated by mutableStateOf(CharArray(0))
    private var arbitrary by mutableStateOf(false)
    private var change by mutableStateOf(false)
    private var confirmation by mutableStateOf<EnclavePinAction?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        if (android.os.Build.VERSION.SDK_INT < 28) { finish(); return }
        val name = intent.getStringExtra("account"); val id = intent.getStringExtra("google_user_id")
        if (name.isNullOrBlank() || id.isNullOrBlank()) { finish(); return }
        account = Account(name, "com.google"); accountId = id
        if (!validAccount()) { finish(); return }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        render()
        authenticateOwner()
    }
    private fun render() = setContent { CyclonMaterialTheme {
        EnclavePinScreen(account.name, unlocked, hasSecureScreenLock, busy, failed, status, change, arbitrary, pin, repeated,
            validInput(), confirmation,
            onBack = { cancel(); clearInput(); finish() }, onUnlock = ::authenticateOwner,
            onRefresh = { perform(EnclavePinAction.REFRESH) }, onArbitrary = { clearInput(); arbitrary = it },
            onPin = { pin.fill('\u0000'); pin = it }, onRepeated = { repeated.fill('\u0000'); repeated = it },
            onAsk = { confirmation = it },
            onConfirm = { action ->
                confirmation = null
                val input = if (action == EnclavePinAction.SET && validInput()) pin.copyOf() else null
                clearInput(); perform(action, input)
            })
    } }
    private fun validInput() = pin.isNotEmpty() && pin.contentEquals(repeated) &&
        (arbitrary || pin.size == 6 && pin.all { it in '0'..'9' })
    private fun validAccount() = AccountManager.get(this).let {
        it.getAccountsByType("com.google").contains(account) && it.getUserData(account, "GoogleUserId") == accountId
    }
    private fun perform(action: EnclavePinAction, input: CharArray? = null) {
        try {
            requireOwner()
            if (busy || !allowed.get() || !validAccount() || !hasSecureScreenLock || action == EnclavePinAction.SET && input == null) throw VaultLockedException()
        } catch (_: Exception) { input?.fill('\u0000'); failed = true; return }
        val stopped = AtomicBoolean(false); val grant = generation.get(); val id = status?.id; val replacing = change
        if (action == EnclavePinAction.SET && status?.hasPin != replacing) { input?.fill('\u0000'); failed = true; return }
        if (action != EnclavePinAction.SET) clearInput()
        cancellation = stopped; activePin = input; busy = true; failed = false
        val verify = {
            if (stopped.get() || !allowed.get() || generation.get() != grant || !validAccount() || !hasSecureScreenLock) throw VaultLockedException()
            requireOwner()
        }
        lifecycleScope.launch {
            var reauthShown = false
            try {
                // A new token is obtained for this exact owner-selected account and used only by this call.
                val proof = if (action == EnclavePinAction.SET) {
                    reauthShown = true
                    EnclavePinReauthUi.show(this@EnclavePinActivity, account.name, accountId, verify)
                } else null
                verify()
                var problem = false
                val result = withContext(Dispatchers.IO) {
                    verify(); val current = EnclavePinClient(this@EnclavePinActivity, account, accountId, verify)
                    if (stopped.get() || cancellation !== stopped) { current.cancel(); throw VaultLockedException() }
                    client = current
                    try { when (action) {
                        EnclavePinAction.SET -> current.setPin(input ?: throw VaultLockedException(), replacing, proof ?: throw VaultLockedException())
                        EnclavePinAction.CONTINUE -> current.continueOnce(id ?: throw VaultLockedException())
                        EnclavePinAction.CLOSE -> current.close(id ?: throw VaultLockedException())
                        EnclavePinAction.REFRESH -> current.refresh()
                    } } catch (_: Exception) { problem = true; verify(); current.status() }
                }
                verify()
                if (cancellation === stopped) { status = result; result.hasPin?.let { change = it }; failed = problem }
            } catch (_: Exception) { if (allowed.get() && generation.get() == grant && cancellation === stopped) failed = true }
            finally {
                input?.fill('\u0000'); if (activePin === input) activePin = null
                if (cancellation === stopped) { busy = false; cancellation = null; client = null }
                if (reauthShown && !isFinishing && !isDestroyed) render()
            }
        }
    }
    private fun clearInput() { pin.fill('\u0000'); repeated.fill('\u0000'); pin = CharArray(0); repeated = CharArray(0) }
    private fun cancel() { cancellation?.set(true); client?.cancel(); activePin?.fill('\u0000'); activePin = null }
    override fun onOwnerAuthenticated() { generation.incrementAndGet(); allowed.set(true); unlocked = true
        if (hasSecureScreenLock) perform(EnclavePinAction.REFRESH) }
    override fun onOwnerAuthenticationFailed() { finish() }
    override fun onPause() { allowed.set(false); generation.incrementAndGet(); cancel(); clearInput(); super.onPause() }
    override fun onResume() { super.onResume(); if (unlocked) allowed.set(runCatching { requireOwner(); true }.getOrDefault(false)) }
    override fun onOwnerLocked() { allowed.set(false); generation.incrementAndGet(); cancel(); clearInput()
        unlocked = false; busy = false; status = null; confirmation = null; failed = false }
}

internal enum class EnclavePinAction { REFRESH, SET, CONTINUE, CLOSE }

/**
 * What [EnclavePinActivity] shows, from its state alone. The activity owns the PIN arrays, clears them and keeps every
 * check and request; the fields only mask and filter what the owner types, as before.
 */
@Composable
internal fun EnclavePinScreen(
    account: String, unlocked: Boolean, secure: Boolean, busy: Boolean, failed: Boolean, status: EnclavePinStatus?,
    change: Boolean, arbitrary: Boolean, pin: CharArray, repeated: CharArray, inputValid: Boolean, confirmation: EnclavePinAction?,
    onBack: () -> Unit, onUnlock: () -> Unit, onRefresh: () -> Unit, onArbitrary: (Boolean) -> Unit,
    onPin: (CharArray) -> Unit, onRepeated: (CharArray) -> Unit, onAsk: (EnclavePinAction?) -> Unit, onConfirm: (EnclavePinAction) -> Unit,
) {
    PasswordOwnerPage(stringResource(R.string.passwords_enclave_pin_title), account, onBack) {
        if (!unlocked) { PasswordOwnerLocked(secure, enabled = !busy, onUnlock = onUnlock); return@PasswordOwnerPage }
        CyclonNote(stringResource(R.string.passwords_enclave_pin_description))
        if (!secure) PasswordOwnerText(stringResource(R.string.passwords_recovery_enroll_lock_required))
        PasswordOwnerStatus(busy, if (failed) stringResource(R.string.passwords_enclave_pin_failed) else null)
        status?.phase?.let { phase -> PasswordOwnerText(stringResource(when (phase) {
            EnclavePinPhase.CONFIRMED -> R.string.passwords_enclave_pin_complete
            EnclavePinPhase.REVOKED -> R.string.passwords_pin_renewal_revoked
            EnclavePinPhase.REGISTER_PREPARED, EnclavePinPhase.WRAP_PREPARED,
            EnclavePinPhase.PIN_PREPARED, EnclavePinPhase.VAULT_PREPARED -> R.string.passwords_recoverable_vault_prepared
            EnclavePinPhase.VAULT_CONFIRMED -> R.string.passwords_recoverable_vault_uploaded
            else -> R.string.passwords_recoverable_vault_uncertain
        })) }
        if (!secure) return@PasswordOwnerPage
        if (status != null && status.id == null && status.hasPin != null) {
            CyclonSectionLabel(stringResource(if (change) R.string.passwords_enclave_pin_change_action else R.string.passwords_enclave_pin_create_action))
            if (change) CyclonNote(stringResource(R.string.passwords_enclave_pin_change))
            CyclonSwitchRow(stringResource(R.string.passwords_enclave_pin_arbitrary), null, arbitrary, onChange = onArbitrary, enabled = !busy)
            EnclavePinField(pin, R.string.passwords_enclave_pin_new, busy, arbitrary, onPin)
            EnclavePinField(repeated, R.string.passwords_enclave_pin_repeat, busy, arbitrary, onRepeated)
        }
        PasswordOwnerActions {
            if (status != null && status.id == null && status.hasPin != null)
                CyclonInkButton(stringResource(if (change) R.string.passwords_enclave_pin_change_action else R.string.passwords_enclave_pin_create_action),
                    enabled = !busy && inputValid) { onAsk(EnclavePinAction.SET) }
            if (status?.phase in setOf(EnclavePinPhase.VAULT_PREPARED, EnclavePinPhase.VAULT_CONFIRMED))
                CyclonInkButton(stringResource(R.string.passwords_recoverable_vault_continue), enabled = !busy) { onAsk(EnclavePinAction.CONTINUE) }
            CyclonLineButton(stringResource(R.string.passwords_refresh), enabled = !busy, onClick = onRefresh)
            if (status?.id != null) CyclonTextAction(stringResource(R.string.passwords_recovery_enroll_close_attempt), enabled = !busy) {
                onAsk(EnclavePinAction.CLOSE)
            }
        }
    }
    confirmation?.takeIf { unlocked }?.let { action ->
        PasswordOwnerConfirm(stringResource(R.string.passwords_enclave_pin_title), account, listOf(stringResource(
            if (action == EnclavePinAction.CLOSE) R.string.passwords_pin_renewal_close_confirm else R.string.passwords_enclave_pin_confirm)),
            confirmEnabled = !busy && secure, onConfirm = { onConfirm(action) }, onDismiss = { onAsk(null) })
    }
}

/** A masked PIN field: digits only and at most six unless [arbitrary], never more than 1024 characters. */
@Composable
private fun EnclavePinField(value: CharArray, label: Int, busy: Boolean, arbitrary: Boolean, update: (CharArray) -> Unit) {
    OutlinedTextField(value = String(value), onValueChange = { text ->
        if (!busy && text.length <= 1024 && (arbitrary || text.length <= 6 && text.all { it in '0'..'9' })) update(text.toCharArray())
    }, enabled = !busy, singleLine = true, label = { Text(stringResource(label)) },
        visualTransformation = PasswordVisualTransformation(), colors = cyclonFieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = if (arbitrary) KeyboardType.Password else KeyboardType.NumberPassword),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().semantics { password() })
}
