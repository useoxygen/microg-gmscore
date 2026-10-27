/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.accounts.Account
import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import org.microg.gms.people.sync.Person

/** Synthetic host fixture: real SQL selections and provider batches, not the device ContactsProvider. */
internal class ImportContactsProvider : ContentProvider() {
    private val database = SQLiteDatabase.create(null).apply {
        execSQL("""CREATE TABLE raw_contacts (
            _id INTEGER PRIMARY KEY AUTOINCREMENT, account_name TEXT, account_type TEXT, data_set TEXT,
            sourceid TEXT, sync1 TEXT, sync2 TEXT, sync3 TEXT, sync4 TEXT,
            version INTEGER NOT NULL DEFAULT 1, dirty INTEGER NOT NULL DEFAULT 0,
            deleted INTEGER NOT NULL DEFAULT 0, starred INTEGER NOT NULL DEFAULT 0,
            raw_contact_is_read_only INTEGER NOT NULL DEFAULT 0, contact_id INTEGER,
            aggregation_mode INTEGER NOT NULL DEFAULT 0)
        """.trimIndent())
        execSQL("""CREATE TABLE data (
            _id INTEGER PRIMARY KEY AUTOINCREMENT, raw_contact_id INTEGER NOT NULL, mimetype TEXT,
            is_primary INTEGER NOT NULL DEFAULT 0, is_super_primary INTEGER NOT NULL DEFAULT 0,
            is_read_only INTEGER NOT NULL DEFAULT 0, data_version INTEGER NOT NULL DEFAULT 0,
            data_sync1 TEXT, data_sync2 TEXT, data_sync3 TEXT, data_sync4 TEXT, photo_file_id INTEGER,
            ${ (1..15).joinToString(", ") { "data$it" } })
        """.trimIndent())
        execSQL("""CREATE TABLE groups (
            _id INTEGER PRIMARY KEY AUTOINCREMENT, account_name TEXT, account_type TEXT, data_set TEXT,
            sourceid TEXT, sync1 TEXT, sync2 TEXT, sync3 TEXT, sync4 TEXT,
            version INTEGER NOT NULL DEFAULT 1, dirty INTEGER NOT NULL DEFAULT 0,
            deleted INTEGER NOT NULL DEFAULT 0, title TEXT, notes TEXT, system_id TEXT,
            favorites INTEGER NOT NULL DEFAULT 0, auto_add INTEGER NOT NULL DEFAULT 0,
            group_visible INTEGER NOT NULL DEFAULT 1, should_sync INTEGER NOT NULL DEFAULT 1,
            group_is_read_only INTEGER NOT NULL DEFAULT 0)
        """.trimIndent())
        execSQL("""CREATE TABLE syncstate (
            _id INTEGER PRIMARY KEY AUTOINCREMENT, account_name TEXT, account_type TEXT, data BLOB,
            UNIQUE (account_name, account_type))
        """.trimIndent())
        execSQL("""CREATE TABLE settings (
            _id INTEGER PRIMARY KEY AUTOINCREMENT, account_name TEXT, account_type TEXT, data_set TEXT,
            should_sync INTEGER, ungrouped_visible INTEGER)
        """.trimIndent())
    }

    /** Runs outside the batch transaction so a simulated concurrent edit survives rollback. */
    var beforeBatch: (() -> Unit)? = null
    val rawIds: List<Long> get() = ids("raw_contacts", null, null)

    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null

