/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import org.microg.gms.people.sync.SyncMode

class ContactSyncPreferences(context: Context, val account: Account) {
    private val prefs = context.getSharedPreferences("contacts-sync", Context.MODE_PRIVATE)
    private val prefix = "${account.type}:${account.name}:"
    var mode: SyncMode
        get() = runCatching { SyncMode.valueOf(prefs.getString(prefix + "mode", "OFF")!!) }.getOrDefault(SyncMode.OFF)
        private set(value) { check(prefs.edit().putString(prefix + "mode", value.name).commit()) }
    val adoptionFloor: Long get() = prefs.getLong(prefix + "adoptionFloor", Long.MAX_VALUE)
    var status: String
        get() = prefs.getString(prefix + "status", "")!!
        set(value) { prefs.edit().putString(prefix + "status", value).apply() }
    var lastSuccess: Long
        get() = prefs.getLong(prefix + "lastSuccess", 0)
        set(value) { prefs.edit().putLong(prefix + "lastSuccess", value).apply() }

    fun configure(value: SyncMode, floor: Long) {
        // Pre-existing unowned account rows are never adopted by enabling sync.
        if (value != SyncMode.OFF && !prefs.contains(prefix + "adoptionFloor")) {
            check(prefs.edit().putLong(prefix + "adoptionFloor", floor).commit())
        }
        mode = value
        ContentResolver.setIsSyncable(account, AUTHORITY, if (value == SyncMode.OFF) 0 else 1)
        ContentResolver.setSyncAutomatically(account, AUTHORITY, value != SyncMode.OFF)
        if (value == SyncMode.OFF) {
            ContentResolver.cancelSync(account, AUTHORITY)
            ContentResolver.removePeriodicSync(account, AUTHORITY, Bundle.EMPTY)
        } else {
            ContentResolver.addPeriodicSync(account, AUTHORITY, Bundle.EMPTY, 3600)
            refresh()
        }
    }
    fun refresh() {
        if (mode == SyncMode.OFF) return
        ContentResolver.requestSync(account, AUTHORITY, Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        })
    }
    companion object { const val AUTHORITY = "com.android.contacts" }
}
