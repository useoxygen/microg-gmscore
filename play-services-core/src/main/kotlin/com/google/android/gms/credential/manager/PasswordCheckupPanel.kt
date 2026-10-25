/* SPDX-License-Identifier: Apache-2.0 */
package com.google.android.gms.credential.manager

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.gms.R
import org.microg.gms.passwords.*

/** Metadata only. A view of stored warnings must never be presented as a newly completed safety scan. */
@Composable
internal fun PasswordCheckupPanel(entries: List<PasswordView>, query: String, dismissed: Boolean, busy: Boolean,
    onEdit: (PasswordView) -> Unit, onDelete: (PasswordView) -> Unit,
    onDismissal: (PasswordView, Boolean) -> Unit, onWebsite: (String) -> Unit) {
    Text(stringResource(R.string.passwords_checkup_description))
    Text(stringResource(R.string.passwords_checkup_edit_hint))
    var invalid = false
    val reports = entries.filter { !it.data.blocked && it.data.matchesSearch(query) }.mapNotNull { entry ->
        val risks = runCatching { PasswordCheckup.risks(entry.data).filter { it.dismissed == dismissed } }.getOrNull()
        if (risks == null) { invalid = true; null } else entry to risks
    }
    if (invalid) Text(stringResource(R.string.passwords_sync_failed))
    if (reports.none { it.second.isNotEmpty() }) Text(stringResource(R.string.passwords_checkup_empty))
    val groups = listOf(
        R.string.passwords_checkup_compromised to setOf(PasswordRiskKind.LEAKED, PasswordRiskKind.PHISHED),
        R.string.passwords_checkup_reused to setOf(PasswordRiskKind.REUSED),
        R.string.passwords_checkup_weak to setOf(PasswordRiskKind.WEAK))
    groups.forEach { (title, kinds) ->
        val grouped = reports.filter { (_, risks) -> risks.any { it.kind in kinds } }
        if (grouped.isNotEmpty()) {
            Text(stringResource(title) + " (${grouped.size})", style = MaterialTheme.typography.titleMedium)
            grouped.forEach { (entry, risks) ->
                HorizontalDivider()
                Text(entry.data.realm); Text(entry.data.username)
                risks.filter { it.kind in kinds }.forEach { risk ->
                    Text(stringResource(when (risk.kind) {
                        PasswordRiskKind.LEAKED -> R.string.passwords_checkup_leaked
                        PasswordRiskKind.PHISHED -> R.string.passwords_checkup_phished
                        PasswordRiskKind.REUSED -> R.string.passwords_checkup_reused_reason
                        PasswordRiskKind.WEAK -> R.string.passwords_checkup_weak_reason
                    }))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onEdit(entry) }, enabled = !busy) { Text(stringResource(R.string.passwords_edit)) }
                    TextButton(onClick = { onDelete(entry) }, enabled = !busy) { Text(stringResource(R.string.passwords_delete)) }
                }
                PasswordCheckup.website(entry.data)?.let { url ->
                    TextButton(onClick = { onWebsite(url) }, enabled = !busy) { Text(stringResource(R.string.passwords_checkup_open_site)) }
                }
                if (risks.any { it.kind == PasswordRiskKind.LEAKED }) TextButton(onClick = { onDismissal(entry, !dismissed) }, enabled = !busy) {
                    Text(stringResource(if (dismissed) R.string.passwords_checkup_restore else R.string.passwords_checkup_dismiss))
                }
            }
        }
    }
}
