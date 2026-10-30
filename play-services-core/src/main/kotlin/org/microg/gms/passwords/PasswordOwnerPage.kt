/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.R
import org.microg.gms.cyclon.Cyclon
import org.microg.gms.cyclon.CyclonSettingsPage
import org.microg.gms.cyclon.CyclonInkButton
import org.microg.gms.cyclon.CyclonProgress
import org.microg.gms.cyclon.CyclonTextAction
import org.microg.gms.cyclon.CyclonTextInset
import org.microg.gms.cyclon.CyclonTopBar
import org.microg.gms.cyclon.LocalCyclonInset

/*
 * The frame of the pages Passwords' Settings opens (encryption, recovery, breach check, passkeys, sharing, Wi-Fi) and of
 * the recovery pages that Recovery's rows open, in Android Settings' look: a Settings sub-page head (back mark and title in
 * one row, the account under it), then flat rows in groups, sentences and pills between them. Use inside
 * CyclonMaterialTheme with settingsLook on.
 */

/** Settings' page under the system bars, for a page that scrolls itself (a LazyColumn). */
@Composable
internal fun PasswordOwnerSurface(content: @Composable () -> Unit) {
    CyclonSettingsPage(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) { content() }
    }
}

/** Back and the page's title in one row, and the Google account it is about under it. */
@Composable
internal fun PasswordOwnerHeader(title: String, account: String?, onBack: () -> Unit) {
    CyclonTopBar(title, stringResource(R.string.cyclon_back), onBack, status = account?.takeIf { it.isNotBlank() })
}

/** A whole scrolling page: [PasswordOwnerHeader], then [content]. */
@Composable
internal fun PasswordOwnerPage(title: String, account: String?, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    PasswordOwnerSurface {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            PasswordOwnerHeader(title, account, onBack)
            content()
        }
    }
}

/** Locked: why the page needs the screen lock, and the button that asks for it. */
@Composable
internal fun PasswordOwnerLocked(secure: Boolean, enabled: Boolean = true, onUnlock: () -> Unit) {
    PasswordOwnerText(stringResource(if (secure) R.string.passwords_unlock_description else R.string.passwords_no_lock_description))
    PasswordOwnerActions {
        CyclonInkButton(stringResource(if (secure) R.string.passwords_unlock else R.string.passwords_open), enabled = enabled, onClick = onUnlock)
    }
}

/** The thin progress bar and the page's latest result, as on the Passwords page. */
@Composable
internal fun PasswordOwnerStatus(busy: Boolean, message: String?) {
    CyclonProgress(busy, Modifier.padding(horizontal = CyclonTextInset, vertical = 4.dp))
    message?.let { PasswordOwnerText(it) }
}

/** A sentence the owner should read: ink body text where words start, between groups or in one. */
@Composable
internal fun PasswordOwnerText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = LocalCyclonInset.current, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp), color = Cyclon.palette.ink)
}

/** The page's pills, wrapping at large font sizes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PasswordOwnerActions(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier.padding(horizontal = CyclonTextInset, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

/** A dialog's explanation in ink; [scrolls] for long warnings on small screens and large fonts. */
@Composable
internal fun PasswordDialogText(text: String, modifier: Modifier = Modifier, scrolls: Boolean = false) {
    Text(text, (if (scrolls) modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()) else modifier),
        style = MaterialTheme.typography.bodyMedium, color = Cyclon.palette.ink)
}

/**
 * The question before a recovery step: [title], the account it is about in ink, then [messages]; OK runs [onConfirm]
 * when [confirmEnabled]. Protected as every password dialog, and scrolling for long warnings at large font sizes.
 */
@Composable
internal fun PasswordOwnerConfirm(title: String, account: String, messages: List<String>, confirmEnabled: Boolean,
                                  onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(containerColor = Cyclon.page.dialog, onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            PasswordDialogProtection()
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(account, style = MaterialTheme.typography.titleMedium, color = Cyclon.palette.ink)
                messages.forEach { PasswordDialogText(it) }
            }
        },
        confirmButton = { CyclonTextAction(stringResource(android.R.string.ok), enabled = confirmEnabled, onClick = onConfirm) },
        dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel), onClick = onDismiss) })
}
