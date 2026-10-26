/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

enum class PasswordWifiAccess { AVAILABLE, IMAGE_GRANT_REQUIRED, LOCATION_PERMISSION_REQUIRED, LOCATION_DISABLED, APP_OP_DENIED, UNSUPPORTED_TARGET }

/** The shipped target-29 caller takes WifiServiceImpl's legacy location branch, including on Android 17. */
object PasswordWifiAccessPolicy {
    fun evaluate(sdk: Int, targetSdk: Int, systemApp: Boolean, readCredentials: Boolean,
        accessWifi: Boolean, changeWifi: Boolean, networkSettings: Boolean, preciseLocation: Boolean,
        locationEnabled: Boolean, locationAppOp: Boolean, scanAppOp: Boolean): PasswordWifiAccess {
        if (sdk < 26 || targetSdk >= 33) return PasswordWifiAccess.UNSUPPORTED_TARGET
        if (!systemApp || !readCredentials || !accessWifi || !changeWifi) return PasswordWifiAccess.IMAGE_GRANT_REQUIRED
        if (networkSettings) return PasswordWifiAccess.AVAILABLE // Only an actual framework grant, never an XML declaration.
        if (!preciseLocation) return PasswordWifiAccess.LOCATION_PERMISSION_REQUIRED
        if (!locationEnabled) return PasswordWifiAccess.LOCATION_DISABLED
        if (!locationAppOp || !scanAppOp) return PasswordWifiAccess.APP_OP_DENIED
        return PasswordWifiAccess.AVAILABLE
    }
}
