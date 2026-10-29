/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class AgentSignInLockPolicyTest {
    @Test fun lockedTargetsNeverOpenCredentialStorageBeforeFirstUnlockOrUseTheOwnersScreen() {
        assertFalse(AgentSignInLockPolicy.targetReady(false, true, true))
        assertFalse(AgentSignInLockPolicy.targetReady(false, false, true))
        assertFalse(AgentSignInLockPolicy.targetReady(true, true, false))
        assertTrue(AgentSignInLockPolicy.targetReady(true, true, true))
        assertTrue(AgentSignInLockPolicy.targetReady(true, false, false))
    }

    @Test fun aLockedDeliveryNeedsAnOwnedBackgroundTargetAndAnExistingNonAuthenticationAlwaysGrant() {
        for (background in listOf(false, true)) for (once in listOf(false, true)) for (authentication in listOf(false, true)) {
            assertEquals(background && !once && !authentication,
                AgentSignInLockPolicy.deliveryAllowed(true, background, once, authentication))
            assertTrue(AgentSignInLockPolicy.deliveryAllowed(false, background, once, authentication))
        }
    }
}
