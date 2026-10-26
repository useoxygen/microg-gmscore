/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class TrustedVaultRecoveryPolicyTest {
    @Test fun recoveryMessagesRequireExactGoogleOriginMainFrameStableAccountAndRequestedDomain() {
        val policy = TrustedVaultRecoveryPolicy("fixture-gaia-id", "chromesync")
        assertTrue(policy.acceptsMessage("https://accounts.google.com", true, "fixture-gaia-id", "chromesync"))
        assertFalse(policy.acceptsMessage("https://accounts.google.com", false, "fixture-gaia-id", "chromesync"))
        assertFalse(policy.acceptsMessage("https://accounts.google.com", true, "replacement-gaia-id", "chromesync"))
        assertFalse(policy.acceptsMessage("https://accounts.google.com", true, "fixture-gaia-id", "gpm_passkeys"))
        for (origin in listOf("https://evilgoogle.com", "https://accounts.google.com.attacker.invalid", "http://accounts.google.com",
            "https://accounts.google.com:444", "https://fixture@accounts.google.com", "https://accounts.google.com/path", "https://accounts.google.com?query")) {
            assertFalse(origin, policy.acceptsMessage(origin, true, "fixture-gaia-id", "chromesync"))
        }
    }
    @Test fun webNavigationKeepsAccountRecoveryOnItsExactHttpsHost() {
        assertTrue(TrustedVaultRecoveryPolicy.navigationAllowed("https://accounts.google.com/encryption/unlock/android?kdi=fixture"))
        assertFalse(TrustedVaultRecoveryPolicy.navigationAllowed("https://examplegoogle.com/encryption/unlock/android"))
        assertFalse(TrustedVaultRecoveryPolicy.navigationAllowed("intent://accounts.google.com"))
        assertFalse(TrustedVaultRecoveryPolicy.navigationAllowed("javascript:fixture"))
        assertFalse(TrustedVaultRecoveryPolicy.navigationAllowed("https://accounts.google.com.attacker.invalid/"))
    }
    @Test fun multiDomainGoogleDeliveryImportsOnlyTheRequestedDomain() {
        val policy = TrustedVaultRecoveryPolicy("fixture-gaia-id", "chromesync")
        assertTrue(policy.selectsKeys(setOf("chromesync", "hw_protected")))
        assertFalse(policy.selectsKeys(setOf("hw_protected")))
        assertFalse(policy.selectsKeys(emptySet()))
        assertFalse(policy.selectsKeys((1..33).map { "domain$it" }.toSet() + "chromesync"))
    }
}
