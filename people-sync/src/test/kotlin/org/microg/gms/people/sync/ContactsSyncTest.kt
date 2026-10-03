/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ContactsSyncTest {
    private fun fields(name: String = "Alice", phone: String = "123") = mapOf(
        "names" to "[{\"givenName\":\"$name\"}]", "phoneNumbers" to "[{\"value\":\"$phone\"}]")
    private fun person(id: String = "a", etag: String = "e1", fields: Fields = fields(), creation: String? = null): Person {
        val json = JSONObject().put("resourceName", "people/$id").put("metadata", JSONObject().put("sources",
            JSONArray().put(JSONObject().put("type", "CONTACT").put("id", id).put("etag", etag))))
        fields.forEach { (key, value) -> json.put(key, JSONArray(value)) }
        if (creation != null) json.put("clientData", JSONArray().put(JSONObject().put("key", Person.CREATE_KEY).put("value", creation)))
        return Person.parse(json)
    }
    private fun deleted(id: String) = Person.parse(JSONObject().put("resourceName", "people/$id")
        .put("metadata", JSONObject().put("deleted", true)))

    private class Store : ContactStore {
        var token: String? = null
        var commits = 0
        var afterBegin: (() -> Unit)? = null
        var nextId = 1L
        val rows = linkedMapOf<Long, LocalContact>()
        override fun checkpoint() = token
        override fun checkpoint(token: String) { this.token = token; commits++ }
        override fun contacts() = rows.values.toList()
        override fun project(person: Person): Fields = person.json.keys().asSequence()
            .filter { it in PeopleApi.EDITABLE_FIELDS }.associateWith { canonical(person.json.get(it)) }
        override fun apply(person: Person, existing: LocalContact?, readOnly: Boolean) {
            if (existing != null) check(rows.getValue(existing.id).version == existing.version)
            val id = existing?.id ?: nextId++
            rows[id] = LocalContact(id, (existing?.version ?: 0) + 1, person.resource, person.etag,
                project(person), project(person), false, false)
        }
        override fun remove(existing: LocalContact) {
            check(rows.getValue(existing.id).version == existing.version)
            rows.remove(existing.id)
        }
        override fun begin(existing: LocalContact, pending: JSONObject): LocalContact {
            check(rows.getValue(existing.id).version == existing.version)
            return existing.copy(version = existing.version + 1, pending = pending).also { rows[existing.id] = it; afterBegin?.invoke() }
        }
        override fun rejected(existing: LocalContact) { rows[existing.id] = rows.getValue(existing.id).copy(pending = null) }
        override fun acknowledge(existing: LocalContact, remote: Person?) {
            val current = rows.getValue(existing.id)
            if (remote == null) remove(existing)
            else if (current.version == existing.version) apply(remote, current, false)
            else {
                val sent = existing.pending?.optJSONObject("local")?.let { ContactsSync.jsonFields(it) } ?: existing.fields
                val merged = project(remote).toMutableMap()
                for (field in PeopleApi.EDITABLE_FIELDS) if ((current.fields[field] ?: "[]") != (sent[field] ?: "[]")) {
                    merged[field] = current.fields[field] ?: "[]"
                }
                rows[existing.id] = current.copy(resource = remote.resource, etag = remote.etag,
                    fields = merged.filterValues { it != "[]" }, baseline = project(remote), pending = null, dirty = true)
            }
        }
        fun edit(id: Long = 1, fields: Fields = rows.getValue(id).fields, deleted: Boolean = false) {
            rows[id] = rows.getValue(id).copy(version = rows.getValue(id).version + 1, fields = fields, dirty = true, deleted = deleted)
        }
    }
    private inner class Api : ContactsApi {
        val remote = linkedMapOf<String, Person>()
        var page: ((String?, String?) -> Page)? = null
        var writes = 0
        var creates = 0
        var createFailure = false
        var updateFailure: ApiException? = null
        var afterWrite: (() -> Unit)? = null
        var mask: Set<String>? = null
        override fun list(syncToken: String?, pageToken: String?) = page?.invoke(syncToken, pageToken)
            ?: Page(remote.values.toList(), null, "checkpoint")
        override fun get(resource: String) = remote[resource]
        override fun create(body: JSONObject): Person {
            writes++; creates++
            val result = person("created", "created-etag", body.keys().asSequence().filter { it in PeopleApi.EDITABLE_FIELDS }
                .associateWith { canonical(body.get(it)) }, body.getJSONArray("clientData").getJSONObject(0).getString("value"))
            remote[result.resource] = result
            afterWrite?.invoke()
            if (createFailure) throw IOException("Reply lost")
            return result
        }
        override fun update(person: Person, fields: Set<String>, body: JSONObject): Person {
            updateFailure?.let { throw it }
            writes++; mask = fields
            assertEquals(remote.getValue(person.resource).etag, person.etag)
            val values = Store().project(remote.getValue(person.resource)).toMutableMap()
            fields.forEach { values[it] = canonical(body.get(it)) }
            val result = person(person.resource.removePrefix("people/"), "updated-etag", values)
            remote[result.resource] = result
            afterWrite?.invoke()
            return result
        }
        override fun delete(resource: String) { writes++; remote.remove(resource); afterWrite?.invoke() }
    }
    private fun setup(): Pair<Store, Api> {
        val store = Store()
        val api = Api().apply { remote["people/a"] = person() }
        ContactsSync(api, store) { SyncMode.DOWNLOAD }.sync()
        return store to api
    }

    @Test fun offDoesNotReadOrWrite() {
        val store = Store(); val api = Api().apply { page = { _, _ -> error("Unexpected read") } }
        assertEquals(SyncReport(0, 0, 0, 0), ContactsSync(api, store) { SyncMode.OFF }.sync())
    }
    @Test fun disablingUploadsBeforeDispatchDoesNotLeaveAnUnsentJournal() {
        val (store, api) = setup()
        store.edit(fields = fields(phone = "456"))
        var mode = SyncMode.TWO_WAY
        store.afterBegin = { mode = SyncMode.OFF }
        ContactsSync(api, store) { mode }.sync()
        assertEquals(0, api.writes)
        assertNull(store.rows.getValue(1).pending)
        assertTrue(store.rows.getValue(1).dirty)
        store.afterBegin = null
        mode = SyncMode.TWO_WAY
        ContactsSync(api, store) { mode }.sync()
        assertEquals(1, api.writes)
        assertFalse(store.rows.getValue(1).dirty)
    }
    @Test fun fullDownloadAndReplayDoNotDuplicate() {
        val (store, api) = setup()
        ContactsSync(api, store) { SyncMode.DOWNLOAD }.sync()
        assertEquals(1, store.rows.size); assertEquals(0, api.writes)
    }
    @Test fun partialPageCannotDeleteContactsOrAdvanceToken() {
        val (store, api) = setup(); store.token = null
        api.page = { _, page -> if (page == null) Page(emptyList(), "next", null) else throw IOException("Offline") }
        assertThrows(IOException::class.java) { ContactsSync(api, store) { SyncMode.DOWNLOAD }.sync() }
        assertEquals(1, store.rows.size); assertNull(store.token)
    }
    @Test fun paginationRetainsParametersAndCommitsLastToken() {
        val store = Store(); val api = Api(); val requests = mutableListOf<Pair<String?, String?>>()
        api.page = { sync, page -> requests.add(sync to page)
            if (page == null) Page(listOf(person()), "next", null) else Page(listOf(person("b")), null, "last") }
        ContactsSync(api, store) { SyncMode.DOWNLOAD }.sync()
        assertEquals(listOf(null to null, null to "next"), requests); assertEquals("last", store.token); assertEquals(2, store.rows.size)
    }
    @Test fun expiredTokenRestartsFullRead() {
        val (store, api) = setup(); val reads = mutableListOf<String?>()
        api.page = { sync, _ -> reads.add(sync); if (sync != null) throw ApiException(410, true)
            Page(listOf(person()), null, "fresh") }
        ContactsSync(api, store) { SyncMode.DOWNLOAD }.sync()
        assertEquals(listOf("checkpoint", null), reads); assertEquals("fresh", store.token)
    }
    @Test fun other410DoesNotDeleteOrReset() {
        val (store, api) = setup(); api.page = { _, _ -> throw ApiException(410) }
        assertThrows(ApiException::class.java) { ContactsSync(api, store) { SyncMode.DOWNLOAD }.sync() }
        assertEquals(1, store.rows.size); assertEquals("checkpoint", store.token)
    }
    @Test fun quotaErrorKeepsRowsAndCheckpoint() {
        val (store, api) = setup(); api.page = { _, _ -> throw ApiException(429, retryAfterSeconds = 60) }
        assertThrows(ApiException::class.java) { ContactsSync(api, store) { SyncMode.TWO_WAY }.sync() }
        assertEquals(1, store.rows.size); assertEquals(0, api.writes); assertEquals("checkpoint", store.token)
    }
    @Test fun remoteDeletionRemovesOnlyManagedRecord() {
        val (store, api) = setup(); api.page = { _, _ -> Page(listOf(deleted("a")), null, "deleted") }
        ContactsSync(api, store) { SyncMode.DOWNLOAD }.sync()
        assertTrue(store.rows.isEmpty()); assertEquals(0, api.writes)
    }
    @Test fun fullAbsenceIsVerifiedWithDirectRead() {
        val (store, api) = setup(); store.token = null; api.page = { _, _ -> Page(emptyList(), null, "fresh") }
        ContactsSync(api, store) { SyncMode.DOWNLOAD }.sync()
        assertEquals(1, store.rows.size)
        api.remote.clear(); store.token = null
        ContactsSync(api, store) { SyncMode.DOWNLOAD }.sync()
        assertTrue(store.rows.isEmpty())
    }
    @Test fun readOnlyNeverUploadsDirtyLocalChanges() {
        val (store, api) = setup(); store.edit(fields = fields(phone = "456"))
        assertEquals(1, ContactsSync(api, store) { SyncMode.DOWNLOAD }.sync().conflicts)
        assertEquals(0, api.writes); assertTrue(store.rows.getValue(1).dirty)
    }
    @Test fun uploadsChangedFieldsOnly() {
        val (store, api) = setup(); store.edit(fields = fields(phone = "456"))
        assertEquals(1, ContactsSync(api, store) { SyncMode.TWO_WAY }.sync().uploaded)
        assertEquals(setOf("phoneNumbers"), api.mask); assertFalse(store.rows.getValue(1).dirty)
    }
    @Test fun disjointEditsMergeUsingLatestEtag() {
        val (store, api) = setup(); store.edit(fields = fields(phone = "456")); api.remote["people/a"] = person(etag = "e2", fields = fields(name = "Alicia"))
        val report = ContactsSync(api, store) { SyncMode.TWO_WAY }.sync()
        assertEquals(0, report.conflicts); assertEquals(fields(name = "Alicia", phone = "456"), store.rows.getValue(1).fields)
    }
    @Test fun sameFieldConflictPreservesBothSides() {
        val (store, api) = setup(); store.edit(fields = fields(phone = "456")); api.remote["people/a"] = person(etag = "e2", fields = fields(phone = "789"))
        val report = ContactsSync(api, store) { SyncMode.TWO_WAY }.sync()
        assertEquals(1, report.conflicts); assertEquals(0, api.writes); assertEquals(fields(phone = "456"), store.rows.getValue(1).fields)
    }
    @Test fun remoteDeleteDoesNotOverwriteLocalEdit() {
        val (store, api) = setup(); store.edit(fields = fields(phone = "456")); api.remote.clear()
        api.page = { _, _ -> Page(listOf(deleted("a")), null, "deleted") }
        assertEquals(1, ContactsSync(api, store) { SyncMode.TWO_WAY }.sync().conflicts)
        assertEquals(1, store.rows.size); assertEquals(0, api.writes)
    }
    @Test fun localDeletionUploadsAndAcknowledgesTombstone() {
        val (store, api) = setup(); store.edit(deleted = true)
        ContactsSync(api, store) { SyncMode.TWO_WAY }.sync()
        assertEquals(1, api.writes); assertTrue(api.remote.isEmpty()); assertTrue(store.rows.isEmpty())
    }
    @Test fun deletionConflictsWithNewRemoteVersion() {
        val (store, api) = setup(); store.edit(deleted = true); api.remote["people/a"] = person(etag = "e2")
        assertEquals(1, ContactsSync(api, store) { SyncMode.TWO_WAY }.sync().conflicts); assertEquals(0, api.writes)
    }
    @Test fun createIsJournaledBeforeDispatchAndLostReplyIsRecovered() {
        val store = Store(); val api = Api().apply { createFailure = true }
        store.rows[1] = LocalContact(1, 1, null, null, fields(), emptyMap(), true, false)
        api.afterWrite = { assertEquals("create", store.rows.getValue(1).pending!!.getString("kind")) }
        assertThrows(IOException::class.java) { ContactsSync(api, store) { SyncMode.TWO_WAY }.sync() }
        assertNotNull(store.rows.getValue(1).pending)
        val report = ContactsSync(api, store) { SyncMode.TWO_WAY }.sync()
        assertEquals(1, report.uploaded); assertEquals(1, api.creates); assertEquals(1, store.rows.size)
        assertEquals("people/created", store.rows.getValue(1).resource)
    }
    @Test fun lostCreateReplyWithNoReadbackIsNeverRetried() {
        val store = Store(); val api = Api().apply { createFailure = true }
        store.rows[1] = LocalContact(1, 1, null, null, fields(), emptyMap(), true, false)
        assertThrows(IOException::class.java) { ContactsSync(api, store) { SyncMode.TWO_WAY }.sync() }
        api.remote.clear()
        assertEquals(1, ContactsSync(api, store) { SyncMode.TWO_WAY }.sync().uncertain)
        assertEquals(1, api.creates); assertNull(store.token)
    }
    @Test fun concurrentLocalEditSurvivesUploadAcknowledgment() {
        val (store, api) = setup(); store.edit(fields = fields(phone = "456"))
        api.afterWrite = { store.edit(fields = fields(phone = "999")) }
        ContactsSync(api, store) { SyncMode.TWO_WAY }.sync()
        assertTrue(store.rows.getValue(1).dirty); assertEquals(fields(phone = "999"), store.rows.getValue(1).fields)
        assertEquals(fields(phone = "456"), store.rows.getValue(1).baseline)
    }
    @Test fun concurrentLocalEditDoesNotLoseDisjointServerChange() {
        val (store, api) = setup(); store.edit(fields = fields(phone = "456"))
        api.remote["people/a"] = person(etag = "e2", fields = fields(name = "Alicia"))
        api.afterWrite = { store.edit(fields = fields(phone = "999")) }
        ContactsSync(api, store) { SyncMode.TWO_WAY }.sync()
        assertEquals(fields(name = "Alicia", phone = "999"), store.rows.getValue(1).fields)
        assertEquals(fields(name = "Alicia", phone = "456"), store.rows.getValue(1).baseline)
    }
    @Test fun concurrentEditSurvivesLostCreateReplyRecovery() {
        val store = Store(); val api = Api().apply { createFailure = true }
        store.rows[1] = LocalContact(1, 1, null, null, fields(), emptyMap(), true, false)
        api.afterWrite = { store.edit(fields = fields(phone = "999")) }
        assertThrows(IOException::class.java) { ContactsSync(api, store) { SyncMode.TWO_WAY }.sync() }
        api.afterWrite = null
        ContactsSync(api, store) { SyncMode.DOWNLOAD }.sync()
        assertTrue(store.rows.getValue(1).dirty); assertEquals(fields(phone = "999"), store.rows.getValue(1).fields)
        assertEquals(1, api.creates)
    }
    @Test fun rejectedMutationKeepsDirtyAndClearsJournal() {
        val (store, api) = setup(); store.edit(fields = fields(phone = "456")); api.updateFailure = ApiException(400)
        assertEquals(1, ContactsSync(api, store) { SyncMode.TWO_WAY }.sync().conflicts)
        assertNull(store.rows.getValue(1).pending); assertTrue(store.rows.getValue(1).dirty)
    }
    @Test fun lostUpdateReplyIsRecoveredWithoutSendingAnotherPatch() {
        val (store, api) = setup(); store.edit(fields = fields(phone = "456"))
        api.afterWrite = { throw IOException("Reply lost") }
        assertThrows(IOException::class.java) { ContactsSync(api, store) { SyncMode.TWO_WAY }.sync() }
        api.afterWrite = null
        assertEquals(1, ContactsSync(api, store) { SyncMode.TWO_WAY }.sync().uploaded)
        assertEquals(1, api.writes); assertFalse(store.rows.getValue(1).dirty)
    }
    @Test fun concurrentEditSurvivesLostUpdateReplyRecovery() {
        val (store, api) = setup(); store.edit(fields = fields(phone = "456"))
        api.remote["people/a"] = person(etag = "e2", fields = fields(name = "Alicia"))
        api.afterWrite = { store.edit(fields = fields(phone = "999")); throw IOException("Reply lost") }
        assertThrows(IOException::class.java) { ContactsSync(api, store) { SyncMode.TWO_WAY }.sync() }
        api.afterWrite = null
        ContactsSync(api, store) { SyncMode.DOWNLOAD }.sync()
        assertEquals(fields(name = "Alicia", phone = "999"), store.rows.getValue(1).fields)
        assertTrue(store.rows.getValue(1).dirty); assertEquals(1, api.writes)
    }
    @Test fun duplicatePageDoesNotWriteAnyContacts() {
        val store = Store(); val api = Api().apply { page = { _, next -> Page(listOf(person()), if (next == null) "next" else null, "bad") } }
        assertThrows(IllegalStateException::class.java) { ContactsSync(api, store) { SyncMode.DOWNLOAD }.sync() }
        assertTrue(store.rows.isEmpty()); assertNull(store.token)
    }
    @Test fun missingCheckpointCannotDeleteOrWrite() {
        val store = Store(); val api = Api().apply { page = { _, _ -> Page(listOf(person()), null, null) } }
        assertThrows(IOException::class.java) { ContactsSync(api, store) { SyncMode.DOWNLOAD }.sync() }
        assertTrue(store.rows.isEmpty())
    }
    @Test fun unsupportedLocalFieldsAreNotSilentlyUploaded() {
        val (store, api) = setup(); store.edit(fields = fields(phone = "456"))
        store.rows[1] = store.rows.getValue(1).copy(unsupported = true)
        assertEquals(1, ContactsSync(api, store) { SyncMode.TWO_WAY }.sync().conflicts); assertEquals(0, api.writes)
    }
}
