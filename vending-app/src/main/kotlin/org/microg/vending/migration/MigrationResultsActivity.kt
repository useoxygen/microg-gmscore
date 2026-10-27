/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owner-visible receipts; this Activity never accepts an app list from an external caller. */
class MigrationResultsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Opened on first use, inside the IO blocks below: a legacy upgrade takes the install lock.
            val queue by remember { lazy { MigrationInstallQueue(applicationContext) } }
            val scope = rememberCoroutineScope()
            var sessions by remember { mutableStateOf(emptyList<String>()) }
            var sessionId by remember { mutableStateOf(intent.getStringExtra("session_id")) }
            var results by remember { mutableStateOf(emptyList<Bundle>()) }
            var retryable by remember { mutableStateOf(emptySet<String>()) }
            var ownerAccountSupported by remember { mutableStateOf(false) }
            var confirmOwnerAccount by remember { mutableStateOf(false) }
            var complete by remember { mutableStateOf(false) }
            var selectionClosed by remember { mutableStateOf(false) }
            var refresh by remember { mutableStateOf(0) }
            var showSessions by remember { mutableStateOf(false) }
            DisposableEffect(Unit) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) { refresh++ }
                }
                registerReceiver(receiver, IntentFilter("org.microg.vending.migration.CHANGED"), Context.RECEIVER_NOT_EXPORTED)
                onDispose { unregisterReceiver(receiver) }
            }
            LaunchedEffect(sessionId, refresh) {
                val receipt = withContext(Dispatchers.IO) {
                    val ids = queue.sessions()
                    val id = sessionId?.takeIf { it in ids } ?: queue.currentSessionId()
                    Triple(ids, id, id?.let { queue.summary(it) })
                }
                sessions = receipt.first
                if (sessionId != receipt.second) sessionId = receipt.second
                results = receipt.third?.getParcelableArray("cyclon_app_results")?.map { it as Bundle }.orEmpty()
                complete = receipt.third?.getBoolean("complete") ?: false
                selectionClosed = receipt.third?.getBoolean("selection_closed") ?: false
                retryable = receipt.second?.let { id ->
                    withContext(Dispatchers.IO) { queue.retryDocuments(id).mapNotNull { it.getString("package_name") }.toSet() }
                }.orEmpty()
                if (retryable.isNotEmpty()) ownerAccountSupported = withContext(Dispatchers.IO) { queue.ownerAccountSupported() }
            }
            fun retry(packageName: String? = null, ownerAccount: Boolean = false) {
                val id = sessionId ?: return
                scope.launch {
                    withContext(Dispatchers.IO) { queue.retry(id, packageName, ownerAccount) }
                    refresh++
                }
            }
            if (confirmOwnerAccount) AlertDialog(
                onDismissRequest = { confirmOwnerAccount = false },
                title = { Text("Use your Google account?") },
                text = { Text(MigrationResultText.ownerAccountConsent(retryable.size)) },
                confirmButton = { TextButton(onClick = { confirmOwnerAccount = false; retry(ownerAccount = true) }) { Text("Install") } },
                dismissButton = { TextButton(onClick = { confirmOwnerAccount = false }) { Text("Cancel") } },
            )
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(20.dp).systemBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Transferred apps", style = MaterialTheme.typography.headlineMedium)
                        Text(if (complete) "App transfer is ready to review. Check the results below."
                            else "Your data transfer can finish while App Store installs your apps. Results update as each app finishes.")
                        if (sessions.size > 1) Box {
                            TextButton(onClick = { showSessions = true }) {
                                Text(if (sessionId == sessions.first()) "Latest transfer" else "Earlier transfer ${sessions.size - sessions.indexOf(sessionId)}")
                            }
                            DropdownMenu(expanded = showSessions, onDismissRequest = { showSessions = false }) {
                                sessions.forEachIndexed { index, id ->
                                    DropdownMenuItem(text = { Text(if (index == 0) "Latest transfer" else "Earlier transfer ${sessions.size - index}") },
                                        onClick = { sessionId = id; showSessions = false })
                                }
                            }
                        }
                        if (results.isEmpty()) Text("No apps were selected for this transfer.")
                        if (retryable.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { retry() }) { Text("Retry failed apps") }
                            if (ownerAccountSupported) OutlinedButton(onClick = { confirmOwnerAccount = true }) { Text("Use Google account") }
                        }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(results, key = { it.getString("package_name").orEmpty() }) { result ->
                                val name = result.getString("package_name").orEmpty()
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AsyncImage(model = result.getString("icon_url"), contentDescription = null, modifier = Modifier.size(48.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(result.getString("title")?.takeIf { it.isNotBlank() } ?: name, style = MaterialTheme.typography.titleMedium)
                                        Text(MigrationResultText.description(result))
                                        Row {
                                            if (name in retryable) TextButton(onClick = { retry(name) }) { Text("Retry") }
                                            if (!result.getBoolean("installed")) TextButton(onClick = {
                                                runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$name")).setPackage("com.aurora.store")) }
                                            }) { Text("App Store") }
                                            if (result.getBoolean("installed")) {
                                                val launch = packageManager.getLaunchIntentForPackage(name)
                                                if (launch != null) TextButton(onClick = { runCatching { startActivity(launch) } }) { Text("Open") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (!selectionClosed && sessionId != null) {
                            Text("When you have finished choosing apps to copy, finish app selection here.")
                            Button(onClick = {
                                val id = sessionId ?: return@Button
                                scope.launch { withContext(Dispatchers.IO) { queue.finishSelection(id) }; refresh++ }
                            }) { Text("Finish app selection") }
                        }
                        TextButton(onClick = { finish() }) { Text("Done") }
                    }
                }
            }
        }
    }
}

