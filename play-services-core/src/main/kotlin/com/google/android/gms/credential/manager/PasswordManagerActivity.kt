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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
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
import org.microg.gms.cyclon.*
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
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(scroll).padding(bottom = 32.dp)) {
                val detail = entries.singleOrNull { it.id == detailId && !it.data.blocked }
                if (unlocked && !showSettings && !showCheckup && detail != null) DetailPage(detail) else MainPage()
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
                text = { PasswordDialogProtection(); Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(loginTitle(entry), style = MaterialTheme.typography.titleMedium)
                    Text(entry.data.username, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(if (dismissed) R.string.passwords_checkup_dismiss_description else R.string.passwords_checkup_restore_description),
                        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Cyclon.palette.muted)
                } },
                confirmButton = { CyclonTextAction(stringResource(if (dismissed) R.string.passwords_checkup_dismiss else R.string.passwords_checkup_restore), enabled = !busy) {
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
                } },
                dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel)) { checkupDismissal = null } }
            ) }
            if (blockedSiteOpen && unlocked) BlockedSiteEditor(onDismiss = { blockedSiteOpen = false }) { data ->
                blockedSiteOpen = false
                perform { repository().upsert(Proto.field(1, data.encode())); repository().refresh() }
            }
            deleting?.takeIf { unlocked }?.let { entry -> AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text(stringResource(if (entry.data.blocked) R.string.passwords_allow_save_question else R.string.passwords_delete_question)) },
                text = { PasswordDialogProtection(); Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(loginTitle(entry), style = MaterialTheme.typography.titleMedium)
                    if (!entry.data.blocked && entry.data.username.isNotEmpty()) Text(entry.data.username, style = MaterialTheme.typography.bodyMedium)
                    if (loginTitle(entry) != loginTarget(entry)) Text(loginTarget(entry), style = MaterialTheme.typography.bodySmall, color = Cyclon.palette.muted)
                } },
                confirmButton = { CyclonTextAction(stringResource(if (entry.data.blocked) R.string.passwords_allow_save else R.string.passwords_delete)) { deleting = null; perform {
                    repository().deleteIfUnchanged(entry.chromeBytes); repository().refresh()
                } } },
                dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel)) { deleting = null } }
            ) }
            resolving?.takeIf { unlocked }?.let { change -> AlertDialog(
                onDismissRequest = { resolving = null }, title = { Text(stringResource(R.string.passwords_resolve_question)) },
                text = { PasswordDialogProtection(); DialogBody(stringResource(R.string.passwords_resolve_description)) },
                confirmButton = { CyclonTextAction(stringResource(R.string.passwords_retry_owner)) { resolving = null; perform { repository().retryChange(change.id, true); repository().refresh() } } },
                dismissButton = { CyclonTextAction(stringResource(R.string.passwords_drop_owner)) { resolving = null; perform { repository().dropChange(change.id) } } }
            ) }
            if (resetOpen && unlocked) AlertDialog(
                onDismissRequest = { resetOpen = false }, title = { Text(stringResource(R.string.passwords_reset_question)) },
                text = { PasswordDialogProtection(); DialogBody(stringResource(R.string.passwords_reset_description)) },
                confirmButton = { CyclonTextAction(stringResource(R.string.passwords_reset_accept)) { resetOpen = false; perform { repository().acceptRemoteReset(); repository().refresh() } } },
                dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel)) { resetOpen = false } }
            )
            moving?.takeIf { unlocked }?.let { entry -> AlertDialog(
                onDismissRequest = { moving = null }, title = { Text(stringResource(R.string.passwords_move_question)) },
                text = { PasswordDialogProtection(); Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    Text(loginTitle(entry), style = MaterialTheme.typography.titleMedium)
                    DialogBody(stringResource(R.string.passwords_move_description), Modifier.padding(top = 8.dp, bottom = 8.dp))
                    CyclonHairline()
                    val accounts = AccountManager.get(this@PasswordManagerActivity).getAccountsByType("com.google").toList()
                    (listOf<Account?>(null) + accounts).filter { it != selected }.forEach { destination ->
                        CyclonRow(destination?.name ?: stringResource(R.string.passwords_on_device),
                            stringResource(if (destination == null) R.string.passwords_on_device_summary else R.string.passwords_storage_account),
                            inset = 0.dp, onClick = { moving = null; perform {
                                ownerOperations.resume(ownerOperations.move(selected, destination, entry.chromeBytes))
                            } }, trailing = { CyclonChevron() })
                    }
                } }, confirmButton = {}, dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel)) { moving = null } }
            ) }
            droppingTransfer?.takeIf { unlocked }?.let { id -> AlertDialog(
                onDismissRequest = { droppingTransfer = null }, title = { Text(stringResource(R.string.passwords_transfer_drop_question)) },
                text = { PasswordDialogProtection(); DialogBody(stringResource(R.string.passwords_transfer_drop_description)) },
                confirmButton = { CyclonTextAction(stringResource(R.string.passwords_transfer_drop)) { droppingTransfer = null; perform { ownerOperations.drop(id) } } },
                dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel)) { droppingTransfer = null } }
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
                text = { PasswordDialogProtection(); DialogBody(stringResource(R.string.passwords_export_csv_warning), scrolls = true) },
                confirmButton = { CyclonTextAction(stringResource(R.string.passwords_export_csv)) {
                    csvExportOpen = false
                    perform(onSuccess = { prepareExport(); exportCsvDocument.launch("passwords.csv") }) {
                        exportIdentity = ownerOperations.identity(selected)
                        exportBytes?.fill(0); exportBytes = PasswordCsv.encode(repository().list().map { it.data }.filterNot { it.blocked })
                    }
                } },
                dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel)) { csvExportOpen = false } }
            )
            if (csvImportOpen && unlocked) AlertDialog(
                onDismissRequest = { csvImportOpen = false; importUri = null }, title = { Text(stringResource(R.string.passwords_import_csv)) },
                text = { PasswordDialogProtection(); DialogBody(stringResource(R.string.passwords_import_csv_warning), scrolls = true) },
                confirmButton = { CyclonTextAction(stringResource(R.string.passwords_import_csv)) {
                    csvImportOpen = false
                    perform {
                        val uri = importUri ?: throw ProtocolException(); importUri = null
                        val bytes = readImport(uri)
                        val passwords = try { PasswordCsv.decode(bytes, System.currentTimeMillis()) } finally { bytes.fill(0) }
                        val ids = ownerOperations.import(selected, passwords)
                        for (id in ids) ownerOperations.resume(id)
                    }
                } },
                dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel)) { csvImportOpen = false; importUri = null } }
            )
        } } }
    }

    /** Back, the title with its count, then storage, tabs and the chosen tab; changes awaiting the owner last. */
    @Composable
    private fun MainPage() {
        val p = Cyclon.palette
        val saved = entries.count { !it.data.blocked }
        CyclonBack(stringResource(R.string.cyclon_back)) { finish() }
        CyclonTitle(stringResource(R.string.passwords_title), status = when {
            !unlocked || !storageChosen -> null
            selected != null && !enabled -> stringResource(R.string.passwords_status_sync_off)
            enabled && (saved > 0 || message == null) -> resources.getQuantityString(R.plurals.passwords_count, saved, saved)
            else -> null
        })
        if (!unlocked) {
            Text(stringResource(if (hasSecureScreenLock) R.string.passwords_unlock_description else R.string.passwords_no_lock_description),
                Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = p.ink)
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                CyclonInkButton(stringResource(if (hasSecureScreenLock) R.string.passwords_unlock else R.string.passwords_open),
                    enabled = !busy, onClick = ::authenticateOwner)
            }
            StatusBlock()
            return
        }
        StatusBlock()

        CyclonSectionLabel(stringResource(R.string.passwords_storage))
        val accounts = AccountManager.get(this@PasswordManagerActivity).getAccountsByType("com.google").toList()
        if (accounts.isEmpty()) CyclonNote(stringResource(R.string.passwords_no_accounts))
        Column(Modifier.selectableGroup()) {
            accounts.forEach { account ->
                CyclonChoiceRow(account.name, stringResource(R.string.passwords_storage_account),
                    selected = storageChosen && account == selected, enabled = !busy) { choose(account) }
            }
            CyclonChoiceRow(stringResource(R.string.passwords_on_device), stringResource(R.string.passwords_on_device_summary),
                selected = storageChosen && selected == null, enabled = !busy) { choose(null) }
        }
        if (!storageChosen) { CyclonNote(stringResource(R.string.passwords_choose_storage)); return }

        CyclonHairline(Modifier.padding(top = 8.dp))
        CyclonTabs(listOf(stringResource(R.string.passwords_tab_passwords), stringResource(R.string.passwords_tab_checkup),
            stringResource(R.string.passwords_tab_settings)), selected = if (showSettings) 2 else if (showCheckup) 1 else 0,
            enabled = { it != 1 || enabled }) { tab ->
            showSettings = tab == 2; showCheckup = tab == 1; detailId = null
        }
        when {
            showSettings -> SettingsTab()
            showCheckup -> CheckupTab()
            else -> PasswordsTab()
        }
        PendingWork()
    }

    @Composable
    private fun StatusBlock() {
        CyclonProgress(busy, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        message?.let { Text(stringResource(it), Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium, color = Cyclon.palette.ink) }
    }

    /** Recovery needs the owner in Settings: one row pointing there from the other tabs. */
    @Composable
    private fun SettingsNeeded() {
        if (trustedRecovery || remoteReset || message == R.string.passwords_locked || message == R.string.passwords_no_vault) {
            CyclonHairline(Modifier.padding(top = 8.dp))
            CyclonRow(stringResource(R.string.passwords_open_settings), onClick = { showSettings = true; showCheckup = false; detailId = null },
                trailing = { CyclonChevron() })
        }
    }

    @Composable
    private fun PasswordsTab() {
        SettingsNeeded()
        if (selected != null && !enabled) {
            CyclonNote(stringResource(R.string.passwords_sync_off), Modifier.padding(top = 8.dp))
            CyclonHairline()
            CyclonSwitchRow(stringResource(R.string.passwords_sync), null, enabled, onChange = ::setSync, enabled = !busy)
        }
        OutlinedTextField(query, { query = it }, singleLine = true, colors = cyclonFieldColors(),
            label = { Text(stringResource(R.string.passwords_search)) },
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp))
        if (enabled) Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CyclonInkButton(stringResource(R.string.passwords_add), enabled = !busy) { editing = null; editorOpen = true }
            CyclonLineButton(stringResource(R.string.passwords_refresh), enabled = !busy) { perform { repository().refresh() } }
        }
        val visible = entries.filter { !it.data.blocked && it.data.matchesSearch(query) }
        if (enabled || visible.isNotEmpty()) CyclonSectionLabel(stringResource(R.string.passwords_saved))
        if (enabled && visible.isEmpty() && !busy)
            CyclonNote(stringResource(if (query.isBlank()) R.string.passwords_empty else R.string.passwords_no_results))
        visible.forEach { entry ->
            CyclonRow(loginTitle(entry), entry.data.username.ifEmpty { stringResource(R.string.passwords_assistant_saved_login) },
                onClick = { detailId = entry.id }, enabled = !busy, summaryLines = 1,
                leading = { CyclonMonogram(loginTitle(entry), loginPackage(entry)) }, trailing = { CyclonChevron() })
        }
    }

    @Composable
    private fun CheckupTab() {
        SettingsNeeded()
        OutlinedTextField(query, { query = it }, singleLine = true, colors = cyclonFieldColors(),
            label = { Text(stringResource(R.string.passwords_search)) },
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp))
        Column(Modifier.selectableGroup()) {
            CyclonChoiceRow(stringResource(R.string.passwords_checkup_active), null, !dismissedCheckup) { dismissedCheckup = false }
            CyclonChoiceRow(stringResource(R.string.passwords_checkup_dismissed), null, dismissedCheckup) { dismissedCheckup = true }
        }
        CyclonHairline()
        PasswordCheckupPanel(entries, query, dismissedCheckup, busy,
            onEdit = { editing = it; editorOpen = true }, onDelete = { deleting = it },
            onDismissal = { entry, dismissed -> checkupDismissal = entry to dismissed },
            onWebsite = { url -> requireOwner(); runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { message = R.string.passwords_sync_failed } })
    }

    /** Sync, recovery and the account's pages, backups, then never-saved websites: Assistant access's sections. */
    @Composable
    private fun SettingsTab() {
        val account = selected
        if (account != null) {
            CyclonSectionLabel(stringResource(R.string.passwords_section_sync))
            CyclonSwitchRow(stringResource(R.string.passwords_sync), account.name, enabled, onChange = ::setSync, enabled = !busy)
            if (enabled) CyclonRow(stringResource(R.string.passwords_refresh), onClick = { perform { repository().refresh() } }, enabled = !busy)
        }
        if (enabled) {
            if (message == R.string.passwords_locked && !trustedRecovery) {
                OutlinedTextField(passphrase, { passphrase = it }, label = { Text(stringResource(R.string.passwords_passphrase)) },
                    colors = cyclonFieldColors(), singleLine = true, modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    CyclonInkButton(stringResource(R.string.passwords_unlock), enabled = !busy && passphrase.isNotEmpty()) {
                        showCheckup = false
                        val secret = passphrase.toByteArray(Charsets.UTF_8); passphrase = ""
                        perform { try { repository().unlockPassphrase(secret); repository().refresh() } finally { secret.fill(0) } }
                    }
                }
            }
            if (trustedRecovery && account != null)
                CyclonRow(stringResource(R.string.passwords_recover_account_keys), onClick = ::recoverAccountKeys, enabled = !busy, trailing = { CyclonChevron() })
            if (remoteReset) {
                CyclonNote(stringResource(R.string.passwords_reset_pending), Modifier.padding(top = 8.dp), color = Cyclon.palette.ink)
                Row(Modifier.padding(horizontal = 20.dp)) {
                    CyclonLineButton(stringResource(R.string.passwords_reset_accept), enabled = !busy) { resetOpen = true }
                }
            }
            if (message == R.string.passwords_no_vault) Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                CyclonInkButton(stringResource(R.string.passwords_create_vault), enabled = !busy) {
                    perform { repository().initializeEmptyVault(); repository().refresh() }
                }
            }
        }
        if (account != null && (android.os.Build.VERSION.SDK_INT >= 28 || enabled)) {
            CyclonSectionLabel(stringResource(R.string.passwords_section_security))
            if (android.os.Build.VERSION.SDK_INT >= 28) AccountPageRow(R.string.passwords_encryption_manage, PasswordEncryptionActivity::class.java)
            if (enabled) {
                AccountPageRow(R.string.passwords_recovery_enroll_phone, PasswordRecoveryEnrollmentActivity::class.java)
                AccountPageRow(R.string.passwords_breach_assess, PasswordBreachActivity::class.java)
                AccountPageRow(R.string.passwords_synced_passkeys, SyncedPasskeyManagerActivity::class.java)
                CyclonSectionLabel(stringResource(R.string.passwords_section_sharing))
                AccountPageRow(R.string.passwords_share, PasswordSharingActivity::class.java)
                AccountPageRow(R.string.passwords_wifi_title, PasswordWifiSyncActivity::class.java)
            }
        }
        if (enabled) {
            CyclonSectionLabel(stringResource(R.string.passwords_section_backup))
            CyclonRow(stringResource(R.string.passwords_export), onClick = { archiveImport = false }, enabled = !busy)
            CyclonRow(stringResource(R.string.passwords_import), onClick = {
                csvImportRequested = false; importDocument.launch(arrayOf("application/octet-stream", "application/x-cyclon-passwords"))
            }, enabled = !busy)
            CyclonRow(stringResource(R.string.passwords_export_csv), onClick = { csvExportOpen = true }, enabled = !busy)
            CyclonRow(stringResource(R.string.passwords_import_csv), onClick = {
                csvImportRequested = true; importDocument.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel"))
            }, enabled = !busy)
        }
        CyclonSectionLabel(stringResource(R.string.passwords_never_saved))
        CyclonNote(stringResource(R.string.passwords_never_saved_description))
        CyclonHairline()
        entries.filter { it.data.blocked }.forEach { entry ->
            CyclonRow(loginTitle(entry), if (loginTitle(entry) != loginTarget(entry)) loginTarget(entry) else null, summaryLines = 1,
                trailing = { CyclonTextAction(stringResource(R.string.passwords_allow_save), enabled = !busy) { deleting = entry } })
        }
        if (enabled) CyclonRow(stringResource(R.string.passwords_never_save_add), onClick = { blockedSiteOpen = true }, enabled = !busy,
            trailing = { CyclonVoice("+", color = Cyclon.palette.ink, size = 20) })
    }

    @Composable
    private fun AccountPageRow(title: Int, page: Class<*>) {
        CyclonRow(stringResource(title), onClick = { openAccountPage(page) }, enabled = !busy, trailing = { CyclonChevron() })
    }

    /** Changes and transfers that still need the owner, on every tab. */
    @Composable
    private fun PendingWork() {
        if (pendingChanges.isNotEmpty()) CyclonSectionLabel(stringResource(R.string.passwords_changes))
        pendingChanges.forEach { pending ->
            CyclonRow(pending.realm, stringResource(if (pending.kind == MutationKind.DELETE) R.string.passwords_delete else R.string.passwords_save) + " · " +
                stringResource(when (pending.phase) {
                    MutationPhase.QUEUED -> R.string.passwords_change_waiting
                    MutationPhase.CONFLICT -> R.string.passwords_change_conflict
                    else -> R.string.passwords_change_uncertain
                }), trailing = { CyclonTextAction(stringResource(R.string.passwords_resolve), enabled = !busy) { resolving = pending } })
        }
        if (transfers.isNotEmpty()) CyclonSectionLabel(stringResource(R.string.passwords_transfers))
        transfers.forEach { transfer ->
            val after = PasswordData.decode(Proto.decode(transfer.desired).one(1)).realm
            val source = if (transfer.source == null) stringResource(R.string.passwords_transfer_import_source)
                else transfer.source?.accountName ?: stringResource(R.string.passwords_on_device)
            val lines = mutableListOf(source + " → " + (transfer.destination.accountName ?: stringResource(R.string.passwords_on_device)))
            transfer.original?.let { original ->
                val before = PasswordData.decode(Proto.decode(original).one(1)).realm
                if (before != after) lines += "$before → $after"
            }
            lines += stringResource(if (transfer.phase == PasswordTransferPhase.COPY) R.string.passwords_transfer_state_copy else R.string.passwords_transfer_state_remove)
            CyclonRow(after, lines.joinToString("\n"), divider = false)
            Row(Modifier.padding(horizontal = 4.dp)) {
                CyclonTextAction(stringResource(R.string.passwords_transfer_resume), enabled = !busy) { perform { ownerOperations.resume(transfer.id) } }
                CyclonTextAction(stringResource(R.string.passwords_transfer_drop), enabled = !busy) { droppingTransfer = transfer.id }
            }
            CyclonHairline(Modifier.padding(start = 20.dp))
        }
    }

    /** One saved login: what it signs in to, its username, its actions and whether Cyclon may use it. Never the password. */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun DetailPage(entry: PasswordView) {
        val access = assistantAccess[entry.id]
        CyclonBack(stringResource(R.string.passwords_back_to_passwords)) { detailId = null }
        CyclonTitle(loginTitle(entry), leading = { CyclonMonogram(loginTitle(entry), loginPackage(entry), 44.dp) })
        StatusBlock()
        CyclonSectionLabel(stringResource(R.string.passwords_login))
        DetailField(stringResource(if (loginPackage(entry) != null) R.string.passwords_app else R.string.passwords_website), loginTarget(entry))
        DetailField(stringResource(R.string.passwords_username), entry.data.username.ifEmpty { stringResource(R.string.passwords_assistant_saved_login) })
        if (entry.data.displayName.isNotEmpty()) DetailField(stringResource(R.string.passwords_name), entry.data.displayName)
        FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CyclonInkButton(stringResource(R.string.passwords_edit), enabled = !busy) { editing = entry; editorOpen = true }
            CyclonLineButton(stringResource(R.string.passwords_move), enabled = !busy) { moving = entry }
            CyclonTextAction(stringResource(R.string.passwords_delete), enabled = !busy) { deleting = entry }
        }
        if (access != null) {
            CyclonSectionLabel(stringResource(R.string.passwords_assistant_access))
            val details = listOfNotNull(
                stringResource(R.string.passwords_assistant_authentication_summary).takeIf { access.requireDeviceAuthentication },
                access.lastUsed?.let { used -> stringResource(R.string.passwords_assistant_last_used,
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(used))) })
            CyclonRow(stringResource(R.string.passwords_assistant_access_status, stringResource(accessLabel(access.level))),
                details.joinToString("\n").ifEmpty { null }, onClick = { accessEditing = entry }, enabled = !busy,
                trailing = { CyclonChevron() })
        }
    }

    private fun setSync(value: Boolean) = perform {
        repository().setEnabled(value)
        if (value) repository().refresh()
    }

    /** Opens one of the selected Google account's pages, bound to its exact account incarnation. */
    private fun openAccountPage(page: Class<*>) {
        requireOwner()
        val account = selected ?: return
        val identity = ownerOperations.identity(account)
        startActivity(Intent(this, page).putExtra("account", account.name).putExtra("google_user_id", identity.userId))
    }

    private fun recoverAccountKeys() {
        requireOwner()
        val account = selected ?: return
        val identity = ownerOperations.identity(account)
        recoverKeys.launch(Intent(this, GenericActivity::class.java)
            .putExtra(GenericActivity.EXTRA_ACCOUNT_NAME, account.name)
            .putExtra(GenericActivity.EXTRA_ACCOUNT_ID, identity.userId)
            .putExtra(GenericActivity.EXTRA_SECURITY_DOMAIN, "chromesync")
            .putExtra(GenericActivity.EXTRA_OPERATION, 1)
            .putExtra(GenericActivity.EXTRA_OFFER_RESET, false)
            .putExtra(GenericActivity.EXTRA_PASSWORD_OWNER_RECOVERY, true))
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

/** The app a login belongs to, for its icon; null for websites. */
private fun loginPackage(entry: PasswordView): String? =
    loginTarget(entry).takeIf { entry.data.realm.startsWith("android://") && it.isNotEmpty() }

/** One read-only field of a login: its label above, the value in ink. */
@Composable
private fun DetailField(label: String, value: String) {
    val p = Cyclon.palette
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = p.muted)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = p.ink)
    }
    CyclonHairline(Modifier.padding(start = 20.dp))
}

