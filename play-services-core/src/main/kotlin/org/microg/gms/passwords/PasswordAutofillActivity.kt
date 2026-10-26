/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
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
import java.security.MessageDigest

@RequiresApi(28)
class PasswordAutofillActivity : PasswordOwnerActivity() {
    private var saving by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private var localSavingAllowed by mutableStateOf(false)
    private var savingBlocked by mutableStateOf(false)
    private var accounts by mutableStateOf<List<Account>>(emptyList())
    private var ticket: PasswordRequestTicket? = null
    private var payload: Bundle? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        setContent { CyclonMaterialTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.passwords_save), style = MaterialTheme.typography.headlineSmall)
                if (saving) {
                    Text(ticket?.realm.orEmpty())
                    Text(payload?.getString("username").orEmpty())
                    if (localSavingAllowed) Button(onClick = { save(null) }, enabled = !busy) { Text(stringResource(R.string.passwords_on_device)) }
                    accounts.forEach { account -> Button(onClick = { save(account) }, enabled = !busy) { Text(account.name) } }
                }
                if (failed) Text(stringResource(R.string.passwords_sync_failed))
                if (savingBlocked) Text(stringResource(R.string.passwords_saving_disabled))
                if (busy) LinearProgressIndicator()
                TextButton(onClick = { finish() }) { Text(stringResource(android.R.string.cancel)) }
            }
        } } }
        authenticateOwner()
    }
    override fun onOwnerAuthenticated() {
        lifecycleScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val tickets = PasswordRequestTickets(this@PasswordAutofillActivity)
                    val selected = tickets.inspect(intent.data)
                    if (selected.mode !in 2..3) throw SecurityException()
                    val fields = PasswordAutofillTarget.unpack(selected.payload ?: throw SecurityException())
                    val caller = PasswordAutofillTarget.recheck(this@PasswordAutofillActivity, fields, selected.realm)
                    val consumed = tickets.consume(intent.data, caller, selected.mode)
                    consumed to fields
                }
                requireOwner()
                ticket = prepared.first; payload = prepared.second
                if (prepared.first.mode == 2) fill(prepared.first, prepared.second)
                else {
                    val available = withContext(Dispatchers.IO) {
                        val local = runCatching { DevicePasswordVault(this@PasswordAutofillActivity).acceptsAutomaticSave(prepared.first.realm) }.getOrDefault(false)
                        local to AccountManager.get(this@PasswordAutofillActivity).getAccountsByType("com.google").filter {
                            runCatching { PasswordRepository(this@PasswordAutofillActivity, it).acceptsAutomaticSave(prepared.first.realm) }.getOrDefault(false)
                        }
                    }
                    requireOwner()
                    localSavingAllowed = available.first; accounts = available.second
                    saving = localSavingAllowed || accounts.isNotEmpty(); savingBlocked = !saving
                }
            } catch (_: Exception) { finish() }
        }
    }
    private suspend fun fill(selected: PasswordRequestTicket, fields: Bundle) {
        @Suppress("DEPRECATION")
        val structure = intent.getParcelableExtra<AssistStructure>(AutofillManager.EXTRA_ASSIST_STRUCTURE) ?: throw SecurityException()
        val current = PasswordAutofillTarget.parse(this, structure)
        @Suppress("DEPRECATION")
        val usernameId = fields.getParcelable<AutofillId>("usernameId")
        @Suppress("DEPRECATION")
        val passwordId = fields.getParcelable<AutofillId>("passwordId") ?: throw SecurityException()
        if (!MessageDigest.isEqual(current.caller, selected.caller) || current.realm != selected.realm ||
            current.usernameId != usernameId || current.passwordId != passwordId) throw SecurityException()
        val entry = withContext(Dispatchers.IO) {
            val vault: PasswordVault = selected.account?.let { PasswordRepository(this@PasswordAutofillActivity, it) }
                ?: DevicePasswordVault(this@PasswordAutofillActivity)
            val matcher = PasswordAffiliationMatcher(this@PasswordAutofillActivity, vault)
            vault.list()
                .singleOrNull { it.id == selected.tag && matcher.canFill(it.data, selected.realm) } ?: throw VaultLockedException()
        }
        requireOwner()
        val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply { setTextViewText(android.R.id.text1, getString(R.string.passwords_fill)) }
        @Suppress("DEPRECATION")
        val dataset = Dataset.Builder(presentation).setValue(passwordId, AutofillValue.forText(entry.data.password)).apply {
            usernameId?.let { setValue(it, AutofillValue.forText(entry.data.username)) }
        }.build()
        setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)); finish()
    }
    private fun save(account: Account?) {
        requireOwner()
        if (busy) return
        val selected = ticket ?: return
        val fields = payload ?: return
        busy = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PasswordAutofillTarget.recheck(this@PasswordAutofillActivity, fields, selected.realm)
                    val username = fields.getString("username") ?: throw SecurityException()
                    val password = fields.getString("password")?.takeIf { it.isNotEmpty() } ?: throw SecurityException()
                    val repo: PasswordVault = account?.let { PasswordRepository(this@PasswordAutofillActivity, it) }
                        ?: DevicePasswordVault(this@PasswordAutofillActivity)
                    if (!repo.acceptsAutomaticSave(selected.realm)) throw PasswordSaveBlockedException()
                    val old = repo.list().singleOrNull { !it.data.blocked && it.data.matchesRealmAuthority(selected.realm) && it.data.username == username }
                    val data = old?.data?.withPassword(password, System.currentTimeMillis()) ?: PasswordData.create(
                        selected.realm, selected.realm, username, password, System.currentTimeMillis())
                    repo.upsertAutomatic(Proto.field(1, data.encode()))
                }
                setResult(RESULT_OK); finish()
            } catch (_: PasswordSaveBlockedException) { savingBlocked = true }
            catch (_: Exception) { failed = true } finally { busy = false }
        }
    }
    override fun onOwnerAuthenticationFailed() { finish() }
    override fun onOwnerLocked() {
        saving = false; localSavingAllowed = false; savingBlocked = false
        ticket?.payload?.fill(0); ticket = null; payload?.clear(); payload = null; accounts = emptyList()
    }
}
