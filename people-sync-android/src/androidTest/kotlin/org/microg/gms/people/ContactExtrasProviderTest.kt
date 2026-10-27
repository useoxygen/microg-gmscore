/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.microg.gms.people.sync.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

/** Synthetic accounts, real provider; default runs refuse physical devices. */
@RunWith(AndroidJUnit4::class)
class ContactExtrasProviderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private lateinit var account: Account
    private lateinit var prefs: ContactSyncPreferences
    private var created = false
    private val api = Api()
    private fun syncUri(uri: android.net.Uri) = uri.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
    private fun group(resource: String, name: String, marker: String? = null) = ContactGroup.parse(JSONObject()
        .put("resourceName", resource).put("name", name).put("etag", "etag-$name")
        .put("groupType", if (resource in setOf(MY_CONTACTS, STARRED)) "SYSTEM_CONTACT_GROUP" else "USER_CONTACT_GROUP")
        .apply { if (marker != null) put("clientData", JSONArray().put(JSONObject().put("key", ContactGroup.CREATE_KEY).put("value", marker))) })
    private fun person(resource: String = "people/a", photo: String? = null, members: Set<String> = setOf(MY_CONTACTS, "contactGroups/label", STARRED)) = Person.parse(JSONObject()
        .put("resourceName", resource).put("metadata", JSONObject().put("sources", JSONArray().put(JSONObject().put("type", "CONTACT").put("etag", "person-etag"))))
        .put("names", JSONArray().put(JSONObject().put("givenName", "Synthetic extras fixture")))
        .put("memberships", Memberships.json(members)).apply {
            if (photo != null) put("photos", JSONArray().put(JSONObject().put("url", photo).put("metadata", JSONObject().put("source", JSONObject().put("type", "CONTACT")))))
        })
    private inner class Api : ContactsApi, GroupsApi, PhotosApi {
        var remote: Person? = person()
        val labels = linkedMapOf(MY_CONTACTS to group(MY_CONTACTS, "myContacts"), STARRED to group(STARRED, "starred"),
            "contactGroups/label" to group("contactGroups/label", "Label"))
        val images = mutableMapOf<String, ByteArray>()
        var photoWrites = 0; var photoReads = 0; var groupCreates = 0; var contactCreates = 0; var contactDeletes = 0
        var losePhotoReply = false; var loseGroupReply = false; var afterPhotoWrite: (() -> Unit)? = null
        var lastMask: Set<String>? = null; var omitLabelFromList = false
        override fun list(syncToken: String?, pageToken: String?) = Page(listOfNotNull(remote), null, "contacts-token")
        override fun get(resource: String) = remote?.takeIf { it.resource == resource }
        override fun create(body: JSONObject): Person {
            contactCreates++
            return Person.parse(JSONObject(body.toString()).put("resourceName", "people/created")
                .put("metadata", JSONObject().put("sources", JSONArray().put(JSONObject().put("type", "CONTACT").put("etag", "created"))))).also { remote = it }
        }
        override fun update(person: Person, fields: Set<String>, body: JSONObject): Person {
            lastMask = fields
            return Person.parse(JSONObject(remote!!.json.toString()).apply {
                for (field in fields) put(field, body.get(field))
                getJSONObject("metadata").getJSONArray("sources").getJSONObject(0).put("etag", "updated")
            }).also { remote = it }
        }
        override fun delete(resource: String) { contactDeletes++; remote = null }
        override fun listGroups(syncToken: String?, pageToken: String?) = GroupPage(labels.values.filter { !omitLabelFromList || it.resource != "contactGroups/label" }, null, "groups-token")
        override fun getGroup(resource: String) = labels[resource]
        override fun createGroup(name: String, creationId: String): ContactGroup {
            groupCreates++
            return group("contactGroups/created$groupCreates", name, creationId).also {
                labels[it.resource] = it
                if (loseGroupReply) throw IOException("Lost group reply")
            }
        }
        override fun updateGroup(group: ContactGroup, name: String) = group(group.resource, name, group.creationId()).also { labels[it.resource] = it }
        override fun deleteGroup(resource: String) {
            labels.remove(resource)
            remote?.json?.put("memberships", Memberships.json(Memberships.resources(remote!!) - resource))
        }
        override fun readPhoto(url: String): ByteArray { photoReads++; return images.getValue(url) }
        override fun writePhoto(resource: String, bytes: ByteArray?): Person {
            photoWrites++
            val next = JSONObject(remote!!.json.toString())
            if (bytes == null) next.remove("photos") else {
                val url = "https://lh3.googleusercontent.com/fixture-$photoWrites"
                images[url] = bytes
                next.put("photos", JSONArray().put(JSONObject().put("url", url).put("metadata", JSONObject().put("source", JSONObject().put("type", "CONTACT")))))
            }
            next.getJSONObject("metadata").getJSONArray("sources").getJSONObject(0).put("etag", "photo-$photoWrites")
            return Person.parse(next).also { remote = it; afterPhotoWrite?.invoke(); if (losePhotoReply) throw IOException("Lost photo reply") }
        }
    }
    @Before fun setup() {
        check(context.packageName == "org.microg.gms.people.sync.android.test" && FixtureDeviceApproval.approved(context)) { "Extras fixtures require an emulator or the exact development-device proof" }
        for (permission in listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)) check(
            context.packageManager.checkPermission(permission, context.packageName) == PackageManager.PERMISSION_GRANTED)
        account = Account("extras-" + UUID.randomUUID(), "org.microg.gms.contacts.fixture")
        created = AccountManager.get(context).addAccountExplicitly(account, null, null); assertTrue(created)
        prefs = ContactSyncPreferences(context, account); prefs.configure(SyncMode.TWO_WAY, 0, 0)
    }
    @After fun cleanup() {
        if (!created) return
        prefs.configure(SyncMode.OFF, 0)
        resolver.delete(syncUri(RawContacts.CONTENT_URI), "${RawContacts.ACCOUNT_NAME}=? AND ${RawContacts.ACCOUNT_TYPE}=?", arrayOf(account.name, account.type))
        resolver.delete(syncUri(Groups.CONTENT_URI), "${Groups.ACCOUNT_NAME}=? AND ${Groups.ACCOUNT_TYPE}=?", arrayOf(account.name, account.type))
        AccountManager.get(context).removeAccountExplicitly(account)
    }
    private fun <T> stores(block: (AndroidContactStore, AndroidGroupStore) -> T): T = resolver.acquireContentProviderClient(ContactsContract.AUTHORITY)!!.use { provider ->
        val groups = AndroidGroupStore(provider, account, prefs); groups.initialize()
        val contacts = AndroidContactStore(provider, account, prefs, groups, api); contacts.configureMode()
        block(contacts, groups)
    }
    private fun sync() = stores { contacts, groups ->
        GroupsSync(api, groups) { prefs.mode }.sync() + ContactsSync(api, contacts) { prefs.mode }.sync() + PhotosSync(api, api, contacts) { prefs.mode }.sync()
    }
    private fun id() = stores { contacts, _ -> contacts.contacts().single().id }
    private fun jpeg(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it); bitmap.recycle() }.toByteArray()
    }
    private fun editPhoto(id: Long, bytes: ByteArray?) {
        if (bytes == null) resolver.delete(Data.CONTENT_URI, "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(id.toString(), Photo.CONTENT_ITEM_TYPE))
        else {
            val values = ContentValues().apply { put(Photo.PHOTO, bytes) }
            if (resolver.update(Data.CONTENT_URI, values, "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(id.toString(), Photo.CONTENT_ITEM_TYPE)) == 0) {
                values.put(Data.RAW_CONTACT_ID, id); values.put(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE); resolver.insert(Data.CONTENT_URI, values)
            }
        }
    }
    private fun insertGroup(name: String): Long = ContentUris.parseId(resolver.insert(Groups.CONTENT_URI, ContentValues().apply {
        put(Groups.ACCOUNT_NAME, account.name); put(Groups.ACCOUNT_TYPE, account.type); put(Groups.TITLE, name); put(Groups.DIRTY, 1)
    })!!)
    @Test fun cascadingGroupDeletionRefusesEditedOrUnownedMembers() {
        sync(); val raw = id()
        resolver.update(RawContacts.CONTENT_URI, ContentValues().apply { put(RawContacts.DIRTY, 1) }, "${RawContacts._ID}=?", arrayOf(raw.toString()))
        stores { _, groups ->
            val group = groups.groups().single { it.resource == "contactGroups/label" }
            assertThrows(GroupInUse::class.java) { groups.removeGroup(group) }
            assertNotNull(groups.id(group.resource!!))
        }
        resolver.update(syncUri(RawContacts.CONTENT_URI), ContentValues().apply { put(RawContacts.DIRTY, 0) }, "${RawContacts._ID}=?", arrayOf(raw.toString()))
        val unowned = ContentUris.parseId(resolver.insert(RawContacts.CONTENT_URI, ContentValues().apply {
            put(RawContacts.ACCOUNT_NAME, account.name); put(RawContacts.ACCOUNT_TYPE, account.type)
        })!!)
        stores { _, groups ->
            val group = groups.groups().single { it.resource == "contactGroups/label" }
            resolver.insert(Data.CONTENT_URI, ContentValues().apply { put(Data.RAW_CONTACT_ID, unowned); put(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE); put(GroupMembership.GROUP_ROW_ID, group.id) })
            resolver.update(syncUri(RawContacts.CONTENT_URI), ContentValues().apply { put(RawContacts.DIRTY, 0) }, "${RawContacts._ID}=?", arrayOf(unowned.toString()))
            assertThrows(GroupInUse::class.java) { groups.removeGroup(group) }
            assertNotNull(groups.id(group.resource!!))
        }
    }
    @Test fun preMappingNativeMembershipsArePreservedForReview() {
        sync()
        stores { contacts, _ ->
            val contact = contacts.contacts().single()
            val old = ContactsSync.fieldsJson(contact.baseline, contact.baseline.keys).apply { remove("memberships") }
            resolver.update(syncUri(RawContacts.CONTENT_URI), ContentValues().apply { put(RawContacts.SYNC2, old.toString()); put(RawContacts.DIRTY, 1) },
                "${RawContacts._ID}=?", arrayOf(contact.id.toString()))
        }
        val report = sync()
        assertTrue(report.conflicts > 0 || report.skipped > 0)
        stores { contacts, _ -> assertTrue(contacts.contacts().single().unsupported); assertTrue(contacts.contacts().single().dirty) }
        assertNull(api.lastMask)
    }
    @Test fun mapsMyContactsLabelsStarredAndUploadsOnlyMembershipChanges() {
        sync()
        stores { contacts, groups ->
            val local = contacts.contacts().single(); assertEquals(contacts.project(api.remote!!), local.fields)
            assertNotNull(groups.id(MY_CONTACTS)); assertNotNull(groups.id(STARRED))
            resolver.query(Groups.CONTENT_URI, arrayOf(Groups.AUTO_ADD), "${Groups._ID}=?", arrayOf(groups.id(MY_CONTACTS).toString()), null)!!.use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            resolver.query(Groups.CONTENT_URI, arrayOf(Groups.FAVORITES), "${Groups._ID}=?", arrayOf(groups.id(STARRED).toString()), null)!!.use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            resolver.update(RawContacts.CONTENT_URI, ContentValues().apply { put(RawContacts.STARRED, 0) }, "${RawContacts._ID}=?", arrayOf(local.id.toString()))
        }
        sync()
        assertEquals(setOf("memberships"), api.lastMask); assertEquals(setOf(MY_CONTACTS, "contactGroups/label"), Memberships.resources(api.remote!!))
        stores { contacts, _ -> assertFalse(contacts.contacts().single().dirty) }
    }
    @Test fun cloudMembershipDoesNotAcquireAutomaticMyContactsMembership() {
        api.remote = person(members = setOf("contactGroups/label")); sync()
        stores { contacts, _ -> assertEquals(Memberships.field(setOf("contactGroups/label")), contacts.contacts().single().fields.getValue("memberships")) }
    }
    @Test fun resolvesGroupMissingFromEventuallyConsistentListByDirectRead() {
        api.omitLabelFromList = true; sync()
        stores { contacts, groups -> assertNotNull(groups.id("contactGroups/label")); assertEquals(contacts.project(api.remote!!), contacts.contacts().single().fields) }
    }
    @Test fun groupCreateJournalSurvivesStoreRecreationAndDoesNotNameMatch() {
        sync(); val id = insertGroup("New label"); api.loseGroupReply = true
        assertThrows(IOException::class.java) { sync() }
        stores { _, groups -> assertNotNull(groups.groups().single { it.id == id }.pending) }
        api.loseGroupReply = false; sync(); sync()
        stores { _, groups -> assertEquals("contactGroups/created1", groups.groups().single { it.id == id }.resource); assertNull(groups.groups().single { it.id == id }.pending) }
        assertEquals(1, api.groupCreates)
    }
    @Test fun labelRenameAndDeletePreserveContacts() {
        sync(); val raw = id(); val group = stores { _, groups -> groups.id("contactGroups/label")!! }
        resolver.update(Groups.CONTENT_URI, ContentValues().apply { put(Groups.TITLE, "Renamed"); put(Groups.DIRTY, 1) }, "${Groups._ID}=?", arrayOf(group.toString()))
        sync(); assertEquals("Renamed", api.labels.getValue("contactGroups/label").name)
        resolver.delete(ContentUris.withAppendedId(Groups.CONTENT_URI, group), null, null); sync()
        assertFalse(api.labels.containsKey("contactGroups/label")); assertEquals(0, api.contactDeletes)
        resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID), "${RawContacts._ID}=?", arrayOf(raw.toString()), null)!!.use { assertTrue(it.moveToFirst()) }
    }
    @Test fun photoDownloadUsesVersionBaselineAndDoesNotUploadRecompressedImage() {
        val url = "https://lh3.googleusercontent.com/download-fixture"; api.images[url] = jpeg(Color.RED); api.remote = person(photo = url)
        sync(); val reads = api.photoReads
        stores { contacts, _ ->
            val photo = contacts.photoContacts().single(); assertEquals(photo.token, photo.baseline!!.token)
            val bytes = contacts.photoBytes(photo)!!; assertNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            assertFalse(photo.contact.dirty)
        }
        sync(); assertEquals(reads, api.photoReads); assertEquals(0, api.photoWrites)
    }
    @Test fun photoUploadLostReplyRecoveryAndDeleteUseDurableRawJournal() {
        sync(); val raw = id(); editPhoto(raw, jpeg(Color.BLUE)); api.losePhotoReply = true
        assertThrows(IOException::class.java) { sync() }
        stores { contacts, _ -> assertEquals("photo", contacts.contacts().single().pending!!.getString("kind")) }
        api.losePhotoReply = false; sync(); sync()
        assertEquals(1, api.photoWrites)
        stores { contacts, _ -> assertNull(contacts.contacts().single().pending); assertFalse(contacts.contacts().single().dirty) }
        editPhoto(raw, null); sync(); assertNull(ContactPhotos.reference(api.remote!!)); assertEquals(2, api.photoWrites)
    }
    @Test fun photoAcknowledgmentPreservesConcurrentLocalImage() {
        sync(); val raw = id(); editPhoto(raw, jpeg(Color.RED)); var laterToken: String? = null
        api.afterPhotoWrite = { editPhoto(raw, jpeg(Color.GREEN)); laterToken = stores { contacts, _ -> contacts.photoContacts().single().token } }
        sync()
        stores { contacts, _ -> val current = contacts.photoContacts().single(); assertEquals(laterToken, current.token); assertTrue(current.contact.dirty); assertNull(current.contact.pending) }
    }
    @Test fun localLabelIsResolvedBeforeNewContactUpload() {
        sync(); api.remote = null
        val label = insertGroup("New group")
        val raw = ContentUris.parseId(resolver.insert(RawContacts.CONTENT_URI, ContentValues().apply { put(RawContacts.ACCOUNT_NAME, account.name); put(RawContacts.ACCOUNT_TYPE, account.type) })!!)
        resolver.insert(Data.CONTENT_URI, ContentValues().apply { put(Data.RAW_CONTACT_ID, raw); put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE); put(StructuredName.GIVEN_NAME, "New fixture") })
        resolver.insert(Data.CONTENT_URI, ContentValues().apply { put(Data.RAW_CONTACT_ID, raw); put(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE); put(GroupMembership.GROUP_ROW_ID, label) })
        // Preserve the earlier managed contact by running only the new-contact path after removing it from the fixture provider.
        resolver.delete(syncUri(RawContacts.CONTENT_URI), "${RawContacts.ACCOUNT_NAME}=? AND ${RawContacts.ACCOUNT_TYPE}=? AND ${RawContacts._ID}!=?", arrayOf(account.name, account.type, raw.toString()))
        sync()
        assertEquals(1, api.groupCreates); assertEquals(1, api.contactCreates)
        assertTrue(Memberships.resources(api.remote!!).contains("contactGroups/created1"))
    }
}
