/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.folsom.AccountData
import org.microg.gms.auth.folsom.DomainData
import org.microg.gms.auth.folsom.utils.FolsomAccountProtection
import org.microg.gms.auth.folsom.utils.FolsomConsentNotificationPolicy

class FolsomConsentNotificationTest {
    private val owner = "synthetic-notification-gaia"
    private val domain = "chromesync"
    private val day = 86_400_000L

    @Test fun actualPostedTimeSurvivesReopenWithStrictDayBoundaryAndBackwardsWallClock() {
        val posted = 1_800_000_000_000L
        val initial = AccountData(googleUserId = owner)
        assertTrue(FolsomConsentNotificationPolicy.eligible(initial, owner, domain, posted))
        // A read/blocked notification is not a write. Only the actual posted seam starts cooldown.
        assertEquals(initial, AccountData.ADAPTER.decode(initial.encode()))
        val saved = FolsomConsentNotificationPolicy.posted(initial, owner, domain, posted)
        val reopened = AccountData.ADAPTER.decode(saved.encode())
        assertEquals(posted, Proto.decode(reopened.domains.single().value_!!.encode()).integer(16))
        assertFalse(FolsomConsentNotificationPolicy.eligible(reopened, owner, domain, posted - 1))
        assertFalse(FolsomConsentNotificationPolicy.eligible(reopened, owner, domain, posted + day - 1))
        assertFalse(FolsomConsentNotificationPolicy.eligible(reopened, owner, domain, posted + day))
        assertTrue(FolsomConsentNotificationPolicy.eligible(reopened, owner, domain, posted + day + 1))
        assertFalse(FolsomConsentNotificationPolicy.eligible(FolsomConsentNotificationPolicy.posted(initial, owner, domain, Long.MAX_VALUE), owner, domain, 0))
    }

    @Test fun accountAndDomainBindingCannotInheritRemovedOrUnboundSameNameHistory() {
        val posted = 1_800_000_000_000L
        val raw = DomainData.ADAPTER.decode(Proto.integer(16, posted) + Proto.field(99, "opaque-domain"))
        val legacy = AccountData(domains = listOf(AccountData.DomainsEntry(domain, raw)))
        assertTrue(FolsomConsentNotificationPolicy.eligible(legacy, owner, domain, posted + 1))
        val rebound = FolsomAccountProtection.bind(legacy, owner)
        assertNull(rebound.domains.single().value_!!.consentNotificationTimestamp)
        val saved = FolsomConsentNotificationPolicy.posted(rebound, owner, domain, posted)
        assertEquals(raw.unknownFields, saved.domains.single().value_!!.unknownFields)
        assertTrue(FolsomConsentNotificationPolicy.eligible(saved, owner, "hw_protected", posted + 1))
        assertThrows(Exception::class.java) { FolsomConsentNotificationPolicy.eligible(saved, "replacement-gaia", domain, posted + day + 1) }
        assertThrows(Exception::class.java) { FolsomConsentNotificationPolicy.posted(saved, "replacement-gaia", domain, posted + day + 1) }
        val wrongDomainIdentity = AccountData(googleUserId = owner, domains = listOf(
            AccountData.DomainsEntry(domain, raw.copy(googleUserId = "replacement-gaia"))))
        assertThrows(Exception::class.java) { FolsomConsentNotificationPolicy.eligible(wrongDomainIdentity, owner, domain, posted + day + 1) }
    }
}
