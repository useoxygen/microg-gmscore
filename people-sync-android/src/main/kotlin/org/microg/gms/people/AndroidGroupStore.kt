/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.provider.ContactsContract
import android.provider.ContactsContract.Groups
import org.json.JSONObject
import org.microg.gms.people.sync.*

class AndroidGroupStore(private val provider: ContentProviderClient, private val account: Account,
    private val prefs: ContactSyncPreferences) : GroupStore {
    private val scope = "${Groups.ACCOUNT_NAME}=? AND ${Groups.ACCOUNT_TYPE}=? AND ${Groups.DATA_SET} IS NULL"
    private val args get() = arrayOf(account.name, account.type)
    private fun uri() = Groups.CONTENT_URI.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
    private fun selection(id: Long) = "$scope AND ${Groups._ID}=$id"
    private fun assertion(group: LocalGroup) = ContentProviderOperation.newAssertQuery(uri())
        .withSelection(selection(group.id) + " AND ${Groups.VERSION}=?", args + group.version.toString()).withExpectedCount(1).build()
    fun initialize() {
        check(prefs.mode != SyncMode.OFF)
        if (prefs.groupAdoptionFloor == null) {
            val floor = provider.query(Groups.CONTENT_URI, arrayOf(Groups._ID), null, null, "${Groups._ID} DESC")!!.use { if (it.moveToFirst()) it.getLong(0) else 0L }
            prefs.initializeGroupFloor(floor)
        }
        provider.update(uri(), ContentValues().apply { put(Groups.GROUP_IS_READ_ONLY, if (prefs.mode == SyncMode.TWO_WAY) 0 else 1) },
            "$scope AND ${Groups.SYNC4}=? AND ${Groups.SYSTEM_ID} IS NULL", args + AndroidContactStore.OWNER)
    }
    override fun groupCheckpoint() = prefs.groupCheckpoint
    override fun groupCheckpoint(token: String) { check(prefs.mode != SyncMode.OFF); prefs.groupCheckpoint = token }
    override fun groups(): List<LocalGroup> = provider.query(Groups.CONTENT_URI, null,
        "$scope AND (${Groups.SYNC4}=? OR (${Groups.SYNC4} IS NULL AND ${Groups.SOURCE_ID} IS NULL AND ${Groups.SYSTEM_ID} IS NULL AND ${Groups._ID}>? AND ${Groups.DIRTY}=1))",
        args + arrayOf(AndroidContactStore.OWNER, (prefs.groupAdoptionFloor ?: Long.MAX_VALUE).toString()), "${Groups._ID} ASC")!!.use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                fun text(column: String) = cursor.getString(cursor.getColumnIndexOrThrow(column))
                fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                val system = text(Groups.SYSTEM_ID) != null
                add(LocalGroup(number(Groups._ID), number(Groups.VERSION), text(Groups.SOURCE_ID), text(Groups.SYNC1),
                    if (system) text(Groups.SYNC2).orEmpty() else text(Groups.TITLE).orEmpty(), text(Groups.SYNC2),
                    number(Groups.DIRTY) != 0L, number(Groups.DELETED) != 0L, system, text(Groups.SYNC3)?.let(::JSONObject)))
            }
        }
    }
    private fun values(group: ContactGroup) = ContentValues().apply {
        put(Groups.ACCOUNT_NAME, account.name); put(Groups.ACCOUNT_TYPE, account.type)
        put(Groups.SOURCE_ID, group.resource); put(Groups.SYNC1, group.etag); put(Groups.SYNC2, group.name)
        putNull(Groups.SYNC3); put(Groups.SYNC4, AndroidContactStore.OWNER)
        put(Groups.TITLE, group.json.optString("formattedName").ifBlank { group.name })
        if (group.system) put(Groups.SYSTEM_ID, group.name) else putNull(Groups.SYSTEM_ID)
        put(Groups.AUTO_ADD, if (group.resource == MY_CONTACTS) 1 else 0)
        put(Groups.FAVORITES, if (group.resource == STARRED) 1 else 0)
        put(Groups.GROUP_IS_READ_ONLY, if (group.system || prefs.mode != SyncMode.TWO_WAY) 1 else 0)
        put(Groups.GROUP_VISIBLE, if (!group.system || group.resource in setOf(MY_CONTACTS, STARRED)) 1 else 0)
        put(Groups.SHOULD_SYNC, 1); put(Groups.DIRTY, 0)
    }
    override fun applyGroup(group: ContactGroup, existing: LocalGroup?) {
        check(prefs.mode != SyncMode.OFF); check(!group.deleted)
        val operations = arrayListOf<ContentProviderOperation>()
        if (existing != null) operations.add(assertion(existing))
        operations.add(if (existing == null) ContentProviderOperation.newInsert(uri()).withValues(values(group)).build()
            else ContentProviderOperation.newUpdate(uri()).withSelection(selection(existing.id), args).withValues(values(group)).withExpectedCount(1).build())
        provider.applyBatch(operations)
    }
    override fun removeGroup(existing: LocalGroup) {
        check(prefs.mode != SyncMode.OFF)
        val members = provider.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.RAW_CONTACT_ID),
            "${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID}=?",
            arrayOf(ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, existing.id.toString()), null)!!.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        val operations = arrayListOf(assertion(existing))
        val ids = members.distinct()
        if (ids.isNotEmpty()) {
            // IDs are provider longs, not caller-supplied SQL. One assertion
            // avoids a Binder operation and query for every member.
            operations.add(ContentProviderOperation.newAssertQuery(ContactsContract.RawContacts.CONTENT_URI)
                .withSelection("${ContactsContract.RawContacts._ID} IN (${ids.joinToString(",")}) AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.DATA_SET} IS NULL AND ${ContactsContract.RawContacts.SYNC4}=? AND ${ContactsContract.RawContacts.DIRTY}=0 AND ${ContactsContract.RawContacts.SYNC3} IS NULL",
                    arrayOf(account.name, account.type, AndroidContactStore.OWNER)).withExpectedCount(ids.size).build())
        }
        // Also refuse a member added after discovery. The check and cascading
        // delete execute in one provider transaction, without yielding.
        val unexpected = if (ids.isEmpty()) "" else " AND ${ContactsContract.Data.RAW_CONTACT_ID} NOT IN (${ids.joinToString(",")})"
        operations.add(ContentProviderOperation.newAssertQuery(ContactsContract.Data.CONTENT_URI)
            .withSelection("${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID}=?$unexpected",
                arrayOf(ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, existing.id.toString())).withExpectedCount(0).build())
        operations.add(ContentProviderOperation.newDelete(uri())
            .withSelection(selection(existing.id), args).withExpectedCount(1).build())
        try { provider.applyBatch(operations) }
        catch (error: android.content.OperationApplicationException) { throw GroupInUse() }
    }
    override fun beginGroup(existing: LocalGroup, pending: JSONObject): LocalGroup {
        check(prefs.mode == SyncMode.TWO_WAY && !existing.system)
        provider.applyBatch(arrayListOf(assertion(existing), ContentProviderOperation.newUpdate(uri())
            .withSelection(selection(existing.id), args).withValue(Groups.SYNC3, pending.toString())
            .withValue(Groups.SYNC4, AndroidContactStore.OWNER).withExpectedCount(1).build()))
        val current = groups().single { it.id == existing.id }
        if (current.name != existing.name || current.deleted != existing.deleted) {
            rejectGroup(current); throw IllegalStateException("Group changed before upload")
        }
        return current
    }
    override fun rejectGroup(existing: LocalGroup) {
        provider.update(uri(), ContentValues().apply { putNull(Groups.SYNC3) }, selection(existing.id), args)
    }
    override fun acknowledgeGroup(existing: LocalGroup, remote: ContactGroup?) {
        check(prefs.mode != SyncMode.OFF)
        val current = groups().single { it.id == existing.id }
        if (remote == null) {
            if (current.version == existing.version && current.deleted) removeGroup(current)
            else throw IllegalStateException("Group changed during deletion")
        } else if (current.version == existing.version) applyGroup(remote, current)
        else {
            val sent = existing.pending?.optString("localName") ?: existing.name
            val title = if (current.name != sent) current.name else remote.name
            val values = values(remote).apply { put(Groups.TITLE, title); put(Groups.DIRTY, if (current.deleted || title != remote.name) 1 else 0) }
            provider.applyBatch(arrayListOf(assertion(current), ContentProviderOperation.newUpdate(uri())
                .withSelection(selection(current.id), args).withValues(values).withExpectedCount(1).build()))
        }
    }
    fun id(resource: String): Long? = provider.query(Groups.CONTENT_URI, arrayOf(Groups._ID),
        "$scope AND ${Groups.SOURCE_ID}=? AND ${Groups.SYNC4}=? AND ${Groups.DELETED}=0",
        args + arrayOf(resource, AndroidContactStore.OWNER), null)!!.use { if (it.count > 1) throw UnreadableContact(); if (it.moveToFirst()) it.getLong(0) else null }
    fun isDeleted(id: Long): Boolean = provider.query(Groups.CONTENT_URI, arrayOf(Groups.DELETED), selection(id), args, null)!!.use {
        it.moveToFirst() && it.getInt(0) != 0
    }
    fun resource(id: Long): String? = provider.query(Groups.CONTENT_URI, arrayOf(Groups.SOURCE_ID),
        selection(id) + " AND ${Groups.SYNC4}=? AND ${Groups.DELETED}=0", args + AndroidContactStore.OWNER, null)!!.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    fun resolve(resource: String, api: GroupsApi): Long {
        id(resource)?.let { return it }
        val existing = groups().singleOrNull { it.resource == resource }
        if (existing?.dirty == true || existing?.pending != null) throw UnreadableContact()
        val remote = api.getGroup(resource)?.takeUnless { it.deleted } ?: throw UnreadableContact()
        applyGroup(remote, existing)
        return id(resource) ?: throw UnreadableContact()
    }
}
