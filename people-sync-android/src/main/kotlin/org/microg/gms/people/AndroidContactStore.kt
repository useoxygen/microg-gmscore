/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.ContentUris
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import java.io.ByteArrayOutputStream
import java.util.UUID
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import org.json.JSONObject
import org.microg.gms.people.sync.*

class AndroidContactStore(private val provider: ContentProviderClient, private val account: Account,
    private val prefs: ContactSyncPreferences, private val groupStore: AndroidGroupStore? = null,
    private val groupApi: GroupsApi? = null, private val backendOwner: String = OWNER,
    private val checkpointBinding: String = PeopleApi.READ_FIELDS, private val deferCheckpoint: Boolean = false) : ContactStore, PhotoStore {
    companion object { const val OWNER = "microg-people-v1" }
    private val scope = "${RawContacts.ACCOUNT_NAME}=? AND ${RawContacts.ACCOUNT_TYPE}=? AND ${RawContacts.DATA_SET} IS NULL"
    private val args get() = arrayOf(account.name, account.type)
    private fun syncUri(uri: Uri) = uri.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
    private fun rawScope(id: Long) = "$scope AND ${RawContacts._ID}=$id"
    private val privateBackend get() = backendOwner == PrivatePeopleApi.OWNER
    private var stagedCheckpoint: String? = null
    fun commitCheckpoint() { stagedCheckpoint?.let { saveCheckpoint(it) }; stagedCheckpoint = null }
    /** One-time replacement of hypothetical public-v1 imports, never native edits. */
    fun preparePrivateSync(): Boolean {
        check(privateBackend && prefs.mode != SyncMode.OFF)
        if (prefs.privateLegacyCleanupComplete) return true
        val tables = listOf(RawContacts.CONTENT_URI, ContactsContract.Groups.CONTENT_URI)
        val legacy = "$scope AND ${RawContacts.SYNC4}=?"
        val legacyArgs = args + OWNER
        val ids = tables.map { uri ->
            provider.query(uri, arrayOf("_id", "dirty", "sync3"), legacy, legacyArgs, null)!!.use { cursor ->
                val rows = mutableListOf<Long>()
                while (cursor.moveToNext()) {
                    if (cursor.getLong(1) != 0L || !cursor.isNull(2)) return false
                    rows.add(cursor.getLong(0))
                }
                rows
            }
        }
        val operations = arrayListOf<ContentProviderOperation>()
        // Recheck all legacy rows inside the same transaction as deletion. A
        // concurrent edit/journal or changed row count rolls the entire cleanup back.
        for (uri in tables) operations.add(ContentProviderOperation.newAssertQuery(syncUri(uri))
            .withSelection("$legacy AND (dirty!=0 OR sync3 IS NOT NULL)", legacyArgs).withExpectedCount(0).build())
        operations.add(ContentProviderOperation.newDelete(syncUri(tables[0]))
            .withSelection(legacy, legacyArgs).withExpectedCount(ids[0].size).build())
        if (ids[1].isNotEmpty()) operations.add(ContentProviderOperation.newAssertQuery(Data.CONTENT_URI)
            .withSelection("${Data.MIMETYPE}=? AND ${GroupMembership.GROUP_ROW_ID} IN (${ids[1].joinToString(",")})",
                arrayOf(GroupMembership.CONTENT_ITEM_TYPE)).withExpectedCount(0).build())
        operations.add(ContentProviderOperation.newDelete(syncUri(tables[1]))
            .withSelection(legacy, legacyArgs).withExpectedCount(ids[1].size).build())
        // Reset even an existing private cursor: replacement must begin with a
        // full snapshot. The reset and row removal must survive a crash together.
        operations.add(ContactsContract.SyncState.newSetOperation(account, "{}".toByteArray(Charsets.UTF_8)))
        try { provider.applyBatch(operations) }
        catch (error: android.content.OperationApplicationException) { return false }
        prefs.completePrivateLegacyCleanup()
        return true
    }
    fun configureMode() {
        check(prefs.mode != SyncMode.OFF)
        val readOnly = if (prefs.mode == SyncMode.TWO_WAY) 0 else 1
        provider.update(syncUri(RawContacts.CONTENT_URI), ContentValues().apply {
            put(RawContacts.RAW_CONTACT_IS_READ_ONLY, readOnly)
        }, "$scope AND ${RawContacts.SYNC4}=? AND ${RawContacts.RAW_CONTACT_IS_READ_ONLY}!=?", args + arrayOf(backendOwner, readOnly.toString()))
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
        if (state.optString("backend", OWNER) == backendOwner && state.optString("fields") == checkpointBinding && state.optInt("version") == 1) state.optString("token").takeIf { it.isNotBlank() } else null
    }
    override fun checkpoint(token: String) {
        check(prefs.mode != SyncMode.OFF)
        if (deferCheckpoint) stagedCheckpoint = token else saveCheckpoint(token)
    }
    private fun saveCheckpoint(token: String) {
        check(prefs.mode != SyncMode.OFF)
        ContactsContract.SyncState.set(provider, account, JSONObject().put("version", 1)
            .put("backend", backendOwner).put("fields", checkpointBinding).put("token", token).toString().toByteArray(Charsets.UTF_8))
    }
    override fun project(person: Person) = ContactFieldMapper.fields(ContactFieldMapper.rows(person)).toMutableMap().apply {
        if (groupStore != null) put("memberships", Memberships.field(Memberships.resources(person)))
    }
    private fun state(id: Long): JSONObject = provider.query(RawContacts.CONTENT_URI, arrayOf(RawContacts.SYNC2), rawScope(id), args, null)!!.use {
        check(it.moveToFirst()); it.getString(0)?.let(::JSONObject) ?: JSONObject()
    }
    private fun baselineFields(json: JSONObject): Fields = ContactsSync.jsonFields(json).filterKeys { it in PeopleApi.EDITABLE_FIELDS }
    private fun photoBaseline(json: JSONObject): PhotoBaseline? = json.optJSONObject("_photo")?.let {
        PhotoBaseline(it.getString("token"), if (it.isNull("remote")) null else it.getString("remote"),
            if (it.isNull("revision")) null else it.getString("revision"), if (it.isNull("hash")) null else it.getString("hash"))
    }
    private fun localMemberships(rows: List<ContentValues>, starred: Boolean): Set<String> = buildSet {
        for (row in rows.filter { it.getAsString(Data.MIMETYPE) == GroupMembership.CONTENT_ITEM_TYPE }) {
            val id = row.getAsLong(GroupMembership.GROUP_ROW_ID) ?: throw UnreadableContact()
            if (groupStore?.isDeleted(id) == true) continue
            add(groupStore?.resource(id) ?: "localGroup:$id")
        }
        remove(groupStore?.starredResource ?: STARRED)
        if (starred) add(groupStore?.starredResource ?: STARRED)
        if (isEmpty() && backendOwner == OWNER) add(groupStore?.myContactsResource ?: MY_CONTACTS) // The public API requires at least one contact-group membership.
    }

    override fun contacts(): List<LocalContact> = provider.query(RawContacts.CONTENT_URI, null,
        "$scope AND (${RawContacts.SYNC4}=? OR (${RawContacts.SYNC4} IS NULL AND ${RawContacts.SOURCE_ID} IS NULL AND ${RawContacts._ID}>? AND ${RawContacts.DIRTY}=1))",
        args + arrayOf(backendOwner, (if (privateBackend) prefs.privateAdoptionFloor else prefs.adoptionFloor).toString()), "${RawContacts._ID} ASC")!!.use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                fun text(column: String) = cursor.getString(cursor.getColumnIndexOrThrow(column))
                fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                val id = number(RawContacts._ID)
                val rows = provider.query(Data.CONTENT_URI, null, "${Data.RAW_CONTACT_ID}=?", arrayOf(id.toString()), "${Data._ID} ASC")!!
                    .use { ContactFieldMapper.readRows(it) }
                val membership = if (groupStore != null) localMemberships(rows, number(RawContacts.STARRED) != 0L) else emptySet()
                val oldBaseline = text(RawContacts.SYNC2)?.let(::JSONObject) ?: JSONObject()
                val legacyMemberships = groupStore != null && text(RawContacts.SOURCE_ID) != null && !oldBaseline.has("memberships")
                val legacyNeedsReview = legacyMemberships && (number(RawContacts.DIRTY) != 0L || number(RawContacts.STARRED) != 0L ||
                    rows.any { it.getAsString(Data.MIMETYPE) == GroupMembership.CONTENT_ITEM_TYPE })
                var unreadablePhoto = false
                val hasPhoto = try { photoToken(id) != ContactPhotos.NONE } catch (error: UnreadableContact) { unreadablePhoto = true; true }
                val unsupported = legacyNeedsReview || unreadablePhoto || rows.any { !ContactFieldMapper.supported(it) && it.getAsString(Data.MIMETYPE) != Photo.CONTENT_ITEM_TYPE &&
                    !(groupStore != null && it.getAsString(Data.MIMETYPE) == GroupMembership.CONTENT_ITEM_TYPE) } || membership.any { it.startsWith("localGroup:") }
                val fields = ContactFieldMapper.fields(rows).toMutableMap().apply { if (groupStore != null) put("memberships", Memberships.field(membership)) }
                add(LocalContact(id, number(RawContacts.VERSION), text(RawContacts.SOURCE_ID), text(RawContacts.SYNC1),
                    fields, text(RawContacts.SYNC2)?.let { baselineFields(JSONObject(it)) }.orEmpty(),
                    number(RawContacts.DIRTY) != 0L, number(RawContacts.DELETED) != 0L,
                    text(RawContacts.SYNC3)?.let { JSONObject(it) }, unsupported, hasPhoto, photoBaseline(oldBaseline)?.revision))
            }
        }
    }
    private fun assertVersion(existing: LocalContact) = ContentProviderOperation.newAssertQuery(syncUri(RawContacts.CONTENT_URI))
        .withSelection(rawScope(existing.id) + " AND ${RawContacts.VERSION}=?" +
            " AND (${RawContacts.SYNC4}=? OR (${RawContacts.SYNC4} IS NULL AND ${RawContacts.SOURCE_ID} IS NULL AND ${RawContacts._ID}>?))",
            args + arrayOf(existing.version.toString(), backendOwner, (if (privateBackend) prefs.privateAdoptionFloor else prefs.adoptionFloor).toString()))
        .withExpectedCount(1).build()
    private fun baseline(person: Person, existing: LocalContact?): String = JSONObject().apply {
        for ((key, value) in project(person)) put(key, org.json.JSONArray(value))
        existing?.let { state(it.id).optJSONObject("_photo")?.let { put("_photo", it) } }
        put("_photoRemote", ContactPhotos.reference(person) ?: JSONObject.NULL)
        put("_photoRevision", ContactPhotos.revision(person))
    }.toString()
    private fun photoDirty(existing: LocalContact?): Boolean {
        if (existing == null) return false
        return try { photoToken(existing.id) != (photoBaseline(state(existing.id))?.token ?: ContactPhotos.NONE) }
        catch (error: UnreadableContact) { true }
    }

    override fun apply(person: Person, existing: LocalContact?, readOnly: Boolean) {
        write(person, existing, readOnly, person, false)
    }
    private fun write(person: Person, existing: LocalContact?, readOnly: Boolean, remoteBaseline: Person, dirty: Boolean) {
        check(prefs.mode != SyncMode.OFF)
        if (groupStore != null && existing?.resource != null && !state(existing.id).has("memberships")) {
            val legacy = contacts().single { it.id == existing.id }
            if (legacy.unsupported) throw UnreadableContact() // Do not reinterpret pre-mapping native edits as cloud changes.
        }
        val memberships = if (groupStore != null) Memberships.resources(person) else emptySet()
        val membershipIds = memberships.map { resource -> groupStore!!.id(resource)
            ?: groupApi?.let { groupStore.resolve(resource, it) } ?: throw UnreadableContact() }
        val operations = arrayListOf<ContentProviderOperation>()
        existing?.let { operations.add(assertVersion(it)) }
        val rawIndex = operations.size
        val values = ContentValues().apply {
            put(RawContacts.ACCOUNT_NAME, account.name); put(RawContacts.ACCOUNT_TYPE, account.type)
            put(RawContacts.SOURCE_ID, person.resource); put(RawContacts.SYNC1, person.etag)
            put(RawContacts.SYNC2, baseline(remoteBaseline, existing)); putNull(RawContacts.SYNC3); put(RawContacts.SYNC4, backendOwner)
            put(RawContacts.DIRTY, if (dirty || photoDirty(existing)) 1 else 0)
            if (groupStore != null) put(RawContacts.STARRED, if ((groupStore?.starredResource ?: STARRED) in memberships) 1 else 0)
            put(RawContacts.RAW_CONTACT_IS_READ_ONLY, if (readOnly || prefs.mode != SyncMode.TWO_WAY) 1 else 0)
            if (groupStore == null && privateBackend && person.json.has("_privateStarred")) put(RawContacts.STARRED, if (person.json.getBoolean("_privateStarred")) 1 else 0)
        }
        operations.add(if (existing == null) ContentProviderOperation.newInsert(syncUri(RawContacts.CONTENT_URI)).withValues(values).build()
            else ContentProviderOperation.newUpdate(syncUri(RawContacts.CONTENT_URI)).withSelection(rawScope(existing.id), args).withValues(values).withExpectedCount(1).build())
        if (existing != null) {
            operations.add(ContentProviderOperation.newDelete(syncUri(Data.CONTENT_URI)).withSelection(
                "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE} IN (${ContactFieldMapper.managedMimes.joinToString(",") { "?" }})",
                arrayOf(existing.id.toString()) + ContactFieldMapper.managedMimes).build())
        }
        if (groupStore != null) {
            val delete = ContentProviderOperation.newDelete(syncUri(Data.CONTENT_URI)).withSelection(
                "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf((existing?.id ?: 0L).toString(), GroupMembership.CONTENT_ITEM_TYPE))
            if (existing == null) delete.withSelectionBackReference(0, rawIndex)
            operations.add(delete.build())
        }
        val rows = ContactFieldMapper.rows(person) + membershipIds.map { id -> ContentValues().apply {
            put(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE); put(GroupMembership.GROUP_ROW_ID, id)
        } }
        for (row in rows) {
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
            .withValue(RawContacts.SYNC4, backendOwner).withExpectedCount(1).build()))
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
                    merged.put(field, org.json.JSONArray(if (field == "memberships") Memberships.merge(sent[field], current.fields[field], project(remote)[field]) else current.fields[field] ?: "[]"))
                }
            }
            // Merge unchanged fields from the server, retain subsequent local
            // edits, and keep the actual server response as the next baseline.
            write(Person.parse(merged), current, prefs.mode != SyncMode.TWO_WAY, remote, true)
        }
    }
    private fun photoToken(id: Long): String = provider.query(Data.CONTENT_URI,
        arrayOf(Data._ID, Data.DATA_VERSION, Data.SYNC1, Photo.PHOTO_FILE_ID, Photo.PHOTO),
        "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(id.toString(), Photo.CONTENT_ITEM_TYPE), null)!!.use {
        if (!it.moveToFirst()) return@use ContactPhotos.NONE
        if (it.count != 1) throw UnreadableContact()
        if (it.isNull(3) && (it.isNull(4) || it.getBlob(4).isEmpty())) return@use ContactPhotos.NONE
        val marker = it.getString(2)
        // AOSP initializes DATA_VERSION=0 and increments it on every data update.
        // A sync-created immutable row carries its nonce only while unedited.
        if (marker?.startsWith("microg-photo:") == true && it.getLong(1) == 0L) "sync:$marker"
        else "${it.getLong(0)}:${it.getLong(1)}"
    }
    override fun photoContacts(): List<PhotoContact> = contacts().map { row ->
        val state = state(row.id)
        var unreadable = false
        val token = try { photoToken(row.id) } catch (error: UnreadableContact) { unreadable = true; "unreadable" }
        PhotoContact(row, token, photoBaseline(state), if (state.isNull("_photoRemote")) null else state.optString("_photoRemote").takeIf { it.isNotEmpty() }, unreadable,
            if (state.isNull("_photoRevision")) null else state.optString("_photoRevision"))
    }
    override fun photoBytes(existing: PhotoContact): ByteArray? {
        if (existing.token == ContactPhotos.NONE) return null
        val uri = syncUri(ContentUris.withAppendedId(RawContacts.CONTENT_URI, existing.contact.id).buildUpon().appendPath("display_photo").build())
        try {
            provider.openAssetFile(uri, "r")?.use { asset ->
                asset.createInputStream().use { input ->
                    val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        if (out.size() + count > ContactPhotos.MAX_BYTES) throw UnreadableContact()
                        out.write(buffer, 0, count)
                    }
                    if (out.size() == 0) throw UnreadableContact()
                    return out.toByteArray()
                }
            }
        } catch (error: java.io.FileNotFoundException) { /* Older providers may have only a thumbnail. */ }
        return provider.query(Data.CONTENT_URI, arrayOf(Photo.PHOTO), "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?",
            arrayOf(existing.contact.id.toString(), Photo.CONTENT_ITEM_TYPE), null)!!.use {
            if (!it.moveToFirst() || it.isNull(0)) throw UnreadableContact()
            it.getBlob(0).also { bytes -> if (bytes.isEmpty() || bytes.size > ContactPhotos.MAX_BYTES) throw UnreadableContact() }
        }
    }
    override fun beginPhoto(existing: PhotoContact, pending: JSONObject): PhotoContact {
        val saved = begin(existing.contact, pending)
        val current = photoContacts().single { it.contact.id == saved.id }
        if (current.token != existing.token) { rejected(current.contact); throw IllegalStateException("Photo changed before upload") }
        return current
    }
    override fun rejectPhoto(existing: PhotoContact) = rejected(existing.contact)
    private fun textDirty(contact: LocalContact) = contact.deleted || contact.unsupported || contact.fields != contact.baseline
    private fun photoState(json: JSONObject, token: String, remote: Person, hash: String?): JSONObject = json.apply {
        val ref = ContactPhotos.reference(remote)
        put("_photo", JSONObject().put("token", token).put("remote", ref ?: JSONObject.NULL).put("revision", ContactPhotos.revision(remote)).put("hash", hash ?: JSONObject.NULL))
        put("_photoRemote", ref ?: JSONObject.NULL); put("_photoRevision", ContactPhotos.revision(remote))
    }
    override fun completePhoto(existing: PhotoContact, remote: Person, sentToken: String, remoteHash: String?) {
        check(prefs.mode != SyncMode.OFF)
        val current = photoContacts().single { it.contact.id == existing.contact.id }
        if (existing.contact.pending != null) check(current.contact.pending?.let(::canonical) == canonical(existing.contact.pending)) { "Photo operation changed" }
        val values = ContentValues().apply {
            put(RawContacts.SYNC2, photoState(state(current.contact.id), sentToken, remote, remoteHash).toString())
            putNull(RawContacts.SYNC3)
            if (current.contact.baseline == project(remote)) put(RawContacts.SYNC1, remote.etag)
            put(RawContacts.DIRTY, if (textDirty(current.contact) || current.token != sentToken) 1 else 0)
        }
        provider.applyBatch(arrayListOf(assertVersion(current.contact), ContentProviderOperation.newUpdate(syncUri(RawContacts.CONTENT_URI))
            .withSelection(rawScope(current.contact.id), args).withValues(values).withExpectedCount(1).build()))
    }
    override fun installPhoto(existing: PhotoContact, remote: Person, bytes: ByteArray?) {
        check(prefs.mode != SyncMode.OFF)
        val prepared = bytes?.let(PhotoEncoding::providerBytes)
        val marker = "microg-photo:" + UUID.randomUUID()
        val token = if (prepared == null) ContactPhotos.NONE else "sync:$marker"
        val operations = arrayListOf(assertVersion(existing.contact), ContentProviderOperation.newDelete(syncUri(Data.CONTENT_URI))
            .withSelection("${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(existing.contact.id.toString(), Photo.CONTENT_ITEM_TYPE)).build())
        if (prepared != null) operations.add(ContentProviderOperation.newInsert(syncUri(Data.CONTENT_URI))
            .withValue(Data.RAW_CONTACT_ID, existing.contact.id).withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
            .withValue(Data.SYNC1, marker).withValue(Data.IS_PRIMARY, 1).withValue(Photo.PHOTO, prepared).build())
        val values = ContentValues().apply {
            put(RawContacts.SYNC2, photoState(state(existing.contact.id), token, remote, bytes?.let(ContactPhotos::hash)).toString())
            put(RawContacts.DIRTY, if (textDirty(existing.contact)) 1 else 0)
            if (existing.contact.baseline == project(remote)) put(RawContacts.SYNC1, remote.etag)
        }
        operations.add(ContentProviderOperation.newUpdate(syncUri(RawContacts.CONTENT_URI)).withSelection(rawScope(existing.contact.id), args)
            .withValues(values).withExpectedCount(1).build())
        provider.applyBatch(operations)
    }

}
