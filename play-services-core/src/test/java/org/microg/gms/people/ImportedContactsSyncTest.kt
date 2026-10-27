/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.accounts.Account
import android.app.Application
import android.content.ContentProviderClient
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.people.sync.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ImportedContactsSyncTest {
    private val account = Account("switch-fixture@invalid", "com.google")
    private fun person(id: String = "17", name: String = "Synthetic", phone: String = "123", etag: String = "e1"): Person = Person.parse(
        JSONObject().put("resourceName", "people/fsa_$id").put("metadata", JSONObject().put("sources", JSONArray()
            .put(JSONObject().put("type", "CONTACT").put("id", id).put("etag", etag))))
            .put("names", JSONArray().put(JSONObject().put("givenName", name)))
            .put("phoneNumbers", JSONArray().put(JSONObject().put("value", phone).put("type", "mobile"))))

    private inner class Api : ContactsApi {
        val remote = linkedMapOf<String, Person>()
        val tombstones = mutableListOf<Person>()
        val listTokens = mutableListOf<String?>()
        var creates = 0; var updates = 0; var deletes = 0; var skipped = 0
        var canUpload: () -> Boolean = { true }
        var afterList: (() -> Unit)? = null
        override fun list(syncToken: String?, pageToken: String?): Page {
            listTokens.add(syncToken)
            return Page(remote.values.toList() + tombstones, null, "cursor", skipped).also { afterList?.invoke() }
        }
        override fun get(resource: String) = remote[resource]
        override fun create(body: JSONObject): Person {
            if (!canUpload()) throw UploadDisabled()
            creates++
            val json = JSONObject(body.toString()).put("resourceName", "people/fsa_a${creates}")
                .put("metadata", JSONObject().put("sources", JSONArray().put(JSONObject().put("type", "CONTACT")
                    .put("id", "a$creates").put("etag", "created"))))
            return Person.parse(json).also { remote[it.resource] = it }
        }
        override fun update(person: Person, fields: Set<String>, body: JSONObject): Person {
            if (!canUpload()) throw UploadDisabled()
            updates++
            val json = JSONObject(remote.getValue(person.resource).json.toString())
            for (field in fields) json.put(field, body.getJSONArray(field))
            json.getJSONObject("metadata").getJSONArray("sources").getJSONObject(0).put("etag", "updated-$updates")
            return Person.parse(json).also { remote[it.resource] = it }
        }
        override fun delete(resource: String) {
            if (!canUpload()) throw UploadDisabled()
            deletes++; remote.remove(resource)
        }
    }
    private inner class Fixture(val provider: ImportContactsProvider, val client: ContentProviderClient, val prefs: ContactSyncPreferences) {
        val api = Api()
        val store get() = AndroidContactStore(client, account, prefs, backendOwner = PrivatePeopleApi.OWNER)
        fun sync() = ContactsSync(api, store) { prefs.mode }.sync()
        fun imported(source: String? = null, data: Person = person(), rawAccount: Account? = account, owner: String? = null,
            dirty: Boolean = true, dataset: String? = null, deleted: Boolean = false): Long =
            provider.addRaw(rawAccount, source, owner, dirty, dataset, deleted).also { provider.addFields(it, data) }
        fun editPhone(id: Long, number: String) = provider.update(Data.CONTENT_URI, ContentValues().apply { put(Phone.NUMBER, number) },
            "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(id.toString(), Phone.CONTENT_ITEM_TYPE))
        fun raw(id: Long, column: String): String? = provider.query(RawContacts.CONTENT_URI, arrayOf(column), "_id=?", arrayOf(id.toString()), null).use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
    }
    private fun fixture(mode: SyncMode = SyncMode.TWO_WAY, block: Fixture.() -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("contacts-sync", 0).edit().clear().commit()
        context.getSharedPreferences("contacts-migration-privacy", 0).edit().clear().commit()
        val provider = ImportContactsProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = ContactsContract.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        val prefs = ContactSyncPreferences(context, account)
        prefs.configure(mode, 0)
        context.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY)!!.use {
            try { Fixture(provider, it, prefs).block() } finally { provider.shutdown() }
        }
    }

    @Test fun exactGoogleSourceIdAdoptsTheImportedRawRowBeforeDownload() = fixture {
        val original = person(); api.remote[original.resource] = original
        val id = imported("17")
        sync()
        val row = store.contacts().single()
        assertEquals(id, row.id); assertEquals(original.resource, row.resource); assertEquals(original.etag, row.etag)
        assertEquals(PrivatePeopleApi.OWNER, raw(id, RawContacts.SYNC4)); assertFalse(row.dirty)
        assertEquals(listOf(id), provider.rawIds); assertEquals(0, api.creates); assertEquals(0, api.updates)
    }
    @Test fun sourceLessImportUploadsOnceAndDownloadUsesTheSameRawRow() = fixture {
        val id = imported()
        assertEquals(1, sync().uploaded)
        assertEquals(1, api.creates); assertEquals(id, store.contacts().single().id)
        assertNotNull(store.contacts().single().resource)
        sync(); assertEquals(1, api.creates); assertEquals(listOf(id), provider.rawIds)
    }
    @Test fun sourceLessImportCanMatchOneCompleteRemoteContactWithoutCreatingItAgain() = fixture {
        val original = person(); api.remote[original.resource] = original
        val id = imported()
        sync()
        assertEquals(original.resource, store.contacts().single().resource); assertEquals(listOf(id), provider.rawIds)
        assertEquals(0, api.creates)
    }
    @Test fun preAdoptionDirtyEditSurvivesAsAConflictWithoutAnOriginalEtag() = fixture {
        val original = person(); api.remote[original.resource] = original
        val id = imported("17")
        editPhone(id, "456")
        assertEquals(1, sync().conflicts)
        assertEquals(0, api.updates); assertEquals(0, api.creates)
        assertEquals("123", api.remote.getValue(original.resource).json.getJSONArray("phoneNumbers").getJSONObject(0).getString("value"))
        val row = store.contacts().single()
        assertEquals(id, row.id); assertTrue(row.dirty); assertEquals("conflict", row.pending!!.getString("kind"))
        assertEquals("456", JSONArray(row.fields.getValue("phoneNumbers")).getJSONObject(0).getString("value"))
        sync(); assertEquals(0, api.updates)
    }
    @Test fun importedProjectionDoesNotEraseCloudFieldsThatSwitchDidNotCopy() = fixture {
        val original = person().also { it.json.put("emailAddresses", JSONArray().put(JSONObject().put("value", "synthetic@invalid").put("type", "home"))) }
        api.remote[original.resource] = original
        imported("17")
        sync()
        assertEquals(0, api.updates)
        assertEquals("synthetic@invalid", api.remote.getValue(original.resource).json.getJSONArray("emailAddresses").getJSONObject(0).getString("value"))
        assertEquals(store.project(api.remote.getValue(original.resource)), store.contacts().single().fields)
    }
    @Test fun foreignOrUnknownSourceIdsAreNeverAdoptedByContentOrGuessedNumericAliases() = fixture {
        val original = person(); api.remote[original.resource] = original
        val foreign = listOf("foreign-id", "23", "people/c23").map { imported(it) }
        assertEquals(3, sync().unverifiedImports)
        assertEquals(1, store.contacts().size); assertEquals(0, api.creates); assertEquals(0, api.updates)
        for (id in foreign) { assertNull(raw(id, RawContacts.SYNC4)); assertEquals("1", raw(id, RawContacts.DIRTY)) }
        assertEquals(4, provider.rawIds.size)
    }
    @Test fun accountDatasetAndOtherAdaptersRowsRemainSeparate() = fixture {
        val excluded = listOf(imported("17", rawAccount = Account("other@invalid", "com.google")), imported("17", rawAccount = null),
            imported("17", dataset = "foreign"), imported("17", owner = "foreign-adapter"))
        api.remote[person().resource] = person()
        sync()
        assertEquals(1, store.contacts().size); assertEquals(5, provider.rawIds.size)
        assertNull(raw(excluded[0], RawContacts.SYNC4)); assertNull(raw(excluded[1], RawContacts.SYNC4))
        assertNull(raw(excluded[2], RawContacts.SYNC4)); assertEquals("foreign-adapter", raw(excluded[3], RawContacts.SYNC4))
    }
    @Test fun twoSourceIdCopiesBeforeTheFirstSyncConvergeToOneRawRow() = fixture {
        api.remote[person().resource] = person()
        val first = imported("17"); imported("17")
        sync()
        assertEquals(listOf(first), provider.rawIds); assertEquals(0, api.creates); assertEquals(0, api.updates)
    }
    @Test fun secondSourceLessTransferAfterUploadAndLocalEditDoesNotRevertOrDuplicate() = fixture {
        val first = imported(); sync()
        editPhone(first, "456"); sync()
        imported(); sync(); sync()
        assertEquals(listOf(first), provider.rawIds); assertEquals(1, api.creates); assertEquals(1, api.updates)
        assertEquals("456", store.contacts().single().fields.let { JSONArray(it.getValue("phoneNumbers")).getJSONObject(0).getString("value") })
    }
    @Test fun downloadAfterImportAndSecondGoogleTransferBothKeepTheOriginalRawId() = fixture {
        val original = person(); api.remote[original.resource] = original
        val id = imported("17"); sync()
        api.remote[original.resource] = person(name = "Changed on Google", etag = "e2")
        sync(); assertEquals(id, store.contacts().single().id)
        imported("17"); sync()
        assertEquals(listOf(id), provider.rawIds); assertEquals(0, api.updates); assertEquals(0, api.creates)
        assertEquals(store.project(api.remote.getValue(original.resource)), store.contacts().single().fields)
    }
    @Test fun downloadOnlyKeepsDirtyImportEditsWithoutUploading() = fixture(SyncMode.DOWNLOAD) {
        val original = person(); api.remote[original.resource] = original
        val id = imported("17"); editPhone(id, "456")
        sync(); sync()
        val row = store.contacts().single()
        assertEquals(id, row.id); assertTrue(row.dirty); assertEquals("1", raw(id, RawContacts.RAW_CONTACT_IS_READ_ONLY))
        assertEquals("456", JSONArray(row.fields.getValue("phoneNumbers")).getJSONObject(0).getString("value"))
        assertEquals(SyncMode.DOWNLOAD, prefs.mode); assertEquals(0, api.updates)
    }
    @Test fun syncOffDoesNotReadOrAdoptAnything() = fixture(SyncMode.OFF) {
        val id = imported("17"); sync()
        assertTrue(api.listTokens.isEmpty()); assertNull(raw(id, RawContacts.SYNC4)); assertEquals(SyncMode.OFF, prefs.mode)
    }
    @Test fun migrationHoldSurvivesAdoptionAndResumesTheSameDirtyRow() = fixture {
        val privacy = ContactMigrationPrivacy(RuntimeEnvironment.getApplication())
        privacy.developerControlsEnabled = true; privacy.beginMigration()
        api.canUpload = { !ContactMigrationPrivacy(RuntimeEnvironment.getApplication()).held }
        val id = imported()
        sync()
        assertTrue(privacy.held); assertTrue(store.contacts().single().dirty); assertNull(store.contacts().single().pending)
        assertEquals(0, api.creates); assertEquals(listOf(id), provider.rawIds)
        privacy.ownerResume(); sync()
        assertEquals(1, api.creates); assertFalse(store.contacts().single().dirty)
    }
    @Test fun developerImportGateAndFloorStillExcludeUnownedRows() = fixture {
        ContactMigrationPrivacy(RuntimeEnvironment.getApplication()).developerControlsEnabled = true
        val old = imported("17")
        prefs.initializePrivateFloors(old, 0)
        prefs.uploadNewContacts = false
        val later = imported()
        sync(); assertTrue(store.contacts().isEmpty()); assertNull(raw(old, RawContacts.SYNC4)); assertNull(raw(later, RawContacts.SYNC4))
        prefs.uploadNewContacts = true; sync()
        assertEquals(1, api.creates); assertNull(raw(old, RawContacts.SYNC4)); assertEquals(later, store.contacts().single().id)
    }
    @Test fun incompleteSnapshotCannotAdoptOrUploadANewImport() = fixture {
        val id = imported(); api.skipped = 1
        val report = sync()
        assertEquals(1, report.skipped); assertNull(raw(id, RawContacts.SYNC4)); assertEquals(0, api.creates)
        assertNull(store.checkpoint())
    }
    @Test fun concurrentEditDuringAdoptionFailsTheVersionAssertionAndKeepsNativeData() = fixture {
        val id = imported("17"); api.remote[person().resource] = person()
        provider.beforeBatch = { provider.beforeBatch = null; editPhone(id, "456") }
        assertThrows(android.content.OperationApplicationException::class.java) { sync() }
        assertNull(raw(id, RawContacts.SYNC4)); assertEquals(listOf(id), provider.rawIds)
        sync(); assertEquals(0, api.updates); assertTrue(store.contacts().single().dirty)
    }
    @Test fun ambiguousContentAndDifferentDirtyDuplicateAreKeptForReview() = fixture {
        api.remote[person().resource] = person(); api.remote[person("18").resource] = person("18")
        val ambiguous = imported()
        assertTrue(sync().unverifiedImports > 0); assertNull(raw(ambiguous, RawContacts.SYNC4)); assertEquals(0, api.creates)
        val changed = imported("17", person(phone = "999"))
        assertTrue(sync().unverifiedImports > 0); assertNull(raw(changed, RawContacts.SYNC4)); assertEquals(0, api.updates)
    }
    @Test fun sourceLessRepeatFindsOwnedIdentityEvenWithADirtyNativeEdit() = fixture {
        val original = person(); api.remote[original.resource] = original
        sync()
        val keeper = store.contacts().single().id
        editPhone(keeper, "456")
        imported()
        sync()
        assertEquals(listOf(keeper), provider.rawIds); assertEquals(1, api.updates); assertEquals(0, api.creates)
    }
    @Test fun identicalSecondImportCollapsesWhileFirstImportIsHeldInConflict() = fixture {
        api.remote[person().resource] = person()
        val first = imported("17", person(phone = "456")); sync()
        val pending = canonical(store.contacts().single().pending)
        imported("17", person(phone = "456")); sync()
        assertEquals(listOf(first), provider.rawIds)
        assertEquals(pending, canonical(store.contacts().single().pending)); assertEquals(0, api.updates)
    }
    @Test fun unknownSourceDoesNotHoldCheckpointOrUnrelatedRemoteDeletion() = fixture {
        api.remote[person().resource] = person()
        val foreign = imported("foreign")
        assertEquals(1, sync().unverifiedImports); assertEquals("cursor", store.checkpoint())
        val downloaded = store.contacts().single().id
        api.remote.clear()
        api.tombstones.add(Person.parse(JSONObject().put("resourceName", person().resource).put("metadata", JSONObject().put("deleted", true))))
        assertEquals(1, sync().unverifiedImports)
        assertEquals(listOf(null, "cursor"), api.listTokens)
        assertEquals(listOf(foreign), provider.rawIds); assertFalse(provider.rawIds.contains(downloaded))
    }
    @Test fun unknownSourceCanBeRevisitedWhenTheRemoteIdentityAppearsLater() = fixture {
        val id = imported("17")
        sync(); assertNull(raw(id, RawContacts.SYNC4))
        api.remote[person().resource] = person()
        sync()
        assertEquals(listOf(id), provider.rawIds); assertEquals(person().resource, store.contacts().single().resource)
        assertEquals(listOf(null, "cursor"), api.listTokens)
    }
    @Test fun nativeEditAndDeletionAfterAdoptionUseTheSameCloudIdentity() = fixture {
        val original = person(); api.remote[original.resource] = original
        val id = imported("17"); sync()
        editPhone(id, "456"); sync()
        assertEquals(1, api.updates); assertEquals(0, api.creates)
        provider.delete(RawContacts.CONTENT_URI, "_id=?", arrayOf(id.toString()))
        sync(); assertEquals(1, api.deletes); assertTrue(provider.rawIds.isEmpty())
    }
    @Test fun repeatedImportRetainsTheFavoriteFlag() = fixture {
        api.remote[person().resource] = person()
        val id = imported("17"); sync()
        val duplicate = imported("17")
        provider.update(RawContacts.CONTENT_URI, ContentValues().apply { put(RawContacts.STARRED, 1) }, "_id=?", arrayOf(duplicate.toString()))
        // Reconciliation itself must retain STARRED even without a group mapper.
        store.reconcileImports(listOf(person()), full = true)
        assertEquals(listOf(id), provider.rawIds); assertEquals("1", raw(id, RawContacts.STARRED)); assertTrue(store.contacts().single().dirty)
    }
    @Test fun photoOnlySourceLessContactRemainsEligibleForCreation() = fixture {
        val empty = Person.parse(JSONObject().put("resourceName", "people/fsa_17").put("metadata", JSONObject().put("sources", JSONArray()
            .put(JSONObject().put("type", "CONTACT").put("id", "17").put("etag", "e1")))))
        val id = imported(data = empty)
        provider.insert(Data.CONTENT_URI, ContentValues().apply {
            put(Data.RAW_CONTACT_ID, id); put(Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.Photo.PHOTO, byteArrayOf(1, 2, 3))
        })
        sync(); assertEquals(1, api.creates); assertEquals(listOf(id), provider.rawIds)
    }
    @Test fun originalFavoriteDoesNotReturnWhenTheSameImportIsRepeatedAfterAnUnstar() = fixture {
        api.remote[person().resource] = person()
        val id = imported("17")
        provider.update(RawContacts.CONTENT_URI, ContentValues().apply { put(RawContacts.STARRED, 1) }, "_id=?", arrayOf(id.toString()))
        store.reconcileImports(listOf(person()), full = true)
        provider.update(RawContacts.CONTENT_URI, ContentValues().apply { put(RawContacts.STARRED, 0) }, "_id=?", arrayOf(id.toString()))
        val duplicate = imported("17")
        provider.update(RawContacts.CONTENT_URI, ContentValues().apply { put(RawContacts.STARRED, 1) }, "_id=?", arrayOf(duplicate.toString()))
        store.reconcileImports(listOf(person()), full = true)
        assertEquals(listOf(id), provider.rawIds); assertEquals("0", raw(id, RawContacts.STARRED))
    }
    @Test fun differentPhotoOnlyImportsDoNotShareAnEmptyTextIdentity() = fixture {
        val empty = Person.parse(JSONObject().put("resourceName", "people/fsa_17").put("metadata", JSONObject().put("sources", JSONArray()
            .put(JSONObject().put("type", "CONTACT").put("id", "17").put("etag", "e1")))))
        fun photo(bytes: ByteArray): Long = imported(data = empty).also { id ->
            provider.insert(Data.CONTENT_URI, ContentValues().apply {
                put(Data.RAW_CONTACT_ID, id); put(Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Photo.PHOTO, bytes)
            })
        }
        val first = photo(byteArrayOf(1, 2, 3)); sync()
        val second = photo(byteArrayOf(4, 5, 6)); sync()
        assertEquals(2, api.creates); assertEquals(listOf(first, second), provider.rawIds)
        photo(byteArrayOf(1, 2, 3)); sync()
        assertEquals(2, api.creates); assertEquals(listOf(first, second), provider.rawIds)
    }
    @Test fun incompleteImportSnapshotStillAllowsOwnedContactsToConverge() = fixture {
        val original = person(); api.remote[original.resource] = original; sync()
        val owned = store.contacts().single().id
        editPhone(owned, "456")
        api.remote[original.resource] = person(name = "Remote edit", etag = "e2")
        val unowned = imported(data = person("18", name = "New copy"))
        api.remote[person("19").resource] = person("19", name = "New cloud contact")
        api.skipped = 1
        val report = sync()
        assertEquals(1, report.skipped); assertEquals(1, report.uploaded)
        assertEquals(1, api.updates); assertEquals(0, api.creates)
        assertNull(raw(unowned, RawContacts.SYNC4)); assertEquals(listOf(owned, unowned), provider.rawIds)
        assertEquals(store.project(api.remote.getValue(original.resource)), store.contacts().single().fields)
    }
}
