/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.assertEquals
import org.junit.Test

class PasswordWifiAccessTest {
    private fun access(sdk: Int = 37, target: Int = 29, system: Boolean = true, credentials: Boolean = true,
        read: Boolean = true, write: Boolean = true, settings: Boolean = false, fine: Boolean = true,
        location: Boolean = true, fineOp: Boolean = true, scanOp: Boolean = true) = PasswordWifiAccessPolicy.evaluate(
        sdk, target, system, credentials, read, write, settings, fine, location, fineOp, scanOp)
    @Test fun target29UsesItsOrdinaryLocationGrantOnAndroid17WithoutNetworkSettings() {
        assertEquals(PasswordWifiAccess.AVAILABLE, access())
        assertEquals(PasswordWifiAccess.AVAILABLE, access(sdk = 26))
    }
    @Test fun newerDeviceAloneNeverSelectsNearbyInsteadOfRequiredPreciseLocation() {
        assertEquals(PasswordWifiAccess.LOCATION_PERMISSION_REQUIRED, access(sdk = 37, target = 29, fine = false))
        assertEquals(PasswordWifiAccess.UNSUPPORTED_TARGET, access(target = 33))
    }
    @Test fun actualCredentialReadAndSystemWriteExemptionRemainMandatory() {
        assertEquals(PasswordWifiAccess.IMAGE_GRANT_REQUIRED, access(credentials = false))
        assertEquals(PasswordWifiAccess.IMAGE_GRANT_REQUIRED, access(system = false))
        assertEquals(PasswordWifiAccess.IMAGE_GRANT_REQUIRED, access(read = false))
        assertEquals(PasswordWifiAccess.IMAGE_GRANT_REQUIRED, access(write = false))
    }
    @Test fun enabledLocationAndBothActualAppOpsAreRequiredOnOrdinaryPath() {
        assertEquals(PasswordWifiAccess.LOCATION_DISABLED, access(location = false))
        assertEquals(PasswordWifiAccess.APP_OP_DENIED, access(fineOp = false))
        assertEquals(PasswordWifiAccess.APP_OP_DENIED, access(scanOp = false))
    }
    @Test fun realNetworkSettingsGrantMayBypassLocationButNeverCredentialRead() {
        assertEquals(PasswordWifiAccess.AVAILABLE, access(settings = true, fine = false, location = false, fineOp = false, scanOp = false))
        assertEquals(PasswordWifiAccess.IMAGE_GRANT_REQUIRED, access(settings = true, credentials = false))
    }
    @Test fun unsupportedNativeApiLevelRefusesWithoutClaimingImageCapability() {
        assertEquals(PasswordWifiAccess.UNSUPPORTED_TARGET, access(sdk = 25))
    }
}
