/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar

import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle

class CalendarSyncPreferences(context: Context, val account: Account) {
    private val prefs = context.getSharedPreferences("calendar-sync", Context.MODE_PRIVATE)
    private val prefix = "${account.type}:${account.name}:"
    val enabled get() = prefs.getBoolean(prefix + "enabled", false)
    var status: String
        get() = prefs.getString(prefix + "status", "")!!
        set(value) { prefs.edit().putString(prefix + "status", value).apply() }
    var lastSuccess: Long
        get() = prefs.getLong(prefix + "lastSuccess", 0)
        set(value) { prefs.edit().putLong(prefix + "lastSuccess", value).apply() }
    fun checkpoint(calendar: String): String? = prefs.getString(prefix + "checkpoint:" + calendar, null)
    fun checkpoint(calendar: String, value: String) {
        check(enabled)
        check(prefs.edit().putString(prefix + "checkpoint:" + calendar, value).commit())
    }
    fun configure(value: Boolean) {
        check(prefs.edit().putBoolean(prefix + "enabled", value).commit())
        ContentResolver.setIsSyncable(account, AUTHORITY, if (value) 1 else 0)
        ContentResolver.setSyncAutomatically(account, AUTHORITY, value)
        if (value) {
            ContentResolver.addPeriodicSync(account, AUTHORITY, Bundle.EMPTY, 3600)
            refresh()
        } else {
            ContentResolver.cancelSync(account, AUTHORITY)
            ContentResolver.removePeriodicSync(account, AUTHORITY, Bundle.EMPTY)
        }
    }
    fun refresh() {
        if (enabled) ContentResolver.requestSync(account, AUTHORITY, Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        })
    }
    companion object { const val AUTHORITY = "com.android.calendar" }
}
