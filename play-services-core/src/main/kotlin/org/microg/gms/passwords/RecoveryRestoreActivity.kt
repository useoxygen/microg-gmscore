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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.microg.gms.cyclon.*
import org.microg.gms.auth.folsom.FolsomAndroidAdmission
import org.microg.gms.auth.folsom.FolsomCallerAdmission
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Native-only screen-lock and GPM PIN entry. No input, hash, recovered key or result enters an Intent. */
class RecoveryRestoreActivity : PasswordOwnerActivity() {
    private lateinit var account: Account
    private lateinit var accountId: String
    private lateinit var domain: String
    private var parent: String? = null
    private var requestScope: ByteArray? = null
    private val allowed = AtomicBoolean(false)
    private val generation = AtomicLong()
    @Volatile private var client: RecoveryRestoreClient? = null
    @Volatile private var cancellation: AtomicBoolean? = null
    @Volatile private var activeSecret: CharArray? = null
    private var unlocked by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private var choices by mutableStateOf<List<RecoveryRestoreChoice>>(emptyList())
    private var selected by mutableStateOf<RecoveryRestoreChoice?>(null)
    private var secret by mutableStateOf(CharArray(0))
    private var patternView: RecoveryPatternView? = null
    private var status by mutableStateOf<RecoveryRestoreStatus?>(null)
    private var confirmation by mutableStateOf<RecoveryRestoreAction?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        if (android.os.Build.VERSION.SDK_INT < 28) { finish(); return }
        val name = intent.getStringExtra("account"); val id = intent.getStringExtra("google_user_id")
        val requestedDomain = intent.getStringExtra("domain")
        if (name.isNullOrBlank() || id.isNullOrBlank() || requestedDomain !in setOf("chromesync", "hw_protected")) { finish(); return }
        account = Account(name, "com.google"); accountId = id; domain = requestedDomain!!
        parent = intent.getStringExtra("password_parent")
        requestScope = intent.getByteArrayExtra("restore_scope_digest")?.copyOf()
        if (requestScope?.size?.let { it != 32 } == true ||
            intent.getBooleanExtra("lifecycle_return_on_confirmed", false) &&
            (requestScope == null || !intent.hasExtra("lifecycle_caller"))) { finish(); return }
        if (!validAccount()) { finish(); return }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setContent { CyclonMaterialTheme(glass = true) {
            RecoveryRestoreScreen(account.name, domain, unlocked, hasSecureScreenLock, busy, failed, status,
                status?.canFinish == true && matchesScope(status?.requestScopeDigest), choices, selected, secret, confirmation,
                onBack = { cancel(); clearSecret(); finish() }, onUnlock = ::authenticateOwner,
                onRefresh = { perform(RecoveryRestoreAction.REFRESH) }, onSelect = { choice -> clearSecret(); selected = choice },
                onPatternView = { patternView = it }, onPattern = { value -> secret.fill('\u0000'); secret = value },
                onSecret = { value -> clearSecret(); secret = value }, onAsk = { confirmation = it },
                onConfirm = { action ->
                    confirmation = null
                    val owned = if (action == RecoveryRestoreAction.RESTORE) secret.copyOf() else null
                    clearSecret(); perform(action, owned)
                })
        } }
        authenticateOwner()
    }
    private fun validAccount(): Boolean = AccountManager.get(this).let {
        if (!it.getAccountsByType("com.google").contains(account) || it.getUserData(account, "GoogleUserId") != accountId) return@let false
        try {
            intent.getStringExtra("lifecycle_caller")?.let { caller ->
                val identity = FolsomAndroidAdmission.identity(this, caller) ?: throw VaultLockedException()
                if (identity.uid != intent.getIntExtra("lifecycle_uid", -1) || identity.certificate != intent.getStringExtra("lifecycle_signer") ||
                    !FolsomCallerAdmission.allowed(identity, domain, Process.myUid())) throw VaultLockedException()
                val now = SystemClock.elapsedRealtime(); val expires = intent.getLongExtra("lifecycle_expires", -1)
                if (expires < now || expires - now > 120_000 || intent.getIntExtra("lifecycle_boot", -1) !=
                    Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -2)) throw VaultLockedException()
            }
            true
        } catch (_: Exception) { false }
    }
    private fun matchesScope(actual: ByteArray?): Boolean = requestScope?.let { expected ->
        actual != null && MessageDigest.isEqual(expected, actual)
    } ?: true
    private fun perform(action: RecoveryRestoreAction, input: CharArray? = null) {
        try { requireOwner(); if (!allowed.get() || !validAccount() || !hasSecureScreenLock || busy) throw VaultLockedException() }
        catch (_: Exception) { input?.fill('\u0000'); failed = true; return }
        val stopped = AtomicBoolean(false); val grant = generation.get(); val attempt = status?.attemptId
        val digest = selected?.digest?.copyOf()
        if (action == RecoveryRestoreAction.RESTORE && !matchesScope(selected?.requestScopeDigest) ||
            action == RecoveryRestoreAction.FINISH && !matchesScope(status?.requestScopeDigest)) {
            input?.fill('\u0000'); failed = true; return
        }
        if (action != RecoveryRestoreAction.RESTORE) clearSecret()
        cancellation = stopped; activeSecret = input; busy = true; failed = false
        val verify = {
            if (stopped.get() || !allowed.get() || generation.get() != grant || !validAccount() || !hasSecureScreenLock) throw VaultLockedException()
            requireOwner()
        }
        lifecycleScope.launch {
            try {
                var problem = false
                val result = withContext(Dispatchers.IO) {
                    verify()
                    val current = RecoveryRestoreClient(this@RecoveryRestoreActivity, account, accountId, domain, parent, verify)
                    if (stopped.get() || cancellation !== stopped) { current.cancel(); throw VaultLockedException() }
                    client = current
                    val loaded = try { when (action) {
                        RecoveryRestoreAction.RESTORE -> current.restore(digest ?: throw VaultLockedException(), input ?: throw VaultLockedException())
                        RecoveryRestoreAction.FINISH -> current.finishPending(attempt ?: throw VaultLockedException(), requestScope?.copyOf())
                        RecoveryRestoreAction.CLOSE -> { current.close(attempt ?: throw VaultLockedException()); current.status() }
                        RecoveryRestoreAction.REFRESH -> current.status()
                    } } catch (_: Exception) { problem = true; verify(); current.status() }
                    verify()
                    val available = if (loaded.attemptId == null) current.list().filter { it.format in 1..3 && matchesScope(it.requestScopeDigest) } else emptyList()
                    loaded to available
                }
                verify()
                if (cancellation === stopped) {
                    status = result.first; choices = result.second; selected = null; failed = problem
                    if (!problem && action in setOf(RecoveryRestoreAction.RESTORE, RecoveryRestoreAction.FINISH) && result.first.acknowledges(requestScope) &&
                        intent.getBooleanExtra("lifecycle_return_on_confirmed", false)) {
                        verify(); setResult(RESULT_OK); finish()
                    }
                }
            } catch (_: Exception) { if (allowed.get() && generation.get() == grant && cancellation === stopped) failed = true }
            finally {
                input?.fill('\u0000')
                if (activeSecret === input) activeSecret = null
                if (cancellation === stopped) { busy = false; client = null; cancellation = null }
            }
        }
    }
    private fun clearSecret() { secret.fill('\u0000'); secret = CharArray(0); patternView?.clear() }
    private fun cancel() { cancellation?.set(true); client?.cancel(); activeSecret?.fill('\u0000'); activeSecret = null }
    override fun onOwnerAuthenticated() { generation.incrementAndGet(); allowed.set(true); unlocked = true
        if (hasSecureScreenLock) perform(RecoveryRestoreAction.REFRESH) }
    override fun onOwnerAuthenticationFailed() { finish() }
    override fun onPause() { allowed.set(false); generation.incrementAndGet(); cancel(); clearSecret(); super.onPause() }
    override fun onResume() { super.onResume(); if (unlocked) allowed.set(runCatching { requireOwner(); true }.getOrDefault(false)) }
    override fun onOwnerLocked() { allowed.set(false); generation.incrementAndGet(); cancel(); clearSecret()
        unlocked = false; busy = false; choices = emptyList(); selected = null; status = null; confirmation = null; failed = false }
}

