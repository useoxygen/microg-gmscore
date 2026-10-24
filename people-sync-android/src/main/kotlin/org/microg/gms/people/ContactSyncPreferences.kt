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
    val groupAdoptionFloor: Long? get() = if (prefs.contains(prefix + "groupAdoptionFloor")) prefs.getLong(prefix + "groupAdoptionFloor", Long.MAX_VALUE) else null
    fun initializeGroupFloor(floor: Long) {
        if (groupAdoptionFloor == null) check(prefs.edit().putLong(prefix + "groupAdoptionFloor", floor).commit())
    }
    val privateAdoptionFloor: Long get() = prefs.getLong(prefix + "privateAdoptionFloor", Long.MAX_VALUE)
    val privateGroupAdoptionFloor: Long get() = prefs.getLong(prefix + "privateGroupAdoptionFloor", Long.MAX_VALUE)
    fun initializePrivateFloors(contacts: Long, groups: Long) {
        val edit = prefs.edit()
        if (!prefs.contains(prefix + "privateAdoptionFloor")) edit.putLong(prefix + "privateAdoptionFloor", contacts)
        if (!prefs.contains(prefix + "privateGroupAdoptionFloor")) edit.putLong(prefix + "privateGroupAdoptionFloor", groups)
        check(edit.commit())
    }
    val privateLegacyCleanupComplete: Boolean get() = prefs.getBoolean(prefix + "privateLegacyCleanupComplete", false)
    fun completePrivateLegacyCleanup() {
        check(prefs.edit().putBoolean(prefix + "privateLegacyCleanupComplete", true)
            .remove(prefix + "privateGroupBinding").remove(prefix + "privateGroupCheckpoint").commit())
    }
    var groupCheckpoint: String?
        get() = prefs.getString(prefix + "groupCheckpoint-v1", null)
        set(value) { check(prefs.edit().putString(prefix + "groupCheckpoint-v1", value).commit()) }
    fun privateGroupCheckpoint(binding: String): String? = if (prefs.getString(prefix + "privateGroupBinding", null) == binding)
        prefs.getString(prefix + "privateGroupCheckpoint", null) else null
    fun privateGroupCheckpoint(binding: String, token: String) {
        check(prefs.edit().putString(prefix + "privateGroupBinding", binding).putString(prefix + "privateGroupCheckpoint", token).commit())
    }
    var blockedClientContext: String?
        get() = prefs.getString(prefix + "blockedClientContext", null)
        set(value) { check(prefs.edit().putString(prefix + "blockedClientContext", value).commit()) }
    var status: String
        get() = prefs.getString(prefix + "status", "")!!
        set(value) { prefs.edit().putString(prefix + "status", value).apply() }
    var diagnostic: String
        get() = prefs.getString(prefix + "diagnostic", "")!!
        set(value) { prefs.edit().putString(prefix + "diagnostic", value).apply() }
    var lastSuccess: Long
        get() = prefs.getLong(prefix + "lastSuccess", 0)
        set(value) { prefs.edit().putLong(prefix + "lastSuccess", value).apply() }

    fun configure(value: SyncMode, floor: Long, groupFloor: Long? = null) {
        // Pre-existing unowned account rows are never adopted by enabling sync.
        if (value != SyncMode.OFF && !prefs.contains(prefix + "adoptionFloor")) {
            check(prefs.edit().putLong(prefix + "adoptionFloor", floor).commit())
        }
        if (value != SyncMode.OFF && groupFloor != null) initializeGroupFloor(groupFloor)
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
        blockedClientContext = null // Explicit user refresh/enable permits one fresh attempt.
        ContentResolver.requestSync(account, AUTHORITY, Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        })
    }
    companion object { const val AUTHORITY = "com.android.contacts" }
}
