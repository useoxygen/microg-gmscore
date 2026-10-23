/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

enum class PasswordRiskKind(val field: Int) { LEAKED(1), REUSED(2), WEAK(3), PHISHED(4) }

class PasswordRisk(val kind: PasswordRiskKind, val firstDetectedWindowsMicros: Long?, val isMuted: Boolean) {
    /** The pinned owner checkup supports dismissal only for the leaked-password warning. */
    val dismissed: Boolean get() = kind == PasswordRiskKind.LEAKED && isMuted
    override fun toString() = "PasswordRisk[redacted]"
}

/** Actual persisted PasswordIssues flags. This decoder performs no leak, weakness or reuse analysis. */
object PasswordCheckup {
    fun risks(data: PasswordData): List<PasswordRisk> {
        if (data.blocked || data.password.isEmpty()) return emptyList()
        val issues = Proto.decode(Proto.decode(data.encode()).optional(19) ?: return emptyList())
        return PasswordRiskKind.values().mapNotNull { kind ->
            val raw = issues.optional(kind.field) ?: return@mapNotNull null
            val issue = Proto.decode(raw)
            val date = issue.integer(1)
            val muted = issue.integer(2) ?: 0
            if (date != null && date < 0 || muted !in 0..1) throw ProtocolException()
            PasswordRisk(kind, date, muted == 1L)
        }
    }
    /** bmvp.E only toggles leaked_password_issue.is_muted; it retains detection and notification metadata. */
    fun withLeakedDismissed(data: PasswordData, dismissed: Boolean): PasswordData {
        val value = Proto.decode(data.encode())
        val issues = Proto.decode(value.optional(19) ?: throw ConflictException())
        val leaked = Proto.decode(issues.optional(PasswordRiskKind.LEAKED.field) ?: throw ConflictException())
        if ((leaked.integer(2) ?: 0) == (if (dismissed) 1L else 0L)) return data
        return PasswordData.decode(value.replace(19, issues.replace(PasswordRiskKind.LEAKED.field,
            leaked.replaceInteger(2, if (dismissed) 1 else 0).encode()).encode()).encode())
    }
    fun website(data: PasswordData): String? = if (data.realm.startsWith("https://"))
        runCatching { PasswordData.webRealm(data.realm) }.getOrNull() else null
}
