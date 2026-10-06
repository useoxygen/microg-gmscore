/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.pm.PackageManager
import android.content.ContentUris
import android.content.ContentValues
import android.os.Build
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import org.microg.gms.people.sync.*
import java.util.UUID
import java.io.File

/** Real Contacts Provider, mock remote service; physical devices require explicit serial opt-in. */
@RunWith(AndroidJUnit4::class)
class ContactProviderTest {
    @get:Rule val permissions = object : ExternalResource() {
        override fun before() {
            for (permission in listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)) {
                check(context.packageManager.checkPermission(permission, context.packageName) == PackageManager.PERMISSION_GRANTED) {
                    "Grant contacts permissions to the fixture APK before running provider tests"
                }
            }
        }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private lateinit var account: Account
    private lateinit var prefs: ContactSyncPreferences
    private var accountCreated = false
    private val deviceRows = mutableSetOf<Long>()
    private val fixtureMarker = "microG fixture " + UUID.randomUUID().toString() + " "
    private val api = Api()

    private fun person(resource: String = "people/a", name: String = "Alice", number: String = "123", etag: String = "e1") = Person.parse(
        JSONObject().put("resourceName", resource).put("metadata", JSONObject().put("sources", JSONArray().put(
            JSONObject().put("type", "CONTACT").put("id", resource.removePrefix("people/")).put("etag", etag))))
            .put("names", JSONArray().put(JSONObject().put("givenName", fixtureMarker + name)))
            .put("phoneNumbers", JSONArray().put(JSONObject().put("value", number).put("type", "mobile"))))
    private inner class Api : ContactsApi {
        var remote: Person? = person()
        var writes = 0
        var afterWrite: (() -> Unit)? = null
        override fun list(syncToken: String?, pageToken: String?) = Page(listOfNotNull(remote), null, "checkpoint")
        override fun get(resource: String) = remote?.takeIf { it.resource == resource }
        override fun create(body: JSONObject): Person {
            writes++
            val response = JSONObject(body.toString()).put("resourceName", "people/created")
                .put("metadata", JSONObject().put("sources", JSONArray().put(JSONObject().put("type", "CONTACT").put("id", "created").put("etag", "created"))))
            return Person.parse(response).also { remote = it; afterWrite?.invoke() }
        }
        override fun update(person: Person, fields: Set<String>, body: JSONObject): Person {
            writes++
            assertEquals(remote!!.etag, person.etag)
            val response = JSONObject(remote!!.json.toString())
            for (field in fields) response.put(field, body.get(field))
            response.getJSONObject("metadata").getJSONArray("sources").getJSONObject(0).put("etag", "updated")
            return Person.parse(response).also { remote = it; afterWrite?.invoke() }
        }
        override fun delete(resource: String) { writes++; remote = null; afterWrite?.invoke() }
    }
    private fun approvedDevice(): Boolean {
        if (Build.HARDWARE in listOf("ranchu", "goldfish")) return true
        // Default runs still refuse physical phones. The caller must supply the
        // exact connected serial, and the phone must be a development build.
        if (Build.TYPE !in listOf("userdebug", "eng") || Build.VERSION.SDK_INT < 29) return false
        val arguments = InstrumentationRegistry.getArguments()
        val expected = arguments.getString("physicalDeviceSerial")
            ?.takeIf { it.isNotBlank() } ?: return false
        val nonce = arguments.getString("physicalDeviceNonce")?.takeIf { it.isNotBlank() } ?: return false
        // The serial-pinned host runner reads ro.serialno through ADB and writes
        // a fresh, one-run proof into this debug APK's private storage. Avoid
        // UiAutomation entirely so another agent can retain its screen connection.
        return runCatching {
            val proof = JSONObject(File(context.filesDir, "physical-device-authorization.json").readText())
            val age = System.currentTimeMillis() - proof.getLong("createdAt")
            proof.getString("serial") == expected && proof.getString("nonce") == nonce &&
                proof.getString("fingerprint") == Build.FINGERPRINT && age in 0..300_000
        }.getOrDefault(false)
    }
    @Before fun setup() {
        check(context.packageName == "org.microg.gms.people.sync.android.test" && approvedDevice()) {
            "Fixture tests require an emulator or an explicitly selected development phone"
        }
        account = Account("fixture-" + UUID.randomUUID(), "org.microg.gms.contacts.fixture")
        accountCreated = AccountManager.get(context).addAccountExplicitly(account, null, null)
        assertTrue(accountCreated)
        prefs = ContactSyncPreferences(context, account)
        prefs.configure(SyncMode.DOWNLOAD, 0)
    }
    @After fun cleanup() {
        if (!accountCreated) return
        if (::prefs.isInitialized) prefs.configure(SyncMode.OFF, 0)
        // Track the raw row before its Data insert: even a failed insertLocal must
        // remove its own unowned row, without touching any pre-existing Device row.
        for (id in deviceRows) {
            resolver.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id).buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(), null, null)
        }
        resolver.delete(RawContacts.CONTENT_URI.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(),
            "${RawContacts.ACCOUNT_NAME}=? AND ${RawContacts.ACCOUNT_TYPE}=?", arrayOf(account.name, account.type))
        AccountManager.get(context).removeAccountExplicitly(account)
    }
    private fun <T> store(block: (AndroidContactStore) -> T): T = resolver.acquireContentProviderClient(ContactsContract.AUTHORITY)!!.use {
        block(AndroidContactStore(it, account, prefs))
    }
    private fun sync() = store { it.configureMode(); ContactsSync(api, it) { prefs.mode }.sync() }
    private fun editPhone(id: Long, number: String) {
        assertEquals(1, resolver.update(Data.CONTENT_URI, ContentValues().apply { put(Phone.NUMBER, number) },
            "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(id.toString(), Phone.CONTENT_ITEM_TYPE)))
    }
    private fun insertLocal(name: String, fixtureAccount: Boolean = true): Long {
        val raw = resolver.insert(RawContacts.CONTENT_URI, ContentValues().apply {
            if (fixtureAccount) { put(RawContacts.ACCOUNT_NAME, account.name); put(RawContacts.ACCOUNT_TYPE, account.type) }
        })!!
        val id = ContentUris.parseId(raw)
        if (!fixtureAccount) deviceRows.add(id)
        resolver.insert(Data.CONTENT_URI, ContentValues().apply {
            put(Data.RAW_CONTACT_ID, id); put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE); put(StructuredName.GIVEN_NAME, fixtureMarker + name)
        })
        return id
    }
    @Test fun downloadIsIdempotentAndDoesNotUploadDeviceRows() {
        val deviceId = insertLocal("Device contact", false)
        try {
            sync(); sync()
            store {
                val managed = it.contacts().single()
                assertEquals("people/a", managed.resource); assertFalse(managed.dirty)
                assertEquals(it.project(api.remote!!), managed.fields)
            }
            assertEquals(0, api.writes)
            resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID), "${RawContacts._ID}=?", arrayOf(deviceId.toString()), null)!!.use { assertTrue(it.moveToFirst()) }
        } finally {
            resolver.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, deviceId).buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(), null, null)
        }
    }
    @Test fun providerDirtyEditUploadsAndBecomesClean() {
        prefs.configure(SyncMode.TWO_WAY, 0); sync()
        val id = store { it.contacts().single().id }
        editPhone(id, "456")
        store { assertTrue(it.contacts().single().dirty) }
        assertEquals(1, sync().uploaded)
        store { assertFalse(it.contacts().single().dirty); assertEquals(it.project(api.remote!!), it.contacts().single().fields) }
        assertEquals("456", api.remote!!.json.getJSONArray("phoneNumbers").getJSONObject(0).getString("value"))
    }
    @Test fun softDeletedRowIsUploadedAndPhysicallyRemoved() {
        prefs.configure(SyncMode.TWO_WAY, 0); sync()
        val id = store { it.contacts().single().id }
        resolver.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id), null, null)
        store { assertTrue(it.contacts().single().deleted) }
        assertEquals(1, sync().uploaded)
        store { assertTrue(it.contacts().isEmpty()) }; assertNull(api.remote)
    }
    @Test fun concurrentProviderEditMergesDisjointCloudChange() {
        prefs.configure(SyncMode.TWO_WAY, 0); sync()
        val id = store { it.contacts().single().id }; editPhone(id, "456")
        api.remote = person(name = "Alicia", etag = "e2")
        api.afterWrite = { editPhone(id, "999") }
        sync()
        store {
            val current = it.contacts().single()
            assertTrue(current.dirty)
            assertEquals(it.project(person(name = "Alicia", number = "999")), current.fields)
            assertEquals(it.project(api.remote!!), current.baseline)
        }
    }
    @Test fun newlyCreatedAccountContactUploadsWithStableSourceId() {
        api.remote = null; prefs.configure(SyncMode.TWO_WAY, 0)
        val id = insertLocal("New contact")
        assertEquals(1, sync().uploaded)
        store { val row = it.contacts().single(); assertEquals(id, row.id); assertEquals("people/created", row.resource); assertFalse(row.dirty) }
        sync(); assertEquals(1, api.writes)
    }
    @Test fun existingUnownedRowsAreNotAdopted() {
        val old = insertLocal("Existing local copy")
        context.getSharedPreferences("contacts-sync", 0).edit().putLong("${account.type}:${account.name}:adoptionFloor", old).commit()
        prefs.configure(SyncMode.TWO_WAY, old)
        sync(); assertEquals(0, api.writes)
        store { assertEquals(1, it.contacts().size); assertTrue(it.contacts().none { row -> row.id == old }) }
    }
    @Test fun supportedFieldsRoundTripThroughRealProvider() {
        api.remote!!.json
            .put("emailAddresses", JSONArray().put(JSONObject().put("value", "alice@example.invalid").put("displayName", "Alice sender").put("type", "work")))
            .put("addresses", JSONArray().put(JSONObject().put("formattedValue", "1 Example Street").put("streetAddress", "1 Example Street").put("type", "home")))
            .put("organizations", JSONArray().put(JSONObject().put("name", "Example").put("title", "Developer").put("type", "work")))
            .put("biographies", JSONArray().put(JSONObject().put("value", "A note").put("contentType", "TEXT_PLAIN")))
            .put("birthdays", JSONArray().put(JSONObject().put("date", JSONObject().put("year", 0).put("month", 3).put("day", 7))))
            .put("nicknames", JSONArray().put(JSONObject().put("value", "Al")))
            .put("urls", JSONArray().put(JSONObject().put("value", "https://example.invalid").put("type", "work")))
        sync()
        store { assertEquals(it.project(api.remote!!), it.contacts().single().fields) }
    }
    @Test fun preferredNumberChangesUploadAsSourcePrimaryMetadata() {
        val first = JSONObject().put("value", "123").put("type", "mobile").put("metadata", JSONObject().put("sourcePrimary", true))
        val second = JSONObject().put("value", "456").put("type", "work").put("metadata", JSONObject().put("sourcePrimary", false))
        api.remote!!.json.put("phoneNumbers", JSONArray().put(first).put(second))
        prefs.configure(SyncMode.TWO_WAY, 0); sync()
        val id = store { it.contacts().single().id }
        resolver.update(Data.CONTENT_URI, ContentValues().apply { put(Data.IS_PRIMARY, 1) },
            "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=? AND ${Phone.NUMBER}=?", arrayOf(id.toString(), Phone.CONTENT_ITEM_TYPE, "456"))
        assertEquals(1, sync().uploaded)
        val phones = api.remote!!.json.getJSONArray("phoneNumbers")
        val primary = (0 until phones.length()).map { phones.getJSONObject(it) }.single { it.getJSONObject("metadata").getBoolean("sourcePrimary") }
        assertEquals("456", primary.getString("value"))
        store { assertFalse(it.contacts().single().dirty) }
    }
    @Test fun partialBirthdayDoesNotBlockDownloadingOtherFields() {
        api.remote!!.json.put("birthdays", JSONArray().put(JSONObject().put("date", JSONObject().put("year", 1980))))
        sync()
        store { assertEquals("[]", it.contacts().single().fields.getValue("birthdays")); assertFalse(it.contacts().single().dirty) }
    }
}
