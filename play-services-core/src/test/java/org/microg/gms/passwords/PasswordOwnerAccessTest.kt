/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class PasswordOwnerAccessTest {
    @Test fun noLockPresenceRequiresAnExplicitRequestAndForegroundUnlockedDevice() {
        val access = PasswordOwnerAccess()
        assertFalse(access.complete(false, false, true, true))
        access.begin(false)
        assertFalse(access.complete(false, false, true, false))
        access.begin(false)
        assertFalse(access.complete(false, false, false, true))
        access.begin(false)
        assertTrue(access.complete(false, false, true, true))
        assertTrue(access.allows(false, true, true))
        assertFalse(access.allows(false, false, true))
        assertFalse(access.allows(false, true, false))
    }

    @Test fun cancelledCredentialChallengeCannotBecomeNoLockAccess() {
        val access = PasswordOwnerAccess()
        access.begin(true)
        assertFalse(access.complete(false, true, true, true))
        assertFalse(access.allows(true, true, true))
        access.begin(true)
        assertFalse(access.complete(false, false, true, true))
        assertFalse(access.allows(false, true, true))
    }

    @Test fun unavailablePromptCannotAcceptALateSuccessfulResult() {
        val access = PasswordOwnerAccess()
        access.begin(true)
        access.cancel()
        assertFalse(access.complete(true, true, true, true))
        assertFalse(access.allows(true, true, true))
    }

    @Test fun successfulCredentialResultRequiresUnchangedPolicyAndUnlockedForeground() {
        val access = PasswordOwnerAccess()
        access.begin(true)
        assertFalse(access.complete(true, true, false, true))
        access.begin(true)
        assertFalse(access.complete(true, true, true, false))
        access.begin(true)
        assertFalse(access.complete(true, false, true, true))
        access.begin(true)
        assertTrue(access.complete(true, true, true, true))
        assertFalse(access.allows(false, true, true))
    }

    @Test fun backgroundingClearsTheGrantAndNoLockPendingPresence() {
        val access = PasswordOwnerAccess()
        access.begin(false)
        assertTrue(access.complete(false, false, true, true))
        access.stopped()
        assertFalse(access.allows(false, true, true))
        access.begin(false)
        access.stopped()
        assertFalse(access.complete(false, false, true, true))
    }

    @Test fun systemCredentialChallengeCanReturnAfterItStopsTheCallingActivity() {
        val access = PasswordOwnerAccess()
        access.begin(true)
        access.stopped()
        assertTrue(access.complete(true, true, true, true))
        access.stopped()
        assertFalse(access.allows(true, true, true))
        assertFalse(access.complete(true, true, true, true))
    }

    @Test fun addingAScreenLockRequiresANewCredentialChallenge() {
        val access = PasswordOwnerAccess()
        access.begin(false)
        assertTrue(access.complete(false, false, true, true))
        assertFalse(access.allows(true, true, true))
        access.stopped()
        access.begin(true)
        assertFalse(access.complete(false, true, true, true))
        access.begin(true)
        assertTrue(access.complete(true, true, true, true))
    }
}
