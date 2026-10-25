/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.net.wifi.WifiConfiguration
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** API28 is already cached. Exercise the real adapter's pre30 branch without reading or changing saved networks. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NativePasswordWifiCloneTest {
    @Suppress("DEPRECATION")
    @Test fun nativeUpdateCopiesTheCompleteConfigurationWithoutMutatingItsBaseline() {
        val adapter = AndroidPasswordWifiPlatform(RuntimeEnvironment.getApplication()) {}
        val original = WifiConfiguration().apply {
            networkId = 41
            SSID = "\"public-before-fixture\""
            BSSID = "02:00:00:00:00:41"
            preSharedKey = "\"public-before-password\""
            priority = 37
            status = WifiConfiguration.Status.ENABLED
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
            allowedProtocols.set(WifiConfiguration.Protocol.WPA)
            allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
            wepKeys[1] = "public-unused-wep-slot"
        }
        val creator = WifiConfiguration::class.java.getField("creatorUid")
        val metered = WifiConfiguration::class.java.getField("meteredOverride")
        creator.setInt(original, 12345)
        metered.setInt(original, 1)
        val before = parcelled(original)
        val replacement = PasswordWifiNetwork.create("74657374", PasswordWifiNetwork.PSK, "public-after-password", metered = 1)
        // This is the production adapter method called by update(), not a parallel test copy implementation.
        val build = AndroidPasswordWifiPlatform::class.java.getDeclaredMethod("build", PasswordWifiNetwork::class.java, WifiConfiguration::class.java)
            .apply { isAccessible = true }
        val updated = build.invoke(adapter, replacement, original) as WifiConfiguration

        assertNotSame(original, updated)
        assertEquals("74657374", updated.SSID)
        assertEquals("\"public-after-password\"", updated.preSharedKey)
        assertEquals(2, metered.getInt(updated))
        assertTrue(updated.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK))
        // PSK uses the framework's default auth algorithm; the explicit OPEN/SHARED bits are WEP-only.
        assertTrue(updated.allowedAuthAlgorithms.isEmpty)
        assertTrue(original.allowedAuthAlgorithms.get(WifiConfiguration.AuthAlgorithm.SHARED))
        assertTrue(updated.wepKeys.all { it == null })
        // Values outside the synchronized credential fields must survive the full native copy.
        assertEquals(41, updated.networkId)
        assertEquals(12345, creator.getInt(updated))
        assertEquals(original.BSSID, updated.BSSID)
        assertEquals(original.priority, updated.priority)
        assertEquals(original.status, updated.status)
        assertEquals(original.allowedProtocols, updated.allowedProtocols)
        assertEquals(original.allowedPairwiseCiphers, updated.allowedPairwiseCiphers)
        assertNotSame(original.allowedProtocols, updated.allowedProtocols)
        assertNotSame(original.wepKeys, updated.wepKeys)
        assertArrayEquals(before, parcelled(original))
        updated.allowedProtocols.clear()
        updated.wepKeys[0] = "public-copy-only"
        assertTrue(original.allowedProtocols.get(WifiConfiguration.Protocol.WPA))
        assertArrayEquals(before, parcelled(original))
    }

    private fun parcelled(configuration: WifiConfiguration): ByteArray {
        val parcel = Parcel.obtain()
        return try { configuration.writeToParcel(parcel, 0); parcel.marshall() }
        finally { parcel.recycle() }
    }
}
