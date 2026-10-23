/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom.utils

import org.microg.gms.auth.folsom.FolsomKeyStore
import org.microg.gms.auth.folsom.AccountData
import org.microg.gms.passwords.EnrollmentState
import org.microg.gms.passwords.Proto
import java.security.MessageDigest

internal object FolsomAccountProtection {
    /** Binding a new journal cannot grant provenance to another domain's legacy keys. */
    fun bind(account: AccountData, current: String): AccountData {
        require(current.isNotBlank())
        check(account.googleUserId == null || account.googleUserId == current) { "Recovery account changed" }
        if (account.googleUserId == current) return account
        return account.copy(googleUserId = current, domains = account.domains.map { entry ->
            val domain = entry.value_
            if (domain == null || domain.googleUserId != null) entry
            else entry.copy(value_ = domain.copy(consent = null, consentNotificationTimestamp = null,
                keys = emptyList(), legacyKeys = domain.legacyKeys + domain.keys))
        })
    }

    /** Preserve the phone identity while dropping old join payloads that wrap the reset keys. */
    fun redactEnrollment(raw: ByteArray): ByteArray {
        val state = EnrollmentState.decode(raw)
        try {
            val receipts = state.history + listOfNotNull(state.attempt?.encode())
            val protectedReceipts = if (receipts.size > 1000) listOf(raw) else receipts
            return EnrollmentState(state.pair, history = protectedReceipts.map {
                Proto.field(1, MessageDigest.getInstance("SHA-256").digest(it))
            }).encode()
        } finally { state.pair?.fill(0); state.attempt?.request?.fill(0); state.history.forEach { it.fill(0) } }
    }
    fun canRead(accountId: String?, domainId: String?, current: String, validatedLegacy: Boolean): Boolean =
        (accountId == null || accountId == current) && (domainId == null || domainId == current) &&
            (accountId == current || domainId == current || validatedLegacy)

    /** Unbound legacy entries remain quarantined; known removed identities never become another account's keys. */
    fun retainPresent(store: FolsomKeyStore, present: Map<String, String?>): FolsomKeyStore {
        check(present.values.none { it.isNullOrBlank() }) { "Recovery identity discovery is incomplete" }
        val kept = store.accounts.mapNotNull { entry ->
            val current = present[entry.key] ?: return@mapNotNull null
            val account = entry.value_ ?: return@mapNotNull null
            if (account.googleUserId != null && account.googleUserId != current) return@mapNotNull null
            val domains = account.domains.filter { it.value_?.googleUserId == null || it.value_?.googleUserId == current }
            if (domains.size == account.domains.size) entry else entry.copy(value_ = account.copy(domains = domains))
        }
        return if (kept == store.accounts) store else store.copy(accounts = kept)
    }
}
