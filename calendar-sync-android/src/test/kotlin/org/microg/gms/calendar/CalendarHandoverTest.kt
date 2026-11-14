/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.calendar.CalendarHandover.Owner
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowSystemProperties

/** CYC-2073: calendar sync on by default (download only) and stepping aside for the Google Calendar app's own adapter. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CalendarHandoverTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val account = Account("calendar-handover@example.invalid", "com.google")
    private val authority = CalendarSyncPreferences.AUTHORITY
    private lateinit var prefs: CalendarSyncPreferences
    private val ours get() = ComponentName(context.packageName, CalendarHandover.SERVICE)
    @Before fun setup() {
        context.getSharedPreferences(CalendarSyncPreferences.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        AccountManager.get(context).addAccountExplicitly(account, null, null)
        prefs = CalendarSyncPreferences(context, account)
    }
    @After fun cleanup() { CalendarHandover.FORCE_OURS.forEach { ShadowSystemProperties.override(it, "") } }
    private fun googleCalendar(enabled: Boolean = true, read: Boolean = true, write: Boolean = true) {
        val flag = PackageInfo.REQUESTED_PERMISSION_GRANTED
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = CalendarHandover.GOOGLE_CALENDAR
            applicationInfo = ApplicationInfo().apply { packageName = CalendarHandover.GOOGLE_CALENDAR; this.enabled = enabled }
            requestedPermissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            requestedPermissionsFlags = intArrayOf(if (read) flag else 0, if (write) flag else 0)
        })
    }
    private fun periodics() = ContentResolver.getPeriodicSyncs(account, authority).map { it.extras.keySet().toSet() }
    private fun marker() = setOf(CalendarSyncPreferences.PERIODIC)

    @Test fun googleCanSyncOnlyWhenInstalledEnabledAndPermitted() {
        assertTrue(CalendarHandover.googleCanSync(true, true, true, true))
        for (missing in 0..3) assertFalse(CalendarHandover.googleCanSync(missing != 0, missing != 1, missing != 2, missing != 3))
        assertEquals(Owner.OURS, CalendarHandover.decide(false, false, false))
        assertEquals(Owner.OURS, CalendarHandover.decide(true, true, false))
        assertEquals(Owner.WAITING, CalendarHandover.decide(true, false, true))
        assertEquals(Owner.GOOGLE, CalendarHandover.decide(true, false, false))
        assertFalse(CalendarHandover.googleCanSync(context))
        googleCalendar(write = false); assertFalse(CalendarHandover.googleCanSync(context))
        googleCalendar(enabled = false); assertFalse(CalendarHandover.googleCanSync(context))
        googleCalendar(); assertTrue(CalendarHandover.googleCanSync(context))
    }

    @Test fun defaultIsDownloadOnlyScheduledAndNotAnOwnerChoice() {
        ContentResolver.addPeriodicSync(account, authority, Bundle.EMPTY, 3600) // an earlier build's schedule
        prefs.ensureDefaults()
        assertTrue(prefs.enabled); assertFalse(prefs.twoWay)
        assertFalse(prefs.hasSavedChoice); assertTrue(prefs.onByDefault)
        assertEquals(1, ContentResolver.getIsSyncable(account, authority))
        assertTrue(ContentResolver.getSyncAutomatically(account, authority))
        // The Google Calendar app removes an empty-extras periodic sync for the authority: ours carries a marker.
        assertEquals(listOf(marker()), periodics())
    }
    @Test fun defaultNeverOverridesASavedChoiceAndIsRecordedOnce() {
        prefs.configure(false)
        prefs.ensureDefaults()
        assertFalse(prefs.enabled); assertEquals(0, ContentResolver.getIsSyncable(account, authority))
        val other = CalendarSyncPreferences(context, Account("second@example.invalid", "com.google"))
        other.ensureDefaults()
        ContentResolver.setSyncAutomatically(other.account, authority, false) // Android's own account switch
        other.ensureDefaults()
        assertFalse(ContentResolver.getSyncAutomatically(other.account, authority))
        other.configure(false)
        assertFalse(other.enabled); assertTrue(other.hasSavedChoice); assertFalse(other.onByDefault)
    }
    @Test fun transferStatusStaysTruthfulForADefault() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val provider = Robolectric.buildContentProvider(CalendarTransferProvider::class.java).create().get()
        prefs.ensureDefaults()
        val status = provider.call("status", account.name, null)
        assertEquals("enabled", status.getString("result"))
        assertFalse(status.getBoolean("two_way")); assertTrue(status.getBoolean("default_on"))
        assertFalse(status.getBoolean("google_calendar_sync"))
        prefs.configure(false)
        assertEquals("saved_off", provider.call("status", account.name, null).getString("result"))
        assertFalse(provider.call("status", account.name, null).getBoolean("default_on"))
    }
    @Test fun permissionMissingIsReportedNotSilentlyOff() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val provider = Robolectric.buildContentProvider(CalendarTransferProvider::class.java).create().get()
        prefs.ensureDefaults()
        assertEquals("permission_required", provider.call("status", account.name, null).getString("result"))
        assertTrue(prefs.enabled)
    }

    @Test fun stepsAsideForGoogleAndTakesBackWhenItCannotSync() {
        // An empty store: a two-way account without unsent rows.
        ShadowContentResolver.registerProviderInternal(authority,
            Robolectric.buildContentProvider(CalendarSqlFixtureProvider::class.java).create().get())
        prefs.configure(true, true)
        googleCalendar()
        assertEquals(Owner.GOOGLE, CalendarHandover.evaluate(context))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, context.packageManager.getComponentEnabledSetting(ours))
        assertTrue(CalendarHandover.steppedAside(context))
        assertTrue(periodics().none { it == marker() })
        // The owner's choices stay; settings while aside do not touch Android's switches, which are Google's adapter's too.
        assertTrue(prefs.enabled); assertTrue(prefs.twoWay); assertFalse(prefs.rebuild)
        val provider = Robolectric.buildContentProvider(CalendarTransferProvider::class.java).create().get()
        assertTrue(provider.call("status", account.name, null).getBoolean("google_calendar_sync"))
        assertEquals(Owner.GOOGLE, CalendarHandover.evaluate(context))
        googleCalendar(read = false) // permission revoked
        assertEquals(Owner.OURS, CalendarHandover.evaluate(context))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, context.packageManager.getComponentEnabledSetting(ours))
        assertFalse(CalendarHandover.steppedAside(context))
        assertTrue(prefs.rebuild)
        assertEquals(listOf(marker()), periodics())
        assertTrue(prefs.enabled); assertTrue(prefs.twoWay)
    }
    @Test fun aDefaultRecordedWhileAsideDoesNotScheduleForGoogle() {
        googleCalendar()
        assertEquals(Owner.GOOGLE, CalendarHandover.evaluate(context))
        prefs.ensureDefaults()
        assertTrue(prefs.enabled)
        assertTrue(periodics().isEmpty())
        shadowOf(context.packageManager).deletePackage(CalendarHandover.GOOGLE_CALENDAR)
        assertEquals(Owner.OURS, CalendarHandover.evaluate(context))
        assertEquals(listOf(marker()), periodics())
    }
    @Test fun killSwitchKeepsOurs() {
        prefs.configure(true)
        googleCalendar()
        ShadowSystemProperties.override("debug.cyclon.calendar_own_sync", "1")
        assertEquals(Owner.OURS, CalendarHandover.evaluate(context))
        assertFalse(CalendarHandover.steppedAside(context))
        ShadowSystemProperties.override("debug.cyclon.calendar_own_sync", "")
        assertEquals(Owner.GOOGLE, CalendarHandover.evaluate(context))
        ShadowSystemProperties.override("persist.cyclon.calendar.own_sync", "1")
        assertEquals(Owner.OURS, CalendarHandover.evaluate(context))
        assertFalse(CalendarHandover.steppedAside(context))
    }
    @Test fun aTransferHoldOrHeldConflictWaitsInsteadOfHandingOver() {
        prefs.configure(true, true)
        prefs.beginMigration()
        googleCalendar()
        assertEquals(Owner.WAITING, CalendarHandover.evaluate(context))
        assertFalse(CalendarHandover.steppedAside(context)); assertTrue(CalendarHandover.waiting(context))
        prefs.ownerResumeUploads()
        prefs.status = "conflict"
        assertEquals(Owner.WAITING, CalendarHandover.evaluate(context))
        prefs.status = "success"
        // No calendar provider in this fixture: unreadable rows count as unsent for a two-way account.
        assertEquals(Owner.WAITING, CalendarHandover.evaluate(context))
        prefs.configure(true, false)
        assertEquals(Owner.GOOGLE, CalendarHandover.evaluate(context))
        assertFalse(CalendarHandover.waiting(context))
    }
}
