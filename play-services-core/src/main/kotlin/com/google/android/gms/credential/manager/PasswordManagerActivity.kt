/* SPDX-License-Identifier: Apache-2.0 */
package com.google.android.gms.credential.manager

import android.accounts.Account
import android.accounts.AccountManager
import android.os.Bundle
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

const val PASSWORD_MANAGER_CLASS_NAME = "com.google.android.gms.credential.manager.PasswordManagerActivity"
const val EXTRA_KEY_ACCOUNT_NAME = "pwm.DataFieldNames.accountName"

class PasswordManagerActivity : PasswordOwnerActivity() {
    private var unlocked by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf<Int?>(null)
    private var entries by mutableStateOf<List<PasswordView>>(emptyList())
    private var selected by mutableStateOf<Account?>(null)
    private var storageChosen by mutableStateOf(false)
    private var enabled by mutableStateOf(false)
    private var editing by mutableStateOf<PasswordView?>(null)
    private var editorOpen by mutableStateOf(false)
    private var deleting by mutableStateOf<PasswordView?>(null)
    private var passphrase by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT < 26) { finish(); return }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setContent { CyclonMaterialTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    if (storageChosen) {
                        if (selected != null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Switch(checked = enabled, enabled = !busy, onCheckedChange = { value -> perform {
                                repository().setEnabled(value)
                                if (value) repository().refresh()
                            } })
                            Text(stringResource(R.string.passwords_sync), Modifier.padding(top = 12.dp))
                        }
                        if (enabled) {
                            if (message == R.string.passwords_locked) {
                                OutlinedTextField(passphrase, { passphrase = it }, label = { Text(stringResource(R.string.passwords_passphrase)) },
                                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                                Button(onClick = {
                                    val secret = passphrase.toByteArray(Charsets.UTF_8); passphrase = ""
                                    perform { try { repository().unlockPassphrase(secret); repository().refresh() } finally { secret.fill(0) } }
                                }, enabled = !busy && passphrase.isNotEmpty()) { Text(stringResource(R.string.passwords_unlock)) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = { perform { repository().refresh() } }, enabled = !busy) { Text(stringResource(R.string.passwords_refresh)) }
                                Button(onClick = { editing = null; editorOpen = true }, enabled = !busy) { Text(stringResource(R.string.passwords_add)) }
                            }
                            if (message == R.string.passwords_no_vault) {
                                Button(onClick = { perform { repository().initializeEmptyVault(); repository().refresh() } }, enabled = !busy) {
                                    Text(stringResource(R.string.passwords_create_vault))
                                }
                            }
                        }
                    }
                    entries.forEach { entry ->
                        HorizontalDivider()
                        Text(entry.data.realm, style = MaterialTheme.typography.titleMedium)
                        Text(entry.data.username)
                        Row {
                            TextButton(onClick = { editing = entry; editorOpen = true }, enabled = !busy) { Text(stringResource(R.string.passwords_edit)) }
                            TextButton(onClick = { deleting = entry }, enabled = !busy) { Text(stringResource(R.string.passwords_delete)) }
                        }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(stringResource(it)) }
                TextButton(onClick = { finish() }) { Text(stringResource(R.string.cyclon_back)) }
            }
            if (editorOpen && unlocked) PasswordEditor(editing, onDismiss = { editorOpen = false; editing = null }) { data ->
                editorOpen = false; editing = null
                perform { repository().upsert(Proto.field(1, data.encode())); repository().refresh() }
            }
            deleting?.takeIf { unlocked }?.let { entry -> AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text(stringResource(R.string.passwords_delete_question)) },
                text = { Text(entry.data.realm) },
                confirmButton = { TextButton(onClick = { deleting = null; perform {
                    repository().delete(entry.chromeBytes); repository().refresh()
                } }) { Text(stringResource(R.string.passwords_delete)) } },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(android.R.string.cancel)) } }
            ) }
        } } }
    }

    override fun onOwnerAuthenticated() {
        unlocked = true; message = null
        val accounts = AccountManager.get(this).getAccountsByType("com.google")
        val requested = intent.getStringExtra(EXTRA_KEY_ACCOUNT_NAME)
        val account = accounts.firstOrNull { it.name == requested } ?: accounts.singleOrNull()
        if (account != null || accounts.isEmpty()) choose(account)
    }
    override fun onOwnerAuthenticationFailed() { message = R.string.passwords_access_failed }
    override fun onOwnerLocked() {
        unlocked = false; entries = emptyList(); editing = null; deleting = null; editorOpen = false; passphrase = ""
    }
    private fun choose(account: Account?) {
        requireOwner(); selected = account; storageChosen = true; entries = emptyList()
        perform { }
    }
    private fun repository(account: Account? = selected): PasswordVault = account?.let { PasswordRepository(this, it) } ?: DevicePasswordVault(this)
    private fun perform(action: () -> Unit) {
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
                    repo.enabled to if (repo.enabled) repo.list() else emptyList()
                }
                requireOwner()
                if (selected == account) { enabled = result.first; entries = result.second }
            } catch (failure: Exception) {
                message = when {
                    failure is PasswordRpcException && failure.code == 5 -> R.string.passwords_no_vault
                    failure is VaultLockedException -> R.string.passwords_locked
                    failure is ConflictException || failure is PendingMutationException -> R.string.passwords_pending
                    else -> R.string.passwords_sync_failed
                }
                enabled = runCatching { repository(account).enabled }.getOrDefault(false)
            } finally { busy = false }
        }
    }
}

@Composable
private fun PasswordEditor(existing: PasswordView?, onDismiss: () -> Unit, onSave: (PasswordData) -> Unit) {
    var url by remember { mutableStateOf(existing?.data?.origin.orEmpty()) }
    var username by remember { mutableStateOf(existing?.data?.username.orEmpty()) }
    var password by remember { mutableStateOf(existing?.data?.password.orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.passwords_add else R.string.passwords_edit)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(url, { url = it }, enabled = existing == null, label = { Text(stringResource(R.string.passwords_site)) })
            OutlinedTextField(username, { username = it }, enabled = existing == null, label = { Text(stringResource(R.string.passwords_username)) })
            OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.passwords_password)) },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (invalid) Text(stringResource(R.string.passwords_invalid))
        } },
        confirmButton = { TextButton(onClick = {
            val data = runCatching {
                if (password.isEmpty()) throw ProtocolException()
                existing?.data?.withPassword(password, System.currentTimeMillis()) ?: PasswordData.create(
                    PasswordData.webRealm(url), url, username, password, System.currentTimeMillis())
            }.getOrNull()
            if (data == null) invalid = true else onSave(data)
        }) { Text(stringResource(R.string.passwords_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}
