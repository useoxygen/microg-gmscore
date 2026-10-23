/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom.utils

import org.microg.gms.auth.folsom.AccountData
import org.microg.gms.auth.folsom.DomainData

/** anqw uses wall-clock time and a strict greater-than comparison, not elapsed time. */
internal object FolsomConsentNotificationPolicy {
    const val DEFAULT_COOLDOWN_MILLIS = 86_400_000L

    private fun domain(account: AccountData?, userId: String, domainId: String): DomainData? {
        require(userId.isNotBlank() && FolsomDomainPolicy.valid(domainId))
        check(account?.googleUserId == null || account.googleUserId == userId) { "Recovery account changed" }
        val entries = account?.domains.orEmpty().filter { it.key == domainId }
        check(entries.size <= 1) { "Ambiguous recovery domain" }
        val value = entries.singleOrNull()?.value_ ?: return null
        check(value.googleUserId == null || value.googleUserId == userId) { "Recovery account changed" }
        // A same-name legacy account cannot contribute another identity's notification history.
        return value.takeIf { FolsomAccountProtection.canRead(account?.googleUserId, it.googleUserId, userId, false) }
    }

    fun eligible(account: AccountData?, userId: String, domainId: String, nowMillis: Long): Boolean {
        require(nowMillis >= 0)
        val last = domain(account, userId, domainId)?.consentNotificationTimestamp ?: 0L
        check(last >= 0) { "Invalid recovery notification time" }
        // A backwards wall-clock jump retains the cooldown. Ordering before subtraction
        // also avoids overflow for an otherwise valid nonnegative persisted timestamp.
        return nowMillis > last && nowMillis - last > DEFAULT_COOLDOWN_MILLIS
    }

    /** Called only after NotificationManager accepted the actual post. */
    fun posted(account: AccountData, userId: String, domainId: String, postedAtMillis: Long): AccountData {
        require(postedAtMillis >= 0)
        domain(account, userId, domainId)
        val bound = FolsomAccountProtection.bind(account, userId)
        val entry = bound.domains.singleOrNull { it.key == domainId }
        val updated = (entry ?: AccountData.DomainsEntry(key = domainId)).copy(value_ =
            (entry?.value_ ?: DomainData()).copy(googleUserId = userId, consentNotificationTimestamp = postedAtMillis))
        return bound.copy(domains = if (entry == null) bound.domains + updated else
            bound.domains.map { if (it.key == domainId) updated else it })
    }
}
