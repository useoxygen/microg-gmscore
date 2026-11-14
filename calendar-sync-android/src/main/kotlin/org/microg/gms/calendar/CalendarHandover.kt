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
import android.content.SyncAdapterType
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.XmlResourceParser
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.CalendarContract.Calendars
import org.xmlpull.v1.XmlPullParser

/**
 * Who keeps the shared calendar store (com.android.calendar) in sync for Google accounts (CYC-2073). Android gives that slot
 * to one sync adapter, and ours wins because it is in the system image. Where the Google Calendar app can sync, its own
 * adapter (guests, push, Google's conflict rules) does it instead: ours disables its sync adapter service, removes its own rows
 * from the phone, Android then gives the slot to Google's, and ours stays the fallback for phones without that app. Never while
 * an account syncs two-way. The owner's saved choices are not changed.
 */
object CalendarHandover {
    const val GOOGLE_CALENDAR = "com.google.android.calendar"
    const val SERVICE = "org.microg.gms.calendar.CalendarSyncService"
    const val SYNC_ADAPTER = "android.content.SyncAdapter"
    /** Kill switch, "1" keeps ours: debug.* the shell can set today, until reboot; persist.* survives a reboot once Cyclon's
     * sepolicy lets the shell set it and microG read it. Read at every evaluation. */
    val FORCE_OURS = listOf("persist.cyclon.calendar.own_sync", "debug.cyclon.calendar_own_sync")
    /** Hourly checks (online, an account's sync on) after which Google's adapter must have written each such account. */
    const val LIVENESS_CHECKS = 6
    enum class Owner { OURS, GOOGLE }

    /** Google's adapter syncs only while its app is installed and enabled, holds both calendar permissions and declares an
     * enabled sync adapter for Google accounts and this authority. */
    fun googleCanSync(installed: Boolean, enabled: Boolean, read: Boolean, write: Boolean, adapter: Boolean) =
        installed && enabled && read && write && adapter
    /** The slot is per account type and authority: one two-way account (or transfer hold) keeps ours for all of them. After
     * Google's adapter failed to take or use the slot, ours waits for a new version of its app ([backingOff]). */
    fun decide(googleCanSync: Boolean, forceOurs: Boolean, twoWay: Boolean, backingOff: Boolean = false): Owner =
        if (googleCanSync && !forceOurs && !twoWay && !backingOff) Owner.GOOGLE else Owner.OURS

    private fun component(context: Context) = ComponentName(context.packageName, SERVICE)
    private fun state(context: Context) = context.getSharedPreferences(CalendarSyncPreferences.PREFERENCES, Context.MODE_PRIVATE)
    /** Ours has stepped aside: its sync adapter service is disabled. Readable from any process. */
    fun steppedAside(context: Context): Boolean = runCatching {
        context.packageManager.getComponentEnabledSetting(component(context)) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }.getOrDefault(false)
    /** Why ours keeps the slot although the Google Calendar app could sync: "two_way", "cleanup", "unclaimed" or "stalled",
     * with the account it concerns. Null while aside or when there is nothing to say. */
    fun reason(context: Context): Pair<String, String?>? = if (steppedAside(context)) null
        else state(context).getString("reason", null)?.let { it to state(context).getString("reasonAccount", null) }

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

    /** Who holds the slot, as Android's sync-adapter cache says: SyncAdapterType's package is not public API, but its
     * toString() names it since Android 10 (without it, not checked). Host tests replace this. */
    internal var slotHolder: () -> String? = {
        ContentResolver.getSyncAdapterTypes().firstOrNull { it.accountType == "com.google" && it.authority == CalendarSyncPreferences.AUTHORITY }
            ?.let { packageOf(it) ?: GOOGLE_CALENDAR }
    }
    internal fun packageOf(type: SyncAdapterType): String? = Regex("packageName=([^,} ]+)").find(type.toString())?.groupValues?.get(1)
    internal var settleMillis = 10_000L
    /** Android has given the slot to the Google Calendar app's adapter, within [settleMillis] of disabling ours. */
    private fun claimed(): Boolean {
        val deadline = System.currentTimeMillis() + settleMillis
        while (slotHolder() != GOOGLE_CALENDAR) if (System.currentTimeMillis() < deadline) Thread.sleep(250) else return false
        return true
    }
    /** A validated network, so Google's adapter could have synced. Host tests replace it. */
    internal var online: (Context) -> Boolean = { context ->
        runCatching {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }.getOrDefault(false)
    }

