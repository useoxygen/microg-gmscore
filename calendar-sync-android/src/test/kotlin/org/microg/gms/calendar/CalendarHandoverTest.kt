/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ComponentName
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
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
    @After fun cleanup() {
        CalendarHandover.FORCE_OURS.forEach { ShadowSystemProperties.override(it, "") }
        CalendarHandover.adapterXml = CalendarHandover.loadAdapterXml
    }
    private fun adapterXml(authority: String, type: String = "com.google") = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(StringReader("""<sync-adapter xmlns:android="http://schemas.android.com/apk/res/android"
            android:contentAuthority="$authority" android:accountType="$type" android:supportsUploading="true" />"""))
    }
    /** The Google Calendar app as Android sees it; [adapter]: the authority its sync-adapter service declares, null for none. */
    private fun googleCalendar(enabled: Boolean = true, read: Boolean = true, write: Boolean = true, adapter: String? = authority, version: Long = 1) {
        val flag = PackageInfo.REQUESTED_PERMISSION_GRANTED
        val app = ApplicationInfo().apply { packageName = CalendarHandover.GOOGLE_CALENDAR; this.enabled = enabled }
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = CalendarHandover.GOOGLE_CALENDAR
            applicationInfo = app
            longVersionCode = version
            requestedPermissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            requestedPermissionsFlags = intArrayOf(if (read) flag else 0, if (write) flag else 0)
        })
        val intent = Intent(CalendarHandover.SYNC_ADAPTER).setPackage(CalendarHandover.GOOGLE_CALENDAR)
        shadowOf(context.packageManager).removeResolveInfosForIntent(intent, CalendarHandover.GOOGLE_CALENDAR)
        if (adapter != null) shadowOf(context.packageManager).addResolveInfoForIntent(intent, ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = CalendarHandover.GOOGLE_CALENDAR; name = "com.google.android.syncadapters.calendar.CalendarSyncAdapterService"
                applicationInfo = app
            }
        })
        CalendarHandover.adapterXml = { _, _ -> adapterXml(adapter ?: "") }
    }
    private fun store(): ContentProviderClient {
        val provider = Robolectric.buildContentProvider(CalendarSqlFixtureProvider::class.java).create().get()
        ShadowContentResolver.registerProviderInternal(authority, provider)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        fixture = provider
        return requireNotNull(context.contentResolver.acquireContentProviderClient(authority))
    }
    private lateinit var fixture: CalendarSqlFixtureProvider
    private fun ContentProviderClient.calendar(marker: String?, access: Int = Calendars.CAL_ACCESS_OWNER, syncing: Int = 1, of: Account = account) =
        ContentUris.parseId(requireNotNull(insert(Calendars.CONTENT_URI, ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, of.name); put(Calendars.ACCOUNT_TYPE, of.type); put(Calendars._SYNC_ID, "cal-$marker-$access-$syncing")
            put(Calendars.CAL_SYNC1, marker); put(Calendars.CALENDAR_ACCESS_LEVEL, access); put(Calendars.SYNC_EVENTS, syncing)
        })))
    private fun ContentProviderClient.event(calendar: Long, remote: String?, dirty: Boolean = false, ours: Boolean = true, deleted: Boolean = false) =
        ContentUris.parseId(requireNotNull(insert(Events.CONTENT_URI, ContentValues().apply {
            put(Events.CALENDAR_ID, calendar); put(Events._SYNC_ID, remote); put(Events.TITLE, "fixture")
            if (ours && remote != null) put(Events.SYNC_DATA1, AndroidCalendarStore.MARKER)
            put(Events.DIRTY, if (dirty) 1 else 0); put(Events.DELETED, if (deleted) 1 else 0)
        })))
    private fun ContentProviderClient.count(table: Uri, selection: String? = null, vararg args: String) =
        requireNotNull(query(table, null, selection, arrayOf(*args), null)).use { it.count }
    private fun state() = context.getSharedPreferences(CalendarSyncPreferences.PREFERENCES, Context.MODE_PRIVATE)
    private fun periodics() = ContentResolver.getPeriodicSyncs(account, authority).map { it.extras.keySet().toSet() }
    private fun marker() = setOf(CalendarSyncPreferences.PERIODIC)

    @Test fun googleCanSyncOnlyWhenInstalledEnabledAndPermitted() {
        assertTrue(CalendarHandover.googleCanSync(true, true, true, true, true))
        for (missing in 0..4) assertFalse(CalendarHandover.googleCanSync(missing != 0, missing != 1, missing != 2, missing != 3, missing != 4))
        assertEquals(Owner.OURS, CalendarHandover.decide(false, false, false))
        assertEquals(Owner.OURS, CalendarHandover.decide(true, true, false))
        assertEquals(Owner.WAITING, CalendarHandover.decide(true, false, true))
        assertEquals(Owner.GOOGLE, CalendarHandover.decide(true, false, false))
        assertFalse(CalendarHandover.googleCanSync(context))
        googleCalendar(write = false); assertFalse(CalendarHandover.googleCanSync(context))
        googleCalendar(enabled = false); assertFalse(CalendarHandover.googleCanSync(context))
        googleCalendar(); assertTrue(CalendarHandover.googleCanSync(context))
    }

    // Ours steps aside only for an app whose enabled sync-adapter service is for Google accounts and com.android.calendar.
    @Test fun googleCanSyncNeedsItsCalendarSyncAdapter() {
        googleCalendar(adapter = null); assertFalse(CalendarHandover.googleCanSync(context))
        googleCalendar(adapter = "com.android.contacts"); assertFalse(CalendarHandover.googleCanSync(context))
        googleCalendar(); assertTrue(CalendarHandover.googleCanSync(context))
        assertFalse(CalendarHandover.calendarAdapter(adapterXml(authority, "com.example")))
        assertTrue(CalendarHandover.calendarAdapter(adapterXml(authority)))
        googleCalendar(adapter = null)
        prefs.configure(true)
        assertEquals(Owner.OURS, CalendarHandover.evaluate(context))
        assertFalse(CalendarHandover.steppedAside(context))
    }

    // Google's adapter starts from nothing of ours: our calendars and their events, a download-only local edit and delete
    // included, leave the phone before ours steps aside; a calendar that is not ours stays.
    @Test fun steppingAsideRemovesOurRowsFirst() {
        val client = store()
        prefs.configure(true)
        val ours = client.calendar(AndroidCalendarStore.MARKER, Calendars.CAL_ACCESS_READ)
        client.event(ours, "clean"); client.event(ours, "edited", dirty = true); client.event(ours, "deleted", dirty = true, deleted = true)
        client.event(ours, null, dirty = true, ours = false) // inserted by another app
        val other = client.calendar(null)
        client.event(other, null, dirty = true, ours = false)
        googleCalendar()
        assertEquals(Owner.GOOGLE, CalendarHandover.evaluate(context))
        assertTrue(CalendarHandover.steppedAside(context))
        assertEquals(0, client.count(Calendars.CONTENT_URI, "${Calendars.CAL_SYNC1}=?", AndroidCalendarStore.MARKER))
        assertEquals(0, client.count(Events.CONTENT_URI, "${Events.CALENDAR_ID}=?", ours.toString()))
        assertEquals(1, client.count(Events.CONTENT_URI, "${Events.CALENDAR_ID}=?", other.toString()))
    }
    // Two-way: an unsent row in a calendar ours uploads from keeps ours and every row; one in a read-only calendar is not an
    // upload and goes with our rows.
    @Test fun twoWayKeepsTheSlotForUploadableRowsOnly() {
        val client = store()
        prefs.configure(true, true)
        val readOnly = client.calendar(AndroidCalendarStore.MARKER, Calendars.CAL_ACCESS_READ)
        client.event(readOnly, "stuck", dirty = true)
        val writable = client.calendar(AndroidCalendarStore.MARKER)
        val edit = client.event(writable, "edited", dirty = true)
        googleCalendar()
        assertEquals(Owner.WAITING, CalendarHandover.evaluate(context))
        assertFalse(CalendarHandover.steppedAside(context))
        assertEquals(2, client.count(Events.CONTENT_URI))
        client.update(Events.CONTENT_URI, ContentValues().apply { put(Events.DIRTY, 0) }, "${Events._ID}=?", arrayOf(edit.toString()))
        assertEquals(Owner.GOOGLE, CalendarHandover.evaluate(context))
        assertEquals(0, client.count(Events.CONTENT_URI)); assertEquals(0, client.count(Calendars.CONTENT_URI))
    }
    // A create another app inserts between the unsent check and the removal keeps its calendar, and ours keeps the slot.
    @Test fun aRowAddedDuringTheRemovalKeepsOurs() {
        val client = store()
        prefs.configure(true, true)
        val writable = client.calendar(AndroidCalendarStore.MARKER)
        client.event(writable, "clean")
        fixture.beforeBatch = { client.event(writable, null, dirty = true, ours = false) }
        googleCalendar()
        assertEquals(Owner.WAITING, CalendarHandover.evaluate(context))
        assertFalse(CalendarHandover.steppedAside(context))
        assertEquals(1, client.count(Calendars.CONTENT_URI)); assertEquals(2, client.count(Events.CONTENT_URI))
    }
    // Liveness: aside for longer than STALL_MILLIS with an account whose sync is on and no calendar from Google's adapter, ours
    // takes back the slot and says why; it does not step aside again until the app's version changes or a day has passed.
    @Test fun handsBackWhenGooglesAdapterNeverSyncsAndDoesNotLoop() {
        val client = store()
        ContentResolver.setMasterSyncAutomatically(true)
        prefs.configure(true)
        googleCalendar()
        assertEquals(Owner.GOOGLE, CalendarHandover.evaluate(context))
        val hour = 3600_000L
        state().edit().putLong("handoverSince", System.currentTimeMillis() - 5 * hour).commit()
        assertEquals(Owner.GOOGLE, CalendarHandover.evaluate(context))
        state().edit().putLong("handoverSince", System.currentTimeMillis() - 7 * hour).commit()
        val google = client.calendar("google-calendar-id@group.calendar.google.com")
        assertEquals(Owner.GOOGLE, CalendarHandover.evaluate(context)) // Google's adapter wrote this account
        client.delete(Calendars.CONTENT_URI, "${Calendars._ID}=?", arrayOf(google.toString()))
        assertEquals(Owner.OURS, CalendarHandover.evaluate(context))
        assertFalse(CalendarHandover.steppedAside(context)); assertTrue(prefs.rebuild)
        assertEquals(account.name, CalendarHandover.stalledAccount(context))
        assertEquals(Owner.OURS, CalendarHandover.evaluate(context))
        googleCalendar(version = 2)
        assertEquals(Owner.GOOGLE, CalendarHandover.evaluate(context))
        assertNull(CalendarHandover.stalledAccount(context))
        state().edit().putLong("handoverSince", System.currentTimeMillis() - 7 * hour).commit()
        assertEquals(Owner.OURS, CalendarHandover.evaluate(context))
        state().edit().putLong("stalledAt", System.currentTimeMillis() - 25 * hour).commit()
        assertEquals(Owner.GOOGLE, CalendarHandover.evaluate(context))
        // An account whose sync is off is not expected to have Google's rows.
        ContentResolver.setSyncAutomatically(account, authority, false)
        state().edit().putLong("handoverSince", System.currentTimeMillis() - 7 * hour).commit()
        assertEquals(Owner.GOOGLE, CalendarHandover.evaluate(context))
    }
    // Android's switch is per account and authority: an account that is off for ours is off for Google's adapter too.
    @Test fun anAccountThatIsOffStaysOffForGooglesAdapterAndSaysSo() {
        val other = Account("second@example.invalid", "com.google")
        AccountManager.get(context).addAccountExplicitly(other, null, null)
        prefs.configure(true)
        CalendarSyncPreferences(context, other).configure(false)
        googleCalendar()
        assertEquals(Owner.GOOGLE, CalendarHandover.evaluate(context))
        assertTrue(prefs.googleSyncs); assertFalse(CalendarSyncPreferences(context, other).googleSyncs)
        val provider = Robolectric.buildContentProvider(CalendarTransferProvider::class.java).create().get()
        assertTrue(provider.call("status", account.name, null).getBoolean("google_calendar_sync"))
        assertFalse(provider.call("status", other.name, null).getBoolean("google_calendar_sync"))
        assertEquals(0, ContentResolver.getIsSyncable(other, authority))
    }
    // Leaving two-way waits for unsent or conflicting rows, which download only would replace with Google's copy.
    @Test fun leavingTwoWayWaitsForUnsentChanges() {
        val client = store()
        prefs.configure(true, true)
        val writable = client.calendar(AndroidCalendarStore.MARKER)
        val edit = client.event(writable, "edited", dirty = true)
        assertFalse(CalendarHandover.canLeaveTwoWay(context, account))
        client.update(Events.CONTENT_URI, ContentValues().apply { put(Events.DIRTY, 0) }, "${Events._ID}=?", arrayOf(edit.toString()))
        assertTrue(CalendarHandover.canLeaveTwoWay(context, account))
        prefs.status = "conflict"
        assertFalse(CalendarHandover.canLeaveTwoWay(context, account))
        prefs.configure(true, false)
        assertTrue(CalendarHandover.canLeaveTwoWay(context, account))
    }
    // An account Android marks not syncable for calendars keeps that state; the default does not turn it on.
    @Test fun defaultRespectsAnAccountAndroidMarksNotSyncable() {
        ContentResolver.setIsSyncable(account, authority, 0)
        prefs.ensureDefaults()
        assertFalse(prefs.enabled); assertFalse(prefs.onByDefault)
        assertEquals(0, ContentResolver.getIsSyncable(account, authority))
        assertFalse(ContentResolver.getSyncAutomatically(account, authority))
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
        assertFalse(CalendarHandover.canLeaveTwoWay(context, account))
        prefs.configure(true, false)
        assertEquals(Owner.GOOGLE, CalendarHandover.evaluate(context))
        assertFalse(CalendarHandover.waiting(context))
    }
}
