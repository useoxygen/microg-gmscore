/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom.ui

import android.accounts.Account
import android.accounts.AccountManager
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
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
import org.microg.gms.auth.folsom.*
import org.microg.gms.cyclon.CyclonMaterialTheme
import org.microg.gms.passwords.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Protected explicit resolution of one finite consent-sync attempt. No recovery keys enter the UI or result. */
class FolsomConsentActivity : PasswordOwnerActivity() {
    private lateinit var account: Account
    private lateinit var accountId: String
    private lateinit var domain: String
    private lateinit var attemptId: String
    private var requestedValue = false
    private val allowed = AtomicBoolean(false)
    private val generation = AtomicLong()
    private var unlocked by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private var status by mutableStateOf<FolsomConsentOwnerStatus?>(null)
    private var review by mutableStateOf<ByteArray?>(null)
    private var confirmation by mutableStateOf<Action?>(null)
    private enum class Action { REFRESH, REVIEW, RETRY, CLOSE }
    @Volatile private var cancellation: AtomicBoolean? = null
    @Volatile private var client: FolsomConsentClient? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setResult(RESULT_CANCELED)
        val name = intent.getStringExtra("account"); val gaia = intent.getStringExtra("google_user_id")
        val selected = intent.getStringExtra("domain"); val id = intent.getStringExtra("consent_expected_id")
        if (android.os.Build.VERSION.SDK_INT < 28 || name.isNullOrBlank() || gaia.isNullOrBlank() ||
            selected !in setOf("chromesync", "hw_protected") || id.isNullOrBlank() || id.length > 128 || !intent.hasExtra("consent_expected_value")) { finish(); return }
        account = Account(name, "com.google"); accountId = gaia; domain = selected!!; attemptId = id
        requestedValue = intent.getBooleanExtra("consent_expected_value", false)
        if (!validAccount()) { finish(); return }
        setContent { CyclonMaterialTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.passwords_consent_resolution_title), style = MaterialTheme.typography.headlineSmall)
                if (!unlocked) Button(onClick = ::authenticateOwner, enabled = !busy) { Text(stringResource(R.string.passwords_unlock)) }
                else {
                    Text(account.name)
                    Text(stringResource(if (domain == "chromesync") R.string.passwords_domain_passwords else R.string.passwords_domain_passkeys))
                    Text(stringResource(if (requestedValue) R.string.passwords_consent_enable else R.string.passwords_consent_disable))
                    Text(stringResource(if (status?.phase?.name == "PREPARED") R.string.passwords_consent_initial_description else R.string.passwords_consent_resolution_description))
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (failed) Text(stringResource(R.string.passwords_consent_resolution_failed))
                    if (!hasSecureScreenLock) Text(stringResource(R.string.passwords_recovery_enroll_lock_required))
                    if (hasSecureScreenLock) {
                        OutlinedButton(onClick = { perform(Action.REFRESH) }, enabled = !busy) { Text(stringResource(R.string.passwords_refresh)) }
                        if (matches(status) && status?.phase?.name != "COMMITTED") {
                            OutlinedButton(onClick = { perform(Action.REVIEW) }, enabled = !busy) { Text(stringResource(R.string.passwords_consent_review)) }
                            if (review != null) Button(onClick = { confirmation = Action.RETRY }, enabled = !busy) {
                                Text(stringResource(R.string.passwords_consent_retry))
                            }
                            TextButton(onClick = { confirmation = Action.CLOSE }, enabled = !busy) {
                                Text(stringResource(R.string.passwords_recovery_enroll_close_attempt))
                            }
                        }
                    }
                }
                TextButton(onClick = { cancel(); finish() }) { Text(stringResource(android.R.string.cancel)) }
            }
            confirmation?.takeIf { unlocked }?.let { action -> AlertDialog(onDismissRequest = { confirmation = null },
                title = { Text(stringResource(R.string.passwords_consent_resolution_title)) },
                text = { PasswordDialogProtection(); Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(account.name); Text(stringResource(if (action == Action.CLOSE) R.string.passwords_consent_close_confirm else R.string.passwords_consent_retry_confirm))
                } }, confirmButton = { TextButton(onClick = { confirmation = null; perform(action) }, enabled = !busy && hasSecureScreenLock) {
                    Text(stringResource(android.R.string.ok))
                } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(android.R.string.cancel)) } }) }
        } } }
        authenticateOwner()
    }
    private fun validAccount(): Boolean = try {
        val manager = AccountManager.get(this)
        if (!manager.getAccountsByType("com.google").contains(account) || manager.getUserData(account, "GoogleUserId") != accountId) throw VaultLockedException()
        intent.getStringExtra("lifecycle_caller")?.let { caller ->
            val identity = FolsomAndroidAdmission.identity(this, caller) ?: throw VaultLockedException()
            val now = SystemClock.elapsedRealtime(); val expires = intent.getLongExtra("lifecycle_expires", -1)
            if (identity.uid != intent.getIntExtra("lifecycle_uid", -1) || identity.certificate != intent.getStringExtra("lifecycle_signer") ||
                !FolsomCallerAdmission.allowed(identity, domain, Process.myUid()) || expires < now || expires - now > 120_000 ||
                intent.getIntExtra("lifecycle_boot", -1) != Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -2)) throw VaultLockedException()
        }
        true
    } catch (_: Exception) { false }
    private fun matches(value: FolsomConsentOwnerStatus?) = value != null && value.id == attemptId && value.domain == domain && value.value == requestedValue
    private fun perform(action: Action) {
        try { requireOwner(); if (!allowed.get() || busy || !validAccount() || !hasSecureScreenLock) throw VaultLockedException() }
        catch (_: Exception) { failed = true; return }
        val selectedReview = review?.copyOf()
        if (action in setOf(Action.RETRY, Action.CLOSE) && !matches(status) || action == Action.RETRY && selectedReview?.size != 32) {
            selectedReview?.fill(0); failed = true; return
        }
        val stopped = AtomicBoolean(false); val grant = generation.get()
        val selectedAttempt = attemptId
        cancellation = stopped; busy = true; failed = false; review?.fill(0); review = null
        val verify = {
            if (stopped.get() || !allowed.get() || generation.get() != grant || !validAccount() || !hasSecureScreenLock) throw VaultLockedException()
            requireOwner()
        }
        lifecycleScope.launch {
            try {
                var problem = false
                val result = withContext(Dispatchers.IO) {
                    verify(); val current = FolsomConsentClient(this@FolsomConsentActivity, account, accountId, verify)
                    if (stopped.get() || cancellation !== stopped) { current.cancel(); throw VaultLockedException() }
                    client = current
                    var reviewed: ByteArray? = null
                    val loaded = try { when (action) {
                        Action.REFRESH -> current.reconcile(selectedAttempt)
                        Action.REVIEW -> { reviewed = current.review(selectedAttempt); current.status() }
                        Action.RETRY -> current.retry(selectedAttempt, selectedReview ?: throw VaultLockedException())
                        Action.CLOSE -> current.close(selectedAttempt)
                    } } catch (_: Exception) { problem = true; verify(); current.status() }
                    verify(); loaded to reviewed
                }
                verify()
                if (cancellation === stopped) {
                    status = result.first; failed = problem
                    if (!problem && action == Action.RETRY && selectedReview?.size == 32 &&
                        result.first.domain == domain && result.first.value == requestedValue) {
                        result.first.id?.takeIf { it.isNotBlank() && it.length <= 128 }?.let { attemptId = it }
                    }
                    if (!problem && action == Action.CLOSE) { verify(); setResult(RESULT_CANCELED); finish() }
                    if (!problem && matches(result.first)) {
                        review = result.second
                        if (result.first.phase?.name == "COMMITTED") { verify(); setResult(RESULT_OK); finish() }
                    } else result.second?.fill(0)
                } else result.second?.fill(0)
            } catch (_: Exception) { if (allowed.get() && generation.get() == grant && cancellation === stopped) failed = true }
            finally { selectedReview?.fill(0); if (cancellation === stopped) { busy = false; client = null; cancellation = null } }
        }
    }
    private fun cancel() { cancellation?.set(true); client?.cancel(); review?.fill(0); review = null }
    override fun onOwnerAuthenticated() { generation.incrementAndGet(); allowed.set(true); unlocked = true
        if (hasSecureScreenLock) perform(Action.REFRESH) }
    override fun onOwnerAuthenticationFailed() { finish() }
    override fun onPause() { allowed.set(false); generation.incrementAndGet(); cancel(); super.onPause() }
    override fun onResume() { super.onResume(); if (unlocked) allowed.set(runCatching { requireOwner(); true }.getOrDefault(false)) }
    override fun onOwnerLocked() { allowed.set(false); generation.incrementAndGet(); cancel()
        unlocked = false; busy = false; status = null; failed = false; confirmation = null }
}