    private fun table(uri: Uri): String = uri.pathSegments.first().also {
        require(it in setOf("raw_contacts", "data", "groups", "syncstate", "settings"))
    }
    private fun selection(uri: Uri, where: String?): String? = uri.pathSegments.getOrNull(1)?.toLongOrNull()?.let {
        if (where.isNullOrBlank()) "_id=$it" else "_id=$it AND ($where)"
    } ?: where
    private fun syncAdapter(uri: Uri) = uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER) == "true"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor = database.query(
        table(uri), projection, selection(uri, selection), selectionArgs, null, null, sortOrder)

    private fun ids(table: String, where: String?, args: Array<out String>?): List<Long> =
        database.query(table, arrayOf("_id"), where, args, null, null, "_id ASC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
    private fun parents(where: String?, args: Array<out String>?): Set<Long> =
        database.query("data", arrayOf(Data.RAW_CONTACT_ID), where, args, null, null, null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
    private fun bumpRaw(ids: Collection<Long>, native: Boolean) {
        if (ids.isEmpty()) return
        database.execSQL("UPDATE raw_contacts SET version=version+1${if (native) ", dirty=1" else ""} WHERE _id IN (${ids.joinToString(",")})")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val table = table(uri)
        val inserted = ContentValues(requireNotNull(values))
        if (table == "raw_contacts" && !syncAdapter(uri) && !inserted.containsKey(RawContacts.DIRTY)) inserted.put(RawContacts.DIRTY, 1)
        val id = if (table == "syncstate") database.insertWithOnConflict(table, null, inserted, SQLiteDatabase.CONFLICT_REPLACE)
            else database.insertOrThrow(table, null, inserted)
        check(id >= 0)
        if (table == "data") bumpRaw(listOf(inserted.getAsLong(Data.RAW_CONTACT_ID)), !syncAdapter(uri))
        return ContentUris.withAppendedId(uri, id)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val table = table(uri)
        val where = selection(uri, selection)
        val oldParents = if (table == "data") parents(where, selectionArgs) else emptySet()
        val dataIds = if (table == "data") ids(table, where, selectionArgs) else emptyList()
        val changedRows = if (table == "raw_contacts" || table == "groups") ids(table, where, selectionArgs) else emptyList()
        val updated = database.update(table, requireNotNull(values), where, selectionArgs)
        if (table == "data") {
            if (updated > 0) {
                database.execSQL("UPDATE data SET data_version=data_version+1 WHERE _id IN (${dataIds.joinToString(",")})")
                val newParent = values.getAsLong(Data.RAW_CONTACT_ID)
                bumpRaw(oldParents + listOfNotNull(newParent), !syncAdapter(uri))
            }
        } else if (table == "raw_contacts") bumpRaw(changedRows, !syncAdapter(uri))
        else if (table == "groups" && changedRows.isNotEmpty()) {
            database.execSQL("UPDATE groups SET version=version+1${if (!syncAdapter(uri)) ", dirty=1" else ""} WHERE _id IN (${changedRows.joinToString(",")})")
        }
        return updated
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val table = table(uri)
        val where = selection(uri, selection)
        if (table == "raw_contacts") {
            val ids = ids(table, where, selectionArgs)
            if (!syncAdapter(uri)) {
                val count = database.update(table, ContentValues().apply { put(RawContacts.DELETED, 1); put(RawContacts.DIRTY, 1) }, where, selectionArgs)
                bumpRaw(ids, true)
                return count
            }
            if (ids.isNotEmpty()) database.delete("data", "raw_contact_id IN (${ids.joinToString(",")})", null)
        }
        val oldParents = if (table == "data") parents(where, selectionArgs) else emptySet()
        val count = database.delete(table, where, selectionArgs)
        if (table == "data") bumpRaw(oldParents, !syncAdapter(uri))
        return count
    }

    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
        beforeBatch?.invoke()
        database.beginTransaction()
        try {
            val result = super.applyBatch(operations)
            database.setTransactionSuccessful()
            return result
        } finally { database.endTransaction() }
    }

    fun addRaw(account: Account?, source: String? = null, owner: String? = null, dirty: Boolean = true,
        dataset: String? = null, deleted: Boolean = false): Long = ContentUris.parseId(insert(
        RawContacts.CONTENT_URI.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(),
        ContentValues().apply {
            put(RawContacts.ACCOUNT_NAME, account?.name); put(RawContacts.ACCOUNT_TYPE, account?.type)
            put(RawContacts.DATA_SET, dataset); put(RawContacts.SOURCE_ID, source); put(RawContacts.SYNC4, owner)
            put(RawContacts.DIRTY, if (dirty) 1 else 0); put(RawContacts.DELETED, if (deleted) 1 else 0)
        }))

    fun addFields(id: Long, person: Person, native: Boolean = false) {
        val uri = if (native) Data.CONTENT_URI else Data.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
        for (row in ContactFieldMapper.rows(person)) insert(uri, ContentValues(row).apply { put(Data.RAW_CONTACT_ID, id) })
    }

    override fun shutdown() { database.close(); super.shutdown() }
}