/** A dialog's explanation; [scrolls] for long warnings on small screens and large fonts. */
@Composable
private fun DialogBody(text: String, modifier: Modifier = Modifier, scrolls: Boolean = false) {
    Text(text, (if (scrolls) modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()) else modifier),
        style = MaterialTheme.typography.bodyMedium, color = Cyclon.palette.ink)
}

@Composable
private fun SignInAccessDialog(entry: PasswordView, current: AgentSignInAccess.Level, currentRequireAuthentication: Boolean,
                               hasSecureScreenLock: Boolean, busy: Boolean, onDismiss: () -> Unit,
                               onSetScreenLock: () -> Unit, onSave: (AgentSignInAccess.Level, Boolean) -> Unit) {
    var selected by remember(entry.id, current) { mutableStateOf(current) }
    var requireAuthentication by remember(entry.id, currentRequireAuthentication) { mutableStateOf(currentRequireAuthentication) }
    val p = Cyclon.palette
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.passwords_assistant_access)) },
        text = {
            PasswordDialogProtection()
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CyclonMonogram(loginTitle(entry), loginPackage(entry), 32.dp)
                    Column(Modifier.weight(1f)) {
                        Text(loginTitle(entry), style = MaterialTheme.typography.titleMedium, color = p.ink)
                        Text(entry.data.username.ifEmpty { stringResource(R.string.passwords_assistant_saved_login) },
                            style = MaterialTheme.typography.bodyMedium, color = p.muted)
                    }
                }
                Text(stringResource(R.string.passwords_assistant_access_description), Modifier.padding(top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = p.muted)
                Column(Modifier.selectableGroup()) {
                    AgentSignInAccess.Level.values().forEach { level ->
                        val choiceEnabled = !busy && !(requireAuthentication && level == AgentSignInAccess.Level.ALWAYS)
                        CyclonChoiceRow(stringResource(accessLabel(level)), stringResource(when (level) {
                            AgentSignInAccess.Level.ASK -> R.string.passwords_assistant_ask_description
                            AgentSignInAccess.Level.ALWAYS -> R.string.passwords_assistant_always_description
                            AgentSignInAccess.Level.NEVER -> R.string.passwords_assistant_never_description
                        }), selected == level, enabled = choiceEnabled, inset = 0.dp) { selected = level }
                    }
                }
                CyclonHairline()
                CyclonSwitchRow(stringResource(R.string.passwords_assistant_require_authentication),
                    stringResource(R.string.passwords_assistant_require_authentication_description), requireAuthentication,
                    onChange = { enabled ->
                        requireAuthentication = enabled
                        if (enabled && selected == AgentSignInAccess.Level.ALWAYS) selected = AgentSignInAccess.Level.ASK
                    }, enabled = !busy && (hasSecureScreenLock || requireAuthentication), inset = 0.dp, divider = false)
                if (!hasSecureScreenLock) {
                    Text(stringResource(R.string.passwords_assistant_screen_lock_required), style = MaterialTheme.typography.bodySmall, color = p.ink)
                    CyclonTextAction(stringResource(R.string.passwords_assistant_set_screen_lock), Modifier.offset(x = (-16).dp),
                        enabled = !busy, onClick = onSetScreenLock)
                }
                Text(stringResource(R.string.passwords_assistant_revoke_description), Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = p.muted)
            }
        },
        confirmButton = { CyclonTextAction(stringResource(R.string.passwords_save),
            enabled = !busy && (selected != current || requireAuthentication != currentRequireAuthentication)) {
            onSave(selected, requireAuthentication)
        } },
        dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel), onClick = onDismiss) })
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
    val fields = cyclonFieldColors()
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.passwords_add else R.string.passwords_edit)) },
        text = { PasswordDialogProtection(); Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(url, { url = it }, enabled = existing?.data?.realm?.startsWith("android://") != true, label = { Text(stringResource(R.string.passwords_site)) },
                singleLine = true, colors = fields, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.passwords_username)) },
                singleLine = true, colors = fields, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.passwords_password)) },
                singleLine = true, colors = fields, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.passwords_name)) },
                singleLine = true, colors = fields, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.passwords_note)) }, minLines = 2, maxLines = 4,
                colors = fields, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (noteVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            CyclonTextAction(stringResource(if (noteVisible) R.string.passwords_hide_note else R.string.passwords_show_note),
                Modifier.offset(x = (-16).dp)) { noteVisible = !noteVisible }
            if (invalid) Text(stringResource(R.string.passwords_invalid), style = MaterialTheme.typography.bodySmall, color = Cyclon.palette.error)
        } },
        confirmButton = { CyclonTextAction(stringResource(R.string.passwords_save)) {
            val data = runCatching {
                if (password.isEmpty()) throw ProtocolException()
                val now = System.currentTimeMillis()
                (existing?.data?.withIdentity(url, username)?.withPassword(password, now) ?: PasswordData.create(
                    PasswordData.webRealm(url), url, username, password, now)).withDisplayName(name).withDefaultNote(note, now)
            }.getOrNull()
            if (data == null) invalid = true else onSave(data)
        } },
        dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel), onClick = onDismiss) })
}