internal enum class RecoveryRestoreAction { REFRESH, RESTORE, FINISH, CLOSE }

/**
 * What [RecoveryRestoreActivity] shows, from its state alone. The activity owns and clears the secret, checks every
 * scope and makes every request; the field and the pattern only mask and filter what the owner enters, as before.
 */
@Composable
internal fun RecoveryRestoreScreen(
    account: String, domain: String, unlocked: Boolean, secure: Boolean, busy: Boolean, failed: Boolean,
    status: RecoveryRestoreStatus?, canFinish: Boolean, choices: List<RecoveryRestoreChoice>, selected: RecoveryRestoreChoice?,
    secret: CharArray, confirmation: RecoveryRestoreAction?, onBack: () -> Unit, onUnlock: () -> Unit, onRefresh: () -> Unit,
    onSelect: (RecoveryRestoreChoice) -> Unit, onPatternView: (RecoveryPatternView) -> Unit, onPattern: (CharArray) -> Unit,
    onSecret: (CharArray) -> Unit, onAsk: (RecoveryRestoreAction?) -> Unit, onConfirm: (RecoveryRestoreAction) -> Unit,
) {
    PasswordOwnerPage(stringResource(if (domain == "chromesync") R.string.passwords_restore_passwords else R.string.passwords_restore_passkeys),
        account, onBack) {
        if (!unlocked) { PasswordOwnerLocked(secure, enabled = !busy, onUnlock = onUnlock); return@PasswordOwnerPage }
        CyclonNote(stringResource(R.string.passwords_restore_description))
        if (!secure) PasswordOwnerText(stringResource(R.string.passwords_recovery_enroll_lock_required))
        PasswordOwnerStatus(busy, if (failed) stringResource(R.string.passwords_restore_failed) else null)
        status?.phase?.let { phase -> PasswordOwnerText(stringResource(when (phase) {
            RecoveryRestorePhase.RECOVERED -> R.string.passwords_restore_ready
            RecoveryRestorePhase.CONFIRMED -> R.string.passwords_restore_complete
            RecoveryRestorePhase.REFUSED -> R.string.passwords_restore_refused
            RecoveryRestorePhase.REVOKED -> R.string.passwords_restore_revoked
            else -> R.string.passwords_restore_pending
        })) }
        if (!secure) return@PasswordOwnerPage
        if (status != null && status.attemptId == null) {
            CyclonSectionLabel(stringResource(R.string.passwords_restore_backups))
            if (choices.isEmpty()) CyclonNote(stringResource(R.string.passwords_restore_empty))
            else CyclonGroup {
                Column(Modifier.selectableGroup()) {
                    choices.forEachIndexed { index, choice ->
                        val chosen = selected?.let { MessageDigest.isEqual(it.digest, choice.digest) } == true
                        CyclonChoiceRow(stringResource(R.string.passwords_restore_backup, index + 1),
                            listOfNotNull(stringResource(restoreSecretLabel(choice, pattern = true)),
                                choice.remainingAttempts?.let { stringResource(R.string.passwords_restore_attempts, it) }).joinToString("\n"),
                            chosen, enabled = !busy) { onSelect(choice) }
                    }
                }
            }
            selected?.let { choice ->
                if (choice.format == 3) {
                    CyclonNote(stringResource(R.string.passwords_restore_pattern_description))
                    AndroidView(factory = { context -> RecoveryPatternView(context, onPattern).also(onPatternView) },
                        update = { it.isEnabled = !busy },
                        modifier = Modifier.padding(horizontal = CyclonTextInset).fillMaxWidth().aspectRatio(1f).semantics { password() })
                } else OutlinedTextField(value = String(secret), onValueChange = { value ->
                    if (!busy && value.length <= 1024 && (choice.format != 1 || value.all { it in '0'..'9' }) &&
                        (choice.vaultType != 3 || choice.format != 1 || value.length <= 6) &&
                        (choice.vaultType == 3 || value.all { it.code in 32..127 })) {
                        onSecret(value.toCharArray())
                    }
                }, enabled = !busy, singleLine = true, label = { Text(stringResource(restoreSecretLabel(choice, pattern = false))) },
                    visualTransformation = PasswordVisualTransformation(), colors = cyclonFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (choice.format == 1) KeyboardType.NumberPassword else KeyboardType.Password),
                    modifier = Modifier.padding(horizontal = CyclonFieldInset, vertical = 6.dp).fillMaxWidth().semantics { password() })
            }
        }
        PasswordOwnerActions {
            if (status != null && status.attemptId == null) selected?.let { choice ->
                CyclonInkButton(stringResource(R.string.passwords_restore_open), enabled = !busy && when {
                    choice.format == 3 -> secret.size >= 4
                    choice.vaultType == 3 && choice.format == 1 -> secret.size == 6
                    else -> secret.isNotEmpty()
                }) { onAsk(RecoveryRestoreAction.RESTORE) }
            }
            if (canFinish) CyclonInkButton(stringResource(R.string.passwords_restore_finish), enabled = !busy) { onAsk(RecoveryRestoreAction.FINISH) }
            CyclonGlassButton(stringResource(R.string.passwords_refresh), enabled = !busy, onClick = onRefresh)
            if (status?.attemptId != null) CyclonTextAction(stringResource(R.string.passwords_recovery_enroll_close_attempt), enabled = !busy) {
                onAsk(RecoveryRestoreAction.CLOSE)
            }
        }
    }
    confirmation?.takeIf { unlocked }?.let { action ->
        PasswordOwnerConfirm(stringResource(R.string.passwords_restore_title), account, listOfNotNull(
            stringResource(when (action) {
                RecoveryRestoreAction.RESTORE -> R.string.passwords_restore_confirm
                RecoveryRestoreAction.CLOSE -> R.string.passwords_restore_close
                else -> R.string.passwords_restore_finish_confirm
            }),
            if (action == RecoveryRestoreAction.RESTORE) selected?.remainingAttempts?.let { stringResource(R.string.passwords_restore_attempts, it) } else null,
        ), confirmEnabled = !busy && secure, onConfirm = { onConfirm(action) }, onDismiss = { onAsk(null) })
    }
}

/** What the owner enters for [choice]: a GPM PIN, or the backup device's PIN, password or (in the list) pattern. */
private fun restoreSecretLabel(choice: RecoveryRestoreChoice, pattern: Boolean): Int = when {
    choice.vaultType == 3 -> R.string.passwords_restore_gpm_pin
    choice.format == 1 -> R.string.passwords_restore_pin
    choice.format == 3 && pattern -> R.string.passwords_restore_pattern
    else -> R.string.passwords_restore_password
}
