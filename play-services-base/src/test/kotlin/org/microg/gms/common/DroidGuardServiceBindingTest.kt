/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.common

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.common.Constants.GMS_PACKAGE_NAME
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The binding microG's DroidGuard client holds while a reCAPTCHA Base session is open goes through
 * [MultiConnectionKeeper]. `BIND_DEBUG_UNBIND` has the value of `ServiceInfo.FLAG_ISOLATED_PROCESS`, and a framework
 * build that tests that bit on the binding when choosing the service's host left the DroidGuard process cached and
 * frozen under a top-state client (CYC-1502). Every bind
 * the keeper makes, on each target path and after the service process dies, must keep the bit clear while still
 * auto-creating the service and following the client's activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DroidGuardServiceBindingTest {
    private val application: Context = RuntimeEnvironment.getApplication()

    private class Bind(val intent: Intent, val connection: ServiceConnection, val flags: Int)

    /** Records each bindService call and answers it as the framework would for a resolvable service. */
    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val binds = mutableListOf<Bind>()
        val unbinds = mutableListOf<ServiceConnection>()

        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            binds += Bind(service, conn, flags)
            return true
        }

        override fun bindService(service: Intent, conn: ServiceConnection, flags: Context.BindServiceFlags): Boolean =
            throw AssertionError("unexpected long-flag bindService")

        override fun unbindService(conn: ServiceConnection) {
            unbinds += conn
        }
    }

    private class RecordingForward : ServiceConnection {
        var binder: IBinder? = null
        var disconnects = 0

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            disconnects++
        }
    }

    @Before
    fun forgetCachedTarget() {
        // The keeper caches its target package; each test chooses its own.
        application.getSharedPreferences("org.microg.gms_connection", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun registerDroidGuardService(packageName: String) {
        val component = ComponentName(packageName, "org.microg.gms.droidguard.core.DroidGuardService")
        val pm = shadowOf(application.packageManager)
        pm.addServiceIfNotPresent(component)
        pm.addIntentFilterForService(component, IntentFilter(GmsService.DROID_GUARD.ACTION))
    }

    private fun assertOrdinaryClientBinding(bind: Bind, targetPackage: String) {
        assertEquals(GmsService.DROID_GUARD.ACTION, bind.intent.action)
        assertEquals(targetPackage, bind.intent.`package`)
        assertEquals(
            "a bound DroidGuard service must not carry a bit the framework reads as FLAG_ISOLATED_PROCESS " +
                "(0x${ServiceInfo.FLAG_ISOLATED_PROCESS.toString(16)}); flags were 0x${bind.flags.toString(16)}",
            0,
            bind.flags and ServiceInfo.FLAG_ISOLATED_PROCESS,
        )
        assertEquals(
            "flags were 0x${bind.flags.toString(16)}",
            Context.BIND_AUTO_CREATE or Context.BIND_ADJUST_WITH_ACTIVITY,
            bind.flags,
        )
    }

    /** microG's own process: reCAPTCHA Base and DroidGuard live in the same package, so the keeper binds itself. */
    @Test
    fun selfPackageDroidGuardBindingIsAnOrdinaryClientBinding() {
        registerDroidGuardService(application.packageName)
        val context = RecordingContext(application)
        val forward = RecordingForward()

        assertTrue(MultiConnectionKeeper(context).bind(GmsService.DROID_GUARD.ACTION, forward))

        val bind = context.binds.single()
        assertOrdinaryClientBinding(bind, application.packageName)
        val binder = Binder()
        bind.connection.onServiceConnected(ComponentName(application.packageName, "DroidGuardService"), binder)
        assertSame(binder, forward.binder)
        assertTrue(context.unbinds.isEmpty())
    }

    /** A client app binding an installed system GMS package takes the same bind. */
    @Test
    fun systemGmsPackageDroidGuardBindingIsAnOrdinaryClientBinding() {
        shadowOf(application.packageManager).installPackage(PackageInfo().apply {
            packageName = GMS_PACKAGE_NAME
            applicationInfo = ApplicationInfo().apply {
                packageName = GMS_PACKAGE_NAME
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        })
        registerDroidGuardService(GMS_PACKAGE_NAME)
        val context = RecordingContext(application)

        assertTrue(MultiConnectionKeeper(context).bind(GmsService.DROID_GUARD.ACTION, RecordingForward()))

        assertOrdinaryClientBinding(context.binds.single(), GMS_PACKAGE_NAME)
    }

    /** After the DroidGuard process dies, the next client's bind binds again, with the same ordinary flags. */
    @Test
    fun rebindAfterServiceDeathIsAnOrdinaryClientBinding() {
        registerDroidGuardService(application.packageName)
        val context = RecordingContext(application)
        val keeper = MultiConnectionKeeper(context)
        val first = RecordingForward()
        assertTrue(keeper.bind(GmsService.DROID_GUARD.ACTION, first))
        val component = ComponentName(application.packageName, "DroidGuardService")
        val connection = context.binds.single().connection
        connection.onServiceConnected(component, Binder())
        connection.onServiceDisconnected(component)
        assertEquals(1, first.disconnects)

        assertTrue(keeper.bind(GmsService.DROID_GUARD.ACTION, RecordingForward()))

        assertEquals(2, context.binds.size)
        context.binds.forEach { assertOrdinaryClientBinding(it, application.packageName) }
    }
}
