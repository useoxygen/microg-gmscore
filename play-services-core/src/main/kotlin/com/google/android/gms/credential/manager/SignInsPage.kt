/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package com.google.android.gms.credential.manager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.gms.R
import org.microg.gms.cyclon.*
import org.microg.gms.passwords.AgentSignInAccess
import java.text.DateFormat
import java.util.Date

/**
 * Sign-ins for Cyclon (CYC-1594): which saved logins Cyclon may sign in with, reached from Home's AI settings and by
 * voice. Setup first, only the steps still undone; then every login with its choice; then the general password
 * settings. Words only: [PasswordManagerActivity] owns the vault, the lock and the choice dialog.
 */
internal object SignIns {
    /** The extra and value that open [PasswordManagerActivity] on this page; older versions ignore it and show Passwords. */
    const val EXTRA_PAGE = "org.microg.gms.cyclon.extra.PAGE"
    const val PAGE = "sign_ins"

    /** The setup steps still to do; each is false once done or while it cannot be known. */
    data class Setup(val account: Boolean, val autofill: Boolean, val passwords: Boolean) {
        val any get() = account || autofill || passwords
    }

    class Login(val id: String, val title: String, val username: String, val packageName: String?,
                val level: AgentSignInAccess.Level, val lastUsed: Long?)

    sealed interface Logins {
        /** The vault is locked: the owner unlocks it to see the list. */
        object Locked : Logins
        /** Unlocked but where passwords are saved is not chosen yet (several accounts): the picker above says so. */
        object Choosing : Logins
        class Shown(val logins: List<Login>) : Logins
    }

    /** A login's line under its name: its username when it has one, Cyclon's choice, and when Cyclon last used it. */
    fun details(username: String, level: String, lastUsed: String?) = listOfNotNull(username.ifEmpty { null }, level, lastUsed).joinToString(" · ")

    /** "No saved logins yet" only where the setup step does not already say so, and not while reading. */
    fun saysNone(setup: Setup, logins: Logins, busy: Boolean) = logins is Logins.Shown && logins.logins.isEmpty() && !busy && !setup.passwords

    /** The logins' section, unless it would be empty: no logins, and the setup step says so. */
    fun showsLogins(setup: Setup, logins: Logins, busy: Boolean) =
        logins !is Logins.Shown || logins.logins.isNotEmpty() || saysNone(setup, logins, busy)

    /** That the choices stay on this phone, wherever there are (or may be) logins to choose for. */
    fun saysThisPhone(logins: Logins) = logins !is Logins.Shown || logins.logins.isNotEmpty()

    fun levelLabel(level: AgentSignInAccess.Level) = when (level) {
        AgentSignInAccess.Level.ASK -> R.string.passwords_assistant_ask
        AgentSignInAccess.Level.ALWAYS -> R.string.passwords_assistant_always
        AgentSignInAccess.Level.NEVER -> R.string.passwords_assistant_never
    }
}

internal class SignInsActions(
    val back: () -> Unit, val addAccount: () -> Unit, val autofill: () -> Unit, val addPassword: () -> Unit,
    val import: () -> Unit, val unlock: () -> Unit, val login: (String) -> Unit, val allPasswords: () -> Unit,
    val passwordSettings: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SignInsPage(
    setup: SignIns.Setup, logins: SignIns.Logins, busy: Boolean, unlockLabel: String, actions: SignInsActions,
    status: @Composable () -> Unit = {}, storage: @Composable () -> Unit = {},
) {
    CyclonTopBar(stringResource(R.string.cyclon_sign_ins_title), stringResource(R.string.cyclon_back), actions.back)
    CyclonNote(stringResource(R.string.cyclon_sign_ins_intro), inset = CyclonTextInset)
    status()

    if (setup.any) {
        CyclonSectionLabel(stringResource(R.string.cyclon_sign_ins_setup))
        CyclonGroup {
            if (setup.account) CyclonRow(stringResource(R.string.cyclon_sign_ins_setup_account),
                stringResource(R.string.cyclon_sign_ins_setup_account_summary), onClick = actions.addAccount, trailing = { CyclonChevron() })
            if (setup.autofill) CyclonRow(stringResource(R.string.cyclon_sign_ins_setup_autofill),
                stringResource(R.string.cyclon_sign_ins_setup_autofill_summary), onClick = actions.autofill, trailing = { CyclonChevron() })
            if (setup.passwords) Column {
                CyclonRow(stringResource(R.string.cyclon_sign_ins_setup_passwords), stringResource(R.string.cyclon_sign_ins_setup_passwords_summary))
                FlowRow(Modifier.padding(start = CyclonTextInset, end = CyclonTextInset, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CyclonInkButton(stringResource(R.string.cyclon_sign_ins_add), enabled = !busy, onClick = actions.addPassword)
                    CyclonSecondaryButton(stringResource(R.string.cyclon_sign_ins_import), enabled = !busy, onClick = actions.import)
                }
            }
        }
    }

    if (SignIns.showsLogins(setup, logins, busy)) CyclonSectionLabel(stringResource(R.string.cyclon_sign_ins_logins))
    when (logins) {
        SignIns.Logins.Locked -> {
            CyclonNote(stringResource(R.string.cyclon_sign_ins_locked), inset = CyclonTextInset)
            CyclonInkButton(unlockLabel, Modifier.padding(horizontal = CyclonTextInset, vertical = 8.dp), enabled = !busy, onClick = actions.unlock)
        }
        SignIns.Logins.Choosing -> storage()
        is SignIns.Logins.Shown -> {
            storage()
            if (SignIns.saysNone(setup, logins, busy)) CyclonNote(stringResource(R.string.cyclon_sign_ins_none), inset = CyclonTextInset)
            if (logins.logins.isNotEmpty()) CyclonGroup {
                logins.logins.forEach { login ->
                    val level = stringResource(SignIns.levelLabel(login.level))
                    val summary = SignIns.details(login.username, level,
                        login.lastUsed?.let { stringResource(R.string.cyclon_sign_ins_last_used, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))) })
                    CyclonRow(login.title, summary, onClick = { actions.login(login.id) }, enabled = !busy,
                        leading = { CyclonMonogram(login.title, login.packageName) }, trailing = { CyclonChevron() })
                }
            }
        }
    }
    if (SignIns.saysThisPhone(logins))
        CyclonNote(stringResource(R.string.cyclon_sign_ins_this_phone), inset = CyclonTextInset)

    CyclonSectionLabel(stringResource(R.string.cyclon_sign_ins_settings))
    CyclonGroup {
        CyclonRow(stringResource(R.string.cyclon_sign_ins_all_passwords), stringResource(R.string.cyclon_sign_ins_all_passwords_summary),
            onClick = actions.allPasswords, trailing = { CyclonChevron() })
        CyclonRow(stringResource(R.string.cyclon_sign_ins_sync_settings), stringResource(R.string.cyclon_sign_ins_sync_settings_summary),
            onClick = actions.passwordSettings, trailing = { CyclonChevron() })
    }
}
