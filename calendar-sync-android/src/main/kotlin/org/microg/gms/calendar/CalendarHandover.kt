/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Who keeps the shared calendar store (com.android.calendar) in sync for Google accounts (CYC-2073). Android gives that slot
 * to one sync adapter, and ours wins because it is in the system image. Where the Google Calendar app can sync, its own
 * adapter (guests, push, Google's conflict rules) does it instead: ours disables its sync adapter service, Android then gives
 * the slot to Google's, and ours stays the fallback for phones without that app. The owner's saved choices are not changed.
 */
object CalendarHandover {
    const val GOOGLE_CALENDAR = "com.google.android.calendar"
    const val SERVICE = "org.microg.gms.calendar.CalendarSyncService"
    /** Kill switch, "1" keeps ours: debug.* the shell can set today, until reboot; persist.* survives a reboot once Cyclon's
     * sepolicy lets the shell set it and microG read it. Read at every evaluation. */
    val FORCE_OURS = listOf("persist.cyclon.calendar.own_sync", "debug.cyclon.calendar_own_sync")
    /** WAITING: Google's adapter could sync, but ours still has unsent changes or a transfer hold, so ours keeps the slot. */
    enum class Owner { OURS, GOOGLE, WAITING }

    /** Google's adapter syncs only while its app is installed and enabled and holds both calendar permissions. */
    fun googleCanSync(installed: Boolean, enabled: Boolean, read: Boolean, write: Boolean) = installed && enabled && read && write
    fun decide(googleCanSync: Boolean, forceOurs: Boolean, unsent: Boolean): Owner = when {
        !googleCanSync || forceOurs -> Owner.OURS
        unsent -> Owner.WAITING
        else -> Owner.GOOGLE
    }

    private fun component(context: Context) = ComponentName(context.packageName, SERVICE)
    /** Ours has stepped aside: its sync adapter service is disabled. Readable from any process. */
    fun steppedAside(context: Context): Boolean = runCatching {
        context.packageManager.getComponentEnabledSetting(component(context)) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }.getOrDefault(false)
    /** The last evaluation found Google's adapter able to sync, but ours waits for its unsent changes. */
    fun waiting(context: Context): Boolean = !steppedAside(context) &&
        context.getSharedPreferences(CalendarSyncPreferences.PREFERENCES, Context.MODE_PRIVATE).getString("handover", "") == "waiting"

    fun googleCanSync(context: Context): Boolean {
        val pm = context.packageManager
        val app = try {
            @Suppress("DEPRECATION") pm.getApplicationInfo(GOOGLE_CALENDAR, PackageManager.GET_DISABLED_COMPONENTS)
        } catch (_: PackageManager.NameNotFoundException) { null }
        fun granted(permission: String) = app != null && pm.checkPermission(permission, GOOGLE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        return googleCanSync(app != null, app?.enabled == true, granted(Manifest.permission.READ_CALENDAR), granted(Manifest.permission.WRITE_CALENDAR))
    }

    fun forceOurs(): Boolean = FORCE_OURS.any { name ->
        runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
                .invoke(null, name, "") as String == "1"
        }.getOrDefault(false)
    }

    /** Never hand over an owner's unsent edit: two-way rows still dirty or journaled, a held conflict, or a transfer hold
     * (Google's adapter has no hold and would upload at once). Unreadable counts as unsent. */
    private fun unsent(context: Context, account: Account): Boolean {
        val prefs = CalendarSyncPreferences(context, account)
        if (!prefs.enabled) return false
        if (prefs.uploadsHeld) return true
        if (!prefs.twoWay) return false
        if (prefs.status == "conflict") return true
        return runCatching {
            val client = requireNotNull(context.contentResolver.acquireContentProviderClient(CalendarSyncPreferences.AUTHORITY))
            try { AndroidCalendarStore(client, account, prefs).hasUnsentChanges() } finally { @Suppress("DEPRECATION") client.release() }
        }.getOrDefault(true)
    }

    /**
     * Decides and applies; returns who holds the slot now. Changes something only on a transition, so it is safe to call
     * often. Stepping aside disables ours, removes its periodic sync and requests one sync, which Android gives to Google's
     * adapter. Taking back restores ours to its manifest default and marks every account for a rebuild.
     */
    @Synchronized fun evaluate(context: Context): Owner {
        val accounts = AccountManager.get(context).getAccountsByType("com.google")
        val can = googleCanSync(context)
        val force = forceOurs()
        val owner = decide(can, force, can && !force && accounts.any { unsent(context, it) })
        context.getSharedPreferences(CalendarSyncPreferences.PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("handover", owner.name.lowercase()).apply()
        val aside = steppedAside(context)
        if (owner == Owner.GOOGLE && !aside) {
            context.packageManager.setComponentEnabledSetting(component(context),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            for (account in accounts) {
                // Only ours: the Google Calendar app registers its own periodic sync.
                ContentResolver.removePeriodicSync(account, CalendarSyncPreferences.AUTHORITY, CalendarSyncPreferences.periodicExtras())
                ContentResolver.requestSync(account, CalendarSyncPreferences.AUTHORITY, Bundle.EMPTY)
            }
        } else if (owner != Owner.GOOGLE && aside) {
            context.packageManager.setComponentEnabledSetting(component(context),
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
            for (account in accounts) CalendarSyncPreferences(context, account).takeBack()
        }
        return owner
    }
}
