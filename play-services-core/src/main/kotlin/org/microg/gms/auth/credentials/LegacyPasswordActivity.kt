/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.credentials

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Intent
import android.os.Build
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.R
import com.google.android.gms.auth.api.credentials.Credential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.microg.gms.cyclon.CyclonMaterialTheme
import org.microg.gms.passwords.*
import java.security.MessageDigest

/** Native legacy API resolution. No caller-provided realm or default Google account. */
class LegacyPasswordActivity : PasswordOwnerActivity() {
    private class Choice(val account: Account?, val userId: String?, val label: String, val username: String, val original: ByteArray?)
    private var request: LegacyPasswordRequest? = null
    private var choices by mutableStateOf<List<Choice>>(emptyList())
    private var ready by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        if (Build.VERSION.SDK_INT < 28 || savedInstanceState != null) { finish(); return }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        try { request = LegacyPasswordRequests(this).consume(intent.data, callingActivity?.packageName) }
        catch (_: Exception) { finish(); return }
        setContent { CyclonMaterialTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.passwords_title), style = MaterialTheme.typography.headlineSmall)
                if (ready) {
                    val value = request!!
                    Text(stringResource(R.string.passwords_legacy_request, value.packageName))
                    Text(stringResource(when (value.mode) {
                        LegacyPasswordRequests.SAVE -> R.string.passwords_legacy_save
                        LegacyPasswordRequests.DELETE -> R.string.passwords_legacy_delete
                        else -> R.string.passwords_legacy_get
                    }))
                    if (choices.isEmpty()) Text(stringResource(R.string.passwords_legacy_empty))
                    choices.forEach { choice ->
                        Button(onClick = { select(choice) }, enabled = !busy) { Text(choice.label + "\n" + choice.username) }
                    }
                }
                TextButton(onClick = { finish() }, enabled = !busy) { Text(stringResource(android.R.string.cancel)) }
            }
        } } }
        authenticateOwner()
    }
    private fun accountGuard(account: Account?, userId: String?) = LegacyPasswordAccountGuard(userId) {
        PasswordOwnerOperations(this).identity(account).userId
    }
    private fun vault(account: Account?, userId: String?): ManagedPasswordVault = accountGuard(account, userId).checked {
        account?.let { PasswordRepository(this, it) } ?: DevicePasswordVault(this)
    }
    override fun onOwnerAuthenticated() {
        if (ready || busy) return
        busy = true
        lifecycleScope.launch {
            try {
                requireOwner()
                val value = request ?: throw SecurityException()
                val loaded = withContext(Dispatchers.IO) {
                    LegacyPasswordRequests(this@LegacyPasswordActivity).recheck(value)
                    val stores = listOf<Account?>(null) + AccountManager.get(this@LegacyPasswordActivity).getAccountsByType("com.google").toList()
                    stores.flatMap { account ->
                        val userId = PasswordOwnerOperations(this@LegacyPasswordActivity).identity(account).userId
                        val repository = vault(account, userId)
                        val matcher = PasswordAffiliationMatcher(this@LegacyPasswordActivity, repository)
                        if (!repository.enabled) emptyList() else {
                            val label = account?.name ?: getString(R.string.passwords_on_device)
                            val entries = repository.list().filter { matcher.canFill(it.data, value.realm) &&
                                (value.mode == LegacyPasswordRequests.GET || it.data.username == value.username) }
                            if (value.mode == LegacyPasswordRequests.SAVE) {
                                if (entries.size > 1) throw ConflictException()
                                listOf(Choice(account, userId, label, value.username!!, entries.singleOrNull()?.chromeBytes))
                            } else entries.take(100).map { Choice(account, userId, label, it.data.username, it.chromeBytes) }
                        }
                    }.take(200)
                }
                requireOwner()
                choices = loaded; ready = true
            } catch (_: Exception) { finish() }
            finally { busy = false }
        }
    }
    private fun select(choice: Choice) {
        if (busy || !ready) return
        busy = true
        lifecycleScope.launch {
            try {
                requireOwner()
                val value = request ?: throw SecurityException()
                val guard = accountGuard(choice.account, choice.userId)
                val result = withContext(Dispatchers.IO) {
                    guard.checked {
                        LegacyPasswordRequests(this@LegacyPasswordActivity).recheck(value)
                        val repository = vault(choice.account, choice.userId)
                        val matcher = PasswordAffiliationMatcher(this@LegacyPasswordActivity, repository)
                        if (!repository.enabled) throw VaultLockedException()
                        when (value.mode) {
                            LegacyPasswordRequests.GET -> {
                                val original = PasswordData.decode(Proto.decode(choice.original ?: throw SecurityException()).one(1))
                                val current = repository.list().singleOrNull { it.id == original.tag() && matcher.canFill(it.data, value.realm) }
                                    ?: throw VaultLockedException()
                                if (!MessageDigest.isEqual(original.encode(), current.data.encode())) throw ConflictException()
                                Intent().putExtra(Credential.EXTRA_KEY, Credential.Builder(current.data.username)
                                    .setPassword(current.data.password).setName(current.data.displayName).build())
                            }
                            LegacyPasswordRequests.SAVE -> {
                                val original = choice.original?.let { PasswordData.decode(Proto.decode(it).one(1)) }
                                val updated = (original?.withPassword(value.password!!, System.currentTimeMillis()) ?: PasswordData.create(
                                    value.realm, value.realm, value.username!!, value.password!!, System.currentTimeMillis()))
                                    .withDisplayName(value.displayName.orEmpty())
                                val replacement = Proto.field(1, updated.encode())
                                if (choice.original == null) repository.insertIfAbsent(replacement)
                                else repository.editIfUnchanged(choice.original, replacement)
                                Intent()
                            }
                            LegacyPasswordRequests.DELETE -> { repository.deleteIfUnchanged(choice.original ?: throw SecurityException()); Intent() }
                            else -> throw SecurityException()
                        }
                    }
                }
                requireOwner()
                LegacyPasswordRequests(this@LegacyPasswordActivity).recheck(value)
                guard.check()
                setResult(RESULT_OK, result); finish()
            } catch (_: Exception) { finish() }
            finally { busy = false }
        }
    }
    override fun onOwnerAuthenticationFailed() { finish() }
    override fun onOwnerLocked() {
        choices.forEach { it.original?.fill(0) }; choices = emptyList()
        if (ready) { ready = false; request = null; finish() }
    }
}
