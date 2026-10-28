/* SPDX-License-Identifier: Apache-2.0 */
package com.google.android.gms.credential.manager

import android.accounts.Account
import android.accounts.AccountManager
import android.os.Bundle
import android.net.Uri
import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.microg.gms.cyclon.CyclonMaterialTheme
import org.microg.gms.passwords.*
import org.microg.gms.auth.folsom.ui.GenericActivity

const val PASSWORD_MANAGER_CLASS_NAME = "com.google.android.gms.credential.manager.PasswordManagerActivity"
const val EXTRA_KEY_ACCOUNT_NAME = "pwm.DataFieldNames.accountName"
const val EXTRA_KEY_SIGNON_REALM = "pwm.DataFieldNames.deepLinkToSignonRealm"

class PasswordManagerActivity : PasswordOwnerActivity() {
    private var unlocked by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf<Int?>(null)
    private var entries by mutableStateOf<List<PasswordView>>(emptyList())
    private var selected by mutableStateOf<Account?>(null)
    private var storageChosen by mutableStateOf(false)
    private var enabled by mutableStateOf(false)
    private var assistantAccess by mutableStateOf<Map<String, AgentSignInAccess.State>>(emptyMap())
    private var accessEditing by mutableStateOf<PasswordView?>(null)
    private var editing by mutableStateOf<PasswordView?>(null)
    private var editorOpen by mutableStateOf(false)
    private var deleting by mutableStateOf<PasswordView?>(null)
    private var passphrase by mutableStateOf("")
    private var query by mutableStateOf("")
    private var showSettings by mutableStateOf(false)
    private var detailId by mutableStateOf<String?>(null)
    private var blockedSiteOpen by mutableStateOf(false)
    private var showCheckup by mutableStateOf(false)
    private var dismissedCheckup by mutableStateOf(false)
    private var checkupDismissal by mutableStateOf<Pair<PasswordView, Boolean>?>(null)
    private var pendingChanges by mutableStateOf<List<PasswordPendingChange>>(emptyList())
    private var resolving by mutableStateOf<PasswordPendingChange?>(null)
    private var remoteReset by mutableStateOf(false)
    private var resetOpen by mutableStateOf(false)
    private var moving by mutableStateOf<PasswordView?>(null)
    private var transfers by mutableStateOf<List<PasswordTransferOperation>>(emptyList())
    private var droppingTransfer by mutableStateOf<String?>(null)
    private var archiveImport by mutableStateOf<Boolean?>(null)
    private var importUri: Uri? = null
    private var exportBytes: ByteArray? = null
    private var exportIdentity: PasswordStoreIdentity? = null
    private var exportCreatedMillis = 0L
    private var exportPending = false
    private var trustedRecovery by mutableStateOf(false)
    private var csvExportOpen by mutableStateOf(false)
    private var csvImportOpen by mutableStateOf(false)
    private var csvImportRequested = false
    private var afterAuthentication: (() -> Unit)? = null
    private val ownerOperations by lazy { PasswordOwnerOperations(this) }
    private val recoverKeys = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            afterAuthentication = { perform { repository().refresh() } }
            authenticateOwner()
        }
    }
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { importUri = uri; afterAuthentication = {
            if (csvImportRequested) csvImportOpen = true else archiveImport = true
        }; authenticateOwner() }
    }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), ::exportSelected)
    private val exportCsvDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv"), ::exportSelected)
    private fun exportSelected(uri: Uri?) {
        if (uri == null) clearExport()
        else {
            afterAuthentication = { perform {
                val bytes = exportBytes ?: throw VaultLockedException()
                try {
                    ownerOperations.verifyIdentity(exportIdentity ?: throw VaultLockedException())
                    if (SystemClock.elapsedRealtime() - exportCreatedMillis !in 0..300_000L) throw VaultLockedException()
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw ProtocolException()
                } finally { clearExport() }
            } }
            authenticateOwner()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT < 26) { finish(); return }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setContent { CyclonMaterialTheme { Surface(Modifier.fillMaxSize()) {
            BackHandler(enabled = detailId != null) { detailId = null }
            val scroll = rememberScrollState()
            LaunchedEffect(showSettings, showCheckup, detailId) { scroll.scrollTo(0) }
            Column(Modifier.verticalScroll(scroll).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.passwords_title), style = MaterialTheme.typography.headlineSmall)
                if (!unlocked) {
                    Text(stringResource(if (hasSecureScreenLock) R.string.passwords_unlock_description else R.string.passwords_no_lock_description))
                    Button(onClick = ::authenticateOwner, enabled = !busy) {
                        Text(stringResource(if (hasSecureScreenLock) R.string.passwords_unlock else R.string.passwords_open))
                    }
                } else {
                    val accounts = AccountManager.get(this@PasswordManagerActivity).getAccountsByType("com.google").toList()
                    if (accounts.isEmpty()) Text(stringResource(R.string.contacts_sync_no_accounts))
                    accounts.forEach { account ->
                        TextButton(onClick = { choose(account) }, enabled = !busy) {
                            Text(account.name + if (account == selected) " ✓" else "")
                        }
                    }
                    TextButton(onClick = { choose(null) }, enabled = !busy) { Text(stringResource(R.string.passwords_on_device)) }
                    TabRow(selectedTabIndex = if (showSettings) 2 else if (showCheckup) 1 else 0) {
                        Tab(selected = !showSettings && !showCheckup,
                            onClick = { showSettings = false; showCheckup = false; detailId = null },
                            text = { Text(stringResource(R.string.passwords_tab_passwords)) })
                        Tab(selected = showCheckup, enabled = enabled,
                            onClick = { showSettings = false; showCheckup = true; detailId = null },
                            text = { Text(stringResource(R.string.passwords_tab_checkup)) })
                        Tab(selected = showSettings,
                            onClick = { showSettings = true; showCheckup = false; detailId = null },
                            text = { Text(stringResource(R.string.passwords_tab_settings)) })
                    }
                    if (storageChosen && showSettings) {
                        if (selected != null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Switch(checked = enabled, enabled = !busy, onCheckedChange = { value -> perform {
                                repository().setEnabled(value)
                                if (value) repository().refresh()
                            } })
                            Text(stringResource(R.string.passwords_sync), Modifier.padding(top = 12.dp))
                        }
                        if (selected != null && android.os.Build.VERSION.SDK_INT >= 28) TextButton(onClick = {
                            requireOwner()
                            val account = selected ?: return@TextButton
                            val identity = ownerOperations.identity(account)
                            startActivity(Intent(this@PasswordManagerActivity, PasswordEncryptionActivity::class.java)
                                .putExtra("account", account.name).putExtra("google_user_id", identity.userId))
                        }, enabled = !busy) { Text(stringResource(R.string.passwords_encryption_manage)) }
                        if (enabled) {
                            if (selected != null) TextButton(onClick = {
                                requireOwner()
                                val account = selected ?: return@TextButton
                                val identity = ownerOperations.identity(account)
                                startActivity(Intent(this@PasswordManagerActivity, PasswordWifiSyncActivity::class.java)
                                    .putExtra("account", account.name).putExtra("google_user_id", identity.userId))
                            }, enabled = !busy) { Text(stringResource(R.string.passwords_wifi_title)) }
                            if (selected != null) TextButton(onClick = {
                                requireOwner()
                                val account = selected ?: return@TextButton
                                val identity = ownerOperations.identity(account)
                                startActivity(Intent(this@PasswordManagerActivity, PasswordSharingActivity::class.java)
                                    .putExtra("account", account.name).putExtra("google_user_id", identity.userId))
                            }, enabled = !busy) { Text(stringResource(R.string.passwords_share)) }
                            if (selected != null) TextButton(onClick = {
                                requireOwner()
                                val account = selected ?: return@TextButton
                                val identity = ownerOperations.identity(account)
                                startActivity(Intent(this@PasswordManagerActivity, SyncedPasskeyManagerActivity::class.java)
                                    .putExtra("account", account.name).putExtra("google_user_id", identity.userId))
                            }, enabled = !busy) { Text(stringResource(R.string.passwords_synced_passkeys)) }
                            if (selected != null) TextButton(onClick = {
                                requireOwner()
                                val account = selected ?: return@TextButton
                                val identity = ownerOperations.identity(account)
                                startActivity(Intent(this@PasswordManagerActivity, PasswordBreachActivity::class.java)
                                    .putExtra("account", account.name).putExtra("google_user_id", identity.userId))
                            }, enabled = !busy) { Text(stringResource(R.string.passwords_breach_assess)) }
                            if (selected != null) TextButton(onClick = {
                                requireOwner()
                                val account = selected ?: return@TextButton
                                val identity = ownerOperations.identity(account)
                                startActivity(Intent(this@PasswordManagerActivity, PasswordRecoveryEnrollmentActivity::class.java)
                                    .putExtra("account", account.name).putExtra("google_user_id", identity.userId))
                            }, enabled = !busy) { Text(stringResource(R.string.passwords_recovery_enroll_phone)) }
                            if (message == R.string.passwords_locked && !trustedRecovery) {
                                OutlinedTextField(passphrase, { passphrase = it }, label = { Text(stringResource(R.string.passwords_passphrase)) },
                                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                                Button(onClick = {
                                    showCheckup = false
                                    val secret = passphrase.toByteArray(Charsets.UTF_8); passphrase = ""
                                    perform { try { repository().unlockPassphrase(secret); repository().refresh() } finally { secret.fill(0) } }
                                }, enabled = !busy && passphrase.isNotEmpty()) { Text(stringResource(R.string.passwords_unlock)) }
                            }
                            if (trustedRecovery && selected != null) OutlinedButton(onClick = {
                                    requireOwner()
                                    val account = selected ?: return@OutlinedButton
                                    val identity = ownerOperations.identity(account)
                                    recoverKeys.launch(Intent(this@PasswordManagerActivity, GenericActivity::class.java)
                                        .putExtra(GenericActivity.EXTRA_ACCOUNT_NAME, account.name)
                                        .putExtra(GenericActivity.EXTRA_ACCOUNT_ID, identity.userId)
                                        .putExtra(GenericActivity.EXTRA_SECURITY_DOMAIN, "chromesync")
                                        .putExtra(GenericActivity.EXTRA_OPERATION, 1)
                                        .putExtra(GenericActivity.EXTRA_OFFER_RESET, false)
                                        .putExtra(GenericActivity.EXTRA_PASSWORD_OWNER_RECOVERY, true))
                            }, enabled = !busy) { Text(stringResource(R.string.passwords_recover_account_keys)) }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = { perform { repository().refresh() } }, enabled = !busy) { Text(stringResource(R.string.passwords_refresh)) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { archiveImport = false }, enabled = !busy) { Text(stringResource(R.string.passwords_export)) }
                                TextButton(onClick = { csvImportRequested = false; importDocument.launch(arrayOf("application/octet-stream", "application/x-cyclon-passwords")) }, enabled = !busy) { Text(stringResource(R.string.passwords_import)) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { csvExportOpen = true }, enabled = !busy) { Text(stringResource(R.string.passwords_export_csv)) }
                                TextButton(onClick = { csvImportRequested = true; importDocument.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel")) }, enabled = !busy) { Text(stringResource(R.string.passwords_import_csv)) }
                            }
                            if (remoteReset) {
                                Text(stringResource(R.string.passwords_reset_pending))
                                OutlinedButton(onClick = { resetOpen = true }, enabled = !busy) { Text(stringResource(R.string.passwords_reset_accept)) }
                            }
                            if (message == R.string.passwords_no_vault) {
                                Button(onClick = { perform { repository().initializeEmptyVault(); repository().refresh() } }, enabled = !busy) {
                                    Text(stringResource(R.string.passwords_create_vault))
                                }
                            }
                        }
                    }
                    if (!showSettings && detailId == null) {
                        OutlinedTextField(query, { query = it }, singleLine = true,
                            label = { Text(stringResource(R.string.passwords_search)) }, modifier = Modifier.fillMaxWidth())
                        if (!showCheckup && enabled) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { perform { repository().refresh() } }, enabled = !busy) { Text(stringResource(R.string.passwords_refresh)) }
                            Button(onClick = { editing = null; editorOpen = true }, enabled = !busy) { Text(stringResource(R.string.passwords_add)) }
                        }
                    }
                    if (!showSettings && (trustedRecovery || remoteReset || message == R.string.passwords_locked || message == R.string.passwords_no_vault)) {
                        TextButton(onClick = { showSettings = true; showCheckup = false; detailId = null }) {
                            Text(stringResource(R.string.passwords_open_settings))
                        }
                    }
                    if (showCheckup) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { dismissedCheckup = false }) { Text(stringResource(R.string.passwords_checkup_active) + if (!dismissedCheckup) " ✓" else "") }
                            TextButton(onClick = { dismissedCheckup = true }) { Text(stringResource(R.string.passwords_checkup_dismissed) + if (dismissedCheckup) " ✓" else "") }
                        }
                        PasswordCheckupPanel(entries, query, dismissedCheckup, busy,
                            onEdit = { editing = it; editorOpen = true }, onDelete = { deleting = it },
                            onDismissal = { entry, dismissed -> checkupDismissal = entry to dismissed },
                            onWebsite = { url -> requireOwner(); runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                .onFailure { message = R.string.passwords_sync_failed } })
                    }
                    if (showSettings) {
                        HorizontalDivider()
                        Text(stringResource(R.string.passwords_never_saved), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.passwords_never_saved_description))
                        if (enabled) TextButton(onClick = { blockedSiteOpen = true }, enabled = !busy) {
                            Text(stringResource(R.string.passwords_never_save_add))
                        }
                    }
                    val detail = entries.singleOrNull { it.id == detailId && !it.data.blocked }
                    if (!showSettings && !showCheckup && detail != null) PasswordDetails(detail, assistantAccess[detail.id], busy,
                        onBack = { detailId = null }, onEdit = { editing = detail; editorOpen = true },
                        onMove = { moving = detail }, onDelete = { deleting = detail }, onAccess = { accessEditing = detail })
                    val visible = if (showCheckup || detail != null) emptyList() else entries.filter {
                        it.data.blocked == showSettings && (showSettings || it.data.matchesSearch(query))
                    }
                    if (enabled && !showCheckup && detail == null && visible.isEmpty()) Text(stringResource(R.string.passwords_no_results))
                    visible.forEach { entry ->
                        HorizontalDivider()
                        if (entry.data.blocked) {
                            Text(loginTitle(entry), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { deleting = entry }, enabled = !busy) {
                                Text(stringResource(R.string.passwords_allow_save))
                            }
                        } else {
                            ListItem(headlineContent = { Text(loginTitle(entry)) },
                                supportingContent = { Text(entry.data.username.ifEmpty { stringResource(R.string.passwords_assistant_saved_login) }) },
                                trailingContent = { Text("›") },
                                modifier = Modifier.clickable(enabled = !busy, role = Role.Button) { detailId = entry.id })
                        }
                    }
                    if (pendingChanges.isNotEmpty()) Text(stringResource(R.string.passwords_changes), style = MaterialTheme.typography.titleMedium)
                    pendingChanges.forEach { pending ->
                        Text(pending.realm)
                        Text(stringResource(if (pending.kind == MutationKind.DELETE) R.string.passwords_delete else R.string.passwords_save) + " · " +
                            stringResource(when (pending.phase) {
                                MutationPhase.QUEUED -> R.string.passwords_change_waiting
                                MutationPhase.CONFLICT -> R.string.passwords_change_conflict
                                else -> R.string.passwords_change_uncertain
                            }))
                        TextButton(onClick = { resolving = pending }, enabled = !busy) { Text(stringResource(R.string.passwords_resolve_question)) }
                    }
                    if (transfers.isNotEmpty()) Text(stringResource(R.string.passwords_transfers), style = MaterialTheme.typography.titleMedium)
                    transfers.forEach { transfer ->
                        Text(PasswordData.decode(Proto.decode(transfer.desired).one(1)).realm)
                        val source = if (transfer.source == null) stringResource(R.string.passwords_transfer_import_source)
                            else transfer.source?.accountName ?: stringResource(R.string.passwords_on_device)
                        Text(source + " → " + (transfer.destination.accountName ?: stringResource(R.string.passwords_on_device)))
                        transfer.original?.let { original ->
                            val before = PasswordData.decode(Proto.decode(original).one(1)).realm
                            val after = PasswordData.decode(Proto.decode(transfer.desired).one(1)).realm
                            if (before != after) Text(before + " → " + after)
                        }
                        Text(stringResource(if (transfer.phase == PasswordTransferPhase.COPY) R.string.passwords_transfer_state_copy else R.string.passwords_transfer_state_remove))
                        Row {
                            TextButton(onClick = { perform { ownerOperations.resume(transfer.id) } }, enabled = !busy) { Text(stringResource(R.string.passwords_transfer_resume)) }
                            TextButton(onClick = { droppingTransfer = transfer.id }, enabled = !busy) { Text(stringResource(R.string.passwords_transfer_drop)) }
                        }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(stringResource(it)) }
                TextButton(onClick = { if (detailId != null) detailId = null else finish() }) { Text(stringResource(R.string.cyclon_back)) }
            }
            accessEditing?.takeIf { unlocked }?.let { entry -> assistantAccess[entry.id]?.let { state ->
                SignInAccessDialog(entry, state.level, state.requireDeviceAuthentication, hasSecureScreenLock, busy,
                    onDismiss = { accessEditing = null },
                    onSetScreenLock = {
                        requireOwner()
                        runCatching { startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) }
                            .onFailure { message = R.string.passwords_access_failed }
                    }, onSave = { level, requireAuthentication ->
                        requireOwner()
                        if (requireAuthentication && !state.requireDeviceAuthentication && !hasSecureScreenLock)
                            throw VaultLockedException()
                        val account = selected
                        val identity = ownerOperations.identity(account)
                        accessEditing = null
                        perform {
                                if (!ownerOperations.identity(account).same(identity)) throw VaultLockedException()
                                val repo = repository(account)
                                fun set(current: PasswordData) {
                                    if (current.realm != entry.data.realm || current.username != entry.data.username || current.blocked) throw VaultLockedException()
                                    if (!ownerOperations.identity(account).same(identity)) throw VaultLockedException()
                                    AgentSignInAccess(this@PasswordManagerActivity).setOwnerPolicy(
                                        AgentSignInAccess.vaultKey(identity.userId), entry.id, state.incarnation, level, requireAuthentication)
                                }
                                when (repo) {
                                    is PasswordRepository -> repo.withAgentEntry(entry.id, ::set)
                                    is DevicePasswordVault -> repo.withAgentEntry(entry.id, ::set)
                                    else -> throw VaultLockedException()
                                }
                        }
                    })
            } }
            if (editorOpen && unlocked) PasswordEditor(editing, onDismiss = { editorOpen = false; editing = null }) { data ->
                val original = editing
                editorOpen = false; editing = null
                perform {
                    val desired = Proto.field(1, data.encode())
                    when {
                        original == null -> repository().insertIfAbsent(desired)
                        original.id == data.tag() -> repository().editIfUnchanged(original.chromeBytes, desired)
                        else -> ownerOperations.resume(ownerOperations.move(selected, selected, original.chromeBytes, desired))
                    }
                    repository().refresh()
                }
            }
            checkupDismissal?.takeIf { unlocked }?.let { (entry, dismissed) -> AlertDialog(
                onDismissRequest = { checkupDismissal = null },
                title = { Text(stringResource(if (dismissed) R.string.passwords_checkup_dismiss_question else R.string.passwords_checkup_restore_question)) },
                text = { PasswordDialogProtection(); Column {
                    Text(entry.data.realm); Text(entry.data.username)
                    Text(stringResource(if (dismissed) R.string.passwords_checkup_dismiss_description else R.string.passwords_checkup_restore_description))
                } },
                confirmButton = { TextButton(onClick = {
                    requireOwner()
                    val account = selected
                    val identity = ownerOperations.identity(account)
                    checkupDismissal = null
                    perform {
                        if (!ownerOperations.identity(account).same(identity)) throw VaultLockedException()
                        val vault = repository(account)
                        if (!ownerOperations.identity(account).same(identity)) throw VaultLockedException()
                        val updated = PasswordCheckup.withLeakedDismissed(entry.data, dismissed)
                        vault.editIfUnchanged(entry.chromeBytes, Proto.field(1, updated.encode()))
                        vault.refresh()
                    }
                }, enabled = !busy) { Text(stringResource(if (dismissed) R.string.passwords_checkup_dismiss else R.string.passwords_checkup_restore)) } },
                dismissButton = { TextButton(onClick = { checkupDismissal = null }) { Text(stringResource(android.R.string.cancel)) } }
            ) }
            if (blockedSiteOpen && unlocked) BlockedSiteEditor(onDismiss = { blockedSiteOpen = false }) { data ->
                blockedSiteOpen = false
                perform { repository().upsert(Proto.field(1, data.encode())); repository().refresh() }
            }
            deleting?.takeIf { unlocked }?.let { entry -> AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text(stringResource(if (entry.data.blocked) R.string.passwords_allow_save_question else R.string.passwords_delete_question)) },
                text = { PasswordDialogProtection(); Text(entry.data.realm) },
                confirmButton = { TextButton(onClick = { deleting = null; perform {
                    repository().deleteIfUnchanged(entry.chromeBytes); repository().refresh()
                } }) { Text(stringResource(if (entry.data.blocked) R.string.passwords_allow_save else R.string.passwords_delete)) } },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(android.R.string.cancel)) } }
            ) }
            resolving?.takeIf { unlocked }?.let { change -> AlertDialog(
                onDismissRequest = { resolving = null }, title = { Text(stringResource(R.string.passwords_resolve_question)) },
                text = { PasswordDialogProtection(); Text(stringResource(R.string.passwords_resolve_description)) },
                confirmButton = { TextButton(onClick = { resolving = null; perform { repository().retryChange(change.id, true); repository().refresh() } }) { Text(stringResource(R.string.passwords_retry_owner)) } },
                dismissButton = { TextButton(onClick = { resolving = null; perform { repository().dropChange(change.id) } }) { Text(stringResource(R.string.passwords_drop_owner)) } }
            ) }
            if (resetOpen && unlocked) AlertDialog(
                onDismissRequest = { resetOpen = false }, title = { Text(stringResource(R.string.passwords_reset_question)) },
                text = { PasswordDialogProtection(); Text(stringResource(R.string.passwords_reset_description)) },
                confirmButton = { TextButton(onClick = { resetOpen = false; perform { repository().acceptRemoteReset(); repository().refresh() } }) { Text(stringResource(R.string.passwords_reset_accept)) } },
                dismissButton = { TextButton(onClick = { resetOpen = false }) { Text(stringResource(android.R.string.cancel)) } }
            )
            moving?.takeIf { unlocked }?.let { entry -> AlertDialog(
                onDismissRequest = { moving = null }, title = { Text(stringResource(R.string.passwords_move_question)) },
                text = { PasswordDialogProtection(); Column {
                    Text(stringResource(R.string.passwords_move_description)); Text(entry.data.realm)
                    val accounts = AccountManager.get(this@PasswordManagerActivity).getAccountsByType("com.google").toList()
                    (listOf<Account?>(null) + accounts).filter { it != selected }.forEach { destination ->
                        TextButton(onClick = { moving = null; perform {
                            ownerOperations.resume(ownerOperations.move(selected, destination, entry.chromeBytes))
                        } }) { Text(destination?.name ?: stringResource(R.string.passwords_on_device)) }
                    }
                } }, confirmButton = {}, dismissButton = { TextButton(onClick = { moving = null }) { Text(stringResource(android.R.string.cancel)) } }
            ) }
            droppingTransfer?.takeIf { unlocked }?.let { id -> AlertDialog(
                onDismissRequest = { droppingTransfer = null }, title = { Text(stringResource(R.string.passwords_transfer_drop_question)) },
                text = { PasswordDialogProtection(); Text(stringResource(R.string.passwords_transfer_drop_description)) },
                confirmButton = { TextButton(onClick = { droppingTransfer = null; perform { ownerOperations.drop(id) } }) { Text(stringResource(R.string.passwords_transfer_drop)) } },
                dismissButton = { TextButton(onClick = { droppingTransfer = null }) { Text(stringResource(android.R.string.cancel)) } }
            ) }
            archiveImport?.takeIf { unlocked }?.let { importing -> PasswordArchiveDialog(importing, { archiveImport = null }) { passphrase ->
                archiveImport = null
                if (importing) perform {
                    try {
                        val uri = importUri ?: throw ProtocolException(); importUri = null
                        val bytes = readImport(uri)
                        val records = try { PasswordArchive.decrypt(bytes, passphrase) } finally { bytes.fill(0) }
                        val ids = ownerOperations.import(selected, records)
                        for (id in ids) ownerOperations.resume(id)
                    } finally { passphrase.fill('\u0000') }
                } else {
                    perform(onSuccess = { prepareExport(); exportDocument.launch("passwords.cyclon-pwm") }) {
                        try {
                            exportIdentity = ownerOperations.identity(selected)
                            exportBytes?.fill(0); exportBytes = PasswordArchive.encrypt(repository().list().map { it.data }, passphrase)
                        }
                        finally { passphrase.fill('\u0000') }
                    }
                }
            } }
            if (csvExportOpen && unlocked) AlertDialog(
                onDismissRequest = { csvExportOpen = false }, title = { Text(stringResource(R.string.passwords_export_csv)) },
                text = { PasswordDialogProtection(); Text(stringResource(R.string.passwords_export_csv_warning)) },
                confirmButton = { TextButton(onClick = {
                    csvExportOpen = false
                    perform(onSuccess = { prepareExport(); exportCsvDocument.launch("passwords.csv") }) {
                        exportIdentity = ownerOperations.identity(selected)
                        exportBytes?.fill(0); exportBytes = PasswordCsv.encode(repository().list().map { it.data }.filterNot { it.blocked })
                    }
                }) { Text(stringResource(R.string.passwords_export_csv)) } },
                dismissButton = { TextButton(onClick = { csvExportOpen = false }) { Text(stringResource(android.R.string.cancel)) } }
            )
            if (csvImportOpen && unlocked) AlertDialog(
                onDismissRequest = { csvImportOpen = false; importUri = null }, title = { Text(stringResource(R.string.passwords_import_csv)) },
                text = { PasswordDialogProtection(); Text(stringResource(R.string.passwords_import_csv_warning)) },
                confirmButton = { TextButton(onClick = {
                    csvImportOpen = false
                    perform {
                        val uri = importUri ?: throw ProtocolException(); importUri = null
                        val bytes = readImport(uri)
                        val passwords = try { PasswordCsv.decode(bytes, System.currentTimeMillis()) } finally { bytes.fill(0) }
                        val ids = ownerOperations.import(selected, passwords)
                        for (id in ids) ownerOperations.resume(id)
                    }
                }) { Text(stringResource(R.string.passwords_import_csv)) } },
                dismissButton = { TextButton(onClick = { csvImportOpen = false; importUri = null }) { Text(stringResource(android.R.string.cancel)) } }
            )
        } } }
    }

    override fun onOwnerAuthenticated() {
        unlocked = true; message = null
        afterAuthentication?.let { action -> afterAuthentication = null; action(); return }
        if (storageChosen) { perform { }; return }
        val accounts = AccountManager.get(this).getAccountsByType("com.google")
        val requested = intent.getStringExtra(EXTRA_KEY_ACCOUNT_NAME)
        val expectedUserId = intent.getStringExtra("google_user_id")
        val account = if (requested != null) accounts.singleOrNull { it.name == requested } else accounts.singleOrNull()
        // Notification links keep the exact account incarnation. A removed account
        // must never silently redirect the owner into a different vault.
        if (requested != null && account == null || expectedUserId != null &&
            (account == null || AccountManager.get(this).getUserData(account, "GoogleUserId") != expectedUserId)) {
            message = R.string.passwords_access_failed
            return
        }
        if (account != null || accounts.isEmpty()) {
            val realm = intent.getStringExtra(EXTRA_KEY_SIGNON_REALM)?.takeIf {
                it.length in 1..4096 && it.none(Char::isISOControl)
            }
            choose(account, realm.orEmpty())
        }
    }
    override fun onOwnerAuthenticationFailed() {
        clearExport(); afterAuthentication = null
        message = R.string.passwords_access_failed
    }
    override fun onOwnerLocked() {
        unlocked = false; entries = emptyList(); assistantAccess = emptyMap(); accessEditing = null; editing = null; deleting = null; editorOpen = false; passphrase = ""
        query = ""; showSettings = false; detailId = null; blockedSiteOpen = false
        pendingChanges = emptyList(); transfers = emptyList(); resolving = null; moving = null
        resetOpen = false; archiveImport = null; droppingTransfer = null
        csvExportOpen = false; csvImportOpen = false
        showCheckup = false; dismissedCheckup = false; checkupDismissal = null
        if (!exportPending) clearExport()
    }
    private fun choose(account: Account?, initialQuery: String = "") {
        requireOwner(); selected = account; storageChosen = true; entries = emptyList(); query = initialQuery; detailId = null
        showCheckup = false; dismissedCheckup = false; checkupDismissal = null
        perform { }
    }
    private fun repository(account: Account? = selected): ManagedPasswordVault = account?.let { PasswordRepository(this, it) } ?: DevicePasswordVault(this)
    private fun perform(onSuccess: (() -> Unit)? = null, action: () -> Unit) {
        requireOwner()
        if (busy) return
        if (!storageChosen) return
        val account = selected
        busy = true; message = null
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    action()
                    val repo = repository(account)
                    snapshot(repo)
                }
                requireOwner()
                if (selected == account) applySnapshot(result)
                onSuccess?.invoke()
            } catch (failure: Exception) {
                val cached = withContext(Dispatchers.IO) { runCatching { snapshot(repository(account)) }.getOrNull() }
                if (runCatching { requireOwner() }.isFailure) { if (!exportPending) clearExport(); return@launch }
                if (selected == account && cached != null) applySnapshot(cached)
                message = when {
                    failure is PasswordRpcException && failure.code == 5 -> R.string.passwords_no_vault
                    failure is VaultLockedException -> R.string.passwords_locked
                    failure is ConflictException || failure is PendingMutationException -> R.string.passwords_pending
                    failure is VaultResetException -> R.string.passwords_reset_pending
                    else -> R.string.passwords_sync_failed
                }
                enabled = runCatching { repository(account).enabled }.getOrDefault(false)
            } finally { busy = false }
        }
    }
    private fun readImport(uri: Uri): ByteArray = contentResolver.openInputStream(uri)?.use { stream ->
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        try {
            while (true) {
                val read = stream.read(buffer); if (read < 0) break
                if (read == 0 || output.size() + read > PasswordArchive.MAX_BYTES) throw ProtocolException()
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } finally { buffer.fill(0) }
    } ?: throw ProtocolException()
    private class OwnerSnapshot(val enabled: Boolean, val entries: List<PasswordView>, val pending: List<PasswordPendingChange>,
        val reset: Boolean, val transfers: List<PasswordTransferOperation>, val trustedRecovery: Boolean,
        val assistantAccess: Map<String, AgentSignInAccess.State>)
    private fun snapshot(repo: ManagedPasswordVault): OwnerSnapshot {
        val enabled = repo.enabled
        val reset = repo.hasRemoteReset()
        val trusted = (repo as? PasswordRepository)?.needsTrustedVaultRecovery() == true
        val entries = if (!enabled) emptyList() else if (reset || trusted) runCatching { repo.list() }.getOrDefault(emptyList()) else repo.list()
        val changes = if (!enabled) emptyList() else if (reset || trusted) runCatching { repo.pendingChanges() }.getOrDefault(emptyList()) else repo.pendingChanges()
        val identity = ownerOperations.identity((repo as? PasswordRepository)?.account)
        val access = AgentSignInAccess(this)
        val levels = entries.filter { !it.data.blocked }.mapNotNull { entry ->
            // Issue an incarnation only while the login still exists. A stale snapshot must
            // never create a permission record for a deleted login that is recreated later.
            runCatching {
                fun read(current: PasswordData): Pair<String, AgentSignInAccess.State> {
                    if (current.realm != entry.data.realm || current.username != entry.data.username) throw VaultLockedException()
                    return entry.id to access.get(AgentSignInAccess.vaultKey(identity.userId), entry.id)
                }
                when (repo) {
                    is PasswordRepository -> repo.withAgentEntry(entry.id, ::read)
                    is DevicePasswordVault -> repo.withAgentEntry(entry.id, ::read)
                    else -> throw VaultLockedException()
                }
            }.getOrNull()
        }.toMap()
        return OwnerSnapshot(enabled, entries, changes, reset, ownerOperations.pending(), trusted, levels)
    }
    private fun applySnapshot(snapshot: OwnerSnapshot) {
        enabled = snapshot.enabled; entries = snapshot.entries; pendingChanges = snapshot.pending
        remoteReset = snapshot.reset; transfers = snapshot.transfers
        trustedRecovery = snapshot.trustedRecovery
        assistantAccess = snapshot.assistantAccess
        if (entries.none { it.id == detailId }) detailId = null
    }
    private fun prepareExport() {
        requireOwner(); exportPending = true; exportCreatedMillis = SystemClock.elapsedRealtime()
        val expected = exportBytes
        lifecycleScope.launch { kotlinx.coroutines.delay(300_000L); if (exportBytes === expected) clearExport() }
    }
    private fun clearExport() { exportBytes?.fill(0); exportBytes = null; exportIdentity = null; exportPending = false; exportCreatedMillis = 0L }
    override fun onDestroy() { clearExport(); afterAuthentication = null; super.onDestroy() }
}

