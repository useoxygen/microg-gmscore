/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
        setContent {
            CyclonMaterialTheme {
                LaunchedEffect(Unit) { while (true) { delay(3000); revision++ } }
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CyclonTextButton(onClick = { finish() }) { Text(stringResource(R.string.cyclon_back)) }
                        Text(stringResource(R.string.contacts_sync_title), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.contacts_sync_header), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CyclonOutlinedButton(onClick = { openUrl("https://contacts.google.com") }) {
                            Text(stringResource(R.string.contacts_sync_open_google_contacts))
                        }
                        failure?.let { ActivationProblem(it) }
                        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        @Suppress("UNUSED_EXPRESSION")
                        revision
                        val accounts = AccountManager.get(this@ContactsSyncActivity).getAccountsByType("com.google")
                        if (accounts.isEmpty()) {
                            Text(stringResource(R.string.contacts_sync_no_accounts))
                            CyclonOutlinedButton(onClick = {
                                startActivity(Intent(Settings.ACTION_ADD_ACCOUNT).putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google")))
                            }) { Text(stringResource(R.string.contacts_sync_add_account)) }
                        }
                        for (account in accounts) AccountCard(account)
                    }
                }
                turnOff?.let { TurnOffDialog(it) }
            }
        }
    }

    private data class TurnOff(val account: Account, val unsynced: Int)
    private var turnOff by mutableStateOf<TurnOff?>(null)
    private val hasContactsPermission get() = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    @Composable
    private fun ActivationProblem(reason: ActivationFailure) {
        Text(stringResource(when (reason) {
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
        }), color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (reason == ActivationFailure.PERMISSION) CyclonOutlinedButton(onClick = { openAppPermissions() }) {
                Text(stringResource(R.string.contacts_sync_open_permissions))
            }
            retryChoice?.let { choice ->
                CyclonOutlinedButton(onClick = { choose(choice.first, choice.second) }, enabled = !busy) {
                    Text(stringResource(R.string.contacts_sync_retry))
                }
            }
        }
    }

    @Composable
    private fun AccountCard(account: Account) {
        val prefs = ContactSyncPreferences(this, account)
        val on = prefs.mode != SyncMode.OFF
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(account.name, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(if (on) R.string.contacts_sync_switch_on else R.string.contacts_sync_switch_off),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = on, enabled = !busy, onCheckedChange = { checked ->
                        // Like Google's sync, turning it on keeps both sides in sync.
                        if (checked) choose(account, SyncMode.TWO_WAY) else confirmTurnOff(account)
                    })
                }
                if (!on) return@Column
                HorizontalDivider()
                SyncStatus(account, prefs)
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.contacts_sync_upload_title))
                        Text(stringResource(if (prefs.mode == SyncMode.TWO_WAY) R.string.contacts_sync_upload_on else R.string.contacts_sync_upload_off),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = prefs.mode == SyncMode.TWO_WAY, enabled = !busy, onCheckedChange = { upload ->
                        choose(account, if (upload) SyncMode.TWO_WAY else SyncMode.DOWNLOAD)
                    })
                }
            }
        }
    }

    @Composable
    private fun SyncStatus(account: Account, prefs: ContactSyncPreferences) {
        val active = ContentResolver.isSyncActive(account, ContactSyncPreferences.AUTHORITY) ||
            ContentResolver.isSyncPending(account, ContactSyncPreferences.AUTHORITY) && prefs.lastSuccess == 0L
        val status = when {
            !hasContactsPermission -> "permission"
            active -> "active"
            else -> prefs.status
        }
        val lastSync = if (prefs.lastSuccess <= 0) null
            else if (System.currentTimeMillis() - prefs.lastSuccess < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.contacts_sync_last_sync_just_now)
            else stringResource(R.string.contacts_sync_last_sync, DateUtils.getRelativeTimeSpanString(prefs.lastSuccess,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString())
        val problem = when (status) {
            "permission" -> R.string.contacts_sync_permission_needed
            "partial" -> R.string.contacts_sync_partial
            "conflict" -> R.string.contacts_sync_conflict
            "uncertain" -> R.string.contacts_sync_uncertain
            "authorization" -> R.string.contacts_sync_authorization
            "network" -> R.string.contacts_sync_network
            "local" -> R.string.contacts_sync_local
            "needs_review" -> R.string.contacts_sync_needs_review
            "needs_update" -> R.string.contacts_sync_needs_update
            "two_way_unavailable" -> R.string.contacts_sync_two_way_unavailable
            else -> null
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(when {
                    status == "active" -> stringResource(R.string.contacts_sync_in_progress)
                    problem == null -> lastSync ?: stringResource(R.string.contacts_sync_ready)
                    else -> stringResource(problem)
                }, color = if (problem != null && status !in listOf("partial", "uncertain")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface)
                if (problem != null && lastSync != null) Text(lastSync, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
            CyclonTextButton(onClick = { prefs.refresh(); revision++ }, enabled = !busy && status != "active") {
                Text(stringResource(R.string.contacts_sync_refresh))
            }
        }
        when (status) {
            "permission" -> CyclonOutlinedButton(onClick = { openAppPermissions() }) { Text(stringResource(R.string.contacts_sync_open_permissions)) }
            "authorization" -> CyclonOutlinedButton(onClick = { choose(account, prefs.mode) }, enabled = !busy) { Text(stringResource(R.string.contacts_sync_reconnect)) }
            "needs_update" -> CyclonOutlinedButton(onClick = { prefs.refresh(); revision++ }, enabled = !busy) { Text(stringResource(R.string.contacts_sync_retry)) }
        }
    }

    @Composable
    private fun TurnOffDialog(request: TurnOff) {
        AlertDialog(onDismissRequest = { turnOff = null },
            title = { Text(stringResource(R.string.contacts_sync_turn_off_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.contacts_sync_turn_off_body, request.account.name))
                    if (request.unsynced > 0) Text(stringResource(R.string.contacts_sync_turn_off_unsynced, request.unsynced),
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

    private fun <T> withSyncedContacts(account: Account, block: (SyncedContacts) -> T): T {
        val client = contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY_URI)
            ?: throw IllegalStateException("Contacts provider unavailable")
        try { return block(SyncedContacts(client, account, ContactSyncPreferences(this, account))) }
        finally { @Suppress("DEPRECATION") client.release() }
    }

    private fun openAppPermissions() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun openUrl(url: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
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
                        AccountManager.get(this@ContactsSyncActivity).invalidateAuthToken(account.type, token)
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
                    ContactSyncPreferences(this@ContactsSyncActivity, account).apply {
                        initializePrivateFloors(floor, groupFloor)
                        configure(mode, floor)
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
