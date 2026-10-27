/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts

/**
 * Contacts saved only on this phone ("Device", or no account on older Android), and moving them into
 * a Google account like Google's "Also sync device contacts". Each move is one provider transaction:
 * the Google copy is created and the device copy deleted together, so a contact is never lost or
 * duplicated. The copy is a new, unsynced contact of the account, so the next sync uploads it.
 * SIM contacts and other accounts (e.g. Exchange) are never touched. The same transfer brings never-uploaded
 * contacts back to the phone when sync is turned off ([keepOnPhone]).
 */
class DeviceContacts(private val provider: ContentProviderClient) {
    /** [supported] is false when the contact has details Google sync can't upload; such a contact stays on the phone. */
    data class Entry(val id: Long, val name: String?, val supported: Boolean)
    data class MoveResult(val moved: Int, val unsupported: Int)

    fun list(): List<Entry> = provider.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID, RawContacts.DISPLAY_NAME_PRIMARY),
        LOCAL, null, "${RawContacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED")?.use { c ->
        buildList { while (c.moveToNext()) add(Entry(c.getLong(0), c.getString(1), rows(c.getLong(0)).all(::uploadable))) }
    } ?: emptyList()

    fun count(): Int = provider.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID), LOCAL, null, null)?.use { it.count } ?: 0

    /**
     * Moves the given device contacts into [account]. [shouldContinue] is checked before each contact, so turning
     * sync off (or the opt-in) stops the move at once. Changed or vanished contacts are skipped; contacts with
     * details Google sync can't upload are left on the phone and counted as unsupported.
     */
    fun moveTo(account: Account, ids: Collection<Long>, shouldContinue: () -> Boolean = { true }): MoveResult {
        var moved = 0; var unsupported = 0
        for (id in ids) {
            if (!shouldContinue()) break
            when (runCatching { move(account, id) }.getOrDefault(Outcome.SKIPPED)) {
                Outcome.MOVED -> moved++
                Outcome.UNSUPPORTED -> unsupported++
                Outcome.SKIPPED -> {}
            }
        }
        return MoveResult(moved, unsupported)
    }

    private enum class Outcome { MOVED, UNSUPPORTED, SKIPPED }

    // Device groups don't exist in the Google account; the photo is copied in full size separately.
    private fun copied(row: ContentValues) = row.getAsString(Data.MIMETYPE) !in setOf(GroupMembership.CONTENT_ITEM_TYPE, Photo.CONTENT_ITEM_TYPE)
    // The same rule the sync uses: a contact with any other row would never be uploaded.
    private fun uploadable(row: ContentValues) = !copied(row) || ContactFieldMapper.supported(row)

    private fun rows(id: Long): List<ContentValues> =
        provider.query(Data.CONTENT_URI, DATA_COLUMNS, "${Data.RAW_CONTACT_ID}=?", arrayOf(id.toString()), null)?.use { c ->
            buildList { while (c.moveToNext()) add(values(c)) }
        } ?: emptyList()

    private fun move(account: Account, id: Long): Outcome {
        val rows = rows(id)
        if (!rows.all(::uploadable)) return Outcome.UNSUPPORTED
        return if (transfer(id, LOCAL, null, rows, account.name, account.type, deleteAsSyncAdapter = false)) Outcome.MOVED else Outcome.SKIPPED
    }

    /**
     * Turning sync off: a contact created on this phone that never reached Google is kept as a device contact
     * instead of being deleted with the synced ones. [selection] and [args] must identify it in its account.
     */
    fun keepOnPhone(id: Long, selection: String, args: Array<String>): Boolean =
        transfer(id, selection, args, rows(id), null, null, deleteAsSyncAdapter = true)

    /** One transaction: assert the source is unchanged, insert the copy (target null = device), delete the source. */
    private fun transfer(id: Long, selection: String, args: Array<String>?, rows: List<ContentValues>,
        targetName: String?, targetType: String?, deleteAsSyncAdapter: Boolean): Boolean {
        val source = ContentUris.withAppendedId(RawContacts.CONTENT_URI, id)
        val raw = provider.query(source, arrayOf(RawContacts.VERSION, RawContacts.STARRED, RawContacts.CUSTOM_RINGTONE, RawContacts.SEND_TO_VOICEMAIL),
            selection, args, null)?.use { if (it.moveToFirst()) values(it) else null } ?: return false
        val version = raw.getAsInteger(RawContacts.VERSION)
        raw.remove(RawContacts.VERSION)
        val ops = arrayListOf(
            // The source must still be the one that was read; else nothing happens.
            ContentProviderOperation.newAssertQuery(source).withSelection(selection, args).withValue(RawContacts.VERSION, version)
                .withExpectedCount(1).build(),
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI).withValues(raw)
                .withValue(RawContacts.ACCOUNT_NAME, targetName).withValue(RawContacts.ACCOUNT_TYPE, targetType).build())
        for (row in rows.filter(::copied))
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI).withValues(row).withValueBackReference(Data.RAW_CONTACT_ID, 1).build())
        photo(id)?.let { bytes ->
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 1)
                .withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE).withValue(Photo.PHOTO, bytes).withValue(Data.IS_SUPER_PRIMARY, 1).build())
        }
        // A sync-adapter delete removes an account row at once instead of leaving a tombstone to sync.
        val delete = if (!deleteAsSyncAdapter) source
            else source.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
        ops.add(ContentProviderOperation.newDelete(delete).withSelection(selection, args).withExpectedCount(1).build())
        provider.applyBatch(ops)
        return true
    }

    /** The full-size photo if the provider has one, else the thumbnail. */
    private fun photo(id: Long): ByteArray? {
        val display = runCatching {
            provider.openAssetFile(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id).buildUpon()
                .appendPath(RawContacts.DisplayPhoto.CONTENT_DIRECTORY).build(), "r")?.use { fd ->
                fd.createInputStream().use { it.readBytes() }.takeIf { it.isNotEmpty() && it.size <= MAX_PHOTO_BYTES }
            }
        }.getOrNull()
        return display ?: provider.query(Data.CONTENT_URI, arrayOf(Photo.PHOTO), "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?",
            arrayOf(id.toString(), Photo.CONTENT_ITEM_TYPE), null)?.use { if (it.moveToFirst()) it.getBlob(0) else null }
    }

    private fun values(c: Cursor) = ContentValues().apply {
        for (i in 0 until c.columnCount) when (c.getType(i)) {
            Cursor.FIELD_TYPE_INTEGER -> put(c.getColumnName(i), c.getLong(i))
            Cursor.FIELD_TYPE_FLOAT -> put(c.getColumnName(i), c.getDouble(i))
            Cursor.FIELD_TYPE_STRING -> put(c.getColumnName(i), c.getString(i))
            Cursor.FIELD_TYPE_BLOB -> put(c.getColumnName(i), c.getBlob(i))
            else -> putNull(c.getColumnName(i))
        }
    }

    companion object {
        private fun prefs(context: android.content.Context) = context.getSharedPreferences("contacts-sync", android.content.Context.MODE_PRIVATE)
        /** The Google account that device contacts move into automatically (Google's "Automatically back up & sync"), or null. */
        fun autoAccount(context: android.content.Context): String? = prefs(context).getString("deviceContactsAutoAccount", null)
        fun setAutoAccount(context: android.content.Context, account: Account?) {
            check(prefs(context).edit().putString("deviceContactsAutoAccount", account?.name).commit())
        }
        private const val MAX_PHOTO_BYTES = 8 * 1024 * 1024
        // "Device" is the local account on Android 15+; earlier versions keep local contacts without an account.
        const val LOCAL = "((${RawContacts.ACCOUNT_TYPE} IS NULL AND ${RawContacts.ACCOUNT_NAME} IS NULL) OR " +
            "(${RawContacts.ACCOUNT_TYPE}='com.android.contacts' AND ${RawContacts.ACCOUNT_NAME}='DEVICE')) AND " +
            "${RawContacts.DATA_SET} IS NULL AND ${RawContacts.DELETED}=0"
        private val DATA_COLUMNS = arrayOf(Data.MIMETYPE, Data.IS_PRIMARY, Data.IS_SUPER_PRIMARY,
            Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5, Data.DATA6, Data.DATA7, Data.DATA8,
            Data.DATA9, Data.DATA10, Data.DATA11, Data.DATA12, Data.DATA13, Data.DATA14, Data.DATA15)
    }
}