@Composable
private fun PasswordArchiveDialog(importing: Boolean, dismiss: () -> Unit, confirm: (CharArray) -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss,
        title = { Text(stringResource(if (importing) R.string.passwords_import else R.string.passwords_export)) },
        text = { PasswordDialogProtection(); Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DialogBody(stringResource(if (importing) R.string.passwords_import_description else R.string.passwords_archive_description))
            OutlinedTextField(passphrase, { passphrase = it }, label = { Text(stringResource(R.string.passwords_archive_password)) },
                singleLine = true, colors = cyclonFieldColors(), modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        } },
        confirmButton = { CyclonTextAction(stringResource(if (importing) R.string.passwords_import else R.string.passwords_export),
            enabled = passphrase.length in 12..1024) {
            val value = passphrase.toCharArray(); passphrase = ""; confirm(value)
        } },
        dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel)) { passphrase = ""; dismiss() } })
}

@Composable
private fun BlockedSiteEditor(onDismiss: () -> Unit, onSave: (PasswordData) -> Unit) {
    var url by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.passwords_never_save_add)) },
        text = { PasswordDialogProtection(); Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.passwords_site)) }, singleLine = true,
                colors = cyclonFieldColors(), modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            if (invalid) Text(stringResource(R.string.passwords_invalid_site), style = MaterialTheme.typography.bodySmall, color = Cyclon.palette.error)
        } },
        confirmButton = { CyclonTextAction(stringResource(R.string.passwords_save)) {
            val data = runCatching { PasswordData.blockedSite(url, System.currentTimeMillis()) }.getOrNull()
            if (data == null) invalid = true else onSave(data)
        } },
        dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel), onClick = onDismiss) })
}
