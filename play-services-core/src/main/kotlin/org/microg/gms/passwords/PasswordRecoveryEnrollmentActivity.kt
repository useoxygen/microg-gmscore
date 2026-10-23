/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Intent
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
import org.microg.gms.auth.folsom.ui.GenericActivity
import org.microg.gms.cyclon.CyclonMaterialTheme
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Protected owner consent for this phone and one explicitly selected Google account. */
class PasswordRecoveryEnrollmentActivity : PasswordOwnerActivity() {
    private lateinit var account: Account
    private lateinit var accountId: String
    private val allowed = AtomicBoolean(false)
    private val generation = AtomicLong()
    @Volatile private var client: PasswordRecoveryEnrollmentClient? = null
    @Volatile private var activeCancellation: AtomicBoolean? = null
    private var parent: String? = null
    private var unlocked by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var status by mutableStateOf<RecoveryEnrollmentStatus?>(null)
    private var message by mutableStateOf<Int?>(null)
    private var confirmation by mutableStateOf<RecoveryEnrollmentAction?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT < 26) { finish(); return }
        val name = intent.getStringExtra("account")
        val id = intent.getStringExtra("google_user_id")
        if (name.isNullOrBlank() || id.isNullOrBlank()) { finish(); return }
        account = Account(name, "com.google"); accountId = id
        if (!validAccount()) { finish(); return }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setContent { CyclonMaterialTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.passwords_recovery_enroll_phone), style = MaterialTheme.typography.headlineSmall)
                if (!unlocked) Button(onClick = ::authenticateOwner, enabled = !busy) {
                    Text(stringResource(R.string.passwords_unlock))
                } else {
                    Text(account.name)
                    Text(stringResource(R.string.passwords_recovery_enroll_description))
                    if (!hasSecureScreenLock) Text(stringResource(R.string.passwords_recovery_enroll_lock_required))
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    message?.let { Text(stringResource(it)) }
                    status?.phase?.let { Text(stringResource(phaseText(it))) }
                    if (hasSecureScreenLock) {
                        OutlinedButton(onClick = { perform(RecoveryEnrollmentAction.REFRESH) }, enabled = !busy) {
                            Text(stringResource(R.string.passwords_refresh))
                        }
                        if (status != null && status?.attemptId == null) Button(onClick = {
                            confirmation = RecoveryEnrollmentAction.ENROLL
                        }, enabled = !busy) { Text(stringResource(R.string.passwords_recovery_enroll_allow)) }
                        if (status?.attemptId != null && status?.phase != EnrollmentPhase.ENROLLED) {
                            TextButton(onClick = { confirmation = RecoveryEnrollmentAction.RETRY }, enabled = !busy) {
                                Text(stringResource(R.string.passwords_retry_owner))
                            }
                            TextButton(onClick = { confirmation = RecoveryEnrollmentAction.CLOSE }, enabled = !busy) {
                                Text(stringResource(R.string.passwords_recovery_enroll_close_attempt))
                            }
                        }
                        TextButton(onClick = ::recoverWithGoogle, enabled = !busy) {
                            Text(stringResource(R.string.passwords_recovery_enroll_recover))
                        }
                    }
                }
                TextButton(onClick = { cancel(); finish() }) { Text(stringResource(android.R.string.cancel)) }
            }
            confirmation?.takeIf { unlocked }?.let { action ->
                AlertDialog(onDismissRequest = { confirmation = null },
                    title = { Text(stringResource(R.string.passwords_recovery_enroll_phone)) },
                    text = { PasswordDialogProtection(); Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(account.name)
                        Text(stringResource(when (action) {
                            RecoveryEnrollmentAction.ENROLL -> R.string.passwords_recovery_enroll_confirm
                            RecoveryEnrollmentAction.RETRY -> R.string.passwords_recovery_enroll_retry_warning
                            else -> R.string.passwords_recovery_enroll_close_warning
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
        val accounts = AccountManager.get(this)
        return accounts.getAccountsByType("com.google").contains(account) && accounts.getUserData(account, "GoogleUserId") == accountId
    }
    private fun perform(action: RecoveryEnrollmentAction) {
        try { requireOwner(); if (!validAccount() || !hasSecureScreenLock) throw VaultLockedException() }
        catch (_: Exception) { message = R.string.passwords_recovery_enroll_lock_required; return }
        if (busy) return
        busy = true; message = null
        val grant = generation.get()
        val stopped = AtomicBoolean(false)
        activeCancellation = stopped
        val selectedParent = parent
        val attemptId = status?.attemptId
        val verify = {
            if (stopped.get() || !allowed.get() || generation.get() != grant || !validAccount() || !hasSecureScreenLock)
                throw VaultLockedException()
            requireOwner()
        }
        lifecycleScope.launch {
            try {
                var problem: Exception? = null
                val loaded = withContext(Dispatchers.IO) {
                    verify()
                    // Both repository construction and Folsom locking can wait for another process.
                    val current = PasswordRecoveryEnrollmentClient(this@PasswordRecoveryEnrollmentActivity,
                        account, accountId, selectedParent, verify)
                    if (stopped.get() || activeCancellation !== stopped) { current.cancel(); throw VaultLockedException() }
                    client = current
                    try { current.perform(action, attemptId) }
                    catch (failure: Exception) { problem = failure; verify(); current.status() }
                }
                verify()
                if (activeCancellation === stopped) {
                    parent = loaded.parent; status = loaded
                    message = problem?.let { if (it is VaultResetException) R.string.passwords_recovery_enroll_changed
                        else R.string.passwords_recovery_enroll_failed }
                }
            } catch (_: Exception) {
                if (allowed.get() && generation.get() == grant && activeCancellation === stopped)
                    message = R.string.passwords_recovery_enroll_failed
            } finally {
                if (activeCancellation === stopped) { busy = false; client = null; activeCancellation = null }
            }
        }
    }
    private fun recoverWithGoogle() {
        try {
            requireOwner()
            if (!allowed.get() || !validAccount() || !hasSecureScreenLock || busy) throw VaultLockedException()
            startActivity(Intent(this, GenericActivity::class.java)
                .putExtra(GenericActivity.EXTRA_ACCOUNT_NAME, account.name)
                .putExtra(GenericActivity.EXTRA_ACCOUNT_ID, accountId)
                .putExtra(GenericActivity.EXTRA_SECURITY_DOMAIN, "chromesync")
                .putExtra(GenericActivity.EXTRA_OPERATION, 1)
                .putExtra(GenericActivity.EXTRA_SESSION_ID, UUID.randomUUID().toString())
                .putExtra(GenericActivity.EXTRA_PASSWORD_OWNER_RECOVERY, true)
                .putExtra(GenericActivity.EXTRA_OFFER_RESET, false))
        } catch (_: Exception) { message = R.string.passwords_recovery_enroll_failed }
    }
    private fun phaseText(phase: EnrollmentPhase): Int = when (phase) {
        EnrollmentPhase.ENROLLED -> R.string.passwords_recovery_enroll_enrolled
        EnrollmentPhase.PREPARED -> R.string.passwords_recovery_enroll_prepared
        EnrollmentPhase.LAUNCHED, EnrollmentPhase.INDETERMINATE -> R.string.passwords_recovery_enroll_uncertain
        EnrollmentPhase.REFUSED -> R.string.passwords_recovery_enroll_refused
        EnrollmentPhase.REVOKED -> R.string.passwords_recovery_enroll_revoked
    }
    private fun cancel() { activeCancellation?.set(true); client?.cancel() }
    override fun onOwnerAuthenticated() {
        generation.incrementAndGet(); allowed.set(true); unlocked = true
        if (hasSecureScreenLock) perform(RecoveryEnrollmentAction.REFRESH)
    }
    override fun onOwnerAuthenticationFailed() { finish() }
    override fun onPause() { allowed.set(false); generation.incrementAndGet(); cancel(); super.onPause() }
    override fun onResume() {
        super.onResume()
        if (unlocked) allowed.set(runCatching { requireOwner(); true }.getOrDefault(false))
    }
    override fun onOwnerLocked() {
        allowed.set(false); generation.incrementAndGet(); cancel()
        client = null; activeCancellation = null; busy = false; unlocked = false
        status = null; message = null; confirmation = null
    }
}
