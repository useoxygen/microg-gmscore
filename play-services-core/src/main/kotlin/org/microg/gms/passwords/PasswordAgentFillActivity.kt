/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.os.Bundle
import android.os.Build
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

/** Owner-selected account and entry; passwords are never drawn, copied, persisted in a ticket or sent to an agent. */
class PasswordAgentFillActivity : PasswordOwnerActivity() {
    private class Choice(val account: Account?, val selection: NativePasswordSelection, val username: String)
    private var choices by mutableStateOf<List<Choice>>(emptyList())
    private var busy by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private var request: PasswordAgentFillRequest? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 28) { finish(); return }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        try {
            request = PasswordAgentFillRequests.find(intent.data).also { selected ->
                if (!selected.valid(this)) throw SecurityException()
                selected.close = { finish() }
            }
        } catch (_: Exception) { finish(); return }
        setContent { CyclonMaterialTheme { Surface(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(stringResource(R.string.passwords_fill), style = MaterialTheme.typography.headlineSmall) }
                item { Text(request?.packageName.orEmpty()) }
                items(choices) { choice -> Button(onClick = { select(choice) }, enabled = !busy) {
                    Column {
                        Text(choice.username)
                        Text(choice.account?.name ?: stringResource(R.string.passwords_on_device))
                    }
                } }
                if (failed) item { Text(stringResource(R.string.passwords_sync_failed)) }
                if (busy) item { LinearProgressIndicator() }
                item { TextButton(onClick = { cancel() }) { Text(stringResource(android.R.string.cancel)) } }
            }
        } } }
        authenticateOwner()
    }
    override fun onOwnerAuthenticated() {
        val selected = request ?: return
        lifecycleScope.launch {
            try {
                val available = withContext(Dispatchers.IO) {
                    if (!selected.valid(this@PasswordAgentFillActivity)) throw SecurityException()
                    val accounts: List<Account?> = listOf(null) + AccountManager.get(this@PasswordAgentFillActivity).getAccountsByType("com.google").take(20)
                    accounts.flatMap { account -> runCatching {
                        val stableId = stableUserId(account)
                        val vault = vault(account)
                        val matcher = PasswordAffiliationMatcher(this@PasswordAgentFillActivity, vault)
                        if (!vault.enabled) emptyList() else vault.list().filter { matcher.canFill(it.data, selected.realm) }
                            .take(100).map { Choice(account, NativePasswordSelection(account?.name, stableId, it.id), it.data.username) }
                    }.getOrDefault(emptyList()) }.take(200)
                }
                requireOwner()
                if (!selected.valid(this@PasswordAgentFillActivity)) throw SecurityException()
                choices = available
            } catch (_: Exception) { cancel() }
        }
    }
    private fun select(choice: Choice) {
        requireOwner()
        if (busy) return
        val selected = request ?: return
        busy = true
        lifecycleScope.launch {
            var secret: CharArray? = null
            try {
                secret = withContext(Dispatchers.IO) {
                    if (!selected.valid(this@PasswordAgentFillActivity)) throw SecurityException()
                    if (!choice.selection.matches(choice.account?.name, stableUserId(choice.account))) throw SecurityException()
                    val vault = vault(choice.account)
                    if (!vault.enabled) throw VaultLockedException()
                    val matcher = PasswordAffiliationMatcher(this@PasswordAgentFillActivity, vault)
                    val entry = vault.list().singleOrNull { it.id == choice.selection.entryId && matcher.canFill(it.data, selected.realm) } ?: throw VaultLockedException()
                    if (!choice.selection.matches(choice.account?.name, stableUserId(choice.account))) throw SecurityException()
                    entry.data.password.toCharArray()
                }
                requireOwner()
                if (!choice.selection.matches(choice.account?.name, stableUserId(choice.account))) throw SecurityException()
                selected.deliver(this@PasswordAgentFillActivity, checkNotNull(secret))
                request = null
                finish()
            } catch (_: Exception) { failed = true }
            finally { secret?.fill('\u0000'); busy = false }
        }
    }
    private fun vault(account: Account?): PasswordVault = account?.let { PasswordRepository(this, it) } ?: DevicePasswordVault(this)
    private fun stableUserId(account: Account?): String? {
        if (account == null) return null
        val accounts = AccountManager.get(this)
        if (account !in accounts.getAccountsByType("com.google")) throw VaultLockedException()
        return accounts.getUserData(account, "GoogleUserId")?.takeIf { it.isNotBlank() } ?: throw VaultLockedException()
    }
    private fun cancel() { request?.let { PasswordAgentFillRequests.cancel(it.handle) }; request = null; finish() }
    override fun onOwnerAuthenticationFailed() = cancel()
    override fun onOwnerLocked() { choices = emptyList() }
    override fun onDestroy() {
        request?.let { PasswordAgentFillRequests.cancel(it.handle) }
        request = null
        super.onDestroy()
    }
}
