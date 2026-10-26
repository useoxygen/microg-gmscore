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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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
import org.microg.gms.cyclon.CyclonMaterialTheme
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
    private var confirmation by mutableStateOf<Action?>(null)
    private enum class Action { REFRESH, RESTORE, FINISH, CLOSE }

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
        setContent { CyclonMaterialTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.passwords_restore_title), style = MaterialTheme.typography.headlineSmall)
                if (!unlocked) Button(onClick = ::authenticateOwner, enabled = !busy) { Text(stringResource(R.string.passwords_unlock)) }
                else {
                    Text(account.name)
                    Text(stringResource(if (domain == "chromesync") R.string.passwords_domain_passwords else R.string.passwords_domain_passkeys))
                    Text(stringResource(R.string.passwords_restore_description))
                    if (!hasSecureScreenLock) Text(stringResource(R.string.passwords_recovery_enroll_lock_required))
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (failed) Text(stringResource(R.string.passwords_restore_failed))
                    status?.phase?.let { phase -> Text(stringResource(when (phase) {
                        RecoveryRestorePhase.RECOVERED -> R.string.passwords_restore_ready
                        RecoveryRestorePhase.CONFIRMED -> R.string.passwords_restore_complete
                        RecoveryRestorePhase.REFUSED -> R.string.passwords_restore_refused
                        RecoveryRestorePhase.REVOKED -> R.string.passwords_restore_revoked
                        else -> R.string.passwords_restore_pending
                    })) }
                    if (hasSecureScreenLock) {
                        OutlinedButton(onClick = { perform(Action.REFRESH) }, enabled = !busy) { Text(stringResource(R.string.passwords_refresh)) }
                        if (status != null && status?.attemptId == null) {
                            if (choices.isEmpty()) Text(stringResource(R.string.passwords_restore_empty))
                            choices.forEachIndexed { index, choice ->
                                val chosen = selected?.let { MessageDigest.isEqual(it.digest, choice.digest) } == true
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    RadioButton(chosen, onClick = { clearSecret(); selected = choice }, enabled = !busy)
                                    Column {
                                        Text(stringResource(R.string.passwords_restore_backup, index + 1))
                                        Text(stringResource(if (choice.vaultType == 3) R.string.passwords_restore_gpm_pin else when (choice.format) {
                                            1 -> R.string.passwords_restore_pin; 3 -> R.string.passwords_restore_pattern
                                            else -> R.string.passwords_restore_password
                                        }))
                                        choice.remainingAttempts?.let { Text(stringResource(R.string.passwords_restore_attempts, it)) }
                                    }
                                }
                            }
                            selected?.let { choice ->
                                if (choice.format == 3) AndroidView(factory = { context ->
                                    RecoveryPatternView(context) { value -> secret.fill('\u0000'); secret = value }.also { patternView = it }
                                }, update = { it.isEnabled = !busy }, modifier = Modifier.fillMaxWidth().aspectRatio(1f).semantics { password() })
                                else OutlinedTextField(value = String(secret), onValueChange = { value ->
                                    if (!busy && value.length <= 1024 && (choice.format != 1 || value.all { it in '0'..'9' }) &&
                                        (choice.vaultType != 3 || choice.format != 1 || value.length <= 6) &&
                                        (choice.vaultType == 3 || value.all { it.code in 32..127 })) {
                                        clearSecret(); secret = value.toCharArray()
                                    }
                                }, enabled = !busy, singleLine = true,
                                    label = { Text(stringResource(if (choice.vaultType == 3) R.string.passwords_restore_gpm_pin else
                                        if (choice.format == 1) R.string.passwords_restore_pin else R.string.passwords_restore_password)) },
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = if (choice.format == 1) KeyboardType.NumberPassword else KeyboardType.Password),
                                    modifier = Modifier.fillMaxWidth().semantics { password() })
                                Button(onClick = { confirmation = Action.RESTORE }, enabled = !busy && when {
                                    choice.format == 3 -> secret.size >= 4
                                    choice.vaultType == 3 && choice.format == 1 -> secret.size == 6
                                    else -> secret.isNotEmpty()
                                }) {
                                    Text(stringResource(R.string.passwords_restore_open))
                                }
                            }
                        }
                        if (status?.canFinish == true && matchesScope(status?.requestScopeDigest)) Button(onClick = { confirmation = Action.FINISH }, enabled = !busy) {
                            Text(stringResource(R.string.passwords_restore_finish))
                        }
                        if (status?.attemptId != null) TextButton(onClick = { confirmation = Action.CLOSE }, enabled = !busy) {
                            Text(stringResource(R.string.passwords_recovery_enroll_close_attempt))
                        }
                    }
                }
                TextButton(onClick = { cancel(); clearSecret(); finish() }) { Text(stringResource(android.R.string.cancel)) }
            }
            confirmation?.takeIf { unlocked }?.let { action -> AlertDialog(onDismissRequest = { confirmation = null },
                title = { Text(stringResource(R.string.passwords_restore_title)) },
                text = { PasswordDialogProtection(); Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(account.name)
                    Text(stringResource(when (action) {
                        Action.RESTORE -> R.string.passwords_restore_confirm
                        Action.CLOSE -> R.string.passwords_restore_close
                        else -> R.string.passwords_restore_finish_confirm
                    }))
                    if (action == Action.RESTORE) selected?.remainingAttempts?.let { Text(stringResource(R.string.passwords_restore_attempts, it)) }
                } }, confirmButton = { TextButton(onClick = {
                    confirmation = null
                    val owned = if (action == Action.RESTORE) secret.copyOf() else null
                    clearSecret(); perform(action, owned)
                }, enabled = !busy && hasSecureScreenLock) { Text(stringResource(android.R.string.ok)) } },
                dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(android.R.string.cancel)) } }) }
        } } }
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
    private fun perform(action: Action, input: CharArray? = null) {
        try { requireOwner(); if (!allowed.get() || !validAccount() || !hasSecureScreenLock || busy) throw VaultLockedException() }
        catch (_: Exception) { input?.fill('\u0000'); failed = true; return }
        val stopped = AtomicBoolean(false); val grant = generation.get(); val attempt = status?.attemptId
        val digest = selected?.digest?.copyOf()
        if (action == Action.RESTORE && !matchesScope(selected?.requestScopeDigest) ||
            action == Action.FINISH && !matchesScope(status?.requestScopeDigest)) {
            input?.fill('\u0000'); failed = true; return
        }
        if (action != Action.RESTORE) clearSecret()
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
                        Action.RESTORE -> current.restore(digest ?: throw VaultLockedException(), input ?: throw VaultLockedException())
                        Action.FINISH -> current.finishPending(attempt ?: throw VaultLockedException(), requestScope?.copyOf())
                        Action.CLOSE -> { current.close(attempt ?: throw VaultLockedException()); current.status() }
                        Action.REFRESH -> current.status()
                    } } catch (_: Exception) { problem = true; verify(); current.status() }
                    verify()
                    val available = if (loaded.attemptId == null) current.list().filter { it.format in 1..3 && matchesScope(it.requestScopeDigest) } else emptyList()
                    loaded to available
                }
                verify()
                if (cancellation === stopped) {
                    status = result.first; choices = result.second; selected = null; failed = problem
                    if (!problem && action in setOf(Action.RESTORE, Action.FINISH) && result.first.acknowledges(requestScope) &&
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
        if (hasSecureScreenLock) perform(Action.REFRESH) }
    override fun onOwnerAuthenticationFailed() { finish() }
    override fun onPause() { allowed.set(false); generation.incrementAndGet(); cancel(); clearSecret(); super.onPause() }
    override fun onResume() { super.onResume(); if (unlocked) allowed.set(runCatching { requireOwner(); true }.getOrDefault(false)) }
    override fun onOwnerLocked() { allowed.set(false); generation.incrementAndGet(); cancel(); clearSecret()
        unlocked = false; busy = false; choices = emptyList(); selected = null; status = null; confirmation = null; failed = false }
}
