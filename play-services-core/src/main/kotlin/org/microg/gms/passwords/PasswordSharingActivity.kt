/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.AccountManager
import android.os.Bundle
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit account and owner-selected recipient; no exported activity or background send path. */
class PasswordSharingActivity : PasswordOwnerActivity() {
    private val owner = AtomicBoolean(false)
    private var screen by mutableStateOf<PasswordSharingScreen?>(null)
    private var busy by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private var unlocked by mutableStateOf(false)
    private var selectedPassword by mutableStateOf<PasswordView?>(null)
    private var selectedRecipient by mutableStateOf<SharingRecipient?>(null)
    private var selectedIncoming by mutableStateOf<PasswordSharingIncoming?>(null)
    private var dismissIncoming by mutableStateOf<PasswordSharingIncoming?>(null)
    private var resolveSend by mutableStateOf<SharingOperation?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT < 28) { finish(); return }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setContent { CyclonMaterialTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.passwords_share), style = MaterialTheme.typography.headlineSmall)
                if (!unlocked) {
                    Button(onClick = ::authenticateOwner, enabled = !busy) { Text(stringResource(R.string.passwords_unlock)) }
                } else {
                    Text(intent.getStringExtra("account").orEmpty())
                    Text(stringResource(R.string.passwords_sharing_family_description))
                    Button(onClick = { perform {} }, enabled = !busy) { Text(stringResource(R.string.passwords_sharing_refresh)) }
                    screen?.let { state ->
                        if (!state.keysAvailable) Text(stringResource(R.string.passwords_sharing_unavailable))
                        if (state.recipients.none { it.eligible }) Text(stringResource(R.string.passwords_sharing_no_recipients))
                        state.passwords.filter { it.data.canFillRealm(it.data.realm) }.forEach { entry ->
                            Text(entry.data.realm); Text(entry.data.username)
                            TextButton(onClick = { requireOwner(); selectedPassword = entry },
                                enabled = !busy && state.keysAvailable && state.recipients.any { it.eligible }) {
                                Text(stringResource(R.string.passwords_share))
                            }
                        }
                        if (state.incoming.isNotEmpty()) Text(stringResource(R.string.passwords_sharing_incoming), style = MaterialTheme.typography.titleMedium)
                        state.incoming.forEach { incoming ->
                            Text(incoming.invitation.sender.name); Text(incoming.invitation.sender.email)
                            incoming.passwords.forEach { Text(it.realm); Text(it.username) }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(onClick = { requireOwner(); selectedIncoming = incoming }, enabled = !busy) {
                                    Text(stringResource(R.string.passwords_sharing_receive))
                                }
                                TextButton(onClick = { requireOwner(); dismissIncoming = incoming }, enabled = !busy) {
                                    Text(stringResource(R.string.passwords_sharing_dismiss))
                                }
                            }
                        }
                        state.operations.filter { !it.phase.terminal }.forEach { operation ->
                            if (operation.kind == SharingOperationKind.SEND) {
                                Text(stringResource(R.string.passwords_sharing_delivery_pending))
                                if (operation.phase in setOf(SharingOperationPhase.PREPARED, SharingOperationPhase.LAUNCHED, SharingOperationPhase.INDETERMINATE)) {
                                    TextButton(onClick = { requireOwner(); resolveSend = operation }, enabled = !busy) {
                                        Text(stringResource(R.string.passwords_sharing_resolve_send))
                                    }
                                }
                            }
                            else TextButton(onClick = { perform { it.resume(operation.id) } }, enabled = !busy) {
                                Text(stringResource(R.string.passwords_sharing_finish_receiving))
                            }
                        }
                    }
                }
                if (busy) LinearProgressIndicator()
                if (failed) Text(stringResource(R.string.passwords_sharing_failed))
                TextButton(onClick = { finish() }) { Text(stringResource(android.R.string.cancel)) }
            }
            selectedPassword?.takeIf { unlocked }?.let { entry ->
                AlertDialog(onDismissRequest = { selectedPassword = null; selectedRecipient = null },
                    title = { Text(stringResource(R.string.passwords_sharing_choose_recipient)) },
                    text = { PasswordDialogProtection(); Column {
                        Text(entry.data.realm); Text(entry.data.username)
                        screen?.recipients.orEmpty().forEach { recipient ->
                            TextButton(onClick = { requireOwner(); selectedRecipient = recipient }, enabled = recipient.eligible) {
                                Text(recipient.name + " " + recipient.email + if (selectedRecipient === recipient) " ✓" else "")
                            }
                        }
                        if (selectedRecipient != null) Text(stringResource(R.string.passwords_sharing_send_warning))
                    } },
                    confirmButton = { TextButton(onClick = {
                        val recipient = selectedRecipient ?: return@TextButton
                        selectedPassword = null; selectedRecipient = null
                        perform { it.send(entry.chromeBytes, recipient) }
                    }, enabled = selectedRecipient != null && !busy) { Text(stringResource(R.string.passwords_sharing_send)) } },
                    dismissButton = { TextButton(onClick = { selectedPassword = null; selectedRecipient = null }) { Text(stringResource(android.R.string.cancel)) } })
            }
            selectedIncoming?.takeIf { unlocked }?.let { incoming ->
                AlertDialog(onDismissRequest = { selectedIncoming = null }, title = { Text(stringResource(R.string.passwords_sharing_receive)) },
                    text = { PasswordDialogProtection(); Column { Text(incoming.invitation.sender.email)
                        incoming.passwords.forEach { Text(it.realm); Text(it.username) }
                        Text(stringResource(R.string.passwords_sharing_receive_description))
                    } },
                    confirmButton = { TextButton(onClick = { selectedIncoming = null; perform { it.accept(incoming) } }, enabled = !busy) {
                        Text(stringResource(R.string.passwords_sharing_receive))
                    } }, dismissButton = { TextButton(onClick = { selectedIncoming = null }) { Text(stringResource(android.R.string.cancel)) } })
            }
            dismissIncoming?.takeIf { unlocked }?.let { incoming ->
                AlertDialog(onDismissRequest = { dismissIncoming = null }, title = { Text(stringResource(R.string.passwords_sharing_dismiss)) },
                    text = { PasswordDialogProtection(); Text(incoming.invitation.sender.email) },
                    confirmButton = { TextButton(onClick = { dismissIncoming = null; perform { it.dismiss(incoming) } }, enabled = !busy) {
                        Text(stringResource(R.string.passwords_sharing_dismiss))
                    } }, dismissButton = { TextButton(onClick = { dismissIncoming = null }) { Text(stringResource(android.R.string.cancel)) } })
            }
            resolveSend?.takeIf { unlocked }?.let { operation ->
                AlertDialog(onDismissRequest = { resolveSend = null }, title = { Text(stringResource(R.string.passwords_sharing_resolve_send)) },
                    text = { PasswordDialogProtection(); Text(stringResource(R.string.passwords_sharing_resolve_warning)) },
                    confirmButton = { TextButton(onClick = {
                        resolveSend = null; perform { it.resolveUnknownSend(operation.id, acknowledgesPossibleDelivery = true) }
                    }, enabled = !busy) { Text(stringResource(R.string.passwords_sharing_allow_new_attempt)) } },
                    dismissButton = { TextButton(onClick = { resolveSend = null }) { Text(stringResource(android.R.string.cancel)) } })
            }
        } } }
        authenticateOwner()
    }
    private fun sharing(): PasswordSharing {
        val name = intent.getStringExtra("account") ?: throw VaultLockedException()
        val expectedId = intent.getStringExtra("google_user_id")?.takeIf { it.isNotBlank() } ?: throw VaultLockedException()
        val accounts = AccountManager.get(this)
        val account = accounts.getAccountsByType("com.google").singleOrNull { it.name == name } ?: throw VaultLockedException()
        if (accounts.getUserData(account, "GoogleUserId") != expectedId) throw VaultLockedException()
        return PasswordSharing(this, PasswordRepository(this, account)) { if (!owner.get()) throw VaultLockedException() }
    }
    private fun perform(action: (PasswordSharing) -> Unit) {
        requireOwner()
        if (busy) return
        busy = true; failed = false
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { sharing().let { client -> action(client); client.load() } }
                requireOwner()
                clearScreen(); screen = loaded
            } catch (_: Exception) { if (owner.get()) failed = true }
            finally { busy = false }
        }
    }
    override fun onOwnerAuthenticated() { owner.set(true); unlocked = true; perform {} }
    override fun onOwnerAuthenticationFailed() { finish() }
    override fun onPause() { owner.set(false); super.onPause() }
    override fun onResume() {
        super.onResume()
        if (unlocked) owner.set(runCatching { requireOwner(); true }.getOrDefault(false))
    }
    override fun onOwnerLocked() {
        owner.set(false); unlocked = false; selectedPassword = null; selectedRecipient = null; selectedIncoming = null; dismissIncoming = null; resolveSend = null
        clearScreen()
    }
    private fun clearScreen() {
        screen?.passwords?.forEach { it.chromeBytes.fill(0) }
        screen?.operations?.forEach { it.passwords.forEach { raw -> raw.fill(0) } }
        screen = null
    }
}
