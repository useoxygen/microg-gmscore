/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import org.json.JSONObject
import org.microg.gms.people.sync.*

class AndroidContactStore(private val provider: ContentProviderClient, private val account: Account,
    private val prefs: ContactSyncPreferences) : ContactStore {
    companion object { const val OWNER = "microg-people-v1" }
    private val scope = "${RawContacts.ACCOUNT_NAME}=? AND ${RawContacts.ACCOUNT_TYPE}=? AND ${RawContacts.DATA_SET} IS NULL"
    private val args get() = arrayOf(account.name, account.type)
    private fun syncUri(uri: Uri) = uri.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
    private fun rawScope(id: Long) = "$scope AND ${RawContacts._ID}=$id"
    fun configureMode() {
        check(prefs.mode != SyncMode.OFF)
        val readOnly = if (prefs.mode == SyncMode.TWO_WAY) 0 else 1
        provider.update(syncUri(RawContacts.CONTENT_URI), ContentValues().apply {
            put(RawContacts.RAW_CONTACT_IS_READ_ONLY, readOnly)
        }, "$scope AND ${RawContacts.SYNC4}=? AND ${RawContacts.RAW_CONTACT_IS_READ_ONLY}!=?", args + arrayOf(OWNER, readOnly.toString()))
        val settings = ContentValues().apply {
            put(ContactsContract.Settings.ACCOUNT_NAME, account.name)
            put(ContactsContract.Settings.ACCOUNT_TYPE, account.type)
            put(ContactsContract.Settings.SHOULD_SYNC, 1)
            put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
        }
        if (provider.update(ContactsContract.Settings.CONTENT_URI, settings,
            "${ContactsContract.Settings.ACCOUNT_NAME}=? AND ${ContactsContract.Settings.ACCOUNT_TYPE}=? AND ${ContactsContract.Settings.DATA_SET} IS NULL", args) == 0) {
            provider.insert(ContactsContract.Settings.CONTENT_URI, settings)
        }
    }
    override fun checkpoint(): String? = ContactsContract.SyncState.get(provider, account)?.let { bytes ->
        val state = JSONObject(String(bytes, Charsets.UTF_8))
        if (state.optString("fields") == PeopleApi.READ_FIELDS && state.optInt("version") == 1) state.optString("token").takeIf { it.isNotBlank() } else null
    }
    override fun checkpoint(token: String) {
        check(prefs.mode != SyncMode.OFF)
        ContactsContract.SyncState.set(provider, account, JSONObject().put("version", 1)
            .put("fields", PeopleApi.READ_FIELDS).put("token", token).toString().toByteArray(Charsets.UTF_8))
    }
    override fun project(person: Person) = ContactFieldMapper.fields(ContactFieldMapper.rows(person))

    override fun contacts(): List<LocalContact> = provider.query(RawContacts.CONTENT_URI, null,
        "$scope AND (${RawContacts.SYNC4}=? OR (${RawContacts.SYNC4} IS NULL AND ${RawContacts.SOURCE_ID} IS NULL AND ${RawContacts._ID}>? AND ${RawContacts.DIRTY}=1))",
        args + arrayOf(OWNER, prefs.adoptionFloor.toString()), "${RawContacts._ID} ASC")!!.use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                fun text(column: String) = cursor.getString(cursor.getColumnIndexOrThrow(column))
                fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                val id = number(RawContacts._ID)
                val rows = provider.query(Data.CONTENT_URI, null, "${Data.RAW_CONTACT_ID}=?", arrayOf(id.toString()), "${Data._ID} ASC")!!
                    .use { ContactFieldMapper.readRows(it) }
                val unsupported = rows.any { !ContactFieldMapper.supported(it) }
                add(LocalContact(id, number(RawContacts.VERSION), text(RawContacts.SOURCE_ID), text(RawContacts.SYNC1),
                    ContactFieldMapper.fields(rows), text(RawContacts.SYNC2)?.let { ContactsSync.jsonFields(JSONObject(it)) }.orEmpty(),
                    number(RawContacts.DIRTY) != 0L, number(RawContacts.DELETED) != 0L,
                    text(RawContacts.SYNC3)?.let { JSONObject(it) }, unsupported))
            }
        }
    }
    private fun assertVersion(existing: LocalContact) = ContentProviderOperation.newAssertQuery(syncUri(RawContacts.CONTENT_URI))
        .withSelection(rawScope(existing.id) + " AND ${RawContacts.VERSION}=?", args + existing.version.toString())
        .withExpectedCount(1).build()
    private fun baseline(person: Person): String = JSONObject().apply {
        for ((key, value) in project(person)) put(key, org.json.JSONArray(value))
    }.toString()

    override fun apply(person: Person, existing: LocalContact?, readOnly: Boolean) {
        write(person, existing, readOnly, person, false)
    }
    private fun write(person: Person, existing: LocalContact?, readOnly: Boolean, remoteBaseline: Person, dirty: Boolean) {
        check(prefs.mode != SyncMode.OFF)
        val operations = arrayListOf<ContentProviderOperation>()
        existing?.let { operations.add(assertVersion(it)) }
        val rawIndex = operations.size
        val values = ContentValues().apply {
            put(RawContacts.ACCOUNT_NAME, account.name); put(RawContacts.ACCOUNT_TYPE, account.type)
            put(RawContacts.SOURCE_ID, person.resource); put(RawContacts.SYNC1, person.etag)
            put(RawContacts.SYNC2, baseline(remoteBaseline)); putNull(RawContacts.SYNC3); put(RawContacts.SYNC4, OWNER)
            put(RawContacts.DIRTY, if (dirty) 1 else 0); put(RawContacts.RAW_CONTACT_IS_READ_ONLY, if (readOnly) 1 else 0)
        }
        operations.add(if (existing == null) ContentProviderOperation.newInsert(syncUri(RawContacts.CONTENT_URI)).withValues(values).build()
            else ContentProviderOperation.newUpdate(syncUri(RawContacts.CONTENT_URI)).withSelection(rawScope(existing.id), args).withValues(values).withExpectedCount(1).build())
        if (existing != null) {
            operations.add(ContentProviderOperation.newDelete(syncUri(Data.CONTENT_URI)).withSelection(
                "${Data.RAW_CONTACT_ID}=? AND (${Data.MIMETYPE} IN (${ContactFieldMapper.specs.joinToString(",") { "?" }}) OR (${Data.MIMETYPE}=? AND ${android.provider.ContactsContract.CommonDataKinds.Event.TYPE}=?))",
                arrayOf(existing.id.toString()) + ContactFieldMapper.specs.map { it.mime } + arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
                    android.provider.ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString())).build())
        }
        for (row in ContactFieldMapper.rows(person)) {
            val operation = ContentProviderOperation.newInsert(syncUri(Data.CONTENT_URI)).withValues(row)
            if (existing == null) operation.withValueBackReference(Data.RAW_CONTACT_ID, rawIndex)
            else operation.withValue(Data.RAW_CONTACT_ID, existing.id)
            operations.add(operation.build())
        }
        provider.applyBatch(operations)
    }
    override fun remove(existing: LocalContact) {
        check(prefs.mode != SyncMode.OFF)
        provider.applyBatch(arrayListOf(assertVersion(existing), ContentProviderOperation.newDelete(syncUri(RawContacts.CONTENT_URI))
            .withSelection(rawScope(existing.id), args).withExpectedCount(1).build()))
    }
    override fun begin(existing: LocalContact, pending: JSONObject): LocalContact {
        check(prefs.mode == SyncMode.TWO_WAY)
        provider.applyBatch(arrayListOf(assertVersion(existing), ContentProviderOperation.newUpdate(syncUri(RawContacts.CONTENT_URI))
            .withSelection(rawScope(existing.id), args).withValue(RawContacts.SYNC3, pending.toString())
            .withValue(RawContacts.SYNC4, OWNER).withExpectedCount(1).build()))
        val current = contacts().single { it.id == existing.id }
        if (current.fields != existing.fields || current.deleted != existing.deleted) {
            rejected(current) // No network request has been dispatched yet.
            throw IllegalStateException("Contact changed before upload")
        }
        return current
    }
    override fun rejected(existing: LocalContact) {
        provider.update(syncUri(RawContacts.CONTENT_URI), ContentValues().apply { putNull(RawContacts.SYNC3) }, rawScope(existing.id), args)
    }
    override fun acknowledge(existing: LocalContact, remote: Person?) {
        val current = contacts().single { it.id == existing.id }
        if (remote == null) {
            // A concurrent edit must survive an acknowledged remote deletion.
            if (current.version == existing.version && current.deleted) remove(current)
            else throw IllegalStateException("Contact changed during deletion")
        } else if (current.version == existing.version) apply(remote, current, prefs.mode != SyncMode.TWO_WAY)
        else {
            val sent = existing.pending?.optJSONObject("local")?.let { ContactsSync.jsonFields(it) } ?: existing.fields
            val merged = JSONObject(remote.json.toString())
            for (field in PeopleApi.EDITABLE_FIELDS) {
                if ((current.fields[field] ?: "[]") != (sent[field] ?: "[]")) {
                    merged.put(field, org.json.JSONArray(current.fields[field] ?: "[]"))
                }
            }
            // Merge unchanged fields from the server, retain subsequent local
            // edits, and keep the actual server response as the next baseline.
            write(Person.parse(merged), current, prefs.mode != SyncMode.TWO_WAY, remote, true)
        }
    }
}
