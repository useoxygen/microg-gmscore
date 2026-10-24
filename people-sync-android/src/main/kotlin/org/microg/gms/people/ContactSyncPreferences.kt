/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import org.microg.gms.people.sync.SyncMode

class ContactSyncPreferences(private val context: Context, val account: Account) {
    private val prefs = context.getSharedPreferences("contacts-sync", Context.MODE_PRIVATE)
    private val prefix = "${account.type}:${account.name}:"
    var mode: SyncMode
        get() = runCatching { SyncMode.valueOf(prefs.getString(prefix + "mode", "TWO_WAY")!!) }.getOrDefault(SyncMode.OFF)
        private set(value) { check(prefs.edit().putString(prefix + "mode", value.name).commit()) }
    // Customer behavior follows Google: account-scoped new/imported contacts upload normally.
    // A developer can opt in to narrower fixture controls without changing the customer default.
    var uploadNewContacts: Boolean
        get() = !ContactMigrationPrivacy(context).developerControlsEnabled || prefs.getBoolean(prefix + "uploadNewContacts", true)
        set(value) { check(prefs.edit().putBoolean(prefix + "uploadNewContacts", value).commit()) }
    val adoptionFloor: Long get() = if (ContactMigrationPrivacy(context).developerControlsEnabled)
        prefs.getLong(prefix + "adoptionFloor", 0) else 0
    var status: String
        get() = prefs.getString(prefix + "status", "")!!
        set(value) { prefs.edit().putString(prefix + "status", value).apply() }
    var lastSuccess: Long
        get() = prefs.getLong(prefix + "lastSuccess", 0)
        set(value) { prefs.edit().putLong(prefix + "lastSuccess", value).apply() }

    fun ensureDefaults() {
        // Do not override a saved owner choice, including OFF/download-only.
        if (!prefs.contains(prefix + "mode")) configure(SyncMode.TWO_WAY, 0, requestImmediate = false)
    }
    fun configure(value: SyncMode, floor: Long, requestImmediate: Boolean = true) {
        // Preserve the optional developer fixture floor; normal Google behavior adopts account rows.
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
            // Account/boot initialization follows automatic sync and the master-sync switch.
            if (requestImmediate) refresh()
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
