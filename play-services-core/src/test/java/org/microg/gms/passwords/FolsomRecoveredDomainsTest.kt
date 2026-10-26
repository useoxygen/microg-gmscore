/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.folsom.AccountData
import org.microg.gms.auth.folsom.DomainData
import org.microg.gms.auth.folsom.Keys
import org.microg.gms.auth.folsom.utils.FolsomRecoveredDomains

class FolsomRecoveredDomainsTest {
    private fun key(epoch: Int) = Keys(keyVersion = epoch, encryptedKeyMaterial = ByteArray(48) { 7 }.toByteString())
    private fun entry(id: String, domain: DomainData) = AccountData.DomainsEntry(key = id, value_ = domain)
    @Test fun sourcePrefixAndLastRecoveredEpochDoNotTurnLegacyOrUninitializedKeysIntoRecovery() {
        val current = DomainData(googleUserId = "gaia", keys = listOf(key(3)))
        val account = AccountData(googleUserId = "gaia", domains = listOf(
            entry("chromesync", current), entry("chromesync_extra", current), entry("hw_protected", current),
            entry("chromesync_pending", current.copy(keys = listOf(key(3), key(0)))),
            entry("chromesync_legacy", current.copy(keys = emptyList(), legacyKeys = listOf(key(8)))),
            entry("chromesync_other", current.copy(googleUserId = "other"))))
        assertEquals(listOf("chromesync", "chromesync_extra"), FolsomRecoveredDomains.list(account, "gaia", "chromesync") { _, _ -> false })
        assertEquals(listOf("hw_protected"), FolsomRecoveredDomains.list(account, "gaia", "hw_protected") { _, _ -> false })
        assertEquals(listOf("chromesync_extra"), FolsomRecoveredDomains.list(account, "gaia", "chromesync") { _, id -> id == "chromesync" })
    }
    @Test fun unboundAndRemovedAccountCannotClaimRecoveredDomains() {
        val domain = DomainData(keys = listOf(key(3)))
        val unbound = AccountData(domains = listOf(entry("chromesync", domain)))
        assertTrue(FolsomRecoveredDomains.list(unbound, "gaia", "chromesync") { _, _ -> false }.isEmpty())
        assertThrows(SecurityException::class.java) {
            FolsomRecoveredDomains.list(unbound.copy(googleUserId = "removed"), "gaia", "chromesync") { _, _ -> false }
        }
        assertTrue(FolsomRecoveredDomains.list(null, "gaia", "chromesync") { _, _ -> false }.isEmpty())
        assertThrows(IllegalStateException::class.java) {
            FolsomRecoveredDomains.list(unbound.copy(domains = unbound.domains + unbound.domains), "gaia", "chromesync") { _, _ -> false }
        }
    }
}
