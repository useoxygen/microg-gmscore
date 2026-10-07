/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar

import android.accounts.Account
import android.accounts.AccountManager
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
    companion object {
        const val AUTHORITY = "com.android.calendar"
        /** Removal must withdraw the old opt-in, even if the same account is added again later. */
        fun forgetRemovedAccounts(context: Context) {
            val accounts = AccountManager.get(context).accounts.toSet()
            val prefs = context.getSharedPreferences("calendar-sync", Context.MODE_PRIVATE)
            val keys = prefs.all.keys
            val removedPrefixes = keys.filter { it.endsWith(":enabled") }.mapNotNull { key ->
                val identity = key.removeSuffix(":enabled")
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
