/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.XmlResourceParser
import android.os.Build
import android.os.Bundle
import android.os.Process
import org.xmlpull.v1.XmlPullParser

/**
 * Who keeps the shared calendar store (com.android.calendar) in sync for Google accounts (CYC-2073). Android gives that slot
 * to one sync adapter, and ours wins because it is in the system image. Where the Google Calendar app can sync, its own
 * adapter (guests, push, Google's conflict rules) does it instead: ours removes its own rows from the phone, disables its sync
 * adapter service, Android then gives the slot to Google's, and ours stays the fallback for phones without that app. The
 * owner's saved choices are not changed.
 */
object CalendarHandover {
    const val GOOGLE_CALENDAR = "com.google.android.calendar"
    const val SERVICE = "org.microg.gms.calendar.CalendarSyncService"
    const val SYNC_ADAPTER = "android.content.SyncAdapter"
    /** Kill switch, "1" keeps ours: debug.* the shell can set today, until reboot; persist.* survives a reboot once Cyclon's
     * sepolicy lets the shell set it and microG read it. Read at every evaluation. */
    val FORCE_OURS = listOf("persist.cyclon.calendar.own_sync", "debug.cyclon.calendar_own_sync")
    /** Liveness: ours hands back when, this long after stepping aside, an account whose sync is on has no calendar from
     * Google's adapter. Its first sync took seconds on a Pixel 7a; six hours leave room for a phone that was offline. */
    const val STALL_MILLIS = 6 * 3600_000L
    /** After such a hand-back, ours steps aside again only once the Google Calendar app's version changed or a day passed. */
    const val RETRY_MILLIS = 24 * 3600_000L
    /** WAITING: Google's adapter could sync, but ours still has unsent changes or a transfer hold, so ours keeps the slot. */
    enum class Owner { OURS, GOOGLE, WAITING }

    /** Google's adapter syncs only while its app is installed and enabled, holds both calendar permissions and declares an
     * enabled sync adapter for Google accounts and this authority. */
    fun googleCanSync(installed: Boolean, enabled: Boolean, read: Boolean, write: Boolean, adapter: Boolean) =
        installed && enabled && read && write && adapter
    fun decide(googleCanSync: Boolean, forceOurs: Boolean, unsent: Boolean, stalled: Boolean = false, backingOff: Boolean = false): Owner = when {
        !googleCanSync || forceOurs || stalled || backingOff -> Owner.OURS
        unsent -> Owner.WAITING
        else -> Owner.GOOGLE
    }
    /** Ours has been aside long enough to expect Google's adapter to have written every account whose sync is on. */
    fun stalled(asideSince: Long, now: Long) = asideSince > 0 && now - asideSince >= STALL_MILLIS
    fun backingOff(stalledAt: Long, stalledVersion: Long, now: Long, version: Long) =
        stalledAt > 0 && now - stalledAt in 0L until RETRY_MILLIS && version == stalledVersion

    private fun component(context: Context) = ComponentName(context.packageName, SERVICE)
    private fun state(context: Context) = context.getSharedPreferences(CalendarSyncPreferences.PREFERENCES, Context.MODE_PRIVATE)
    /** Ours has stepped aside: its sync adapter service is disabled. Readable from any process. */
    fun steppedAside(context: Context): Boolean = runCatching {
        context.packageManager.getComponentEnabledSetting(component(context)) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }.getOrDefault(false)
    /** The last evaluation found Google's adapter able to sync, but ours waits for its unsent changes. */
    fun waiting(context: Context): Boolean = !steppedAside(context) && state(context).getString("handover", "") == "waiting"
    /** The account Google's adapter had not synced when ours took back the slot for that reason, while ours holds it. */
    fun stalledAccount(context: Context): String? =
        if (steppedAside(context)) null else state(context).getString("stalledAccount", null)

