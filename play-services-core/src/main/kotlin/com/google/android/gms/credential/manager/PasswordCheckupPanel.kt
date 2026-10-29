/* SPDX-License-Identifier: Apache-2.0 */
package com.google.android.gms.credential.manager

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.gms.R
import org.microg.gms.cyclon.*
import org.microg.gms.passwords.*

/** Metadata only. A view of stored warnings must never be presented as a newly completed safety scan. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PasswordCheckupPanel(entries: List<PasswordView>, query: String, dismissed: Boolean, busy: Boolean,
    onEdit: (PasswordView) -> Unit, onDelete: (PasswordView) -> Unit,
    onDismissal: (PasswordView, Boolean) -> Unit, onWebsite: (String) -> Unit) {
    CyclonNote(stringResource(R.string.passwords_checkup_description))
    CyclonNote(stringResource(R.string.passwords_checkup_edit_hint))
    var invalid = false
    val reports = entries.filter { !it.data.blocked && it.data.matchesSearch(query) }.mapNotNull { entry ->
        val risks = runCatching { PasswordCheckup.risks(entry.data).filter { it.dismissed == dismissed } }.getOrNull()
        if (risks == null) { invalid = true; null } else entry to risks
    }
    if (invalid) CyclonNote(stringResource(R.string.passwords_sync_failed), color = Cyclon.palette.ink)
    if (reports.none { it.second.isNotEmpty() }) CyclonNote(stringResource(R.string.passwords_checkup_empty), color = Cyclon.palette.ink)
    val groups = listOf(
        R.string.passwords_checkup_compromised to setOf(PasswordRiskKind.LEAKED, PasswordRiskKind.PHISHED),
        R.string.passwords_checkup_reused to setOf(PasswordRiskKind.REUSED),
        R.string.passwords_checkup_weak to setOf(PasswordRiskKind.WEAK))
    groups.forEach { (title, kinds) ->
        val grouped = reports.filter { (_, risks) -> risks.any { it.kind in kinds } }
        if (grouped.isNotEmpty()) {
            CyclonSectionLabel(stringResource(title) + " · ${grouped.size}")
            grouped.forEach { (entry, risks) ->
                val reasons = risks.filter { it.kind in kinds }.map { risk ->
                    stringResource(when (risk.kind) {
                        PasswordRiskKind.LEAKED -> R.string.passwords_checkup_leaked
                        PasswordRiskKind.PHISHED -> R.string.passwords_checkup_phished
                        PasswordRiskKind.REUSED -> R.string.passwords_checkup_reused_reason
                        PasswordRiskKind.WEAK -> R.string.passwords_checkup_weak_reason
                    })
                }
                val site = entry.brandName?.takeIf { it.isNotBlank() } ?: Uri.parse(entry.data.realm).host?.takeIf { it.isNotBlank() } ?: entry.data.realm
                CyclonRow(site, (listOf(entry.data.username).filter { it.isNotEmpty() } + reasons).joinToString("\n"), divider = false,
                    leading = { CyclonMonogram(site) })
                FlowRow(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp)) {
                    CyclonTextAction(stringResource(R.string.passwords_edit), enabled = !busy) { onEdit(entry) }
                    PasswordCheckup.website(entry.data)?.let { url ->
                        CyclonTextAction(stringResource(R.string.passwords_checkup_open_site), enabled = !busy) { onWebsite(url) }
                    }
                    if (risks.any { it.kind == PasswordRiskKind.LEAKED }) CyclonTextAction(
                        stringResource(if (dismissed) R.string.passwords_checkup_restore else R.string.passwords_checkup_dismiss),
                        enabled = !busy) { onDismissal(entry, !dismissed) }
                    CyclonTextAction(stringResource(R.string.passwords_delete), enabled = !busy) { onDelete(entry) }
                }
                CyclonHairline(Modifier.padding(start = 20.dp))
            }
        }
    }
}