internal object MigrationResultText {
    fun ownerAccountConsent(count: Int) = (if (count == 1) "App Store will install the app that failed" else "App Store will install the $count apps that failed") +
        " with the Google account on this phone. They are added to your Google Play library, as on a new Pixel. Paid apps are not bought."
    fun description(result: Bundle): String {
        if (result.getBoolean("installed")) return "Installed and verified"
        return when (result.getString("error") ?: result.getString("errorCode")) {
            "anonymous_sign_in_required", "not_signed_in", "session_expired" -> "App Store could not start an anonymous session. Retry when it is available."
            "store_unavailable", "unavailable", "lookup_timeout", "network_error", "network" -> "App Store is unavailable. Check your connection and retry."
            "rate_limited" -> "App Store is busy. Retry later, or install with your Google account."
            "no_google_account" -> "Sign in to a Google account on this phone to install this app with it."
            "several_google_accounts" -> "This phone has more than one Google account. Install this app from App Store."
            "google_sign_in_needed" -> "Sign in to your Google account again, then retry."
            "google_approval_needed" -> "Allow App Store to use your Google account in its notification, then retry."
            "not_found" -> "This app is not available in App Store."
            "paid", "paid_app", "purchase_required", "account_required" -> "This app requires an account or purchase and cannot be copied anonymously."
            "incompatible", "incompatible_device" -> "This app is not compatible with this phone."
            "insufficient_storage", "storage", "no_space" -> "There is not enough space. Free some storage and retry."
            "owner_removed", "removed_by_owner", "removed" -> "You removed this app. It will not be installed again."
            "store_update_required", "bridge_update_required" -> "Update App Store before transferring apps."
            "installation_confirmation_required" -> "Installation needs your approval. Retry to resume, or open App Store."
            "installation_verification_required" -> "App Store could not verify this installation. Retry or check App Store."
            "selected_version_unavailable" -> "The selected app version is no longer available. Check App Store before retrying."
            "previous_transfer_requires_review", "installation_requires_review" -> "App Store could not confirm the previous request. Retry to approve another installation."
            "cancelled", "canceled" -> "Installation was cancelled."
            "needs_confirmation", "user_action_required" -> "Open App Store to confirm the installation."
            null -> when (result.getString("status")) {
                "queued", "pending", "none" -> "Waiting for App Store"
                "downloading" -> "Downloading"
                "downloaded", "installing" -> "Installing"
                "cancelled", "canceled" -> "Installation was cancelled."
                "removed" -> "You removed this app. It will not be installed again."
                "failed", "unavailable" -> "Could not install this app. Retry or check App Store."
                else -> "Waiting for installation to be verified"
            }
            else -> "Could not install this app. Retry or check App Store."
        }
    }
}
