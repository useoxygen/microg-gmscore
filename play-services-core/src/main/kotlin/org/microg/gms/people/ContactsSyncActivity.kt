/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.OperationCanceledException
import android.content.Intent
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
import org.microg.gms.cyclon.cyclonPrimaryButtonColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.microg.gms.people.sync.PrivatePeopleProbe
import org.microg.gms.people.sync.PrivatePeopleVariant
import org.microg.gms.people.sync.ProbeGrant
import org.microg.gms.people.sync.ProbeIdentity
import org.microg.gms.people.sync.HttpReply
import org.microg.gms.people.sync.ContactsDiagnostics
import org.microg.gms.people.sync.ActivationFailure
import org.microg.gms.people.sync.ActivationStage
import org.microg.gms.people.sync.ActivationAccountRemoved
import org.microg.gms.people.sync.ApiException
import org.microg.gms.people.sync.activationFailure
import org.microg.gms.people.sync.SyncMode
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ContactsSyncActivity : ComponentActivity() {
    private var pending: Pair<Account, SyncMode>? = null
    private var retryChoice: Pair<Account, SyncMode>? = null
    private var waitingForPermission = false
    private var busy by mutableStateOf(false)
    private var failure: ActivationFailure? by mutableStateOf(null)
    private var revision by mutableStateOf(0)
    private var accessCheckResult by mutableStateOf<String?>(null)
    internal var privateProbeAuthorization: (suspend (Account, PrivatePeopleVariant) -> ProbeGrant)? = null
    internal var privateProbeGet: (suspend (String, String) -> HttpReply)? = null
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
                var resumeUploads by remember { mutableStateOf(false) }
                var confirmation by remember { mutableStateOf<Account?>(null) }
                var newContactsConfirmation by remember { mutableStateOf<Account?>(null) }
                LaunchedEffect(Unit) { while (true) { delay(3000); revision++ } }
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.contacts_sync_title), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.contacts_sync_description))
                        accessCheckResult?.let { Text(it) }
                        if (com.google.android.gms.BuildConfig.DEBUG) Row {
                            val privacy = ContactMigrationPrivacy(this@ContactsSyncActivity)
                            Checkbox(checked = privacy.developerControlsEnabled, onCheckedChange = {
                                privacy.developerControlsEnabled = it; revision++
                            })
                            Text(stringResource(R.string.contacts_sync_developer_controls), modifier = Modifier.padding(top = 12.dp))
                        }
                        if (ContactMigrationPrivacy(this@ContactsSyncActivity).held) {
                            Text(stringResource(R.string.contacts_sync_migration_held))
                            CyclonOutlinedButton(onClick = { resumeUploads = true }) { Text(stringResource(R.string.contacts_sync_resume_uploads)) }
                        }
                        failure?.let { reason ->
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
                            if (reason == ActivationFailure.PERMISSION) {
                                CyclonOutlinedButton(onClick = {
                                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                                }) { Text(stringResource(R.string.contacts_sync_open_permissions)) }
                            }
                            retryChoice?.let { choice ->
                                CyclonOutlinedButton(onClick = { choose(choice.first, choice.second) }, enabled = !busy) {
                                    Text(stringResource(R.string.contacts_sync_retry))
                                }
                            }
                        }
                        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        @Suppress("UNUSED_EXPRESSION")
                        revision
                        val accounts = AccountManager.get(this@ContactsSyncActivity).getAccountsByType("com.google")
                        if (accounts.isEmpty()) Text(stringResource(R.string.contacts_sync_no_accounts))
                        for (account in accounts) {
                            val prefs = ContactSyncPreferences(this@ContactsSyncActivity, account)
                            Text(account.name, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.contacts_sync_two_way_consent))
                            for (mode in SyncMode.values()) {
                                Row {
                                    RadioButton(selected = prefs.mode == mode, enabled = !busy, onClick = {
                                        choose(account, mode)
                                    })
                                    Text(stringResource(when (mode) {
                                        SyncMode.OFF -> R.string.contacts_sync_off
                                        SyncMode.DOWNLOAD -> R.string.contacts_sync_download
                                        SyncMode.TWO_WAY -> R.string.contacts_sync_two_way
                                    }), modifier = Modifier.padding(top = 12.dp))
                                }
                            }
                            Text(stringResource(R.string.contacts_sync_check_description))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CyclonOutlinedButton(onClick = { checkAccess(account, SyncMode.DOWNLOAD) }, enabled = !busy) {
                                    Text(stringResource(R.string.contacts_sync_check_download))
                                }
                                CyclonOutlinedButton(onClick = { checkAccess(account, SyncMode.TWO_WAY) }, enabled = !busy) {
                                    Text(stringResource(R.string.contacts_sync_check_two_way))
                                }
                            }
                            CyclonOutlinedButton(onClick = { checkPrivateAccess(account) }, enabled = !busy) {
                                Text(stringResource(R.string.contacts_sync_check_private))
                            }
                            if (prefs.mode == SyncMode.TWO_WAY && ContactMigrationPrivacy(this@ContactsSyncActivity).developerControlsEnabled) {
                                Row {
                                    Checkbox(checked = prefs.uploadNewContacts, enabled = !busy, onCheckedChange = { enabled ->
                                        if (enabled) newContactsConfirmation = account
                                        else { prefs.uploadNewContacts = false; revision++ }
                                    })
                                    Text(stringResource(R.string.contacts_sync_upload_new), modifier = Modifier.padding(top = 12.dp))
                                }
                            }
                            if (prefs.mode != SyncMode.OFF) {
                                Text(stringResource(when (prefs.status) {
                                    "partial" -> R.string.contacts_sync_partial
                                    "success" -> R.string.contacts_sync_success
                                    "conflict" -> R.string.contacts_sync_conflict
                                    "uncertain" -> R.string.contacts_sync_uncertain
                                    "authorization" -> R.string.contacts_sync_authorization
                                    "network" -> R.string.contacts_sync_network
                                    "local" -> R.string.contacts_sync_local
                                    "needs_review" -> R.string.contacts_sync_needs_review
                                    "needs_update" -> R.string.contacts_sync_needs_update
                                    "two_way_unavailable" -> R.string.contacts_sync_two_way_unavailable
                                    else -> R.string.contacts_sync_ready
                                }))
                                if (prefs.lastSuccess > 0) Text(stringResource(R.string.contacts_sync_last_success,
                                    DateFormat.getDateTimeInstance().format(Date(prefs.lastSuccess))))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { prefs.refresh(); revision++ }, enabled = !busy, colors = cyclonPrimaryButtonColors()) { Text(stringResource(R.string.contacts_sync_refresh)) }
                                    CyclonOutlinedButton(onClick = { choose(account, prefs.mode) }, enabled = !busy) { Text(stringResource(R.string.contacts_sync_reconnect)) }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }

                if (resumeUploads) AlertDialog(onDismissRequest = { resumeUploads = false },
                    title = { Text(stringResource(R.string.contacts_sync_resume_uploads)) },
                    text = { Text(stringResource(R.string.contacts_sync_resume_uploads_consent)) },
                    confirmButton = { CyclonTextButton(onClick = {
                        ContactMigrationPrivacy(this@ContactsSyncActivity).ownerResume()
                        AccountManager.get(this@ContactsSyncActivity).getAccountsByType("com.google").forEach {
                            ContactSyncPreferences(this@ContactsSyncActivity, it).refresh()
                        }
                        resumeUploads = false; revision++
                    }) { Text(stringResource(R.string.contacts_sync_enable)) } },
                    dismissButton = { CyclonTextButton(onClick = { resumeUploads = false }) { Text(stringResource(android.R.string.cancel)) } })
                newContactsConfirmation?.let { account ->
                    AlertDialog(onDismissRequest = { newContactsConfirmation = null },
                        title = { Text(stringResource(R.string.contacts_sync_upload_new)) },
                        text = { Text(stringResource(R.string.contacts_sync_upload_new_consent)) },
                        confirmButton = { CyclonTextButton(onClick = {
                            ContactSyncPreferences(this@ContactsSyncActivity, account).apply {
                                uploadNewContacts = true
                                refresh()
                            }
                            newContactsConfirmation = null; revision++
                        }) { Text(stringResource(R.string.contacts_sync_enable)) } },
                        dismissButton = { CyclonTextButton(onClick = { newContactsConfirmation = null }) {
                            Text(stringResource(android.R.string.cancel))
                        } })
                }
                confirmation?.let { account ->
                    AlertDialog(onDismissRequest = { confirmation = null },
                        title = { Text(stringResource(R.string.contacts_sync_two_way)) },
                        text = { Text(stringResource(R.string.contacts_sync_two_way_consent)) },
                        confirmButton = { CyclonTextButton(onClick = { confirmation = null; choose(account, SyncMode.TWO_WAY) }) {
                            Text(stringResource(R.string.contacts_sync_enable))
                        } }, dismissButton = { CyclonTextButton(onClick = { confirmation = null }) { Text(stringResource(android.R.string.cancel)) } })
                }
            }
        }
    }
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
        accessCheckResult = null
        if (mode == SyncMode.OFF) {
            ContactSyncPreferences(this, account).configure(mode, Long.MAX_VALUE)
            revision++
            return
        }
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
    // A separate entry point: no activation, provider, preferences or scheduling
    // capability is passed into this diagnostic-only runner.
    internal fun checkPrivateAccess(account: Account) {
        failure = null; accessCheckResult = null; retryChoice = null; busy = true
        lifecycleScope.launch {
            try {
                PrivatePeopleProbe.run(ContactSyncService.privatePeopleContext, authorize = { variant ->
                    try {
                        withTimeout(60000) {
                            privateProbeAuthorization?.invoke(account, variant) ?: run {
                                val result = authResult(account, "oauth2:" + variant.scope.scope, Bundle().apply {
                                    putBoolean(PrivatePeopleProbe.OPTION_PROBE, true)
                                    putBoolean(PrivatePeopleProbe.OPTION_GMS_APP, variant.identity == ProbeIdentity.GMS_APP)
                                })
                                ProbeGrant(result.getString(AccountManager.KEY_AUTHTOKEN), result.getString(PrivatePeopleProbe.RESULT_AUTH))
                            }
                        }
                    } catch (error: TimeoutCancellationException) { ProbeGrant(null, null) }
                    catch (error: OperationCanceledException) { throw CancellationException("Private access check cancelled") }
                }, get = { token, url ->
                    privateProbeGet?.invoke(token, url) ?: withContext(Dispatchers.IO) { ContactSyncService.privatePeopleProbeGet(token, url) }
                }, emit = { safe ->
                    Log.i("GmsContactSync", safe)
                    accessCheckResult = listOfNotNull(accessCheckResult, safe).joinToString("\n")
                })
            } finally { busy = false }
        }
    }
    private fun checkAccess(account: Account, mode: SyncMode) {
        failure = null
        accessCheckResult = null
        retryChoice = null
        busy = true
        authorize(account, mode, checkOnly = true)
    }
    private fun authorize(account: Account, mode: SyncMode, checkOnly: Boolean = false) {
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
                    if (checkOnly) {
                        Log.i("GmsContactSync", "stage=api mode=${mode.name} result=ACCESS_CHECK_SUCCESS")
                        accessCheckResult = getString(R.string.contacts_sync_check_success)
                        return@withTimeout
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
                if (!checkOnly) ContactSyncPreferences(this@ContactsSyncActivity, account).diagnostic = ContactsDiagnostics.activation(stage, e)
            }
            finally { busy = false; pending = null }
        }
    }
}