    /** Reads a service's sync-adapter declaration. Host tests replace it: they cannot load another package's resources. */
    val loadAdapterXml: (PackageManager, ServiceInfo) -> XmlPullParser? = { pm, service -> service.loadXmlMetaData(pm, SYNC_ADAPTER) }
    internal var adapterXml = loadAdapterXml
    /** Whether a sync-adapter declaration is for Google accounts and com.android.calendar, as Android's sync-adapter cache reads
     * it; [string] resolves an attribute given as a resource reference. */
    fun calendarAdapter(parser: XmlPullParser, string: (Int) -> String? = { null }): Boolean {
        val android = "http://schemas.android.com/apk/res/android"
        fun value(name: String): String? {
            val reference = (parser as? XmlResourceParser)?.getAttributeResourceValue(android, name, 0) ?: 0
            return if (reference != 0) string(reference) else parser.getAttributeValue(android, name)
        }
        while (true) when (parser.next()) {
            XmlPullParser.END_DOCUMENT -> return false
            XmlPullParser.START_TAG -> return parser.name == "sync-adapter" &&
                value("contentAuthority") == CalendarSyncPreferences.AUTHORITY && value("accountType") == "com.google"
        }
    }
    private fun declaresCalendarAdapter(pm: PackageManager): Boolean =
        // Only enabled services match: a disabled adapter never gets the slot.
        @Suppress("DEPRECATION") pm.queryIntentServices(Intent(SYNC_ADAPTER).setPackage(GOOGLE_CALENDAR), PackageManager.GET_META_DATA)
            .any { info ->
                runCatching {
                    val parser = adapterXml(pm, info.serviceInfo) ?: return@runCatching false
                    calendarAdapter(parser) { pm.getResourcesForApplication(info.serviceInfo.applicationInfo).getString(it) }
                }.getOrDefault(false)
            }

