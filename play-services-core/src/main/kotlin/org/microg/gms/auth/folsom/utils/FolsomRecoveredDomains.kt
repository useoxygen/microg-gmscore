/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom.utils

import org.microg.gms.auth.folsom.AccountData
import org.microg.gms.auth.folsom.DomainData

/** anqo: list the connected domain prefix only when its last locally recovered key has a real epoch. */
internal object FolsomRecoveredDomains {
    fun list(account: AccountData?, userId: String, prefix: String, pendingReset: (DomainData, String) -> Boolean): List<String> {
        require(userId.isNotBlank() && FolsomDomainPolicy.valid(prefix))
        if (account == null) return emptyList()
        if (account.googleUserId != null && account.googleUserId != userId) throw SecurityException("Recovery account changed")
        val entries = account.domains
        if (entries.map { it.key }.toSet().size != entries.size) throw IllegalStateException("Recovery domains unavailable")
        return entries.mapNotNull { entry ->
            val id = entry.key?.takeIf { FolsomDomainPolicy.valid(it) && it.startsWith(prefix) } ?: return@mapNotNull null
            val domain = entry.value_ ?: return@mapNotNull null
            if (!FolsomAccountProtection.canRead(account.googleUserId, domain.googleUserId, userId, false) || pendingReset(domain, id))
                return@mapNotNull null
            val key = domain.keys.lastOrNull() ?: return@mapNotNull null
            if ((key.keyVersion ?: 0) <= 0 || key.keyMaterial?.size?.let { it > 0 } != true &&
                key.encryptedKeyMaterial?.size?.let { it > 0 } != true) null else id
        }
    }
}
