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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import org.microg.gms.cyclon.*
import org.microg.gms.passwords.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Private owner decision. A Binder resolution supplies only a bounded public-key request, never a private key. */
class SecurityDomainLifecycleActivity : PasswordOwnerActivity() {
    // Also opened for apps (key retrieval), on whatever display they use.
    override val ownerDisplayOnly get() = false
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
        setContent { CyclonMaterialTheme {
            SecurityDomainLifecycleScreen(account.name, domain, requested, intent.getStringExtra("lifecycle_caller"), externalPublic,
                unlocked, hasSecureScreenLock, busy, message, status, confirmation, retry, closing,
                onBack = { cancel(); finish() }, onUnlock = ::authenticateOwner,
                onDomain = { id -> if (id != domain) { domain = id; status = null; perform() } }, onRefresh = { perform() },
                onAsk = { confirmation = it }, onRetry = { retry = true }, onClose = { closing = true },
                onDismiss = { confirmation = null; retry = false; closing = false },
                onConfirm = { action ->
                    val doRetry = retry; val doClose = closing
                    confirmation = null; retry = false; closing = false
                    perform(action, doRetry, doClose)
                })
        } }
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

/** What [SecurityDomainLifecycleActivity] shows, from its state alone; the activity keeps every check and request. */
@Composable
internal fun SecurityDomainLifecycleScreen(
    account: String, domain: String, requested: DomainLifecycleKind?, requestingApp: String?, externalPublic: ByteArray?,
    unlocked: Boolean, secure: Boolean, busy: Boolean, message: Int?, status: DomainLifecycleOwnerStatus?,
    confirmation: DomainLifecycleKind?, retry: Boolean, closing: Boolean, onBack: () -> Unit, onUnlock: () -> Unit,
    onDomain: (String) -> Unit, onRefresh: () -> Unit, onAsk: (DomainLifecycleKind) -> Unit, onRetry: () -> Unit,
    onClose: () -> Unit, onDismiss: () -> Unit, onConfirm: (DomainLifecycleKind?) -> Unit,
) {
    val fingerprint = externalPublic?.let { public ->
        stringResource(R.string.passwords_domain_device_identity,
            MessageDigest.getInstance("SHA-256").digest(public).take(16).joinToString("") { "%02x".format(it.toInt() and 255) })
    }
    PasswordOwnerPage(stringResource(R.string.passwords_domain_manage), account, onBack) {
        if (!unlocked) { PasswordOwnerLocked(secure, enabled = !busy, onUnlock = onUnlock); return@PasswordOwnerPage }
        if (requested == null) Column(Modifier.selectableGroup()) {
            for ((id, label) in listOf("chromesync" to R.string.passwords_domain_passwords, "hw_protected" to R.string.passwords_domain_passkeys))
                CyclonChoiceRow(stringResource(label), null, selected = id == domain, enabled = !busy) { onDomain(id) }
        } else PasswordOwnerText(stringResource(if (domain == "chromesync") R.string.passwords_domain_passwords else R.string.passwords_domain_passkeys))
        if (!secure) PasswordOwnerText(stringResource(R.string.passwords_domain_lock_required))
        PasswordOwnerStatus(busy, message?.let { stringResource(it) })
        val pending = status != null && status.phase != null && (status.phase != DomainLifecyclePhase.COMMITTED ||
            status.operation == DomainLifecycleKind.RESET && !status.authorityCleared)
        status?.let { current ->
            CyclonSectionLabel(stringResource(R.string.passwords_section_recovery))
            CyclonRow(stringResource(R.string.passwords_domain_devices, current.members), current.phase?.let { stringResource(phaseLabel(current)) })
        }
        fingerprint?.let { CyclonNote(it) }
        PasswordOwnerActions {
            status?.let { current ->
                if (pending) {
                    CyclonTextAction(stringResource(R.string.passwords_retry_owner), enabled = !busy, onClick = onRetry)
                    if (current.operation != DomainLifecycleKind.RESET || current.phase == DomainLifecyclePhase.PREPARED)
                        CyclonTextAction(stringResource(R.string.passwords_domain_close_attempt), enabled = !busy, onClick = onClose)
                } else if (secure) {
                    val actions = requested?.let { listOf(it) } ?: if (current.epoch == 0 && current.members == 0) listOf(DomainLifecycleKind.CREATE) else
                        listOf(DomainLifecycleKind.ENROLL, DomainLifecycleKind.ROTATE, DomainLifecycleKind.RESET)
                    actions.forEach { action ->
                        // Allowing this phone (or the requested step) leads; replacing the key and the reset stay quieter.
                        if (action == DomainLifecycleKind.ROTATE || action == DomainLifecycleKind.RESET && requested == null)
                            CyclonLineButton(stringResource(actionLabel(action)), enabled = !busy) { onAsk(action) }
                        else CyclonInkButton(stringResource(actionLabel(action)), enabled = !busy) { onAsk(action) }
                    }
                }
            }
            CyclonLineButton(stringResource(R.string.passwords_refresh), enabled = !busy && secure, onClick = onRefresh)
        }
    }
    if (confirmation != null || retry || closing) {
        val action = confirmation
        PasswordOwnerConfirm(stringResource(action?.let(::actionLabel) ?: R.string.passwords_domain_manage), account, listOfNotNull(
            if (action == DomainLifecycleKind.ADD_MEMBER) requestingApp?.let { stringResource(R.string.passwords_domain_requesting_app, it) } else null,
            if (action == DomainLifecycleKind.ADD_MEMBER) fingerprint else null,
            stringResource(when {
                retry -> R.string.passwords_domain_retry_warning
                closing && status?.operation == DomainLifecycleKind.RESET && status?.phase == DomainLifecyclePhase.PREPARED ->
                    R.string.passwords_domain_close_prepared_reset
                closing -> R.string.passwords_domain_close_warning
                action == DomainLifecycleKind.RESET -> R.string.passwords_domain_reset_warning
                action == DomainLifecycleKind.ROTATE -> R.string.passwords_domain_rotate_warning
                action == DomainLifecycleKind.CREATE -> R.string.passwords_domain_create_warning
                action == DomainLifecycleKind.ADD_MEMBER -> R.string.passwords_domain_add_warning
                else -> R.string.passwords_domain_enroll_warning
            }),
        ), confirmEnabled = !busy && secure, onConfirm = { onConfirm(action) }, onDismiss = onDismiss)
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
