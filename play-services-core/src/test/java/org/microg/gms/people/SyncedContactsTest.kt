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
import org.microg.gms.people.sync.PrivatePeopleApi
import org.microg.gms.people.sync.SyncMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class SyncedContactsTest {
    private val account = Account("synced-fixture@invalid", "com.google")
    private val other = Account("other-fixture@invalid", "com.google")
    private val private = PrivatePeopleApi.OWNER
    private val legacy = AndroidContactStore.OWNER

    /** Evaluates the real SQL selections against synthetic rows. */
    private class Provider : ContentProvider() {
        val db: SQLiteDatabase = SQLiteDatabase.create(null).apply {
            execSQL("CREATE TABLE raw_contacts (_id INTEGER PRIMARY KEY, account_name TEXT, account_type TEXT, data_set TEXT, " +
                "sourceid TEXT, sync3 TEXT, sync4 TEXT, dirty INTEGER DEFAULT 0, deleted INTEGER DEFAULT 0, version INTEGER DEFAULT 1, " +
                "starred INTEGER DEFAULT 0, custom_ringtone TEXT, send_to_voicemail INTEGER DEFAULT 0)")
            execSQL("CREATE TABLE data (_id INTEGER PRIMARY KEY, raw_contact_id INTEGER, mimetype TEXT, is_primary INTEGER DEFAULT 0, " +
                "is_super_primary INTEGER DEFAULT 0, data1 TEXT, data2 TEXT, data3 TEXT, data4 TEXT, data5 TEXT, data6 TEXT, data7 TEXT, " +
                "data8 TEXT, data9 TEXT, data10 TEXT, data11 TEXT, data12 TEXT, data13 TEXT, data14 TEXT, data15 BLOB)")
            execSQL("CREATE TABLE groups (_id INTEGER PRIMARY KEY, account_name TEXT, account_type TEXT, data_set TEXT, " +
                "sourceid TEXT, sync3 TEXT, sync4 TEXT, system_id TEXT, dirty INTEGER DEFAULT 0, deleted INTEGER DEFAULT 0)")
        }
        var state: ByteArray? = null
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        private fun table(uri: Uri) = uri.pathSegments.first()
        private fun where(uri: Uri, selection: String?): String? {
            val id = uri.pathSegments.getOrNull(1) ?: return selection
            return "_id=$id" + (selection?.let { " AND ($it)" } ?: "")
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
            db.query(table(uri), projection, where(uri, selection), args, null, null, sort)
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            if (table(uri) != "syncstate") return ContentUris.withAppendedId(uri, db.insert(table(uri), null, values))
            state = values!!.getAsByteArray(ContactsContract.SyncState.DATA)
            return ContentUris.withAppendedId(uri, 1)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Unexpected update")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            assertEquals("true", uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER))
            return db.delete(table(uri), where(uri, selection), args)
        }
        fun ids(table: String): Set<Long> = db.rawQuery("SELECT _id FROM $table", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }
    }

    private fun Provider.contact(id: Long, owner: String?, account: Account = this@SyncedContactsTest.account, sourceId: String? = null,
        dirty: Boolean = false, pending: String? = null, dataSet: String? = null) = db.insert("raw_contacts", null, ContentValues().apply {
        put("_id", id); put("account_name", account.name); put("account_type", account.type); put("data_set", dataSet)
        put("sourceid", sourceId); put("sync3", pending); put("sync4", owner); put("dirty", if (dirty) 1 else 0)
    })
    private fun Provider.group(id: Long, owner: String?, systemId: String? = null, dirty: Boolean = false) = db.insert("groups", null, ContentValues().apply {
        put("_id", id); put("account_name", account.name); put("account_type", account.type)
        put("sync4", owner); put("system_id", systemId); put("dirty", if (dirty) 1 else 0)
    })

    @Test fun removesOnlyWhatSyncImportedOrAdoptedAndReportsUnsyncedChanges() {
        val context = RuntimeEnvironment.getApplication()
        val provider = Provider().apply {
            attachInfo(context, ProviderInfo().apply { authority = ContactsContract.AUTHORITY })
            contact(1, private)                               // downloaded: removed
            contact(2, legacy)                                // older sync: removed
            contact(3, null)                                  // existed before sync was enabled: kept
            contact(15, null, dirty = true)                   // between the legacy and private floors: not the engine's, kept
            contact(21, null, dirty = true)                   // created here after enabling, never uploaded: kept as a device contact
            contact(22, null)                                 // clean unowned row above the floor: the engine never adopts it, kept
            contact(23, null, sourceId = "other-adapter")     // another sync adapter's contact: kept
            contact(13, private, dataSet = "plugin")          // other data set: kept
            contact(14, private, account = other)             // other account: kept
            contact(24, private, pending = "{\"kind\":\"update\"}") // upload in flight: removed, unsynced
            group(1, private, systemId = "Contacts")          // system group sync imported: removed
            group(4, null, systemId = "Contacts")             // system group from elsewhere: kept
            group(2, private)                                 // synced label: removed
            group(3, null)                                    // existed before sync: kept
            group(25, null, dirty = true)                     // below the private group floor: kept
            group(31, null, dirty = true)                     // label created here, not uploaded: removed, unsynced
        }
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        val prefs = ContactSyncPreferences(context, account)
        ContactMigrationPrivacy(context).developerControlsEnabled = true // fixture floors (customers use 0, like Google)
        prefs.configure(SyncMode.TWO_WAY, 10, 20)          // legacy floors
        prefs.initializePrivateFloors(20, 30)               // the private engine's floors
        prefs.privateGroupCheckpoint("binding", "token")
        val client = context.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY_URI)!!
        val synced = SyncedContacts(client, account, prefs)

        assertEquals(2, synced.unsyncedChanges())         // 24 (upload in flight) and label 31; 21 is kept, not lost
        assertEquals(1, synced.keptOnPhone())
        assertEquals(4, synced.count())
        assertThrows(IllegalStateException::class.java) { synced.remove() } // Never while sync can still write.

        prefs.configure(SyncMode.OFF, Long.MAX_VALUE); prefs.pendingRemoval = true
        synced.remove()
        assertEquals(setOf(3L, 13L, 14L, 15L, 22L, 23L, 25L), provider.ids("raw_contacts"))
        provider.db.rawQuery("SELECT account_name, account_type FROM raw_contacts WHERE _id=25", null).use { c ->
            assertTrue(c.moveToFirst()); assertTrue(c.isNull(0)); assertTrue(c.isNull(1)) // 21's copy, now on the device
        }
        assertEquals(setOf(3L, 4L, 25L), provider.ids("groups"))
        assertEquals("{}", String(provider.state!!, Charsets.UTF_8))
        assertNull(prefs.privateGroupCheckpoint("binding"))
        assertEquals(0, synced.unsyncedChanges())
        ContactMigrationPrivacy(context).developerControlsEnabled = false
        client.close()
    }

    @Test fun removalWaitsForARunningSyncOfTheAccount() {
        val context = RuntimeEnvironment.getApplication()
        val provider = Provider().apply { attachInfo(context, ProviderInfo().apply { authority = ContactsContract.AUTHORITY }); contact(1, private) }
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        val prefs = ContactSyncPreferences(context, account)
        prefs.configure(SyncMode.OFF, Long.MAX_VALUE); prefs.pendingRemoval = true
        val client = context.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY_URI)!!
        val lock = ContactSyncLock.of(account)
        val syncRunning = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val sync = Thread { lock.lock(); try { syncRunning.countDown(); release.await() } finally { lock.unlock() } }.apply { start() }
        syncRunning.await()
        val removal = Thread { SyncedContacts(client, account, prefs).remove() }.apply { start() }
        removal.join(300)
        assertTrue(removal.isAlive); assertEquals(setOf(1L), provider.ids("raw_contacts")) // still waiting for the sync
        release.countDown(); sync.join(); removal.join(5000)
        assertFalse(removal.isAlive); assertTrue(provider.ids("raw_contacts").isEmpty())
        client.close()
    }
    @Test fun aStuckSyncMakesRemovalGiveUpAndRetryLater() {
        val context = RuntimeEnvironment.getApplication()
        val provider = Provider().apply { attachInfo(context, ProviderInfo().apply { authority = ContactsContract.AUTHORITY }); contact(1, private) }
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        val prefs = ContactSyncPreferences(context, account)
        prefs.configure(SyncMode.OFF, Long.MAX_VALUE); prefs.pendingRemoval = true
        val client = context.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY_URI)!!
        val lock = ContactSyncLock.of(account)
        val held = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val stuck = Thread { lock.lock(); try { held.countDown(); release.await() } finally { lock.unlock() } }.apply { start() }
        held.await()
        assertThrows(SyncStillRunning::class.java) { SyncedContacts(client, account, prefs).remove(waitMillis = 200) }
        assertTrue(prefs.pendingRemoval); assertEquals(setOf(1L), provider.ids("raw_contacts"))
        release.countDown(); stuck.join()
        SyncedContacts(client, account, prefs).remove(waitMillis = 200)
        assertFalse(prefs.pendingRemoval); assertTrue(provider.ids("raw_contacts").isEmpty())
        client.close()
    }
    @Test fun aRemovalCancelledByReEnablingDeletesNothing() {
        val context = RuntimeEnvironment.getApplication()
        val provider = Provider().apply { attachInfo(context, ProviderInfo().apply { authority = ContactsContract.AUTHORITY }); contact(1, private) }
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        val prefs = ContactSyncPreferences(context, account)
        prefs.configure(SyncMode.OFF, Long.MAX_VALUE); prefs.pendingRemoval = false // re-enable cleared the debt
        val client = context.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY_URI)!!
        SyncedContacts(client, account, prefs).remove()
        assertEquals(setOf(1L), provider.ids("raw_contacts"))
        client.close()
    }
}
