/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.location.manager

import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.api.internal.IStatusCallback
import com.google.android.gms.location.internal.FusedLocationProviderResult
import com.google.android.gms.location.internal.IFusedLocationProviderCallback
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationApiContractTest {
    @Test fun flushCompletesExactlyOnceWithSuccessWhenThereIsNoBatch() {
        check(Build.HARDWARE in listOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val results = mutableListOf<FusedLocationProviderResult>()
        instrumentation.runOnMainSync {
            val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
            val context = instrumentation.targetContext
            val service = LocationManagerInstance(context, LocationManager(context, owner.lifecycle), context.packageName, owner.lifecycle)
            service.flushLocations(object : IFusedLocationProviderCallback.Stub() {
                override fun onFusedLocationProviderResult(result: FusedLocationProviderResult) { results += result }
                override fun cancel() { fail("A completed no-op flush must not be cancelled") }
            })
        }
        assertEquals(1,results.size)
        assertTrue(results.single().status.isSuccess)
    }
    @Test fun unsupportedCallsCompleteOnceAndNeverSucceed() {
        check(Build.HARDWARE in listOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val statuses = mutableListOf<Status>()
        instrumentation.runOnMainSync {
            val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
            val context = instrumentation.targetContext
            val service = LocationManagerInstance(context, LocationManager(context, owner.lifecycle), context.packageName, owner.lifecycle)
            val callback = object : IStatusCallback.Stub() { override fun onResult(status: Status) { statuses += status } }
            service.requestActivityTransitionUpdates(null,null,callback)
            service.removeActivityTransitionUpdates(null,callback)
            service.requestActivityUpdatesWithCallback(null,null,callback)
            service.requestSleepSegmentUpdates(null,null,callback)
            service.removeSleepSegmentUpdates(null,callback)
            service.setGoogleLocationAccuracy(null,callback)
            service.setMockModeWithCallback(false,callback)
            service.setMockLocationWithCallback(android.location.Location("fixture"),callback)
        }
        assertEquals(8,statuses.size)
        assertTrue(statuses.none { it.isSuccess })
        assertTrue(statuses.all { it.statusMessage == "This API is not implemented" })
    }
}
