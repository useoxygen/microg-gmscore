/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.os.Bundle
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.microg.gms.cyclon.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Internal owner-only management. Labels/opaque resources stay native; this screen never opens a private key. */
class SyncedPasskeyManagerActivity : PasswordOwnerActivity() {
    private lateinit var account: Account
    private lateinit var accountId: String
    private val allowed = AtomicBoolean(false)
    private val generation = AtomicLong()
    private var unlocked by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var screen by mutableStateOf<SyncedPasskeyOwnerScreen?>(null)
    private var message by mutableStateOf<Int?>(null)
    private var editing by mutableStateOf<SyncedPasskeyOwnerEntry?>(null)
    private var deleting by mutableStateOf<SyncedPasskeyOwnerEntry?>(null)
    private var resolving by mutableStateOf<Boolean?>(null) // true = retry; false = close a pending management attempt.
    private var closeCreate by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT < 28) { finish(); return }
        val name = intent.getStringExtra("account")
        val id = intent.getStringExtra("google_user_id")
        if (name.isNullOrBlank() || id.isNullOrBlank()) { finish(); return }
        account = Account(name, "com.google"); accountId = id
        if (!validAccount()) { finish(); return }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setContent { CyclonMaterialTheme(glass = true) { PasswordOwnerSurface {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                item {
                    PasswordOwnerHeader(stringResource(R.string.passwords_synced_passkeys), account.name, onBack = ::finish)
                    if (!unlocked) PasswordOwnerLocked(hasSecureScreenLock, enabled = !busy, onUnlock = ::authenticateOwner)
                    else {
                        CyclonNote(stringResource(R.string.passwords_passkey_management_description))
                        PasswordOwnerActions { CyclonGlassButton(stringResource(R.string.passwords_refresh), enabled = !busy) { perform(refresh = true) } }
                    }
                    PasswordOwnerStatus(busy, message?.let { stringResource(it) })
                }
                if (unlocked) screen?.let { state ->
                    if (state.reset) item { PasswordOwnerText(stringResource(R.string.passwords_passkey_management_reset)) }
                    if (state.pendingCreate || state.operation != null) item {
                        CyclonSectionLabel(stringResource(R.string.passwords_changes))
                        CyclonGroup {
                            // The actions' words in line with the row's (an action pads its words 16dp).
                            val actions = Modifier.padding(start = LocalCyclonInset.current - 16.dp, bottom = 4.dp)
                            if (state.pendingCreate) Column {
                                CyclonRow(stringResource(R.string.passwords_passkey_management_pending_create))
                                Row(actions) {
                                    CyclonTextAction(stringResource(R.string.passwords_passkey_management_close_create), enabled = !busy) { closeCreate = true }
                                }
                            }
                            state.operation?.let { operation -> Column {
                                val conflict = operation.steps.any { it.phase == PasskeyOwnerPhase.CONFLICT }
                                CyclonRow(stringResource(R.string.passwords_passkey_management_progress,
                                    operation.steps.count { it.phase == PasskeyOwnerPhase.COMMITTED }, operation.steps.size),
                                    listOfNotNull(stringResource(R.string.passwords_passkey_management_pending),
                                        stringResource(R.string.passwords_passkey_management_conflict).takeIf { conflict }).joinToString("\n"))
                                Row(actions) {
                                    if (!state.reset && operation.kind != PasskeyOwnerKind.SECRET_UPDATE && !conflict)
                                        CyclonTextAction(stringResource(R.string.passwords_retry_owner), enabled = !busy) { resolving = true }
                                    CyclonTextAction(stringResource(R.string.passwords_passkey_management_close), enabled = !busy) { resolving = false }
                                }
                            } }
                        }
                    }
                    if (!state.reset) item { CyclonSectionLabel(stringResource(R.string.passwords_synced_passkeys)) }
                    if (!state.reset && state.entries.isEmpty()) item { CyclonNote(stringResource(R.string.passwords_passkey_management_empty)) }
                    // One island: a list of passkeys is short, and an island cannot span lazy items.
                    if (state.entries.isNotEmpty()) item { CyclonGroup { state.entries.forEach { entry -> key(entry.data.tag) { Column {
                        CyclonRow(entry.data.rpId, listOfNotNull(entry.data.username.ifEmpty { null }, entry.data.displayName.ifEmpty { null },
                            stringResource(R.string.passwords_passkey_management_hidden).takeIf { entry.data.hidden },
                            stringResource(R.string.passwords_passkey_management_shadowed).takeIf { entry.shadowed },
                            stringResource(R.string.passwords_passkey_management_record, entry.data.tag.take(12))).joinToString("\n"),
                            leading = { CyclonMonogram(entry.data.rpId) })
                        // The actions line up with the relying party, after the monogram.
                        Row(Modifier.padding(start = LocalCyclonInset.current + 50.dp - 16.dp, bottom = 4.dp)) {
                            CyclonTextAction(stringResource(R.string.passwords_passkey_management_edit), enabled = !busy && !entry.data.hidden &&
                                state.operation == null && !state.pendingCreate) { editing = entry }
                            CyclonTextAction(stringResource(R.string.passwords_delete), enabled = !busy && state.operation == null && !state.pendingCreate) {
                                deleting = entry
                            }
                        }
                    } } } } }
                }
            }
            editing?.takeIf { unlocked }?.let { entry ->
                var username by remember(entry.data.tag) { mutableStateOf(entry.data.username) }
                var display by remember(entry.data.tag) { mutableStateOf(entry.data.displayName) }
                AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.passwords_passkey_management_edit)) },
                    text = { PasswordDialogProtection(); Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(entry.data.rpId, style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(username, { if (it.length <= 1024) username = it }, Modifier.fillMaxWidth(), colors = cyclonFieldColors(),
                            label = { Text(stringResource(R.string.passwords_username)) })
                        OutlinedTextField(display, { if (it.length <= 1024) display = it }, Modifier.fillMaxWidth(), colors = cyclonFieldColors(),
                            label = { Text(stringResource(R.string.passwords_passkey_management_display_name)) })
                        Text(stringResource(R.string.passwords_passkey_management_edit_warning), style = MaterialTheme.typography.bodySmall,
                            color = Cyclon.palette.muted)
                    } },
                    confirmButton = { CyclonTextAction(stringResource(R.string.passwords_save),
                        enabled = username.isNotEmpty() && !busy && (username != entry.data.username || display != entry.data.displayName)) {
                        editing = null
                        perform { vault, verify -> vault.ownerRename(entry.resource, username, display, verify) }
                    } },
                    dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel)) { editing = null } })
            }
            deleting?.takeIf { unlocked }?.let { entry ->
                val group = deletionGroup(entry)
                AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.passwords_delete)) },
                    text = { PasswordDialogProtection(); Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PasswordDialogText(stringResource(R.string.passwords_passkey_management_delete_warning, group.size))
                        group.forEach { Identity(it.data) }
                    } }, confirmButton = { CyclonTextAction(stringResource(R.string.passwords_delete), enabled = !busy && group.isNotEmpty()) {
                        deleting = null
                        perform { vault, verify -> vault.ownerDelete(entry.resource, group.map { it.resource }, verify) }
                    } },
                    dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel)) { deleting = null } })
            }
            resolving?.takeIf { unlocked }?.let { retry -> screen?.operation?.let { operation ->
                AlertDialog(onDismissRequest = { resolving = null },
                    title = { Text(stringResource(if (retry) R.string.passwords_retry_owner else R.string.passwords_passkey_management_close)) },
                    text = { PasswordDialogProtection(); Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PasswordDialogText(stringResource(if (retry) R.string.passwords_passkey_management_retry_warning
                            else R.string.passwords_passkey_management_close_warning))
                        screen?.operationEntries.orEmpty().forEachIndexed { index, data ->
                            Identity(data)
                            CyclonVoice(stringResource(phaseLabel(operation.steps[index].phase)), color = Cyclon.palette.ink)
                        }
                    } }, confirmButton = { CyclonTextAction(stringResource(android.R.string.ok), enabled = !busy) {
                        resolving = null
                        perform { vault, verify -> if (retry) vault.ownerRetry(operation.id, verify) else vault.ownerClose(operation.id, verify) }
                    } },
                    dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel)) { resolving = null } })
            } }
            if (closeCreate && unlocked) AlertDialog(onDismissRequest = { closeCreate = false },
                title = { Text(stringResource(R.string.passwords_passkey_management_close_create)) },
                text = { PasswordDialogProtection(); PasswordDialogText(stringResource(R.string.passwords_passkey_management_close_warning), scrolls = true) },
                confirmButton = { CyclonTextAction(stringResource(android.R.string.ok), enabled = !busy) {
                    closeCreate = false; perform { vault, verify -> vault.discardPendingCreate(verify) }
                } },
                dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel)) { closeCreate = false } })
        } } }
        authenticateOwner()
    }
    @Composable private fun Identity(data: SyncedPasskey) {
        Column {
            Text(data.rpId, style = MaterialTheme.typography.titleMedium, color = Cyclon.palette.ink)
            Text(data.username, style = MaterialTheme.typography.bodyMedium, color = Cyclon.palette.ink)
            if (data.displayName.isNotEmpty()) Text(data.displayName, style = MaterialTheme.typography.bodyMedium, color = Cyclon.palette.ink)
            Text(stringResource(R.string.passwords_passkey_management_record, data.tag.take(12)), style = MaterialTheme.typography.bodySmall,
                color = Cyclon.palette.muted)
        }
    }
    private fun phaseLabel(phase: PasskeyOwnerPhase): Int = when (phase) {
        PasskeyOwnerPhase.COMMITTED -> R.string.passwords_passkey_management_committed
        PasskeyOwnerPhase.LAUNCHED, PasskeyOwnerPhase.INDETERMINATE -> R.string.passwords_passkey_management_unknown
        PasskeyOwnerPhase.REFUSED -> R.string.passwords_passkey_management_refused
        PasskeyOwnerPhase.CONFLICT -> R.string.passwords_passkey_management_conflict
        PasskeyOwnerPhase.QUEUED -> R.string.passwords_passkey_management_queued
    }
    private fun deletionGroup(selected: SyncedPasskeyOwnerEntry): List<SyncedPasskeyOwnerEntry> =
        screen?.entries.orEmpty().filter { entry -> if (selected.shadowed) entry.data.tag == selected.data.tag else
            entry.data.rpId == selected.data.rpId && MessageDigest.isEqual(entry.data.userId, selected.data.userId) }
    private fun validAccount(): Boolean {
        val accounts = AccountManager.get(this)
        return accounts.getAccountsByType("com.google").contains(account) && accounts.getUserData(account, "GoogleUserId") == accountId
    }
    private fun vault(parent: String?): SyncedPasskeyVault {
        if (!validAccount()) throw VaultLockedException()
        return SyncedPasskeyVault(this, account, accountId, parent)
    }
    private fun perform(refresh: Boolean = false, action: (SyncedPasskeyVault, () -> Unit) -> Unit = { _, _ -> }) {
        try { requireOwner(); if (!validAccount()) throw VaultLockedException() } catch (_: Exception) { onOwnerLocked(); return }
        if (busy) return
        busy = true; message = null
        val grant = generation.get()
        val parent = screen?.parent
        val verify = {
            if (!allowed.get() || generation.get() != grant || !validAccount()) throw VaultLockedException()
            requireOwner()
        }
        lifecycleScope.launch {
            try {
                var failure: Exception? = null
                val loaded = withContext(Dispatchers.IO) {
                    val client = vault(parent)
                    try { verify(); action(client, verify); client.ownerScreen(verify, refresh) }
                    catch (problem: Exception) {
                        failure = problem
                        // A reset is displayed for explicit closure; no operation from the previous birthday is replayed.
                        if (problem is VaultResetException) vault(null).ownerScreen(verify, false)
                        else client.ownerScreen(verify, false)
                    }
                }
                requireOwner(); verify()
                screen = loaded
                message = failure?.let { when (it) {
                    is ConflictException -> R.string.passwords_passkey_management_conflict
                    is VaultResetException -> R.string.passwords_passkey_management_reset
                    else -> R.string.passwords_passkey_management_failed
                } }
            } catch (_: Exception) {
                if (allowed.get() && generation.get() == grant) message = R.string.passwords_passkey_management_failed
            } finally { busy = false }
        }
    }
    override fun onOwnerAuthenticated() {
        generation.incrementAndGet(); allowed.set(true); unlocked = true; perform(refresh = true)
    }
    override fun onOwnerAuthenticationFailed() { finish() }
    override fun onPause() { allowed.set(false); super.onPause() }
    override fun onResume() {
        super.onResume()
        if (unlocked) allowed.set(runCatching { requireOwner(); true }.getOrDefault(false))
    }
    override fun onOwnerLocked() {
        allowed.set(false); generation.incrementAndGet(); unlocked = false; screen = null; editing = null; deleting = null
        resolving = null; closeCreate = false; message = null
    }
}
