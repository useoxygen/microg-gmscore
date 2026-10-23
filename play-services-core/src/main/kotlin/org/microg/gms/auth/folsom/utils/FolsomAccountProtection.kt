/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom.utils

import org.microg.gms.auth.folsom.FolsomKeyStore

internal object FolsomAccountProtection {
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