    private fun permitted(context: Context) = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        .all { context.checkPermission(it, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED }
    private fun <T> withStore(context: Context, account: Account, prefs: CalendarSyncPreferences, block: (AndroidCalendarStore) -> T): T {
        val client = requireNotNull(context.contentResolver.acquireContentProviderClient(CalendarSyncPreferences.AUTHORITY))
        try { return block(AndroidCalendarStore(client, account, prefs)) } finally { @Suppress("DEPRECATION") client.release() }
    }
    /** Download only lets Google's copy replace a local edit, so leaving two-way waits until nothing is left to upload or
     * resolve (CYC-2073). Unreadable counts as unsent. */
    fun canLeaveTwoWay(context: Context, account: Account): Boolean {
        val prefs = CalendarSyncPreferences(context, account)
        return !prefs.enabled || !prefs.twoWay || prefs.status != "conflict" &&
            runCatching { withStore(context, account, prefs) { !it.hasUnsentChanges() } }.getOrDefault(false)
    }
    /** Without calendar permission ours cannot remove its rows: it may hold some unless it created none since they last left
     * (before this flag existed: unless no account ever synced). */
    private fun rowsOutOfReach(context: Context, accounts: Array<Account>): Boolean = !permitted(context) && state(context).let { state ->
        if (state.contains(CalendarSyncPreferences.OWN_ROWS)) state.getBoolean(CalendarSyncPreferences.OWN_ROWS, true)
        else accounts.any { CalendarSyncPreferences(context, it).lastSuccess > 0 }
    }
    /** Every calendar of ours, of every Google account, leaves the phone ([AndroidCalendarStore.removeOwnRows]). */
    private fun removeOurRows(context: Context, accounts: Array<Account>): Boolean = !permitted(context) || accounts.all { account ->
        runCatching { withStore(context, account, CalendarSyncPreferences(context, account)) { it.removeOwnRows() } }.getOrDefault(false)
    }.also { if (it) check(state(context).edit().putBoolean(CalendarSyncPreferences.OWN_ROWS, false).commit()) }
    /** Google's adapter would sync this account: Android's switches for it and master sync are on. */
    private fun syncOn(account: Account) = ContentResolver.getMasterSyncAutomatically() &&
        ContentResolver.getSyncAutomatically(account, CalendarSyncPreferences.AUTHORITY) &&
        ContentResolver.getIsSyncable(account, CalendarSyncPreferences.AUTHORITY) != 0
    /** The newest calendar row of the store, or of [account]: after the hand-over only Google's adapter creates calendars. */
    private fun newestCalendar(context: Context, account: Account? = null): Long = runCatching {
        requireNotNull(context.contentResolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID),
            account?.let { "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?" }, account?.let { arrayOf(it.name, it.type) },
            "${Calendars._ID} DESC")).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }.getOrDefault(-1L)

    /**
     * Decides and applies; returns who holds the slot now. Changes something only on a transition, so it is safe to call
     * often. Only the [hourly] check counts towards liveness. [beforeAside] schedules that check before ours disables itself,
     * so a process death cannot leave ours disabled with nothing to decide again. Stepping aside: ours stops its syncs and
     * disables its service, removes every calendar of its own (undone if that fails), checks that Android gave the slot to
     * Google's adapter, then requests a forced sync. Taking back restores ours to its manifest default and marks every account
     * for a rebuild; after Google's adapter did not take or use the slot, ours waits for a new version of its app.
     */
    @Synchronized fun evaluate(context: Context, hourly: Boolean = false, beforeAside: () -> Unit = {}): Owner {
        val accounts = AccountManager.get(context).getAccountsByType("com.google")
        val state = state(context)
        val aside = steppedAside(context)
        val can = googleCanSync(context) && !forceOurs()
        val version = googleVersion(context)
        val twoWay = accounts.firstOrNull { CalendarSyncPreferences(context, it).let { p -> p.enabled && (p.twoWay || p.uploadsHeld) } }
        val backingOff = !aside && state.contains("stalledVersion") && state.getLong("stalledVersion", -1) == version
        var stalled: Account? = null
        if (aside && can) {
            var checks = state.getInt("checks", 0)
            if (hourly && online(context) && accounts.any(::syncOn)) state.edit().putInt("checks", ++checks).apply()
            val since = state.getLong("asideNewest", 0)
            if (checks >= LIVENESS_CHECKS && permitted(context))
                stalled = accounts.firstOrNull { syncOn(it) && newestCalendar(context, it) in 0..since }
        }
        val outOfReach = !aside && can && rowsOutOfReach(context, accounts)
        var owner = if (stalled != null || outOfReach) Owner.OURS else decide(can, false, twoWay != null, backingOff)
        var why: Pair<String, String?>? = when {
            !can -> null
            twoWay != null -> "two_way" to twoWay.name
            backingOff -> reason(context)
            stalled != null -> "stalled" to stalled.name
            outOfReach -> "cleanup" to null
            else -> null
        }
        fun enable(state: Int) = context.packageManager.setComponentEnabledSetting(component(context), state, PackageManager.DONT_KILL_APP)
        fun takeBack() { enable(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT); accounts.forEach { CalendarSyncPreferences(context, it).takeBack() } }
        if (owner == Owner.GOOGLE && !aside) {
            beforeAside()
            // Ours first stops writing, then its rows leave; Google's adapter never sees a row of ours.
            for (account in accounts) {
                ContentResolver.cancelSync(account, CalendarSyncPreferences.AUTHORITY)
                // Only ours: the Google Calendar app registers its own periodic sync.
                ContentResolver.removePeriodicSync(account, CalendarSyncPreferences.AUTHORITY, CalendarSyncPreferences.periodicExtras())
            }
            enable(PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
            when {
                !removeOurRows(context, accounts) -> {
                    // Undone without a rebuild or sync request (no retry loop): a calendar that left lost its checkpoint.
                    enable(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                    accounts.map { CalendarSyncPreferences(context, it) }.filter { it.enabled }.forEach { it.schedulePeriodic() }
                    owner = Owner.OURS; why = "cleanup" to null
                }
                !claimed() -> {
                    takeBack(); owner = Owner.OURS; why = "unclaimed" to null
                    state.edit().putLong("stalledVersion", version).apply()
                }
                else -> {
                    state.edit().putInt("checks", 0).putLong("asideNewest", newestCalendar(context)).remove("stalledVersion").apply()
                    // Forced, so Google's adapter also refills what an earlier hand-back removed; never for an account that is off.
                    for (account in accounts) if (syncOn(account)) ContentResolver.requestSync(account, CalendarSyncPreferences.AUTHORITY, Bundle().apply {
                        putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true); putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                    })
                }
            }
        } else if (owner == Owner.OURS && aside) {
            if (stalled != null) state.edit().putLong("stalledVersion", version).apply()
            takeBack()
        }
        state.edit().putString("reason", why?.first).putString("reasonAccount", why?.second).apply()
        return owner
    }
}
