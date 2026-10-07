/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar

import android.Manifest
import android.accounts.*
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.os.Bundle
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
import kotlinx.coroutines.*
import org.microg.gms.cyclon.CyclonTheme
import java.util.concurrent.TimeUnit
import java.text.DateFormat
import java.util.Date

class CalendarSyncActivity : ComponentActivity() {
    private var pending: Account? = null
    private var busy by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private var revision by mutableStateOf(0)
    private var authFuture: AccountManagerFuture<Bundle>? = null
    private val permissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val account = pending
        if (account != null && permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) authorize(account)
        else { pending = null; busy = false; failed = true }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { CyclonTheme { Surface(Modifier.fillMaxSize()) {
            LaunchedEffect(Unit) { while (true) { delay(3_000); revision++ } }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { finish() }) { Text(stringResource(R.string.cyclon_back)) }
                Text(stringResource(R.string.cyclon_calendar_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.cyclon_calendar_description))
                if (failed) Text(stringResource(R.string.cyclon_calendar_enable_failed))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                @Suppress("UNUSED_EXPRESSION")
                revision
                val accounts = AccountManager.get(this@CalendarSyncActivity).getAccountsByType("com.google")
                if (accounts.isEmpty()) Text(stringResource(R.string.contacts_sync_no_accounts))
                for (account in accounts.take(100)) {
                    val prefs = CalendarSyncPreferences(this@CalendarSyncActivity, account)
                    Text(account.name, style = MaterialTheme.typography.titleMedium)
                    Row {
                        Switch(checked = prefs.enabled, enabled = !busy, onCheckedChange = { enabled ->
                            if (!enabled) { prefs.configure(false); revision++ }
                            else {
                                pending = account; failed = false; busy = true
                                if (permissions.all { ContextCompat.checkSelfPermission(this@CalendarSyncActivity, it) == PackageManager.PERMISSION_GRANTED }) authorize(account)
                                else permissionRequest.launch(permissions)
                            }
                        })
                        Text(stringResource(R.string.cyclon_calendar_download), Modifier.padding(12.dp))
                    }
                    if (prefs.enabled) {
                        Text(stringResource(when {
                            !ContentResolver.getMasterSyncAutomatically() || !ContentResolver.getSyncAutomatically(account, CalendarSyncPreferences.AUTHORITY) -> R.string.cyclon_health_sync_paused
                            permissions.any { ContextCompat.checkSelfPermission(this@CalendarSyncActivity, it) != PackageManager.PERMISSION_GRANTED } -> R.string.cyclon_calendar_permissions
                            prefs.status == "success" -> R.string.cyclon_calendar_success
                            prefs.status == "conflict" -> R.string.cyclon_calendar_conflict
                            prefs.status == "authorization" -> R.string.contacts_sync_authorization
                            prefs.status == "network" -> R.string.contacts_sync_network
                            prefs.status == "local" -> R.string.cyclon_calendar_local
                            else -> R.string.contacts_sync_ready
                        }))
                        if (prefs.lastSuccess > 0) Text(stringResource(R.string.cyclon_health_last_success, DateFormat.getDateTimeInstance().format(Date(prefs.lastSuccess))))
                        OutlinedButton(onClick = { prefs.refresh(); revision++ }, enabled = !busy) { Text(stringResource(R.string.contacts_sync_refresh)) }
                        TextButton(onClick = {
                            pending = account; busy = true; failed = false
                            if (permissions.all { ContextCompat.checkSelfPermission(this@CalendarSyncActivity, it) == PackageManager.PERMISSION_GRANTED }) authorize(account)
                            else permissionRequest.launch(permissions)
                        }, enabled = !busy) { Text(stringResource(R.string.contacts_sync_reconnect)) }
                    }
                    HorizontalDivider()
                }
            }
        } } }
    }
    private fun authorize(account: Account) {
        lifecycleScope.launch {
            try {
                val future = AccountManager.get(this@CalendarSyncActivity).getAuthToken(account,
                    "oauth2:${CalendarSyncService.SCOPE}", null, this@CalendarSyncActivity, null, null)
                authFuture = future
                val token = withContext(Dispatchers.IO) { future.getResult(60, TimeUnit.SECONDS).getString(AccountManager.KEY_AUTHTOKEN) }
                check(!token.isNullOrBlank() && pending == account)
                // Authorization is completed before background scheduling is enabled.
                CalendarSyncPreferences(this@CalendarSyncActivity, account).configure(true)
            } catch (_: Exception) { failed = true }
            finally { authFuture?.cancel(true); authFuture = null; pending = null; busy = false; revision++ }
        }
    }
    override fun onDestroy() { pending = null; authFuture?.cancel(true); super.onDestroy() }
}
