/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.accounts.Account
import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.ContactsContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class DeviceContactsTest {
    private val google = Account("device-fixture@invalid", "com.google")

    /** Evaluates the real selections and batch operations against synthetic rows. */
    private class Provider : ContentProvider() {
        val db: SQLiteDatabase = SQLiteDatabase.create(null).apply {
            execSQL("CREATE TABLE raw_contacts (_id INTEGER PRIMARY KEY, account_name TEXT, account_type TEXT, data_set TEXT, " +
                "deleted INTEGER DEFAULT 0, version INTEGER DEFAULT 1, starred INTEGER DEFAULT 0, custom_ringtone TEXT, " +
                "send_to_voicemail INTEGER DEFAULT 0, display_name TEXT)")
            execSQL("CREATE TABLE data (_id INTEGER PRIMARY KEY, raw_contact_id INTEGER, mimetype TEXT, is_primary INTEGER DEFAULT 0, " +
                "is_super_primary INTEGER DEFAULT 0, data1 TEXT, data2 TEXT, data3 TEXT, data4 TEXT, data5 TEXT, data6 TEXT, data7 TEXT, " +
                "data8 TEXT, data9 TEXT, data10 TEXT, data11 TEXT, data12 TEXT, data13 TEXT, data14 TEXT, data15 BLOB)")
        }
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        private fun table(uri: Uri) = uri.pathSegments.first()
        private fun where(uri: Uri, selection: String?): String? {
            val id = uri.pathSegments.getOrNull(1) ?: return selection
            return "_id=$id" + (selection?.let { " AND ($it)" } ?: "")
        }
        var onDataRead: (() -> Unit)? = null
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            if (table(uri) == "data") onDataRead?.invoke()
            return db.query(table(uri), projection, where(uri, selection), args, null, null, sort?.replace(" COLLATE LOCALIZED", ""))
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri = ContentUris.withAppendedId(uri, db.insert(table(uri), null, values))
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Unexpected update")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            val ids = db.query(table(uri), arrayOf("_id"), where(uri, selection), args, null, null, null).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0)) }
            }
            ids.forEach { db.delete("data", "raw_contact_id=?", arrayOf(it.toString())) }
            return db.delete(table(uri), where(uri, selection), args)
        }
    }

    private fun Provider.contact(id: Long, accountName: String?, accountType: String?, name: String, dataSet: String? = null, starred: Boolean = false) =
        db.insert("raw_contacts", null, ContentValues().apply {
            put("_id", id); put("account_name", accountName); put("account_type", accountType); put("data_set", dataSet)
            put("display_name", name); put("starred", if (starred) 1 else 0)
        })
    private fun Provider.row(contact: Long, mime: String, data1: String?, photo: ByteArray? = null) = db.insert("data", null, ContentValues().apply {
        put("raw_contact_id", contact); put("mimetype", mime); put("data1", data1); put("data15", photo)
    })
    private fun Provider.rows(contact: Long): List<Pair<String, String?>> =
        db.rawQuery("SELECT mimetype, data1 FROM data WHERE raw_contact_id=? ORDER BY mimetype", arrayOf(contact.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }

    private fun setup(): Pair<Provider, DeviceContacts> {
        val context = RuntimeEnvironment.getApplication()
        val provider = Provider().apply { attachInfo(context, ProviderInfo().apply { authority = ContactsContract.AUTHORITY }) }
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        return provider to DeviceContacts(context.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY_URI)!!)
    }

    @Test fun listsOnlyContactsSavedOnThePhone() {
        val (provider, device) = setup()
        provider.contact(1, "DEVICE", "com.android.contacts", "Device")      // Android 15+ local account
        provider.contact(2, null, null, "Legacy local")                     // older Android: no account
        provider.contact(3, google.name, google.type, "Google")             // already a Google contact
        provider.contact(4, "sim1", "com.android.sim", "SIM")               // SIM: never touched
        provider.contact(5, "work@corp", "com.android.exchange", "Exchange")
        provider.contact(6, "DEVICE", "com.android.contacts", "Plugin", dataSet = "plugin")
        assertEquals(setOf(1L, 2L), device.list().map { it.id }.toSet())
        assertEquals(2, device.count())
    }

    @Test fun movesFieldsIntoTheGoogleAccountAndRemovesTheDeviceCopy() {
        val (provider, device) = setup()
        provider.contact(1, "DEVICE", "com.android.contacts", "Ada", starred = true)
        provider.row(1, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, "Ada")
        provider.row(1, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, "+15550100")
        provider.row(1, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, "7") // device group: not copied
        provider.row(1, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE, null, byteArrayOf(1, 2, 3))
        provider.contact(2, "DEVICE", "com.android.contacts", "Keep") // not selected

        assertEquals(DeviceContacts.MoveResult(1, 0), device.moveTo(google, listOf(1L)))

        assertEquals(listOf(2L), device.list().map { it.id })
        val copy = provider.db.rawQuery("SELECT _id, starred FROM raw_contacts WHERE account_name=? AND account_type=?",
            arrayOf(google.name, google.type)).use { c -> assertTrue(c.moveToFirst()); c.getLong(0) to c.getInt(1) }
        assertEquals(1, copy.second)
        assertEquals(listOf( // sorted by MIME type
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE to "Ada",
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE to "+15550100",
            ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE to null), provider.rows(copy.first))
        assertTrue(provider.rows(1).isEmpty())
    }

    @Test fun aContactThatChangedOrVanishedIsLeftAlone() {
        val (provider, device) = setup()
        provider.contact(1, google.name, google.type, "Already Google")
        assertEquals(DeviceContacts.MoveResult(0, 0), device.moveTo(google, listOf(1L, 99L)))
        assertEquals(1L, provider.db.rawQuery("SELECT COUNT(*) FROM raw_contacts", null).use { it.moveToFirst(); it.getLong(0) })
    }

    @Test fun aContactWithDetailsGoogleSyncCantUploadStaysOnThePhone() {
        val (provider, device) = setup()
        provider.contact(1, "DEVICE", "com.android.contacts", "Custom")
        provider.row(1, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, "Custom")
        provider.row(1, "vnd.com.example.cursor.item/app_profile", "app-specific")
        assertFalse(device.list().single().supported)
        assertEquals(DeviceContacts.MoveResult(0, 1), device.moveTo(google, listOf(1L)))
        assertEquals(listOf(1L), device.list().map { it.id })
        assertEquals(1L, provider.db.rawQuery("SELECT COUNT(*) FROM raw_contacts", null).use { it.moveToFirst(); it.getLong(0) })
    }

    @Test fun turningSyncOffStopsTheMoveBeforeTheNextContact() {
        val (provider, device) = setup()
        for (id in 1L..3L) {
            provider.contact(id, "DEVICE", "com.android.contacts", "C$id")
            provider.row(id, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, "C$id")
        }
        var allowed = 1
        assertEquals(DeviceContacts.MoveResult(1, 0), device.moveTo(google, listOf(1L, 2L, 3L)) { allowed-- > 0 })
        assertEquals(listOf(2L, 3L), device.list().map { it.id }.sorted())
    }

    @Test fun anEditWhileTheContactIsReadIsNeverLost() {
        val (provider, device) = setup()
        provider.contact(1, "DEVICE", "com.android.contacts", "Ada")
        provider.row(1, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, "+15550100")
        provider.onDataRead = { // an editor saves a new number right after the move read the contact
            provider.db.execSQL("UPDATE data SET data1='+15550199' WHERE raw_contact_id=1")
            provider.db.execSQL("UPDATE raw_contacts SET version=version+1 WHERE _id=1")
            provider.onDataRead = null
        }
        assertEquals(DeviceContacts.MoveResult(0, 0), device.moveTo(google, listOf(1L)))
        assertEquals(listOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE to "+15550199"), provider.rows(1)) // edit kept on the phone
        assertEquals(1L, provider.db.rawQuery("SELECT COUNT(*) FROM raw_contacts", null).use { it.moveToFirst(); it.getLong(0) })
    }
}
