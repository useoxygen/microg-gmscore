/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.os.Bundle
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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

/** Internal owner surface: a known stable account and fresh foreground owner grant are required. */
class PasswordWifiSyncActivity : PasswordOwnerActivity() {
    private lateinit var account: Account
    private lateinit var userId: String
    private var unlocked by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var enabled by mutableStateOf(false)
    private var available by mutableStateOf(false)
    private var pending by mutableStateOf(0)
    private var conflicts by mutableStateOf(0)
    private var uncertain by mutableStateOf(0)
    private var prompt by mutableStateOf<Int?>(null)
    private var message by mutableStateOf<Int?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT < 26) { finish(); return }
        val name = intent.getStringExtra("account")
        val id = intent.getStringExtra("google_user_id")
        if (name.isNullOrBlank() || id.isNullOrBlank()) { finish(); return }
        account = Account(name, "com.google"); userId = id
        if (!validAccount()) { finish(); return }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setContent { CyclonMaterialTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.passwords_wifi_title), style = MaterialTheme.typography.headlineSmall)
                if (!unlocked) Button(onClick = ::authenticateOwner) { Text(stringResource(R.string.passwords_unlock)) }
                else {
                    Text(account.name)
                    Text(stringResource(R.string.passwords_wifi_description))
                    if (!available) Text(stringResource(R.string.passwords_wifi_unavailable))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Switch(enabled = !busy && (available || enabled), checked = enabled, onCheckedChange = { value ->
                            if (value) prompt = R.string.passwords_wifi_enable_warning else perform { it.setEnabled(false) }
                        })
                        Text(stringResource(R.string.passwords_wifi_enable))
                    }
                    Text(stringResource(R.string.passwords_wifi_state, pending, conflicts))
                    OutlinedButton(enabled = enabled && available && !busy, onClick = { perform { it.synchronizeIfEnabled() } }) {
                        Text(stringResource(R.string.passwords_refresh))
                    }
                    if (uncertain > 0) OutlinedButton(enabled = !busy && enabled && available,
                        onClick = { prompt = R.string.passwords_wifi_retry_warning }) { Text(stringResource(R.string.passwords_retry_owner)) }
                    TextButton(enabled = !busy, onClick = { prompt = R.string.passwords_wifi_forget_warning }) {
                        Text(stringResource(R.string.passwords_wifi_forget))
                    }
                    message?.let { Text(stringResource(it)) }
                }
            }
            prompt?.takeIf { unlocked }?.let { question -> AlertDialog(onDismissRequest = { prompt = null },
                title = { Text(stringResource(R.string.passwords_wifi_title)) }, text = { Text(stringResource(question)) },
                confirmButton = { TextButton(onClick = {
                    prompt = null
                    perform { helper -> when (question) {
                        R.string.passwords_wifi_enable_warning -> { helper.setEnabled(true); helper.synchronizeIfEnabled() }
                        R.string.passwords_wifi_retry_warning -> helper.synchronizeIfEnabled(ownerAuthorizesRetry = true)
                        R.string.passwords_wifi_forget_warning -> helper.pauseAndForget()
                    } }
                }) { Text(stringResource(android.R.string.ok)) } },
                dismissButton = { TextButton(onClick = { prompt = null }) { Text(stringResource(android.R.string.cancel)) } }) }
        } } }
        authenticateOwner()
    }
    private fun validAccount(): Boolean {
        val accounts = AccountManager.get(this)
        return accounts.getAccountsByType("com.google").contains(account) && accounts.getUserData(account, "GoogleUserId") == userId
    }
    private fun helper(): PasswordWifiSync {
        if (!validAccount()) throw VaultLockedException()
        val repo = PasswordRepository(this, account)
        if (!validAccount()) throw VaultLockedException()
        return PasswordWifiSync(this, repo, userId)
    }
    private fun perform(action: (PasswordWifiSync) -> Unit = {}) {
        requireOwner()
        if (busy) return
        busy = true; message = null
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val helper = helper(); action(helper)
                helper.status() to helper.available()
            } }
            busy = false
            if (!unlocked) return@launch
            try { requireOwner(); if (!validAccount()) throw VaultLockedException() }
            catch (_: Exception) { onOwnerLocked(); return@launch }
            result.onSuccess { (state, admitted) ->
                enabled = state.enabled; available = admitted; pending = state.pending; conflicts = state.conflicts; uncertain = state.indeterminate
            }.onFailure {
                message = if (it is PasswordWifiUnavailableException) R.string.passwords_wifi_unavailable else R.string.passwords_sync_failed
            }
        }
    }
    override fun onOwnerAuthenticated() { unlocked = true; perform() }
    override fun onOwnerAuthenticationFailed() { onOwnerLocked() }
    override fun onOwnerLocked() { unlocked = false; prompt = null; message = null; pending = 0; conflicts = 0; uncertain = 0 }
}
