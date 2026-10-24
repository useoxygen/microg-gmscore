/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.checkin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.MatrixCursor
import android.net.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.settings.SettingsContract
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], shadows = [TriggerReceiverTest.Connectivity::class])
class TriggerReceiverTest {
    @Implements(ConnectivityManager::class)
    class Connectivity {
        companion object {
            var physicalInternet = false
            val registered = mutableSetOf<PendingIntent>()
            var registrations = 0
        }
        @Implementation fun getActiveNetworkInfo(): NetworkInfo? = null // UID blocked / offline
        @Implementation fun getActiveNetwork(): Network? = if (physicalInternet) Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(100) else null
        @Implementation fun getNetworkCapabilities(network: Network?): NetworkCapabilities? =
            if (network == null) null else NetworkCapabilities().apply { NetworkCapabilities::class.java.getMethod("addCapability", Int::class.javaPrimitiveType).invoke(this, NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        @Implementation fun registerNetworkCallback(request: NetworkRequest, intent: PendingIntent) {
            registrations++; registered.add(intent)
        }
        @Implementation fun unregisterNetworkCallback(intent: PendingIntent) { registered.remove(intent) }
    }
    class Preferences : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?) =
            MatrixCursor(projection!!).apply { addRow(projection.map { if (it == SettingsContract.CheckIn.ENABLED) 1 else if (it == SettingsContract.CheckIn.LAST_CHECK_IN) 0L else "0" }) }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        ShadowContentResolver.registerProviderInternal(SettingsContract.getAuthority(context), Preferences())
        Connectivity.registered.clear(); Connectivity.registrations = 0; Connectivity.physicalInternet = false
    }
    @Test fun immediateAvailabilityRetiresWaitInsteadOfRegisteringAgain() {
        val receiver = TriggerReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1, Connectivity.registrations)
        assertEquals(1, Connectivity.registered.size)
        repeat(20) { receiver.onReceive(context, Intent(TriggerReceiver.ACTION_NETWORK)) }
        assertEquals(1, Connectivity.registrations)
        assertTrue(Connectivity.registered.isEmpty())
        val alarms = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).scheduledAlarms
        assertEquals(1, alarms.size)
        assertEquals(TriggerReceiver.ACTION_RETRY, shadowOf(alarms.single().operation).savedIntent.action)
    }
    @Test fun physicalNetworkBlockedByUidPolicyUsesAlarmWithoutNetworkWait() {
        Connectivity.physicalInternet = true
        repeat(20) { TriggerReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED)) }
        assertEquals(0, Connectivity.registrations)
        assertEquals(1, shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).scheduledAlarms.size)
    }
}
