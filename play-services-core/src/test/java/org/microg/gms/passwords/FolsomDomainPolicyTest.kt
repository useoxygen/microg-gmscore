/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.folsom.utils.FolsomDomainPolicy

class FolsomDomainPolicyTest {
    @Test fun onlyTheExactRequestedSecurityDomainCanSupplyRecoveredKeys() {
        assertTrue(FolsomDomainPolicy.matches("users/me/securitydomains/hw_protected", "hw_protected"))
        assertTrue(FolsomDomainPolicy.matches("users/me/securitydomains/chromesync", "chromesync"))
        assertFalse(FolsomDomainPolicy.matches("users/me/securitydomains/not_chromesync", "chromesync"))
        assertFalse(FolsomDomainPolicy.matches("users/other/securitydomains/chromesync", "chromesync"))
        assertFalse(FolsomDomainPolicy.matches("users/me/securitydomains/chromesync/extra", "chromesync"))
        assertFalse(FolsomDomainPolicy.matches(null, "chromesync"))
        assertFalse(FolsomDomainPolicy.matches("users/me/securitydomains/../chromesync", "../chromesync"))
    }
}
