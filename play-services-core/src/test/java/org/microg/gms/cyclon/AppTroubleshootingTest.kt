/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon
import org.junit.Test
import org.junit.Assert.*
class AppTroubleshootingTest {
    private val registered = AppPushObservation(true, true, null, 1)
    @Test fun ownerOffAndAppBlocksDominateUnreadableState() {
        assertEquals(AppReason.PUSH_OFF, appPushReading(true, pushHealth(false, null), null).reason)
        assertEquals(AppReason.APP_BLOCKED, appPushReading(true, unknownHealth, registered.copy(allowed = false)).reason)
        assertEquals(AppReason.APP_OFF, appPushReading(false, pushHealth(true, true), registered).reason)
    }
    @Test fun registrationAndConnectionDoNotBecomeADeliveryClaim() {
        assertEquals(AppReason.REGISTERED, appPushReading(true, pushHealth(true, true), registered).reason)
        assertEquals(AppReason.DISCONNECTED, appPushReading(true, pushHealth(true, false), registered).reason)
        assertEquals(AppReason.NO_REGISTRATION, appPushReading(true, pushHealth(true, true), registered.copy(registered = false)).reason)
    }
    @Test fun recordedErrorsRouteToSpecificRecovery() {
        assertEquals(AppReason.AUTHORIZATION, appPushReading(true, pushHealth(true, true), registered.copy(error = "authentication_failed")).reason)
        assertEquals(AppReason.NETWORK, appPushReading(true, pushHealth(true, true), registered.copy(error = "timeout")).reason)
        assertEquals(AppRecovery.PUSH_APP_SETTINGS, appPushReading(true, pushHealth(true, true), registered.copy(error = "invalid_sender")).recovery)
    }
    @Test fun locationReadinessRequiresPermissionAndReadableState() {
        assertEquals(AppRecovery.APP_SETTINGS, appLocationReading(locationHealth(true, true, true), false).recovery)
        assertEquals(AppReason.UNREADABLE, appLocationReading(unknownHealth, true).reason)
        assertEquals(AppReason.LOCATION_READY, appLocationReading(locationHealth(true, true, true), true).reason)
    }
}
