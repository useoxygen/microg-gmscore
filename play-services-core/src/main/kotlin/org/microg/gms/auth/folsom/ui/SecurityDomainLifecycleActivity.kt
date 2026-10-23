/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom.ui

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
import org.microg.gms.auth.folsom.DomainLifecycleClient
import org.microg.gms.auth.folsom.DomainLifecycleOwnerStatus
import org.microg.gms.auth.folsom.FolsomAndroidAdmission
import org.microg.gms.auth.folsom.FolsomCallerAdmission
import org.microg.gms.auth.folsom.utils.SecureBox
import org.microg.gms.cyclon.CyclonMaterialTheme
import org.microg.gms.passwords.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Private owner decision. A Binder resolution supplies only a bounded public-key request, never a private key. */
class SecurityDomainLifecycleActivity : PasswordOwnerActivity() {
    private lateinit var account: Account
    private lateinit var accountId: String
    private var domain by mutableStateOf("chromesync")
    private var requested: DomainLifecycleKind? = null
    private var externalPublic: ByteArray? = null
    private var submittedId: String? = null
    private val allowed = AtomicBoolean(false)
    private val generation = AtomicLong()
    @Volatile private var client: DomainLifecycleClient? = null
    @Volatile private var activeCancellation: AtomicBoolean? = null
    private var unlocked by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var status by mutableStateOf<DomainLifecycleOwnerStatus?>(null)
    private var message by mutableStateOf<Int?>(null)
    private var confirmation by mutableStateOf<DomainLifecycleKind?>(null)
    private var retry by mutableStateOf(false)
    private var closing by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        if (android.os.Build.VERSION.SDK_INT < 28) { finish(); return }
        val name = intent.getStringExtra("account")
        val id = intent.getStringExtra("google_user_id")
        if (name.isNullOrBlank() || id.isNullOrBlank()) { finish(); return }
        account = Account(name, "com.google"); accountId = id
        try {
            domain = DomainLifecycleWire.checkedDomain(intent.getStringExtra("security_domain") ?: "chromesync")
            requested = intent.getStringExtra("lifecycle_action")?.let(DomainLifecycleKind::valueOf)
            externalPublic = intent.getByteArrayExtra("external_public")?.also { SecureBox.deserializePublicKey(it) }
            if (requested == DomainLifecycleKind.ADD_MEMBER && externalPublic == null) throw ProtocolException()
            verifyIdentity()
        } catch (_: Exception) { finish(); return }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setContent { CyclonMaterialTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.passwords_domain_manage), style = MaterialTheme.typography.headlineSmall)
                if (!unlocked) Button(onClick = ::authenticateOwner, enabled = !busy) { Text(stringResource(R.string.passwords_unlock)) }
                else {
                    Text(account.name)
                    Text(stringResource(if (domain == "chromesync") R.string.passwords_domain_passwords else R.string.passwords_domain_passkeys))
                    if (requested == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((id, label) in listOf("chromesync" to R.string.passwords_domain_passwords, "hw_protected" to R.string.passwords_domain_passkeys))
                            TextButton(onClick = { if (id != domain) { domain = id; status = null; perform() } }, enabled = !busy) {
                                Text(stringResource(label))
                            }
                    }
                    if (!hasSecureScreenLock) Text(stringResource(R.string.passwords_domain_lock_required))
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    message?.let { Text(stringResource(it)) }
                    status?.let { current ->
                        Text(stringResource(R.string.passwords_domain_devices, current.members))
                        if (current.phase != null) Text(stringResource(phaseLabel(current)))
                        val pending = current.phase != null && (current.phase != DomainLifecyclePhase.COMMITTED ||
                            current.operation == DomainLifecycleKind.RESET && !current.authorityCleared)
                        if (pending) {
                            TextButton(onClick = { retry = true }, enabled = !busy) { Text(stringResource(R.string.passwords_retry_owner)) }
                            if (current.operation != DomainLifecycleKind.RESET || current.phase == DomainLifecyclePhase.PREPARED)
                                TextButton(onClick = { closing = true }, enabled = !busy) {
                                Text(stringResource(R.string.passwords_domain_close_attempt))
                            }
                        } else if (hasSecureScreenLock) {
                            val actions = requested?.let { listOf(it) } ?: if (current.epoch == 0 && current.members == 0) listOf(DomainLifecycleKind.CREATE) else
                                listOf(DomainLifecycleKind.ENROLL, DomainLifecycleKind.ROTATE, DomainLifecycleKind.RESET)
                            actions.forEach { action -> Button(onClick = { confirmation = action }, enabled = !busy) {
                                Text(stringResource(actionLabel(action)))
                            } }
                        }
                    }
                    OutlinedButton(onClick = { perform() }, enabled = !busy && hasSecureScreenLock) { Text(stringResource(R.string.passwords_refresh)) }
                    externalPublic?.let { public ->
                        Text(stringResource(R.string.passwords_domain_device_identity,
                            MessageDigest.getInstance("SHA-256").digest(public).take(16).joinToString("") { "%02x".format(it.toInt() and 255) }))
                    }
                }
                TextButton(onClick = { cancel(); finish() }) { Text(stringResource(android.R.string.cancel)) }
            }
            if (confirmation != null || retry || closing) {
                val action = confirmation
                AlertDialog(onDismissRequest = { confirmation = null; retry = false; closing = false },
                    title = { Text(stringResource(action?.let(::actionLabel) ?: R.string.passwords_domain_manage)) },
                    text = { PasswordDialogProtection(); Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(account.name)
                        if (action == DomainLifecycleKind.ADD_MEMBER) {
                            intent.getStringExtra("lifecycle_caller")?.let { Text(stringResource(R.string.passwords_domain_requesting_app, it)) }
                            externalPublic?.let { Text(stringResource(R.string.passwords_domain_device_identity,
                                MessageDigest.getInstance("SHA-256").digest(it).take(16).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) })) }
                        }
                        Text(stringResource(when {
                            retry -> R.string.passwords_domain_retry_warning
                            closing && status?.operation == DomainLifecycleKind.RESET && status?.phase == DomainLifecyclePhase.PREPARED ->
                                R.string.passwords_domain_close_prepared_reset
                            closing -> R.string.passwords_domain_close_warning
                            action == DomainLifecycleKind.RESET -> R.string.passwords_domain_reset_warning
                            action == DomainLifecycleKind.ROTATE -> R.string.passwords_domain_rotate_warning
                            action == DomainLifecycleKind.CREATE -> R.string.passwords_domain_create_warning
                            action == DomainLifecycleKind.ADD_MEMBER -> R.string.passwords_domain_add_warning
                            else -> R.string.passwords_domain_enroll_warning
                        }))
                    } }, confirmButton = { TextButton(onClick = {
                        val doRetry = retry; val doClose = closing
                        confirmation = null; retry = false; closing = false
                        perform(action, doRetry, doClose)
                    }, enabled = !busy && hasSecureScreenLock) { Text(stringResource(android.R.string.ok)) } },
                    dismissButton = { TextButton(onClick = { confirmation = null; retry = false; closing = false }) {
                        Text(stringResource(android.R.string.cancel))
                    } })
            }
        } } }
        authenticateOwner()
    }
    private fun verifyIdentity() {
        val accounts = AccountManager.get(this)
        if (!accounts.getAccountsByType("com.google").contains(account) || accounts.getUserData(account, "GoogleUserId") != accountId)
            throw VaultLockedException()
        intent.getStringExtra("lifecycle_caller")?.let { caller ->
            val identity = FolsomAndroidAdmission.identity(this, caller) ?: throw VaultLockedException()
            if (identity.uid != intent.getIntExtra("lifecycle_uid", -1) || identity.certificate != intent.getStringExtra("lifecycle_signer") ||
                !FolsomCallerAdmission.allowed(identity, domain, Process.myUid())) throw VaultLockedException()
            val now = SystemClock.elapsedRealtime()
            val expires = intent.getLongExtra("lifecycle_expires", -1)
            if (expires < now || expires - now > 120_000 || intent.getIntExtra("lifecycle_boot", -1) !=
                Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -2)) throw VaultLockedException()
        }
    }
    private fun perform(action: DomainLifecycleKind? = null, retry: Boolean = false, close: Boolean = false) {
        try { requireOwner(); verifyIdentity(); if (!hasSecureScreenLock) throw VaultLockedException() }
        catch (_: Exception) { message = R.string.passwords_domain_lock_required; return }
        if (busy) return
        val previous = status
        val selectedDomain = domain
        val grant = generation.get()
        val stopped = AtomicBoolean(false)
        activeCancellation = stopped; busy = true; message = null
        val verify = {
            if (stopped.get() || !allowed.get() || generation.get() != grant || !hasSecureScreenLock || domain != selectedDomain)
                throw VaultLockedException()
            requireOwner(); verifyIdentity()
        }
        lifecycleScope.launch {
            try {
                var failure: Exception? = null
                val result = withContext(Dispatchers.IO) {
                    verify()
                    val current = DomainLifecycleClient(this@SecurityDomainLifecycleActivity, account, accountId,
                        selectedDomain, intent.getStringExtra("password_parent"), verify)
                    if (stopped.get() || activeCancellation !== stopped) { current.cancel(); throw VaultLockedException() }
                    client = current
                    try { current.perform(action, previous?.digest, if (retry) previous?.operationId else null,
                        if (close) previous?.operationId else null, externalPublic, intent.getIntExtra("join_options", 0)) }
                    catch (problem: Exception) { failure = problem; verify(); current.localStatus() }
                }
                verify()
                if (activeCancellation === stopped) {
                    status = result
                    if (action != null && action == requested && result.operationId != previous?.operationId) submittedId = result.operationId
                    message = failure?.let { if (it is ConflictException) R.string.passwords_domain_changed else R.string.passwords_domain_failed }
                    if (requested != null && result.operation == requested && result.operationId == submittedId &&
                        result.phase == DomainLifecyclePhase.COMMITTED && (requested != DomainLifecycleKind.RESET || result.authorityCleared)) {
                        verify(); setResult(RESULT_OK); finish()
                    }
                }
            } catch (_: Exception) {
                if (allowed.get() && generation.get() == grant && activeCancellation === stopped) message = R.string.passwords_domain_failed
            } finally { if (activeCancellation === stopped) { busy = false; client = null; activeCancellation = null } }
        }
    }
    private fun actionLabel(kind: DomainLifecycleKind) = when (kind) {
        DomainLifecycleKind.CREATE -> R.string.passwords_domain_create
        DomainLifecycleKind.ENROLL -> R.string.passwords_domain_enroll
        DomainLifecycleKind.ROTATE -> R.string.passwords_domain_rotate
        DomainLifecycleKind.RESET -> R.string.passwords_domain_reset
        DomainLifecycleKind.ADD_MEMBER -> R.string.passwords_domain_add
    }
    private fun phaseLabel(current: DomainLifecycleOwnerStatus) = when (current.phase) {
        DomainLifecyclePhase.COMMITTED -> if (current.operation == DomainLifecycleKind.RESET && !current.authorityCleared)
            R.string.passwords_domain_clear_pending else R.string.passwords_domain_confirmed
        DomainLifecyclePhase.PREPARED -> R.string.passwords_domain_prepared
        DomainLifecyclePhase.CONFLICT -> R.string.passwords_domain_changed
        DomainLifecyclePhase.REFUSED -> R.string.passwords_domain_failed
        else -> R.string.passwords_domain_uncertain
    }
    private fun cancel() { activeCancellation?.set(true); client?.cancel() }
    override fun onOwnerAuthenticated() { generation.incrementAndGet(); allowed.set(true); unlocked = true; if (hasSecureScreenLock) perform() }
    override fun onOwnerAuthenticationFailed() { finish() }
    override fun onPause() { allowed.set(false); generation.incrementAndGet(); cancel(); super.onPause() }
    override fun onResume() { super.onResume(); if (unlocked) allowed.set(runCatching { requireOwner(); true }.getOrDefault(false)) }
    override fun onOwnerLocked() {
        allowed.set(false); generation.incrementAndGet(); cancel(); client = null; activeCancellation = null
        unlocked = false; busy = false; status = null; confirmation = null; retry = false; closing = false; message = null
    }
}
