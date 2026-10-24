/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.os.Bundle
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
        setContent { CyclonMaterialTheme { Surface(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(stringResource(R.string.passwords_synced_passkeys), style = MaterialTheme.typography.headlineSmall)
                    if (!unlocked) Button(onClick = ::authenticateOwner, enabled = !busy) {
                        Text(stringResource(R.string.passwords_unlock))
                    } else {
                        Text(account.name)
                        Text(stringResource(R.string.passwords_passkey_management_description))
                        OutlinedButton(onClick = { perform(refresh = true) }, enabled = !busy) {
                            Text(stringResource(R.string.passwords_refresh))
                        }
                    }
                    if (busy) LinearProgressIndicator()
                    message?.let { Text(stringResource(it)) }
                }
                if (unlocked) screen?.let { state ->
                    if (state.reset) item { Text(stringResource(R.string.passwords_passkey_management_reset)) }
                    if (state.pendingCreate) item {
                        Text(stringResource(R.string.passwords_passkey_management_pending_create))
                        TextButton(onClick = { closeCreate = true }, enabled = !busy) {
                            Text(stringResource(R.string.passwords_passkey_management_close_create))
                        }
                    }
                    state.operation?.let { operation -> item {
                        Text(stringResource(R.string.passwords_passkey_management_progress,
                            operation.steps.count { it.phase == PasskeyOwnerPhase.COMMITTED }, operation.steps.size))
                        Text(stringResource(R.string.passwords_passkey_management_pending))
                        if (!state.reset && operation.kind != PasskeyOwnerKind.SECRET_UPDATE &&
                            operation.steps.none { it.phase == PasskeyOwnerPhase.CONFLICT })
                            TextButton(onClick = { resolving = true }, enabled = !busy) {
                                Text(stringResource(R.string.passwords_retry_owner))
                            }
                        if (operation.steps.any { it.phase == PasskeyOwnerPhase.CONFLICT })
                            Text(stringResource(R.string.passwords_passkey_management_conflict))
                        TextButton(onClick = { resolving = false }, enabled = !busy) {
                            Text(stringResource(R.string.passwords_passkey_management_close))
                        }
                    } }
                    if (!state.reset && state.entries.isEmpty()) item { Text(stringResource(R.string.passwords_passkey_management_empty)) }
                    items(state.entries, key = { it.data.tag }) { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Identity(entry.data)
                            if (entry.data.hidden) Text(stringResource(R.string.passwords_passkey_management_hidden))
                            if (entry.shadowed) Text(stringResource(R.string.passwords_passkey_management_shadowed))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(onClick = { editing = entry }, enabled = !busy && !entry.data.hidden &&
                                    state.operation == null && !state.pendingCreate) { Text(stringResource(R.string.passwords_passkey_management_edit)) }
                                TextButton(onClick = { deleting = entry }, enabled = !busy && state.operation == null && !state.pendingCreate) {
                                    Text(stringResource(R.string.passwords_delete))
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                item { TextButton(onClick = { finish() }) { Text(stringResource(android.R.string.cancel)) } }
            }
            editing?.takeIf { unlocked }?.let { entry ->
                var username by remember(entry.data.tag) { mutableStateOf(entry.data.username) }
                var display by remember(entry.data.tag) { mutableStateOf(entry.data.displayName) }
                AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.passwords_passkey_management_edit)) },
                    text = { PasswordDialogProtection(); Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(entry.data.rpId)
                        OutlinedTextField(username, { if (it.length <= 1024) username = it },
                            label = { Text(stringResource(R.string.passwords_username)) })
                        OutlinedTextField(display, { if (it.length <= 1024) display = it },
                            label = { Text(stringResource(R.string.passwords_passkey_management_display_name)) })
                        Text(stringResource(R.string.passwords_passkey_management_edit_warning))
                    } },
                    confirmButton = { TextButton(onClick = {
                        editing = null
                        perform { vault, verify -> vault.ownerRename(entry.resource, username, display, verify) }
                    }, enabled = username.isNotEmpty() && !busy && (username != entry.data.username || display != entry.data.displayName)) {
                        Text(stringResource(R.string.passwords_save)) } },
                    dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(android.R.string.cancel)) } })
            }
            deleting?.takeIf { unlocked }?.let { entry ->
                val group = deletionGroup(entry)
                AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.passwords_delete)) },
                    text = { PasswordDialogProtection(); Column(Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.passwords_passkey_management_delete_warning, group.size))
                        group.forEach { Identity(it.data) }
                    } }, confirmButton = { TextButton(onClick = {
                        deleting = null
                        perform { vault, verify -> vault.ownerDelete(entry.resource, group.map { it.resource }, verify) }
                    }, enabled = !busy && group.isNotEmpty()) { Text(stringResource(R.string.passwords_delete)) } },
                    dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(android.R.string.cancel)) } })
            }
            resolving?.takeIf { unlocked }?.let { retry -> screen?.operation?.let { operation ->
                AlertDialog(onDismissRequest = { resolving = null },
                    title = { Text(stringResource(if (retry) R.string.passwords_retry_owner else R.string.passwords_passkey_management_close)) },
                    text = { PasswordDialogProtection(); Column(Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(if (retry) R.string.passwords_passkey_management_retry_warning
                            else R.string.passwords_passkey_management_close_warning))
                        screen?.operationEntries.orEmpty().forEachIndexed { index, data ->
                            Identity(data)
                            Text(stringResource(phaseLabel(operation.steps[index].phase)))
                        }
                    } }, confirmButton = { TextButton(onClick = {
                        resolving = null
                        perform { vault, verify -> if (retry) vault.ownerRetry(operation.id, verify) else vault.ownerClose(operation.id, verify) }
                    }, enabled = !busy) { Text(stringResource(android.R.string.ok)) } },
                    dismissButton = { TextButton(onClick = { resolving = null }) { Text(stringResource(android.R.string.cancel)) } })
            } }
            if (closeCreate && unlocked) AlertDialog(onDismissRequest = { closeCreate = false },
                title = { Text(stringResource(R.string.passwords_passkey_management_close_create)) },
                text = { PasswordDialogProtection(); Text(stringResource(R.string.passwords_passkey_management_close_warning)) },
                confirmButton = { TextButton(onClick = {
                    closeCreate = false; perform { vault, verify -> vault.discardPendingCreate(verify) }
                }, enabled = !busy) { Text(stringResource(android.R.string.ok)) } },
                dismissButton = { TextButton(onClick = { closeCreate = false }) { Text(stringResource(android.R.string.cancel)) } })
        } } }
        authenticateOwner()
    }
    @Composable private fun Identity(data: SyncedPasskey) {
        Text(data.rpId, style = MaterialTheme.typography.titleMedium)
        Text(data.username)
        if (data.displayName.isNotEmpty()) Text(data.displayName)
        Text(stringResource(R.string.passwords_passkey_management_record, data.tag.take(12)))
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
