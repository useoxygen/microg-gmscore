/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.folsom.AccountData
import org.microg.gms.auth.folsom.DomainData
import org.microg.gms.auth.folsom.FolsomKeyStore
import org.microg.gms.auth.folsom.utils.FolsomAccountProtection

class FolsomAccountProtectionTest {
    @Test fun sameNameReplacementAndUnboundLegacyKeysCannotAcquireAccountAuthority() {
        assertFalse(FolsomAccountProtection.canRead("old-id", "old-id", "replacement-id", true))
        assertFalse(FolsomAccountProtection.canRead(null, null, "replacement-id", false))
        assertFalse(FolsomAccountProtection.canRead(null, "old-id", "replacement-id", true))
        assertTrue(FolsomAccountProtection.canRead(null, null, "current-id", true))
        assertTrue(FolsomAccountProtection.canRead(null, "current-id", "current-id", false))
    }
    @Test fun removalPurgesOnlyAbsentAndMismatchedIdentitiesAndKeepsActiveRecoveryInputs() {
        fun account(name: String, id: String?, domains: List<AccountData.DomainsEntry> = emptyList()) =
            FolsomKeyStore.AccountsEntry(key = name, value_ = AccountData(googleUserId = id, domains = domains))
        val activeDomain = AccountData.DomainsEntry(key = "chromesync", value_ = DomainData(googleUserId = "active-id"))
        val oldDomain = AccountData.DomainsEntry(key = "gpm_passkeys", value_ = DomainData(googleUserId = "old-id"))
        val legacyDomain = AccountData.DomainsEntry(key = "passwords", value_ = DomainData())
        val store = FolsomKeyStore(accounts = listOf(account("active", "active-id", listOf(activeDomain)),
            account("removed", "removed-id"), account("readded", "old-id"), account("legacy", null, listOf(oldDomain, legacyDomain))))
        val cleaned = FolsomAccountProtection.retainPresent(store, mapOf("active" to "active-id", "readded" to "replacement-id", "legacy" to "legacy-current-id"))
        assertEquals(listOf("active", "legacy"), cleaned.accounts.map { it.key })
        assertEquals(listOf("passwords"), cleaned.accounts.last().value_!!.domains.map { it.key })
        assertEquals(activeDomain, cleaned.accounts.first().value_!!.domains.single())
    }
    @Test fun temporarilyMissingIdentityCannotBecomeEvidenceOfAccountRemoval() {
        val store = FolsomKeyStore(accounts = listOf(FolsomKeyStore.AccountsEntry(key = "present", value_ = AccountData(googleUserId = "owner"))))
        assertThrows(IllegalStateException::class.java) { FolsomAccountProtection.retainPresent(store, mapOf("present" to null)) }
        assertThrows(IllegalStateException::class.java) { FolsomAccountProtection.retainPresent(store, mapOf("present" to "")) }
        assertSame(store, FolsomAccountProtection.retainPresent(store, mapOf("present" to "owner")))
        assertTrue(FolsomAccountProtection.retainPresent(store, emptyMap()).accounts.isEmpty())
    }
}
