/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Owner-visible receipts; this Activity never accepts an app list from an external caller. */
class MigrationResultsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var results by remember { mutableStateOf(emptyList<Bundle>()) }
            var refresh by remember { mutableStateOf(0) }
            val queue = remember { MigrationInstallQueue(applicationContext) }
            LaunchedEffect(refresh) {
                while (true) {
                    results = withContext(Dispatchers.IO) {
                        queue.summary().getParcelableArray("cyclon_app_results")?.map { it as Bundle }.orEmpty()
                    }
                    delay(5000)
                }
            }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Transferred apps", style = MaterialTheme.typography.headlineMedium)
                        Text("Copying your data can finish while App Store continues installing apps. An app is complete only after installation is verified.")
                        Button(onClick = { queue.select(queue.retryDocuments()); refresh++ }, enabled = results.isNotEmpty()) {
                            Text("Retry failed apps")
                        }
                        LazyColumn(Modifier.weight(1f)) {
                            items(results) { result ->
                                Text(result.getString("package_name").orEmpty(), style = MaterialTheme.typography.titleMedium)
                                val error = result.getString("error") ?: result.getString("errorCode")
                                Text(when {
                                    result.getBoolean("installed") -> "Installed and verified"
                                    error == "anonymous_sign_in_required" -> "Open App Store and sign in anonymously, then retry."
                                    error != null -> "Could not install: $error"
                                    else -> result.getString("status", "Waiting for App Store")
                                })
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                        TextButton(onClick = { finish() }) { Text("Done") }
                    }
                }
            }
        }
    }
}