    fun googleCanSync(context: Context): Boolean {
        val pm = context.packageManager
        val app = try {
            @Suppress("DEPRECATION") pm.getApplicationInfo(GOOGLE_CALENDAR, PackageManager.GET_DISABLED_COMPONENTS)
        } catch (_: PackageManager.NameNotFoundException) { null }
        fun granted(permission: String) = app != null && pm.checkPermission(permission, GOOGLE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        return googleCanSync(app != null, app?.enabled == true, granted(Manifest.permission.READ_CALENDAR),
            granted(Manifest.permission.WRITE_CALENDAR), app?.enabled == true && declaresCalendarAdapter(pm))
    }
    private fun googleVersion(context: Context): Long = runCatching {
        @Suppress("DEPRECATION") val info = context.packageManager.getPackageInfo(GOOGLE_CALENDAR, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(-1)

    fun forceOurs(): Boolean = FORCE_OURS.any { name ->
        runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
                .invoke(null, name, "") as String == "1"
        }.getOrDefault(false)
    }

    private fun permitted(context: Context) = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        .all { context.checkPermission(it, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED }
    private fun <T> withStore(context: Context, account: Account, prefs: CalendarSyncPreferences, block: (AndroidCalendarStore) -> T): T {
        val client = requireNotNull(context.contentResolver.acquireContentProviderClient(CalendarSyncPreferences.AUTHORITY))
        try { return block(AndroidCalendarStore(client, account, prefs)) } finally { @Suppress("DEPRECATION") client.release() }
    }
    /** Two-way rows still dirty or journaled in a calendar ours uploads from, or a held conflict. Unreadable counts as unsent. */
    private fun unsentRows(context: Context, account: Account, prefs: CalendarSyncPreferences): Boolean =
        prefs.status == "conflict" || runCatching { withStore(context, account, prefs) { it.hasUnsentChanges() } }.getOrDefault(true)

    /** Never hand over an owner's unsent edit: [unsentRows] of a two-way account, or a transfer hold (Google's adapter has no
     * hold and would upload at once). */
    private fun unsent(context: Context, account: Account): Boolean {
        val prefs = CalendarSyncPreferences(context, account)
        if (!prefs.enabled) return false
        if (prefs.uploadsHeld) return true
        return prefs.twoWay && unsentRows(context, account, prefs)
    }
    /** Download only lets Google's copy replace a local edit, so leaving two-way waits until nothing is left to upload or
     * resolve (CYC-2073). */
    fun canLeaveTwoWay(context: Context, account: Account): Boolean {
        val prefs = CalendarSyncPreferences(context, account)
        return !prefs.enabled || !prefs.twoWay || !unsentRows(context, account, prefs)
    }
    /** Our rows leave the phone before the hand-over ([AndroidCalendarStore.removeOwnRows]). Without calendar permission ours
     * can neither read the store nor have written to it since it lost the permission, so there is nothing it can remove. */
    private fun removeOurRows(context: Context, account: Account): Boolean {
        if (!permitted(context)) return true
        val prefs = CalendarSyncPreferences(context, account)
        return runCatching { withStore(context, account, prefs) { it.removeOwnRows(prefs.enabled && prefs.twoWay) } }.getOrDefault(false)
    }
    /** Google's adapter would sync this account: Android's switches for it and master sync are on. */
    private fun syncOn(account: Account) = ContentResolver.getMasterSyncAutomatically() &&
        ContentResolver.getSyncAutomatically(account, CalendarSyncPreferences.AUTHORITY) &&
        ContentResolver.getIsSyncable(account, CalendarSyncPreferences.AUTHORITY) != 0
    private fun withoutGoogleCalendar(context: Context, account: Account): Boolean = permitted(context) && runCatching {
        withStore(context, account, CalendarSyncPreferences(context, account)) { !it.hasForeignCalendar() }
    }.getOrDefault(false)

    /**
     * Decides and applies; returns who holds the slot now. Changes something only on a transition, so it is safe to call
     * often. Stepping aside removes our rows, disables ours, removes its periodic sync and requests a forced sync for every
     * account whose sync is on, which Android gives to Google's adapter. Taking back restores ours to its manifest default and
     * marks every account for a rebuild; it also happens when Google's adapter has not written an account for
     * [STALL_MILLIS], and then ours does not step aside again until [backingOff] ends.
     */
    @Synchronized fun evaluate(context: Context): Owner {
        val accounts = AccountManager.get(context).getAccountsByType("com.google")
        val state = state(context)
        val now = System.currentTimeMillis()
        val aside = steppedAside(context)
        val can = googleCanSync(context)
        val force = forceOurs()
        val version = googleVersion(context)
        val backingOff = !aside && backingOff(state.getLong("stalledAt", 0), state.getLong("stalledVersion", -1), now, version)
        val stalled = if (aside && can && !force && stalled(state.getLong("handoverSince", 0), now))
            accounts.firstOrNull { syncOn(it) && withoutGoogleCalendar(context, it) } else null
        var owner = decide(can, force, can && !force && accounts.any { unsent(context, it) }, stalled != null, backingOff)
        if (owner == Owner.GOOGLE && !aside) {
            // Every account's rows first: a failure (a row ours would upload appeared meanwhile) keeps ours for now.
            if (!accounts.map { removeOurRows(context, it) }.all { it }) owner = Owner.WAITING
            else {
                context.packageManager.setComponentEnabledSetting(component(context),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                for (account in accounts) {
                    ContentResolver.cancelSync(account, CalendarSyncPreferences.AUTHORITY)
                    // Only ours: the Google Calendar app registers its own periodic sync.
                    ContentResolver.removePeriodicSync(account, CalendarSyncPreferences.AUTHORITY, CalendarSyncPreferences.periodicExtras())
                    // Forced, so Google's adapter also refills what an earlier hand-back removed; never for an account that is off.
                    if (syncOn(account)) ContentResolver.requestSync(account, CalendarSyncPreferences.AUTHORITY, Bundle().apply {
                        putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true); putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                    })
                }
                state.edit().putLong("handoverSince", now).remove("stalledAt").remove("stalledVersion").remove("stalledAccount").apply()
            }
        } else if (owner != Owner.GOOGLE && aside) {
            context.packageManager.setComponentEnabledSetting(component(context),
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
            val edit = state.edit().remove("handoverSince")
            if (stalled != null) edit.putLong("stalledAt", now).putLong("stalledVersion", version).putString("stalledAccount", stalled.name)
            edit.apply()
            for (account in accounts) CalendarSyncPreferences(context, account).takeBack()
        }
        state.edit().putString("handover", owner.name.lowercase()).apply()
        return owner
    }
}