private fun accessLabel(level: AgentSignInAccess.Level) = when (level) {
    AgentSignInAccess.Level.ASK -> R.string.passwords_assistant_ask
    AgentSignInAccess.Level.ALWAYS -> R.string.passwords_assistant_always
    AgentSignInAccess.Level.NEVER -> R.string.passwords_assistant_never
}

private fun loginTarget(entry: PasswordView): String =
    if (entry.data.realm.startsWith("android://")) entry.data.realm.substringAfter('@').trimEnd('/')
    else entry.data.realm

private fun loginTitle(entry: PasswordView): String = entry.brandName?.takeIf { it.isNotBlank() }
    ?: Uri.parse(loginTarget(entry)).host?.takeIf { it.isNotBlank() }
    ?: loginTarget(entry)

@Composable
private fun PasswordDetails(entry: PasswordView, access: AgentSignInAccess.State?, busy: Boolean,
                            onBack: () -> Unit, onEdit: () -> Unit, onMove: () -> Unit,
                            onDelete: () -> Unit, onAccess: () -> Unit) {
    TextButton(onClick = onBack) { Text(stringResource(R.string.passwords_back_to_passwords)) }
    Text(loginTitle(entry), style = MaterialTheme.typography.headlineSmall)
    if (loginTitle(entry) != loginTarget(entry)) Text(loginTarget(entry),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.passwords_username), style = MaterialTheme.typography.labelMedium)
    Text(entry.data.username.ifEmpty { stringResource(R.string.passwords_assistant_saved_login) })
    if (entry.data.displayName.isNotEmpty()) Text(entry.data.displayName)
    Row {
        TextButton(onClick = onEdit, enabled = !busy) { Text(stringResource(R.string.passwords_edit)) }
        TextButton(onClick = onMove, enabled = !busy) { Text(stringResource(R.string.passwords_move)) }
        TextButton(onClick = onDelete, enabled = !busy) { Text(stringResource(R.string.passwords_delete)) }
    }
    if (access != null) {
        HorizontalDivider()
        Text(stringResource(R.string.passwords_assistant_access), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onAccess, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.passwords_assistant_access_status, stringResource(accessLabel(access.level))))
        }
        if (access.requireDeviceAuthentication) Text(stringResource(R.string.passwords_assistant_authentication_summary),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        access.lastUsed?.let { used -> Text(stringResource(R.string.passwords_assistant_last_used,
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                .format(java.util.Date(used))), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun SignInAccessDialog(entry: PasswordView, current: AgentSignInAccess.Level, currentRequireAuthentication: Boolean,
                               hasSecureScreenLock: Boolean, busy: Boolean, onDismiss: () -> Unit,
                               onSetScreenLock: () -> Unit, onSave: (AgentSignInAccess.Level, Boolean) -> Unit) {
    var selected by remember(entry.id, current) { mutableStateOf(current) }
    var requireAuthentication by remember(entry.id, currentRequireAuthentication) { mutableStateOf(currentRequireAuthentication) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.passwords_assistant_access)) },
        text = {
            PasswordDialogProtection()
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(loginTitle(entry), style = MaterialTheme.typography.titleMedium)
                if (loginTitle(entry) != loginTarget(entry)) Text(loginTarget(entry), style = MaterialTheme.typography.bodySmall)
                Text(entry.data.username.ifEmpty { stringResource(R.string.passwords_assistant_saved_login) },
                    style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.passwords_assistant_access_description), style = MaterialTheme.typography.bodySmall)
                Column(Modifier.selectableGroup()) {
                    AgentSignInAccess.Level.values().forEach { level ->
                        val choiceEnabled = !busy && !(requireAuthentication && level == AgentSignInAccess.Level.ALWAYS)
                        Row(Modifier.fillMaxWidth().selectable(selected == level, enabled = choiceEnabled, role = Role.RadioButton,
                            onClick = { selected = level }).padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RadioButton(selected = selected == level, onClick = null, enabled = choiceEnabled)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(accessLabel(level)), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(when (level) {
                                    AgentSignInAccess.Level.ASK -> R.string.passwords_assistant_ask_description
                                    AgentSignInAccess.Level.ALWAYS -> R.string.passwords_assistant_always_description
                                    AgentSignInAccess.Level.NEVER -> R.string.passwords_assistant_never_description
                                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().toggleable(value = requireAuthentication,
                    enabled = !busy && (hasSecureScreenLock || requireAuthentication), role = Role.Switch,
                    onValueChange = { enabled ->
                        requireAuthentication = enabled
                        if (enabled && selected == AgentSignInAccess.Level.ALWAYS) selected = AgentSignInAccess.Level.ASK
                    }).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.passwords_assistant_require_authentication), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.passwords_assistant_require_authentication_description),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = requireAuthentication, onCheckedChange = null,
                        enabled = !busy && (hasSecureScreenLock || requireAuthentication))
                }
                if (!hasSecureScreenLock) {
                    Text(stringResource(R.string.passwords_assistant_screen_lock_required), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onSetScreenLock, enabled = !busy) {
                        Text(stringResource(R.string.passwords_assistant_set_screen_lock))
                    }
                }
                Text(stringResource(R.string.passwords_assistant_revoke_description), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected, requireAuthentication) },
            enabled = !busy && (selected != current || requireAuthentication != currentRequireAuthentication)) {
            Text(stringResource(R.string.passwords_save))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
private fun PasswordEditor(existing: PasswordView?, onDismiss: () -> Unit, onSave: (PasswordData) -> Unit) {
    var url by remember { mutableStateOf(existing?.data?.origin.orEmpty()) }
    var username by remember { mutableStateOf(existing?.data?.username.orEmpty()) }
    var password by remember { mutableStateOf(existing?.data?.password.orEmpty()) }
    var name by remember { mutableStateOf(existing?.data?.displayName.orEmpty()) }
    var note by remember { mutableStateOf(existing?.data?.defaultNote?.value.orEmpty()) }
    var noteVisible by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.passwords_add else R.string.passwords_edit)) },
        text = { PasswordDialogProtection(); Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(url, { url = it }, enabled = existing?.data?.realm?.startsWith("android://") != true, label = { Text(stringResource(R.string.passwords_site)) })
            OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.passwords_username)) })
            OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.passwords_password)) },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.passwords_name)) })
            OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.passwords_note)) }, minLines = 2, maxLines = 4,
                visualTransformation = if (noteVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            TextButton(onClick = { noteVisible = !noteVisible }) {
                Text(stringResource(if (noteVisible) R.string.passwords_hide_note else R.string.passwords_show_note))
            }
            if (invalid) Text(stringResource(R.string.passwords_invalid))
        } },
        confirmButton = { TextButton(onClick = {
            val data = runCatching {
                if (password.isEmpty()) throw ProtocolException()
                val now = System.currentTimeMillis()
                (existing?.data?.withIdentity(url, username)?.withPassword(password, now) ?: PasswordData.create(
                    PasswordData.webRealm(url), url, username, password, now)).withDisplayName(name).withDefaultNote(note, now)
            }.getOrNull()
            if (data == null) invalid = true else onSave(data)
        }) { Text(stringResource(R.string.passwords_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
private fun PasswordArchiveDialog(importing: Boolean, dismiss: () -> Unit, confirm: (CharArray) -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss,
        title = { Text(stringResource(if (importing) R.string.passwords_import else R.string.passwords_export)) },
        text = { PasswordDialogProtection(); Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (importing) R.string.passwords_import_description else R.string.passwords_archive_description))
            OutlinedTextField(passphrase, { passphrase = it }, label = { Text(stringResource(R.string.passwords_archive_password)) },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        } },
        confirmButton = { TextButton(enabled = passphrase.length in 12..1024, onClick = {
            val value = passphrase.toCharArray(); passphrase = ""; confirm(value)
        }) { Text(stringResource(if (importing) R.string.passwords_import else R.string.passwords_export)) } },
        dismissButton = { TextButton(onClick = { passphrase = ""; dismiss() }) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
private fun BlockedSiteEditor(onDismiss: () -> Unit, onSave: (PasswordData) -> Unit) {
    var url by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.passwords_never_save_add)) },
        text = { PasswordDialogProtection(); Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.passwords_site)) })
            if (invalid) Text(stringResource(R.string.passwords_invalid_site))
        } },
        confirmButton = { TextButton(onClick = {
            val data = runCatching { PasswordData.blockedSite(url, System.currentTimeMillis()) }.getOrNull()
            if (data == null) invalid = true else onSave(data)
        }) { Text(stringResource(R.string.passwords_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}
