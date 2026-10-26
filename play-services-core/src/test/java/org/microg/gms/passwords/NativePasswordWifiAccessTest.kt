/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.app.AppOpsManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Process
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises the real Android adapter's target/permission/settings/app-op boundary, without saved credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class NativePasswordWifiAccessTest {
    private val permissions = mutableSetOf<String>()
    private lateinit var context: Context
    private lateinit var ops: AppOpsManager
    private lateinit var location: LocationManager

    @Before fun setup() {
        val application: Context = RuntimeEnvironment.getApplication()
        val info = ApplicationInfo(application.applicationInfo).apply {
            targetSdkVersion = 29
            flags = flags or ApplicationInfo.FLAG_SYSTEM
        }
        context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo(): ApplicationInfo = info
            override fun checkSelfPermission(permission: String): Int =
                if (permission in permissions) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        permissions += listOf("android.permission.READ_WIFI_CREDENTIAL", "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_STATE", "android.permission.ACCESS_FINE_LOCATION")
        ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        location = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(location).setLocationEnabled(true)
        for (op in listOf(AppOpsManager.OPSTR_FINE_LOCATION, "android:wifi_scan"))
            shadowOf(ops).setMode(op, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)
    }

    @Test fun actualTarget29AdapterUsesFineLocationOnANewerDeviceWithoutNetworkSettings() {
        assertEquals(PasswordWifiAccess.AVAILABLE, AndroidPasswordWifiPlatform.access(context))
        permissions -= "android.permission.ACCESS_FINE_LOCATION"
        // A coarse-only grant cannot silently authorize saved credentials or become an empty snapshot.
        permissions += "android.permission.ACCESS_COARSE_LOCATION"
        assertEquals(PasswordWifiAccess.LOCATION_PERMISSION_REQUIRED, AndroidPasswordWifiPlatform.access(context))
        assertThrows(PasswordWifiUnavailableException::class.java) { AndroidPasswordWifiPlatform(context) {}.snapshot() }
    }

    @Test fun locationAndEitherRevokedAppOpRefuseBeforeAnyNativeCredentialRead() {
        shadowOf(location).setLocationEnabled(false)
        assertEquals(PasswordWifiAccess.LOCATION_DISABLED, AndroidPasswordWifiPlatform.access(context))
        assertThrows(PasswordWifiUnavailableException::class.java) { AndroidPasswordWifiPlatform(context) {}.snapshot() }
        shadowOf(location).setLocationEnabled(true)
        for (op in listOf(AppOpsManager.OPSTR_FINE_LOCATION, "android:wifi_scan")) {
            shadowOf(ops).setMode(op, Process.myUid(), context.packageName, AppOpsManager.MODE_IGNORED)
            assertEquals(PasswordWifiAccess.APP_OP_DENIED, AndroidPasswordWifiPlatform.access(context))
            assertThrows(PasswordWifiUnavailableException::class.java) { AndroidPasswordWifiPlatform(context) {}.snapshot() }
            shadowOf(ops).setMode(op, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)
        }
    }

    @Test fun aRealSignatureGrantCanBypassLocationButCannotSupplyMissingPrivilegedReadAuthority() {
        permissions += "android.permission.NETWORK_SETTINGS"
        permissions -= "android.permission.ACCESS_FINE_LOCATION"
        shadowOf(location).setLocationEnabled(false)
        assertEquals(PasswordWifiAccess.AVAILABLE, AndroidPasswordWifiPlatform.access(context))
        permissions -= "android.permission.READ_WIFI_CREDENTIAL"
        assertEquals(PasswordWifiAccess.IMAGE_GRANT_REQUIRED, AndroidPasswordWifiPlatform.access(context))
        assertThrows(PasswordWifiUnavailableException::class.java) { AndroidPasswordWifiPlatform(context) {}.snapshot() }
    }
}
