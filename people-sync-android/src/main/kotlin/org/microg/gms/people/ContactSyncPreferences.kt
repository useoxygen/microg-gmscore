/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import org.microg.gms.people.sync.SyncMode

class ContactSyncPreferences(internal val context: Context, val account: Account) {
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
    val groupAdoptionFloor: Long? get() = if (prefs.contains(prefix + "groupAdoptionFloor")) prefs.getLong(prefix + "groupAdoptionFloor", Long.MAX_VALUE) else null
    fun initializeGroupFloor(floor: Long) {
        if (groupAdoptionFloor == null) check(prefs.edit().putLong(prefix + "groupAdoptionFloor", floor).commit())
    }
    // Same policy as adoptionFloor: customers adopt the account's own new rows like Google; the
    // optional developer fixture controls keep the floor recorded when sync was enabled.
    val privateAdoptionFloor: Long get() = if (ContactMigrationPrivacy(context).developerControlsEnabled)
        prefs.getLong(prefix + "privateAdoptionFloor", 0) else 0
    val privateGroupAdoptionFloor: Long get() = if (ContactMigrationPrivacy(context).developerControlsEnabled)
        prefs.getLong(prefix + "privateGroupAdoptionFloor", 0) else 0
    fun initializePrivateFloors(contacts: Long, groups: Long) {
        val edit = prefs.edit()
        if (!prefs.contains(prefix + "privateAdoptionFloor")) edit.putLong(prefix + "privateAdoptionFloor", contacts)
        if (!prefs.contains(prefix + "privateGroupAdoptionFloor")) edit.putLong(prefix + "privateGroupAdoptionFloor", groups)
        check(edit.commit())
    }
    val privateLegacyCleanupComplete: Boolean get() = prefs.getBoolean(prefix + "privateLegacyCleanupComplete", false)
    // Unverified imports remain unowned. Cache only row/version pairs so an
    // unchanged foreign source ID does not force a full account read every hour.
    // The policy binding invalidates earlier name/style holds; negative values
    // distinguish unavailable/unrepresentable normalization from identity holds.
    fun importChecks(backend: String): Map<Long, Long> = runCatching {
        val json = org.json.JSONObject(prefs.getString(prefix + "importChecks:provider-v2:" + backend, "{}")!!)
        json.keys().asSequence().associate { it.toLong() to json.getLong(it) }
    }.getOrDefault(emptyMap())
    fun importChecks(backend: String, rows: Map<Long, Long>) {
        val json = org.json.JSONObject()
        rows.forEach { (id, version) -> json.put(id.toString(), version) }
        check(prefs.edit().putString(prefix + "importChecks:provider-v2:" + backend, json.toString()).commit())
    }
    fun completePrivateLegacyCleanup() {
        check(prefs.edit().putBoolean(prefix + "privateLegacyCleanupComplete", true)
            .remove(prefix + "privateGroupBinding").remove(prefix + "privateGroupCheckpoint").commit())
    }
    /** Turn-off removal still owed (e.g. a sync was stuck); retried when the settings screen opens. */
    var pendingRemoval: Boolean
        get() = prefs.getBoolean(prefix + "pendingRemoval", false)
        set(value) { check(prefs.edit().putBoolean(prefix + "pendingRemoval", value).commit()) }
    /** After synced rows were removed: the next enable starts with full contact and label downloads. */
    fun resetCheckpoints() {
        check(prefs.edit().remove(prefix + "groupCheckpoint-v1").remove(prefix + "privateGroupBinding")
            .remove(prefix + "privateGroupCheckpoint").putString(prefix + "status", "").commit())
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

    fun ensureDefaults() {
        // Do not override a saved owner choice, including OFF/download-only.
        if (!prefs.contains(prefix + "mode")) configure(SyncMode.TWO_WAY, 0, requestImmediate = false)
    }
    fun configure(value: SyncMode, floor: Long, groupFloor: Long? = null, requestImmediate: Boolean = true) {
        // Preserve the optional developer fixture floor; normal Google behavior adopts account rows.
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
            // Account/boot initialization follows automatic sync and the master-sync switch.
            if (requestImmediate) refresh()
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
