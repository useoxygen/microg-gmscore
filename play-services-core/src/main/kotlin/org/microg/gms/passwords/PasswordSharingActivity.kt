/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.AccountManager
import android.os.Bundle
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import org.microg.gms.cyclon.*
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
        setContent { CyclonMaterialTheme {
            PasswordOwnerPage(stringResource(R.string.passwords_share), intent.getStringExtra("account"), onBack = ::finish) {
                if (!unlocked) {
                    PasswordOwnerLocked(hasSecureScreenLock, enabled = !busy, onUnlock = ::authenticateOwner)
                    PasswordOwnerStatus(busy, stringResource(R.string.passwords_sharing_failed).takeIf { failed })
                    return@PasswordOwnerPage
                }
                CyclonNote(stringResource(R.string.passwords_sharing_family_description))
                PasswordOwnerActions { CyclonLineButton(stringResource(R.string.passwords_sharing_refresh), enabled = !busy) { perform {} } }
                PasswordOwnerStatus(busy, stringResource(R.string.passwords_sharing_failed).takeIf { failed })
                screen?.let { state ->
                    if (!state.keysAvailable) PasswordOwnerText(stringResource(R.string.passwords_sharing_unavailable))
                    if (state.recipients.none { it.eligible }) PasswordOwnerText(stringResource(R.string.passwords_sharing_no_recipients))
                    val shareable = state.passwords.filter { it.data.canFillRealm(it.data.realm) }
                    if (shareable.isNotEmpty()) CyclonSectionLabel(stringResource(R.string.passwords_saved))
                    shareable.forEach { entry ->
                        CyclonRow(sharingTitle(entry.data.realm), entry.data.username.ifEmpty { null }, summaryLines = 1,
                            leading = { CyclonMonogram(sharingTitle(entry.data.realm)) }, trailing = {
                                CyclonTextAction(stringResource(R.string.passwords_sharing_share),
                                    enabled = !busy && state.keysAvailable && state.recipients.any { it.eligible }) {
                                    requireOwner(); selectedPassword = entry
                                }
                            })
                    }
                    if (state.incoming.isNotEmpty()) CyclonSectionLabel(stringResource(R.string.passwords_sharing_incoming))
                    state.incoming.forEach { incoming ->
                        CyclonRow(incoming.invitation.sender.name.ifEmpty { incoming.invitation.sender.email },
                            incoming.invitation.sender.email.takeIf { incoming.invitation.sender.name.isNotEmpty() }, divider = false,
                            leading = { CyclonMonogram(incoming.invitation.sender.name.ifEmpty { incoming.invitation.sender.email }) })
                        incoming.passwords.forEach { CyclonRow(sharingTitle(it.realm), it.username.ifEmpty { null }, divider = false, inset = 70.dp, summaryLines = 1) }
                        Row(Modifier.padding(start = 54.dp)) {
                            CyclonTextAction(stringResource(R.string.passwords_sharing_receive), enabled = !busy) { requireOwner(); selectedIncoming = incoming }
                            CyclonTextAction(stringResource(R.string.passwords_sharing_dismiss), enabled = !busy) { requireOwner(); dismissIncoming = incoming }
                        }
                        CyclonHairline(Modifier.padding(start = 20.dp))
                    }
                    val open = state.operations.filter { !it.phase.terminal }
                    if (open.isNotEmpty()) CyclonSectionLabel(stringResource(R.string.passwords_changes))
                    open.forEach { operation ->
                        if (operation.kind == SharingOperationKind.SEND) {
                            if (operation.phase in setOf(SharingOperationPhase.PREPARED, SharingOperationPhase.LAUNCHED, SharingOperationPhase.INDETERMINATE))
                                CyclonRow(stringResource(R.string.passwords_sharing_delivery_pending), trailing = {
                                    CyclonTextAction(stringResource(R.string.passwords_resolve), enabled = !busy) { requireOwner(); resolveSend = operation }
                                })
                            else CyclonRow(stringResource(R.string.passwords_sharing_delivery_pending))
                        }
                        else CyclonRow(stringResource(R.string.passwords_sharing_finish_receiving), onClick = { perform { it.resume(operation.id) } },
                            enabled = !busy, trailing = { CyclonChevron() })
                    }
                }
            }
            selectedPassword?.takeIf { unlocked }?.let { entry ->
                AlertDialog(onDismissRequest = { selectedPassword = null; selectedRecipient = null },
                    title = { Text(stringResource(R.string.passwords_sharing_choose_recipient)) },
                    text = { PasswordDialogProtection(); Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                        Text(sharingTitle(entry.data.realm), style = MaterialTheme.typography.titleMedium)
                        Text(entry.data.username, style = MaterialTheme.typography.bodyMedium)
                        CyclonHairline(Modifier.padding(top = 12.dp))
                        Column(Modifier.selectableGroup()) {
                            screen?.recipients.orEmpty().forEach { recipient ->
                                CyclonChoiceRow(recipient.name.ifEmpty { recipient.email }, recipient.email.takeIf { recipient.name.isNotEmpty() },
                                    selected = selectedRecipient === recipient, enabled = recipient.eligible, inset = 0.dp) {
                                    requireOwner(); selectedRecipient = recipient
                                }
                            }
                        }
                        if (selectedRecipient != null) PasswordDialogText(stringResource(R.string.passwords_sharing_send_warning), Modifier.padding(top = 8.dp))
                    } },
                    confirmButton = { CyclonTextAction(stringResource(R.string.passwords_sharing_send), enabled = selectedRecipient != null && !busy) {
                        val recipient = selectedRecipient ?: return@CyclonTextAction
                        selectedPassword = null; selectedRecipient = null
                        perform { it.send(entry.chromeBytes, recipient) }
                    } },
                    dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel)) { selectedPassword = null; selectedRecipient = null } })
            }
            selectedIncoming?.takeIf { unlocked }?.let { incoming ->
                AlertDialog(onDismissRequest = { selectedIncoming = null }, title = { Text(stringResource(R.string.passwords_sharing_receive)) },
                    text = { PasswordDialogProtection(); Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(incoming.invitation.sender.email, style = MaterialTheme.typography.bodyMedium, color = Cyclon.palette.muted)
                        incoming.passwords.forEach {
                            Text(sharingTitle(it.realm), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
                            Text(it.username, style = MaterialTheme.typography.bodyMedium)
                        }
                        PasswordDialogText(stringResource(R.string.passwords_sharing_receive_description), Modifier.padding(top = 8.dp))
                    } },
                    confirmButton = { CyclonTextAction(stringResource(R.string.passwords_sharing_receive), enabled = !busy) {
                        selectedIncoming = null; perform { it.accept(incoming) }
                    } }, dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel)) { selectedIncoming = null } })
            }
            dismissIncoming?.takeIf { unlocked }?.let { incoming ->
                AlertDialog(onDismissRequest = { dismissIncoming = null }, title = { Text(stringResource(R.string.passwords_sharing_dismiss)) },
                    text = { PasswordDialogProtection(); PasswordDialogText(incoming.invitation.sender.email) },
                    confirmButton = { CyclonTextAction(stringResource(R.string.passwords_sharing_dismiss), enabled = !busy) {
                        dismissIncoming = null; perform { it.dismiss(incoming) }
                    } }, dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel)) { dismissIncoming = null } })
            }
            resolveSend?.takeIf { unlocked }?.let { operation ->
                AlertDialog(onDismissRequest = { resolveSend = null }, title = { Text(stringResource(R.string.passwords_sharing_resolve_send)) },
                    text = { PasswordDialogProtection(); PasswordDialogText(stringResource(R.string.passwords_sharing_resolve_warning), scrolls = true) },
                    confirmButton = { CyclonTextAction(stringResource(R.string.passwords_sharing_allow_new_attempt), enabled = !busy) {
                        resolveSend = null; perform { it.resolveUnknownSend(operation.id, acknowledgesPossibleDelivery = true) }
                    } },
                    dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel)) { resolveSend = null } })
            }
        } }
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

/** A login's website host, or the app it belongs to, as the Passwords list names it. */
private fun sharingTitle(realm: String): String =
    if (realm.startsWith("android://")) realm.substringAfter('@').trimEnd('/').ifEmpty { realm }
    else Uri.parse(realm).host?.takeIf { it.isNotBlank() } ?: realm
