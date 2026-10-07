/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.location.manager

import android.app.PendingIntent
import android.content.*
import android.location.Location
import android.location.LocationManager as SystemLocationManager
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.common.internal.*
import com.google.android.gms.location.*
import com.google.android.gms.location.internal.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.*

class GeofenceFixtureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { events.offer(intent) }
    companion object { val events = LinkedBlockingQueue<Intent>() }
}
/** Actual service/Binder request and system GPS fixture. Refuses physical phones. */
@RunWith(AndroidJUnit4::class)
class GeofenceApiTest {
    @Test fun addEnterDwellExitAndRemoveCompleteThroughBinder() {
        check(Build.HARDWARE in listOf("ranchu","goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val initialized = CountDownLatch(1)
        var api: IGoogleLocationManagerService? = null
        val connection = object : ServiceConnection {
            override fun onServiceDisconnected(name: ComponentName) = Unit
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val callback = object : IGmsCallbacks.Stub() {
                    override fun onPostInitComplete(code: Int, binder: IBinder?, params: Bundle?) {
                        api = IGoogleLocationManagerService.Stub.asInterface(binder); initialized.countDown()
                    }
                    override fun onAccountValidationComplete(code: Int, params: Bundle?) = Unit
                    override fun onPostInitCompleteWithConnectionInfo(code: Int, binder: IBinder?, info: ConnectionInfo?) = onPostInitComplete(code,binder,null)
                }
                IGmsServiceBroker.Stub.asInterface(binder).getService(callback, GetServiceRequest(23).apply {
                    packageName = context.packageName; extras = Bundle.EMPTY
                })
            }
        }
        val system = context.getSystemService(Context.LOCATION_SERVICE) as SystemLocationManager
        val pending = PendingIntent.getBroadcast(context, 9173, Intent(context,GeofenceFixtureReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT>=31) PendingIntent.FLAG_MUTABLE else 0)
        val results = LinkedBlockingQueue<Int>()
        val callbacks = object : IGeofencerCallbacks.Stub() {
            override fun onAddGeofenceResult(status: Int, ids: Array<out String>?) { results.offer(status) }
            override fun onRemoveGeofencesByRequestIdsResult(status: Int, ids: Array<out String>?) { results.offer(status) }
            override fun onRemoveGeofencesByPendingIntentResult(status: Int, intent: PendingIntent?) { results.offer(status) }
        }
        var bound = false
        var mockAdded = false
        try {
            bound = context.bindService(Intent(context,GoogleLocationManagerService::class.java),connection,Context.BIND_AUTO_CREATE)
            assertTrue(bound); assertTrue(initialized.await(10,TimeUnit.SECONDS))
            // Enforce app-scoped limits without admitting rejected registrations.
            val destinations = (0..5).map { PendingIntent.getBroadcast(context, 9300 + it,
                Intent(context,GeofenceFixtureReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT>=31) PendingIntent.FLAG_MUTABLE else 0) }
            try {
                for ((index,destination) in destinations.withIndex()) {
                    val limitFence = Geofence.Builder().setRequestId("limit-$index").setCircularRegion(37.0,-122.0,100f)
                        .setTransitionTypes(1).setExpirationDuration(120_000).build()
                    api!!.addGeofences(GeofencingRequest.Builder().addGeofence(limitFence).build(),destination,callbacks)
                    assertEquals(if(index < 5) 0 else GeofenceStatusCodes.GEOFENCE_TOO_MANY_PENDING_INTENTS,results.poll(10,TimeUnit.SECONDS))
                }
                api!!.removeAllGeofences(callbacks,context.packageName)
                assertEquals(0,results.poll(5,TimeUnit.SECONDS))
                val hundred = (0 until 100).map { Geofence.Builder().setRequestId("cap-$it").setCircularRegion(37.0,-122.0,100f)
                    .setTransitionTypes(1).setExpirationDuration(120_000).build() }
                api!!.addGeofences(GeofencingRequest.Builder().addGeofences(hundred).build(),pending,callbacks)
                assertEquals(0,results.poll(10,TimeUnit.SECONDS))
                // Replacing an existing ID at the cap must succeed.
                api!!.addGeofences(GeofencingRequest.Builder().addGeofence(hundred.first()).build(),pending,callbacks)
                assertEquals(0,results.poll(10,TimeUnit.SECONDS))
                val overflow = Geofence.Builder().setRequestId("overflow").setCircularRegion(37.0,-122.0,100f)
                    .setTransitionTypes(1).setExpirationDuration(120_000).build()
                api!!.addGeofences(GeofencingRequest.Builder().addGeofence(overflow).build(),pending,callbacks)
                assertEquals(GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES,results.poll(10,TimeUnit.SECONDS))
                api!!.removeAllGeofences(callbacks,context.packageName)
                assertEquals(0,results.poll(5,TimeUnit.SECONDS))
            } finally { destinations.forEach { it.cancel() } }
            system.addTestProvider("gps",false,false,false,false,true,true,true,1,1)
            mockAdded = true; system.setTestProviderEnabled("gps",true)
            GeofenceFixtureReceiver.events.clear()
            val fence = Geofence.Builder().setRequestId("fixture").setCircularRegion(37.0,-122.0,100f)
                .setTransitionTypes(7).setLoiteringDelay(0).setNotificationResponsiveness(5_000).setExpirationDuration(120_000).build()
            api!!.addGeofences(GeofencingRequest.Builder().addGeofence(fence).setInitialTrigger(5).build(),pending,callbacks)
            assertEquals(0,results.poll(10,TimeUnit.SECONDS))
            context.unbindService(connection); bound = false
            fun position(latitude: Double) { system.setTestProviderLocation("gps",Location("gps").apply {
                this.latitude=latitude; longitude=-122.0; accuracy=2f; time=System.currentTimeMillis(); elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()
            }) }
            position(37.0)
            val first = GeofencingEvent.fromIntent(GeofenceFixtureReceiver.events.poll(15,TimeUnit.SECONDS))
            assertNotNull(first); assertFalse(first.hasError()); assertEquals(1,first.geofenceTransition)
            val dwell = GeofencingEvent.fromIntent(GeofenceFixtureReceiver.events.poll(5,TimeUnit.SECONDS))
            assertEquals(4,dwell.geofenceTransition)
            SystemClock.sleep(5_100); position(37.01)
            val exit = GeofencingEvent.fromIntent(GeofenceFixtureReceiver.events.poll(15,TimeUnit.SECONDS))
            assertNotNull(exit); assertEquals(2,exit.geofenceTransition)
            api!!.removeGeofencesByIntent(pending,callbacks,context.packageName)
            assertEquals(0,results.poll(5,TimeUnit.SECONDS))
            assertNull(results.poll(100,TimeUnit.MILLISECONDS))
        } finally {
            runCatching { api?.removeGeofencesByIntent(pending,callbacks,context.packageName) }
            pending.cancel()
            if (mockAdded) system.removeTestProvider("gps")
            if (bound) context.unbindService(connection)
        }
    }
}
