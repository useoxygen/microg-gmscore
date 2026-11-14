/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle

class CalendarSyncPreferences(private val context: Context, val account: Account) {
    private val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val prefix = "${account.type}:${account.name}:"
    /** The owner's saved choice, else the default: download only, on for every visible Google account (CYC-2073). */
    val enabled get() = prefs.getBoolean(prefix + "enabled", prefs.getBoolean(prefix + "defaultEnabled", false))
    /** Absence is different from an owner's saved off choice. Transfers must never overwrite either choice. The default is
     * stored apart ("defaultEnabled"), so it is never mistaken for an owner's choice. */
    val hasSavedChoice get() = prefs.contains(prefix + "enabled")
    /** On only by default: the owner has not chosen on this phone. */
    val onByDefault get() = !hasSavedChoice && enabled
    val twoWay get() = prefs.getBoolean(prefix + "twoWay", false)
    val uploadsHeld get() = prefs.getBoolean(prefix + "uploadsHeld", false)
    val uploadsAllowed get() = enabled && twoWay && !uploadsHeld
    val conflictResolutionGeneration get() = prefs.getLong(prefix + "conflictResolutionGeneration", 0)
    val conflictKeepLocal get() = prefs.getBoolean(prefix + "conflictKeepLocal", false)
    fun resolveConflicts(keepLocal: Boolean) {
        check(prefs.edit().putLong(prefix + "conflictResolutionGeneration", conflictResolutionGeneration + 1)
            .putBoolean(prefix + "conflictKeepLocal", keepLocal).commit())
        refresh()
    }
    fun beginMigration() { check(prefs.edit().putBoolean(prefix + "uploadsHeld", true).commit()) }
    /** Only the owner-facing settings screen releases a migration hold. */
    fun ownerResumeUploads() { check(prefs.edit().putBoolean(prefix + "uploadsHeld", false).commit()); refresh() }
    var status: String
        get() = prefs.getString(prefix + "status", "")!!
        set(value) { prefs.edit().putString(prefix + "status", value).apply() }
    var lastSuccess: Long
        get() = prefs.getLong(prefix + "lastSuccess", 0)
        set(value) { prefs.edit().putLong(prefix + "lastSuccess", value).apply() }
    fun configure(value: Boolean, twoWay: Boolean = this.twoWay) {
        check(prefs.edit().putBoolean(prefix + "enabled", value).putBoolean(prefix + "twoWay", twoWay).commit())
        schedule(value)
        if (value) refresh()
    }
    /**
     * Records the default for an account without a saved choice, once: download only, scheduled like turning sync on. Android
     * requests the first sync itself when the account becomes syncable, following master sync. A saved choice, including off,
     * is never changed; nor is a default already recorded (Android's own account switch may have turned it off since). An
     * account Android marks not syncable for the authority (0, which only an explicit choice sets; auto-initialization sets 1)
     * gets no default and keeps that state. The caller leaves out Cyclon's device account.
     */
    fun ensureDefaults() {
        if (hasSavedChoice || prefs.contains(prefix + "defaultEnabled")) return
        if (ContentResolver.getIsSyncable(account, AUTHORITY) == 0) return
        check(prefs.edit().putBoolean(prefix + "defaultEnabled", true).commit())
        schedule(true)
    }
    /** Android's sync settings follow the choice. While Google Calendar's own adapter holds the slot they are its settings too,
     * so they stay as they are (CYC-2073). */
    private fun schedule(on: Boolean) {
        if (CalendarHandover.steppedAside(context)) return
        ContentResolver.setIsSyncable(account, AUTHORITY, if (on) 1 else 0)
        ContentResolver.setSyncAutomatically(account, AUTHORITY, on)
        if (on) schedulePeriodic() else {
            ContentResolver.cancelSync(account, AUTHORITY)
            ContentResolver.removePeriodicSync(account, AUTHORITY, Bundle.EMPTY)
            ContentResolver.removePeriodicSync(account, AUTHORITY, periodicExtras())
        }
    }
    /** Hourly, with a marker extra: the Google Calendar app removes a periodic sync with empty extras for this authority when
     * it is opened. Replaces the empty-extras schedule earlier builds registered. Called again by every sync ours runs. */
    fun schedulePeriodic() {
        ContentResolver.removePeriodicSync(account, AUTHORITY, Bundle.EMPTY)
        ContentResolver.addPeriodicSync(account, AUTHORITY, periodicExtras(), 3600)
    }
    /** Ours holds the slot again after Google Calendar's adapter: rebuild on the next sync and schedule as before. Android's
     * own account switch stays as the owner left it; only an account Android has never asked about becomes syncable. */
    fun takeBack() {
        rebuild = true
        if (!enabled) return
        if (ContentResolver.getIsSyncable(account, AUTHORITY) < 0) {
            ContentResolver.setIsSyncable(account, AUTHORITY, 1)
            ContentResolver.setSyncAutomatically(account, AUTHORITY, true)
        }
        schedulePeriodic()
        ContentResolver.requestSync(account, AUTHORITY, Bundle.EMPTY)
    }
    /** The first sync after Google Calendar's adapter gave the slot back rebuilds this account from a full download. */
    var rebuild: Boolean
        get() = prefs.getBoolean(prefix + "rebuild", false)
        set(value) { check(prefs.edit().putBoolean(prefix + "rebuild", value).commit()) }
    /** Android's own switch for this account and authority is on (or not yet initialized). It is per account and authority,
     * not per adapter: while Google Calendar's adapter holds the slot, an account that is off here is off for it too. */
    val accountSyncOn: Boolean get() = ContentResolver.getSyncAutomatically(account, AUTHORITY) &&
        ContentResolver.getIsSyncable(account, AUTHORITY) != 0
    /** Google Calendar's own adapter keeps this account in sync (CYC-2073). */
    val googleSyncs: Boolean get() = CalendarHandover.steppedAside(context) && accountSyncOn
    /** Changes left after Google Calendar's adapter handed back that it had not uploaded; ours does not upload them. */
    var strandedChanges: Int
        get() = prefs.getInt(prefix + "strandedChanges", 0)
        set(value) { prefs.edit().putInt(prefix + "strandedChanges", value).apply() }
    /** On here, but Android's own switch for this account (Settings › Accounts › Account sync) stops it running. */
    val androidSyncOff: Boolean get() = enabled &&
        (!ContentResolver.getSyncAutomatically(account, AUTHORITY) || ContentResolver.getIsSyncable(account, AUTHORITY) <= 0)
    /** The owner's "Turn sync back on": schedules this account again as turning sync on does, keeping two-way. */
    fun resumeAndroidSync() {
        if (!enabled) return
        ContentResolver.setIsSyncable(account, AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(account, AUTHORITY, true)
        schedulePeriodic()
        refresh()
    }
    fun refresh() {
        if (enabled) ContentResolver.requestSync(account, AUTHORITY, Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        })
    }
    /** Transfer completion follows automatic sync settings; it does not simulate an owner pressing Refresh. */
    fun refreshAfterTransfer() {
        if (enabled && ContentResolver.getMasterSyncAutomatically() && ContentResolver.getSyncAutomatically(account, AUTHORITY))
            ContentResolver.requestSync(account, AUTHORITY, Bundle.EMPTY)
    }
    companion object {
        const val AUTHORITY = "com.android.calendar"
        const val PREFERENCES = "calendar-sync"
        const val PERIODIC = "cyclon_calendar_periodic"
        fun periodicExtras() = Bundle().apply { putBoolean(PERIODIC, true) }
        /** Removal must withdraw the old opt-in, even if the same account is added again later. */
        fun forgetRemovedAccounts(context: Context) {
            val accounts = AccountManager.get(context).accounts.toSet()
            val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val keys = prefs.all.keys
            val removedPrefixes = keys.filter { key -> listOf(":enabled", ":defaultEnabled", ":uploadsHeld", ":rebuild").any(key::endsWith) }.mapNotNull { key ->
                val identity = key.substringBeforeLast(':')
                val separator = identity.indexOf(':')
                if (separator <= 0 || separator == identity.lastIndex) null else {
                    val account = Account(identity.substring(separator + 1), identity.substring(0, separator))
                    (identity + ":").takeIf { account !in accounts }
                }
            }
            if (removedPrefixes.isEmpty()) return
            val editor = prefs.edit()
            keys.filter { key -> removedPrefixes.any(key::startsWith) }.forEach(editor::remove)
            check(editor.commit())
        }
    }
}
