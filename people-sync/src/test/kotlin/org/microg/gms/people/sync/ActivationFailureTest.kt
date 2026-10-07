/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class ActivationFailureTest {
    @Test fun apiRejectionsAreNotMisclassifiedAsNetworkErrors() {
        for ((status, expected) in mapOf(401 to ActivationFailure.AUTHORIZATION, 403 to ActivationFailure.API_DENIED,
            429 to ActivationFailure.RETRY_LATER, 500 to ActivationFailure.API_UNAVAILABLE, 400 to ActivationFailure.API_UNAVAILABLE)) {
            assertEquals(expected, activationFailure(ActivationStage.API, ApiException(status)))
        }
    }
    @Test fun connectivityAndLocalProviderFailuresHaveDifferentRecovery() {
        assertEquals(ActivationFailure.NETWORK, activationFailure(ActivationStage.AUTHORIZATION, IOException()))
        assertEquals(ActivationFailure.NETWORK, activationFailure(ActivationStage.API, IOException()))
        assertEquals(ActivationFailure.LOCAL, activationFailure(ActivationStage.LOCAL, IOException()))
    }
    @Test fun malformedApiReplyDoesNotAskForAndroidPermissions() {
        assertEquals(ActivationFailure.API_UNAVAILABLE, activationFailure(ActivationStage.API, IllegalArgumentException()))
    }
    @Test fun permissionAndAccountRemovalAreActionable() {
        assertEquals(ActivationFailure.PERMISSION, activationFailure(ActivationStage.LOCAL, SecurityException()))
        assertEquals(ActivationFailure.AUTHORIZATION, activationFailure(ActivationStage.AUTHORIZATION, SecurityException()))
        assertEquals(ActivationFailure.ACCOUNT_REMOVED, activationFailure(ActivationStage.LOCAL, ActivationAccountRemoved()))
    }
}
