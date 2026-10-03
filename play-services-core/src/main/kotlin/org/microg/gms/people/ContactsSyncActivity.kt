/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract.RawContacts
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.microg.gms.people.sync.SyncMode
import java.text.DateFormat
import java.util.Date

class ContactsSyncActivity : ComponentActivity() {
    private var pending: Pair<Account, SyncMode>? = null
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf(false)
    private var revision by mutableStateOf(0)
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val choice = pending
        if (choice != null && arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) authorize(choice.first, choice.second)
        else { error = true; busy = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var confirmation by remember { mutableStateOf<Account?>(null) }
                LaunchedEffect(Unit) { while (true) { delay(3000); revision++ } }
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.contacts_sync_title), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.contacts_sync_description))
                        if (error) Text(stringResource(R.string.contacts_sync_enable_failed), color = MaterialTheme.colorScheme.error)
                        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        @Suppress("UNUSED_EXPRESSION")
                        revision
                        val accounts = AccountManager.get(this@ContactsSyncActivity).getAccountsByType("com.google")
                        if (accounts.isEmpty()) Text(stringResource(R.string.contacts_sync_no_accounts))
                        for (account in accounts) {
                            val prefs = ContactSyncPreferences(this@ContactsSyncActivity, account)
                            Text(account.name, style = MaterialTheme.typography.titleMedium)
                            for (mode in SyncMode.values()) {
                                Row {
                                    RadioButton(selected = prefs.mode == mode, enabled = !busy, onClick = {
                                        if (mode == SyncMode.TWO_WAY) confirmation = account else choose(account, mode)
                                    })
                                    Text(stringResource(when (mode) {
                                        SyncMode.OFF -> R.string.contacts_sync_off
                                        SyncMode.DOWNLOAD -> R.string.contacts_sync_download
                                        SyncMode.TWO_WAY -> R.string.contacts_sync_two_way
                                    }), modifier = Modifier.padding(top = 12.dp))
                                }
                            }
                            if (prefs.mode != SyncMode.OFF) {
                                Text(stringResource(when (prefs.status) {
                                    "success" -> R.string.contacts_sync_success
                                    "conflict" -> R.string.contacts_sync_conflict
                                    "uncertain" -> R.string.contacts_sync_uncertain
                                    "authorization" -> R.string.contacts_sync_authorization
                                    "network" -> R.string.contacts_sync_network
                                    "local" -> R.string.contacts_sync_local
                                    else -> R.string.contacts_sync_ready
                                }))
                                if (prefs.lastSuccess > 0) Text(stringResource(R.string.contacts_sync_last_success,
                                    DateFormat.getDateTimeInstance().format(Date(prefs.lastSuccess))))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { prefs.refresh(); revision++ }, enabled = !busy) { Text(stringResource(R.string.contacts_sync_refresh)) }
                                    OutlinedButton(onClick = { choose(account, prefs.mode) }, enabled = !busy) { Text(stringResource(R.string.contacts_sync_reconnect)) }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                confirmation?.let { account ->
                    AlertDialog(onDismissRequest = { confirmation = null },
                        title = { Text(stringResource(R.string.contacts_sync_two_way)) },
                        text = { Text(stringResource(R.string.contacts_sync_two_way_consent)) },
                        confirmButton = { TextButton(onClick = { confirmation = null; choose(account, SyncMode.TWO_WAY) }) {
                            Text(stringResource(R.string.contacts_sync_enable))
                        } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(android.R.string.cancel)) } })
                }
            }
        }
    }
    private fun choose(account: Account, mode: SyncMode) {
        error = false
        if (mode == SyncMode.OFF) {
            ContactSyncPreferences(this, account).configure(mode, Long.MAX_VALUE)
            revision++
            return
        }
        pending = account to mode
        busy = true
        val permissions = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) permissionRequest.launch(permissions)
        else authorize(account, mode)
    }
    private fun authorize(account: Account, mode: SyncMode) {
        AccountManager.get(this).getAuthToken(account, "oauth2:" + ContactSyncService.scope(mode), null, this, { future ->
            val token = runCatching { future.result.getString(AccountManager.KEY_AUTHTOKEN) }.getOrNull()
            if (token.isNullOrBlank()) { busy = false; error = true; return@getAuthToken }
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        // Verify API access before enabling scheduling or contact writes.
                        ContactSyncService.contactsApi(token) { false }.list(null, null)
                        val floor = contentResolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID), null, null,
                            "${RawContacts._ID} DESC")!!.use { if (it.moveToFirst()) it.getLong(0) else 0L }
                        check(AccountManager.get(this@ContactsSyncActivity).getAccountsByType(account.type).contains(account))
                        ContactSyncPreferences(this@ContactsSyncActivity, account).configure(mode, floor)
                    }
                    revision++
                } catch (e: Exception) { error = true }
                finally { busy = false; pending = null }
            }
        }, null)
    }
}
