/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import org.microg.gms.cyclon.DeviceAccount
import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.OperationCanceledException
import android.content.Intent
import android.content.ContentResolver
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.ui.Alignment
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.R
import org.microg.gms.cyclon.CyclonMaterialTheme
import org.microg.gms.cyclon.CyclonOutlinedButton
import org.microg.gms.cyclon.CyclonTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.microg.gms.people.sync.ContactsDiagnostics
import org.microg.gms.people.sync.ActivationFailure
import org.microg.gms.people.sync.ActivationStage
import org.microg.gms.people.sync.ActivationAccountRemoved
import org.microg.gms.people.sync.ApiException
import org.microg.gms.people.sync.activationFailure
import org.microg.gms.people.sync.SyncMode
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ContactsSyncActivity : ComponentActivity() {
    private var pending: Pair<Account, SyncMode>? = null
    private var retryChoice: Pair<Account, SyncMode>? = null
    private var waitingForPermission = false
    private var busy by mutableStateOf(false)
    private var failure: ActivationFailure? by mutableStateOf(null)
    private var revision by mutableStateOf(0)
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        waitingForPermission = false
        val choice = pending
        if (choice != null && arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) authorize(choice.first, choice.second)
        else { failure = ActivationFailure.PERMISSION; busy = false; pending = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString("turnOffAccount")?.let { name ->
            turnOff = TurnOff(Account(name, "com.google"), savedInstanceState.getInt("turnOffUnsynced"))
        }
        if (savedInstanceState?.getBoolean("waitingForPermission") == true) {
            val name = savedInstanceState.getString("pendingAccount")
            val mode = savedInstanceState.getString("pendingMode")?.let { runCatching { SyncMode.valueOf(it) }.getOrNull() }
            if (name != null && mode != null) {
                pending = Account(name, "com.google") to mode
                retryChoice = pending
                waitingForPermission = true
                busy = true
            }
        }
        retryPendingRemovals()
        setContent {
            CyclonMaterialTheme {
                LaunchedEffect(Unit) { while (true) { delay(3000); revision++ } }
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CyclonTextButton(onClick = { finish() }) { Text(stringResource(R.string.cyclon_back)) }
                        Header()
                        @Suppress("UNUSED_EXPRESSION")
                        revision
                        val accounts = DeviceAccount.visible(this@ContactsSyncActivity, AccountManager.get(this@ContactsSyncActivity).getAccountsByType("com.google"))
                        // Platform settings aren't observable: read them here, on every revision tick, and pass them down.
                        val privacy = ContactMigrationPrivacy(this@ContactsSyncActivity)
                        if (com.google.android.gms.BuildConfig.DEBUG) DeveloperControls(privacy.developerControlsEnabled)
                        if (!ContentResolver.getMasterSyncAutomatically() &&
                            accounts.any { ContactSyncPreferences(this@ContactsSyncActivity, it).mode != SyncMode.OFF }) AutoSyncOff()
                        if (privacy.held) MigrationHold()
                        failure?.let { ActivationProblem(it) }
                        if (accounts.isEmpty()) Notice(stringResource(R.string.contacts_sync_no_accounts)) {
                            CyclonOutlinedButton(onClick = {
                                startActivity(Intent(Settings.ACTION_ADD_ACCOUNT).putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google")))
                            }) { Text(stringResource(R.string.contacts_sync_add_account)) }
                        }
                        // Keyed: each card keeps its own state (e.g. the photo) when accounts are added or removed.
                        for (account in accounts) key(account.name) { AccountCard(account) }
                        // Google's "Also sync device contacts": offered while an account uploads (Two-way).
                        val targets = syncTargets()
                        var autoAccount = DeviceContacts.autoAccount(this@ContactsSyncActivity)
                        if (autoAccount != null && accounts.none { it.name == autoAccount }) { // its account was removed
                            DeviceContacts.setAutoAccount(this@ContactsSyncActivity, null); autoAccount = null
                        }
                        val deviceCount = if ((targets.isEmpty() && autoAccount == null) || !hasContactsPermission) 0
                            else runCatching { withDeviceContacts { it.count() } }.getOrDefault(0)
                        // A kept opt-in whose account doesn't upload right now shows as paused, never as off.
                        if ((targets.isNotEmpty() && deviceCount > 0) || autoAccount != null)
                            DeviceContactsCard(deviceCount, autoAccount, paused = targets.none { it.name == autoAccount }, canSave = targets.isNotEmpty())
                        MoreOptions()
                    }
                }
                turnOff?.let { TurnOffDialog(it) }
                if (resumeUploads) ResumeUploadsDialog()
                newContactsConfirmation?.let { NewContactsDialog(it) }
                if (autoSyncConfirmation) AutoSyncDialog()
                deviceEntries?.let { entries -> syncTargets().takeIf { it.isNotEmpty() }?.let { MoveDeviceContactsDialog(entries, it) } }
                if (deviceAutoConfirmation) syncTargets().takeIf { it.isNotEmpty() }?.let { DeviceAutoDialog(it) }
            }
        }
    }

    private var resumeUploads by mutableStateOf(false)
    private var newContactsConfirmation by mutableStateOf<Account?>(null)

    @Composable
    private fun Header() {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_contacts_sync_hero), contentDescription = null, modifier = Modifier.size(28.dp))
            }
            Text(stringResource(R.string.contacts_sync_title), style = MaterialTheme.typography.headlineMedium)
            val link = stringResource(R.string.contacts_sync_header_link)
            val full = stringResource(R.string.contacts_sync_header, link)
            val start = full.indexOf(link)
            val linkColor = MaterialTheme.colorScheme.onSurface
            val text = remember(full, linkColor) {
                buildAnnotatedString {
                    append(full)
                    if (start >= 0) {
                        addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), start, start + link.length)
                    }
                }
            }
            // The whole sentence opens the link: focusable, Enter/Space and TalkBack come with clickable.
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClickLabel = link) { openUrl(GOOGLE_CONTACTS) })
            Text(stringResource(R.string.contacts_sync_header_note), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    /** A tinted message block, optionally with actions below the text. */
    @Composable
    private fun Notice(text: String, title: String? = null, error: Boolean = false, actions: (@Composable RowScope.() -> Unit)? = null) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (error) Icons.Filled.Warning else Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(20.dp),
                    tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                    actions?.let { Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = it) }
                }
            }
        }
    }

    @Composable
    private fun DeveloperControls(enabled: Boolean) {
        SwitchRow(stringResource(R.string.contacts_sync_developer_controls), null, enabled) {
            ContactMigrationPrivacy(this).developerControlsEnabled = it; revision++
        }
    }

    @Composable
    private fun MigrationHold() {
        Notice(stringResource(R.string.contacts_sync_migration_held), title = stringResource(R.string.contacts_sync_migration_held_title)) {
            CyclonOutlinedButton(onClick = { resumeUploads = true }) { Text(stringResource(R.string.contacts_sync_resume_uploads)) }
        }
    }

    // Like Google's screen: account sync data is only refreshed automatically while Android's auto-sync is on.
    @Composable
    private fun AutoSyncOff() {
        Notice(stringResource(R.string.contacts_sync_auto_sync_off)) {
            CyclonOutlinedButton(onClick = { autoSyncConfirmation = true }) { Text(stringResource(R.string.contacts_sync_turn_on_auto_sync)) }
        }
    }

    private var autoSyncConfirmation by mutableStateOf(false)

    @Composable
    private fun AutoSyncDialog() {
        AlertDialog(onDismissRequest = { autoSyncConfirmation = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.contacts_sync_auto_sync_dialog_title)) },
            text = { Text(stringResource(R.string.contacts_sync_auto_sync_dialog_body)) },
            confirmButton = { CyclonTextButton(onClick = {
                runCatching { ContentResolver.setMasterSyncAutomatically(true) }
                autoSyncConfirmation = false; revision++
            }) { Text(stringResource(R.string.contacts_sync_turn_on)) } },
            dismissButton = { CyclonTextButton(onClick = { autoSyncConfirmation = false }) { Text(stringResource(R.string.contacts_sync_cancel)) } })
    }

    private fun syncTargets() = DeviceAccount.visible(this, AccountManager.get(this).getAccountsByType("com.google"))
        .filter { ContactSyncPreferences(this, it).mode == SyncMode.TWO_WAY }

    private var deviceEntries by mutableStateOf<List<DeviceContacts.Entry>?>(null)
    private var deviceAutoConfirmation by mutableStateOf(false)

    @Composable
    private fun DeviceContactsCard(count: Int, autoAccount: String?, paused: Boolean, canSave: Boolean) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.contacts_sync_device_title), style = MaterialTheme.typography.titleMedium)
                if (count > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(pluralStringResource(R.plurals.contacts_sync_device_not_synced, count, count), style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.contacts_sync_device_info), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (count > 0 && canSave) CyclonOutlinedButton(onClick = { openDeviceReview() }, enabled = !busy) {
                    Text(stringResource(R.string.contacts_sync_device_review))
                }
            }
            HorizontalDivider()
            Column(Modifier.padding(horizontal = 16.dp)) {
                SwitchRow(stringResource(R.string.contacts_sync_device_auto), autoAccount?.let {
                    stringResource(if (paused) R.string.contacts_sync_device_auto_paused else R.string.contacts_sync_device_auto_on, it) },
                    autoAccount != null) { on ->
                    if (on) deviceAutoConfirmation = true else { DeviceContacts.setAutoAccount(this@ContactsSyncActivity, null); revision++ }
                }
                Text(stringResource(R.string.contacts_sync_device_exclusions), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            }
        }
    }

    private fun openDeviceReview() {
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { runCatching { withDeviceContacts { it.list() } }.getOrDefault(emptyList()) }
            if (entries.isNotEmpty()) deviceEntries = entries
        }
    }

    @Composable
    private fun AccountChoice(targets: List<Account>, selected: Account, onSelect: (Account) -> Unit) {
        if (targets.size < 2) return
        Text(stringResource(R.string.contacts_sync_device_account), style = MaterialTheme.typography.labelLarge)
        for (account in targets) Row(Modifier.fillMaxWidth().selectable(selected = account == selected, role = Role.RadioButton) { onSelect(account) }
            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = account == selected, onClick = null)
            Spacer(Modifier.width(8.dp))
            Text(account.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun MoveDeviceContactsDialog(entries: List<DeviceContacts.Entry>, targets: List<Account>) {
        val savable = remember(entries) { entries.filter { it.supported }.map { it.id } }
        val selected = remember(entries) { mutableStateListOf<Long>().apply { addAll(savable) } }
        var target by remember(targets) { mutableStateOf(targets.first()) }
        AlertDialog(onDismissRequest = { deviceEntries = null }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.contacts_sync_device_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.contacts_sync_device_dialog_body), style = MaterialTheme.typography.bodyMedium)
                    AccountChoice(targets, target) { target = it }
                    val all = savable.isNotEmpty() && selected.size == savable.size
                    Row(Modifier.fillMaxWidth().toggleable(value = all, enabled = savable.isNotEmpty(), role = Role.Checkbox) { on ->
                        selected.clear(); if (on) selected.addAll(savable)
                    }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = all, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.contacts_sync_device_select_all), style = MaterialTheme.typography.titleSmall)
                    }
                    HorizontalDivider()
                    // A plain scrolling column: lazy lists can't be measured inside a dialog's content.
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        for (entry in entries) Row(Modifier.fillMaxWidth().toggleable(value = entry.id in selected, enabled = entry.supported,
                            role = Role.Checkbox) { on -> if (on) selected.add(entry.id) else selected.remove(entry.id)
                        }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = entry.id in selected, onCheckedChange = null, enabled = entry.supported)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(entry.name ?: stringResource(R.string.contacts_sync_device_no_name), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (entry.supported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                if (!entry.supported) Text(stringResource(R.string.contacts_sync_device_unsupported),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { CyclonTextButton(enabled = selected.isNotEmpty(), onClick = {
                val ids = selected.toList(); deviceEntries = null; moveDeviceContacts(target, ids)
            }) { Text(pluralStringResource(R.plurals.contacts_sync_device_save, selected.size, selected.size)) } },
            dismissButton = { CyclonTextButton(onClick = { deviceEntries = null }) { Text(stringResource(R.string.contacts_sync_cancel)) } })
    }

    private fun moveDeviceContacts(account: Account, ids: List<Long>) {
        busy = true
        lifecycleScope.launch {
            val prefs = ContactSyncPreferences(this@ContactsSyncActivity, account)
            // Stops at once if sync is turned off meanwhile, so nothing moves into an account that won't upload it.
            val result = withContext(Dispatchers.IO) {
                runCatching { withDeviceContacts { it.moveTo(account, ids) { prefs.mode == SyncMode.TWO_WAY } } }
                    .getOrDefault(DeviceContacts.MoveResult(0, 0))
            }
            busy = false; revision++
            if (result.moved > 0) prefs.refresh()
            val message = buildList {
                add(resources.getQuantityString(R.plurals.contacts_sync_device_saved, result.moved, result.moved, account.name))
                if (result.unsupported > 0) add(resources.getQuantityString(R.plurals.contacts_sync_device_kept, result.unsupported, result.unsupported))
            }.joinToString("\n")
            Toast.makeText(this@ContactsSyncActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    @Composable
    private fun DeviceAutoDialog(targets: List<Account>) {
        var target by remember(targets) { mutableStateOf(targets.first()) }
        AlertDialog(onDismissRequest = { deviceAutoConfirmation = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.contacts_sync_device_auto_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.contacts_sync_device_auto_dialog_body), style = MaterialTheme.typography.bodyMedium)
                    if (targets.size < 2) Text(target.name, style = MaterialTheme.typography.titleSmall)
                    AccountChoice(targets, target) { target = it }
                    Text(stringResource(R.string.contacts_sync_device_exclusions), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { CyclonTextButton(onClick = {
                DeviceContacts.setAutoAccount(this@ContactsSyncActivity, target)
                deviceAutoConfirmation = false; revision++
                ContactSyncPreferences(this@ContactsSyncActivity, target).refresh()
            }) { Text(stringResource(R.string.contacts_sync_turn_on)) } },
            dismissButton = { CyclonTextButton(onClick = { deviceAutoConfirmation = false }) { Text(stringResource(R.string.contacts_sync_cancel)) } })
    }

    @Composable
    private fun MoreOptions() {
        Column {
            Text(stringResource(R.string.contacts_sync_more_title), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            LinkRow(stringResource(R.string.contacts_sync_open_google_contacts), stringResource(R.string.contacts_sync_open_google_contacts_summary)) {
                openUrl(GOOGLE_CONTACTS)
            }
            LinkRow(stringResource(R.string.contacts_sync_account_settings), stringResource(R.string.contacts_sync_account_settings_summary)) {
                runCatching { startActivity(Intent(Settings.ACTION_SYNC_SETTINGS)) }
            }
        }
    }

    @Composable
    private fun LinkRow(title: String, summary: String, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun SwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = !busy, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Switch(checked = checked, onCheckedChange = null, enabled = !busy)
        }
    }

    @Composable
    private fun ResumeUploadsDialog() {
        AlertDialog(onDismissRequest = { resumeUploads = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.contacts_sync_resume_uploads)) },
            text = { Text(stringResource(R.string.contacts_sync_resume_uploads_consent)) },
            confirmButton = { CyclonTextButton(onClick = {
                ContactMigrationPrivacy(this).ownerResume()
                AccountManager.get(this).getAccountsByType("com.google").forEach { ContactSyncPreferences(this, it).refresh() }
                resumeUploads = false; revision++
            }) { Text(stringResource(R.string.contacts_sync_resume_uploads)) } },
            dismissButton = { CyclonTextButton(onClick = { resumeUploads = false }) { Text(stringResource(R.string.contacts_sync_cancel)) } })
    }

    @Composable
    private fun NewContactsDialog(account: Account) {
        AlertDialog(onDismissRequest = { newContactsConfirmation = null }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.contacts_sync_upload_new)) },
            text = { Text(stringResource(R.string.contacts_sync_upload_new_consent)) },
            confirmButton = { CyclonTextButton(onClick = {
                ContactSyncPreferences(this, account).apply { uploadNewContacts = true; refresh() }
                newContactsConfirmation = null; revision++
            }) { Text(stringResource(R.string.contacts_sync_allow)) } },
            dismissButton = { CyclonTextButton(onClick = { newContactsConfirmation = null }) { Text(stringResource(R.string.contacts_sync_cancel)) } })
    }

    private data class TurnOff(val account: Account, val unsynced: Int)
    private var turnOff by mutableStateOf<TurnOff?>(null)
    private val hasContactsPermission get() = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    @Composable
    private fun ActivationProblem(reason: ActivationFailure) {
        val retry = retryChoice
        val hasAction = reason == ActivationFailure.PERMISSION || retry != null
        Notice(stringResource(when (reason) {
            ActivationFailure.PERMISSION -> R.string.contacts_sync_enable_permission
            ActivationFailure.CANCELLED -> R.string.contacts_sync_enable_cancelled
            ActivationFailure.AUTHORIZATION -> R.string.contacts_sync_enable_authorization
            ActivationFailure.NETWORK -> R.string.contacts_sync_enable_network
            ActivationFailure.API_DENIED -> R.string.contacts_sync_enable_api_denied
            ActivationFailure.API_UNAVAILABLE -> R.string.contacts_sync_enable_api_unavailable
            ActivationFailure.NEEDS_UPDATE -> R.string.contacts_sync_needs_update
            ActivationFailure.TWO_WAY_UNAVAILABLE -> R.string.contacts_sync_two_way_unavailable
            ActivationFailure.RETRY_LATER -> R.string.contacts_sync_enable_retry_later
            ActivationFailure.ACCOUNT_REMOVED -> R.string.contacts_sync_enable_account_removed
            ActivationFailure.LOCAL -> R.string.contacts_sync_enable_local
            ActivationFailure.TIMEOUT -> R.string.contacts_sync_enable_timeout
        }), error = true, actions = if (!hasAction) null else ({
            if (reason == ActivationFailure.PERMISSION) CyclonOutlinedButton(onClick = { openAppPermissions() }) {
                Text(stringResource(R.string.contacts_sync_open_permissions))
            }
            retry?.let { choice ->
                CyclonOutlinedButton(onClick = { choose(choice.first, choice.second) }, enabled = !busy) {
                    Text(stringResource(R.string.contacts_sync_retry))
                }
            }
        }))
    }

    @Composable
    private fun AccountCard(account: Account) {
        val prefs = ContactSyncPreferences(this, account)
        val on = prefs.mode != SyncMode.OFF
        val status = statusOf(account, prefs)
        val toggleLabel = stringResource(R.string.contacts_sync_toggle_description, account.name)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Avatar(account)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Like Google's card: the account's name above its email address, when microG knows the name.
                    val name = remember(account.name) {
                        runCatching { PeopleManager.getDisplayName(this@ContactsSyncActivity, account.name) }.getOrNull()?.takeIf { it.isNotBlank() }
                    }
                    Text(name ?: account.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (name != null) Text(account.name, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    StatusLine(status, prefs)
                }
                Switch(checked = on, enabled = !busy, modifier = Modifier.semantics { contentDescription = toggleLabel }, onCheckedChange = { checked ->
                    // Like Google's sync, turning it on keeps both sides in sync.
                    if (checked) choose(account, SyncMode.TWO_WAY) else confirmTurnOff(account)
                })
            }
            if (busy && (pending == null || pending?.first == account)) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!on) return@OutlinedCard
            problemOf(status)?.let { problem ->
                Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { ProblemNotice(account, prefs, status, problem) }
            }
            HorizontalDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.contacts_sync_options_title), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                SwitchRow(stringResource(R.string.contacts_sync_upload_title),
                    stringResource(if (prefs.mode == SyncMode.TWO_WAY) R.string.contacts_sync_upload_on else R.string.contacts_sync_upload_off),
                    prefs.mode == SyncMode.TWO_WAY) { upload -> choose(account, if (upload) SyncMode.TWO_WAY else SyncMode.DOWNLOAD) }
                // Developer fixture control (main's Android Switch work); customers always upload new contacts.
                if (prefs.mode == SyncMode.TWO_WAY && ContactMigrationPrivacy(this@ContactsSyncActivity).developerControlsEnabled) {
                    SwitchRow(stringResource(R.string.contacts_sync_upload_new), null, prefs.uploadNewContacts) { enabled ->
                        if (enabled) newContactsConfirmation = account else { prefs.uploadNewContacts = false; revision++ }
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                CyclonTextButton(onClick = { prefs.refresh(); revision++ }, enabled = !busy && status != "active") {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.contacts_sync_refresh))
                }
            }
        }
    }

    /** The account's Google profile photo (cached, else fetched once), like Google's card; the initial until then. */
    @Composable
    private fun Avatar(account: Account) {
        var photo by remember(account.name) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(account.name) {
            photo = withContext(Dispatchers.IO) {
                runCatching {
                    PeopleManager.getOwnerAvatarBitmap(this@ContactsSyncActivity, account.name, false)
                        ?: PeopleManager.getOwnerAvatarBitmap(this@ContactsSyncActivity, account.name, true)
                }.getOrNull()
            }
        }
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            val image = photo
            if (image != null) Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(account.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
        }
    }

    private fun statusOf(account: Account, prefs: ContactSyncPreferences): String {
        if (prefs.mode == SyncMode.OFF) return "off"
        // Only a running sync is "in progress". Android keeps failed syncs queued for retry, so counting a
        // queued one would hide a stored problem (e.g. sign-in needed) and disable Sync now indefinitely.
        val active = ContentResolver.isSyncActive(account, ContactSyncPreferences.AUTHORITY)
        return when {
            !hasContactsPermission -> "permission"
            active -> "active"
            prefs.status.isNotEmpty() && prefs.status != "success" -> prefs.status
            !ContentResolver.getMasterSyncAutomatically() -> "auto_off"
            else -> prefs.status
        }
    }

    private fun problemOf(status: String): Int? = when (status) {
        "permission" -> R.string.contacts_sync_permission_needed
        "partial" -> R.string.contacts_sync_partial
        "conflict" -> R.string.contacts_sync_conflict
        "uncertain" -> R.string.contacts_sync_uncertain
        "authorization" -> R.string.contacts_sync_authorization
        "network" -> R.string.contacts_sync_network
        "local" -> R.string.contacts_sync_local
        "needs_review" -> R.string.contacts_sync_needs_review
        "import_review" -> R.string.contacts_sync_import_review
        "needs_update" -> R.string.contacts_sync_needs_update
        "two_way_unavailable" -> R.string.contacts_sync_two_way_unavailable
        else -> null
    }

    // Informational states: the sync works, some contacts just need a look.
    private fun isWarning(status: String) = problemOf(status) != null && status !in listOf("partial", "uncertain", "conflict")

    /** Google's one-line status under the account: "Sync on • Last sync 5 minutes ago". */
    @Composable
    private fun StatusLine(status: String, prefs: ContactSyncPreferences) {
        val lastSync = if (prefs.lastSuccess <= 0) null
            else if (System.currentTimeMillis() - prefs.lastSuccess < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.contacts_sync_last_sync_just_now)
            else stringResource(R.string.contacts_sync_last_sync, DateUtils.getRelativeTimeSpanString(prefs.lastSuccess,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString())
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val (icon: Painter?, tint: Color, label: String) = when {
            status == "off" -> Triple(null, muted, stringResource(R.string.contacts_sync_switch_off))
            status == "active" -> Triple(painterResource(R.drawable.ic_contacts_sync_progress), MaterialTheme.colorScheme.primary,
                stringResource(R.string.contacts_sync_in_progress))
            status == "permission" -> Triple(rememberVectorPainter(Icons.Filled.Warning), MaterialTheme.colorScheme.error,
                stringResource(R.string.contacts_sync_status_permission))
            isWarning(status) -> Triple(rememberVectorPainter(Icons.Filled.Warning), MaterialTheme.colorScheme.error,
                stringResource(R.string.contacts_sync_status_trouble))
            status == "auto_off" -> Triple(rememberVectorPainter(Icons.Filled.Info), muted, stringResource(R.string.contacts_sync_auto_sync_off_status))
            else -> Triple(rememberVectorPainter(Icons.Filled.CheckCircle), MaterialTheme.colorScheme.primary, stringResource(R.string.contacts_sync_switch_on))
        }
        val detail = if (status == "active" || status == "off") null else lastSync ?: stringResource(R.string.contacts_sync_ready).takeIf { status != "permission" && !isWarning(status) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            icon?.let { Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp)) }
            Text(if (detail == null) label else stringResource(R.string.contacts_sync_status_bullet, label, detail),
                style = MaterialTheme.typography.bodyMedium, color = muted)
        }
    }

    @Composable
    private fun ProblemNotice(account: Account, prefs: ContactSyncPreferences, status: String, problem: Int) {
        val action: (@Composable RowScope.() -> Unit)? = when (status) {
            "permission" -> ({ CyclonOutlinedButton(onClick = { openAppPermissions() }) { Text(stringResource(R.string.contacts_sync_open_permissions)) } })
            "authorization" -> ({ CyclonOutlinedButton(onClick = { choose(account, prefs.mode) }, enabled = !busy) { Text(stringResource(R.string.contacts_sync_reconnect)) } })
            "needs_update" -> ({ CyclonOutlinedButton(onClick = { prefs.refresh(); revision++ }, enabled = !busy) { Text(stringResource(R.string.contacts_sync_retry)) } })
            else -> null
        }
        Notice(stringResource(problem), error = isWarning(status) || status == "permission", actions = action)
    }

    @Composable
    private fun TurnOffDialog(request: TurnOff) {
        AlertDialog(onDismissRequest = { turnOff = null }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.contacts_sync_turn_off_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.contacts_sync_turn_off_body, request.account.name))
                    if (request.unsynced > 0) Text(pluralStringResource(
                        if (ContactSyncPreferences(this@ContactsSyncActivity, request.account).mode == SyncMode.TWO_WAY) R.plurals.contacts_sync_turn_off_unsynced
                        else R.plurals.contacts_sync_turn_off_unsynced_download, request.unsynced, request.unsynced),
                        color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { CyclonTextButton(onClick = { turnOff = null; turnOffSync(request.account) }) {
                Text(stringResource(R.string.contacts_sync_turn_off_confirm))
            } },
            dismissButton = { CyclonTextButton(onClick = { turnOff = null }) { Text(stringResource(R.string.contacts_sync_cancel)) } })
    }

    private fun confirmTurnOff(account: Account) {
        lifecycleScope.launch {
            val unsynced = withContext(Dispatchers.IO) {
                runCatching { withSyncedContacts(account) { it.unsyncedChanges() } }.getOrDefault(0)
            }
            turnOff = TurnOff(account, unsynced)
        }
    }

    private fun turnOffSync(account: Account) {
        failure = null; retryChoice = null
        val prefs = ContactSyncPreferences(this, account)
        prefs.configure(SyncMode.OFF, Long.MAX_VALUE)
        prefs.pendingRemoval = true
        revision++
        busy = true
        lifecycleScope.launch {
            val removed = withContext(Dispatchers.IO) { runCatching { withSyncedContacts(account) { it.remove() } }.isSuccess }
            busy = false
            revision++
            Toast.makeText(this@ContactsSyncActivity, if (removed) R.string.contacts_sync_turn_off_done
                else R.string.contacts_sync_turn_off_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // A removal that couldn't finish earlier (a sync was stuck) is retried quietly.
    private fun retryPendingRemovals() {
        val owed = AccountManager.get(this).getAccountsByType("com.google").filter {
            ContactSyncPreferences(this, it).let { prefs -> prefs.mode == SyncMode.OFF && prefs.pendingRemoval }
        }
        if (owed.isNotEmpty()) lifecycleScope.launch(Dispatchers.IO) {
            for (account in owed) runCatching { withSyncedContacts(account) { it.remove() } }
        }
    }

    private fun <T> withDeviceContacts(block: (DeviceContacts) -> T): T {
        val client = contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY_URI)
            ?: throw IllegalStateException("Contacts provider unavailable")
        try { return block(DeviceContacts(client, this)) }
        finally { @Suppress("DEPRECATION") client.release() }
    }

    private fun <T> withSyncedContacts(account: Account, block: (SyncedContacts) -> T): T {
        val client = contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY_URI)
            ?: throw IllegalStateException("Contacts provider unavailable")
        try { return block(SyncedContacts(client, account, ContactSyncPreferences(this, account))) }
        finally { @Suppress("DEPRECATION") client.release() }
    }

    private fun openAppPermissions() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private companion object { const val GOOGLE_CONTACTS = "https://contacts.google.com" }

    private fun openUrl(url: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        turnOff?.let { outState.putString("turnOffAccount", it.account.name); outState.putInt("turnOffUnsynced", it.unsynced) }
        if (waitingForPermission) pending?.let { (account, mode) ->
            outState.putBoolean("waitingForPermission", true)
            outState.putString("pendingAccount", account.name)
            outState.putString("pendingMode", mode.name)
        }
    }
    private fun choose(account: Account, mode: SyncMode) {
        failure = null
        if (mode == SyncMode.OFF) { confirmTurnOff(account); return }
        pending = account to mode
        retryChoice = pending
        busy = true
        val permissions = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            waitingForPermission = true
            permissionRequest.launch(permissions)
        }
        else authorize(account, mode)
    }
    private suspend fun authResult(account: Account, service: String, options: Bundle? = null): Bundle = suspendCancellableCoroutine { continuation ->
        val future = AccountManager.get(this).getAuthToken(account, service, options, this, { result ->
            if (continuation.isActive) try { continuation.resume(result.result) }
            catch (e: Exception) { continuation.resumeWithException(e) }
        }, null)
        continuation.invokeOnCancellation { future.cancel(false) }
    }
    private suspend fun authToken(account: Account, mode: SyncMode): String {
        Log.i("GmsContactSync", "stage=authorization oauth2:" + ContactSyncService.scope(mode))
        return authResult(account, "oauth2:" + ContactSyncService.scope(mode)).getString(AccountManager.KEY_AUTHTOKEN)
            ?.takeIf { it.isNotBlank() } ?: throw android.accounts.AuthenticatorException("Contacts authorization required")
    }
    private fun authorize(account: Account, mode: SyncMode) {
        lifecycleScope.launch {
            var stage = ActivationStage.AUTHORIZATION
            try {
                withTimeout(60000) {
                    var token = authToken(account, mode)
                    stage = ActivationStage.API
                    try {
                        // Verify API access before enabling scheduling or contact writes.
                        withContext(Dispatchers.IO) { ContactSyncService.privateContactsApi(token).checkAccess() }
                    } catch (e: ApiException) {
                        if (e.status != 401) throw e
                        // Retry one expired cached token. A 403 is a separate API-access failure.
                        org.microg.gms.auth.invalidateAccountAuthToken(this@ContactsSyncActivity, account.type, token)
                        stage = ActivationStage.AUTHORIZATION
                        token = authToken(account, mode)
                        stage = ActivationStage.API
                        withContext(Dispatchers.IO) { ContactSyncService.privateContactsApi(token).checkAccess() }
                    }
                    stage = ActivationStage.LOCAL
                    val floor = withContext(Dispatchers.IO) {
                        contentResolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID), null, null,
                            "${RawContacts._ID} DESC")?.use { if (it.moveToFirst()) it.getLong(0) else 0L }
                            ?: throw IllegalStateException("Contacts provider unavailable")
                    }
                    // Return to the lifecycle-owned coroutine before committing the choice.
                    // A cancelled/recreated screen must not enable scheduling from an old IO task.
                    if (!AccountManager.get(this@ContactsSyncActivity).getAccountsByType(account.type).contains(account))
                        throw ActivationAccountRemoved()
                    val groupFloor = withContext(Dispatchers.IO) {
                        contentResolver.query(android.provider.ContactsContract.Groups.CONTENT_URI,
                            arrayOf(android.provider.ContactsContract.Groups._ID), null, null, "_id DESC")
                            ?.use { if (it.moveToFirst()) it.getLong(0) else 0L }
                            ?: throw IllegalStateException("Contacts provider unavailable")
                    }
                    // Under the account lock: a turn-off removal still in progress finishes first, and one
                    // still owed is cancelled, so it can never delete contacts after sync is back on.
                    withContext(Dispatchers.IO) {
                        val lock = ContactSyncLock.of(account)
                        if (!lock.tryLock(120, java.util.concurrent.TimeUnit.SECONDS)) throw SyncStillRunning()
                        try {
                            ensureActive()
                            ContactSyncPreferences(this@ContactsSyncActivity, account).apply {
                                pendingRemoval = false
                                initializePrivateFloors(floor, groupFloor)
                                configure(mode, floor)
                            }
                        } finally { lock.unlock() }
                    }
                }
                retryChoice = null
                revision++
            } catch (e: TimeoutCancellationException) { failure = ActivationFailure.TIMEOUT }
            catch (e: OperationCanceledException) { failure = ActivationFailure.CANCELLED }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                failure = activationFailure(stage, e)
                Log.w("GmsContactSync", "activation " + ContactsDiagnostics.activation(stage, e))
                ContactSyncPreferences(this@ContactsSyncActivity, account).diagnostic = ContactsDiagnostics.activation(stage, e)
            }
            finally { busy = false; pending = null }
        }
    }
}
