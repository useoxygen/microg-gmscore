/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.net.URLDecoder

class PrivatePeopleExtrasMutationTest {
    private val context = PrivatePeopleContext("25.24.32")
    private fun query(url: String) = URI(url).rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.associate {
        it.split('=', limit = 2).let { p -> URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p[1], "UTF-8") }
    }
    private fun group(name: String = "Synthetic", marker: String? = null) = JSONObject().put("id", JSONObject().put("id", "42"))
        .put("fingerprint", "fresh").put("statusCode", "OK").put("result", JSONObject().put("id", JSONObject().put("id", "42"))
            .put("name", JSONObject().put("value", name)).put("groupType", JSONObject().put("type", "CONTACT_GROUP")).apply {
                marker?.let { put("clientData", JSONArray().put(JSONObject().put("key", ContactGroup.CREATE_KEY).put("value", it))) }
            })
    private fun groupReply(raw: JSONObject) = HttpReply(200, JSONObject().put("groups", JSONArray().put(raw)).toString())
    private fun remoteGroup() = ContactGroup.parse(JSONObject().put("resourceName", PrivatePeopleApi.groupResource("42"))
        .put("etag", "before").put("name", "Synthetic").put("groupType", "USER_CONTACT_GROUP"))
    @Test fun groupCreateAndRenameHaveExplicitTypeMatchingInnerOuterIdsAndFingerprint() {
        var calls = 0
        val api = PrivatePeopleApi(HttpTransport { method, url, body ->
            calls++; assertEquals("/v2/people/contactGroups", URI(url).path)
            val input = JSONObject(body!!)
            assertEquals(canonical(context.json()), canonical(input.getJSONObject("context")))
            val mutation = input.getJSONArray("groups").getJSONObject(0); val value = mutation.getJSONObject("group")
            assertEquals("CONTACT_GROUP", value.getJSONObject("groupType").getString("type"))
            if (method == "POST") {
                assertFalse(value.has("id")); assertEquals("operation", value.getJSONArray("clientData").getJSONObject(0).getString("value"))
                groupReply(group(marker = "operation"))
            } else {
                assertEquals("PUT", method); assertEquals("name", input.getString("updateMask"))
                assertEquals("FAIL_REQUEST", input.getString("fingerprintMismatchAction"))
                assertEquals("42", mutation.getJSONObject("id").getString("id")); assertEquals("42", value.getJSONObject("id").getString("id"))
                assertEquals("before", mutation.getString("fingerprint")); groupReply(group("Renamed"))
            }
        }, context, { true })
        assertEquals("operation", api.createGroup("Synthetic", "operation").creationId())
        assertEquals("Renamed", api.updateGroup(remoteGroup(), "Renamed").name); assertEquals(2, calls)
    }
    @Test fun groupDeleteNeverDeletesContactsAndRequiresCheckedAbsence() {
        val api = PrivatePeopleApi(HttpTransport { method, url, _ ->
            val q = query(url)
            if (method == "DELETE") {
                assertEquals("42", q["groupId"])
                assertEquals("false", q["deleteContacts"]); assertEquals("ANDROID_FSA2", q["deletionContext.mutationContext.source"])
                groupReply(JSONObject().put("id", JSONObject().put("id", "42")).put("statusCode", "OK"))
            } else {
                // Absence is checked against a full fresh list; direct group reads are refused by Google.
                assertTrue(java.net.URI(url).path.endsWith("contactGroups/list")); HttpReply(200, """{"groups":[]}""")
            }
        }, context, { true })
        api.deleteGroup(PrivatePeopleApi.groupResource("42"))
        for (id in PrivatePeopleApi.SYSTEM_GROUPS) assertThrows(IllegalArgumentException::class.java) { api.deleteGroup(PrivatePeopleApi.groupResource(id)) }
    }
    @Test fun embeddedGroupRejectionsAndMismatchedIdentitiesNeverAcknowledgeWrites() {
        for (raw in listOf(JSONObject().put("id", JSONObject().put("id", "42")).put("statusCode", "INVALID_ARGUMENT").put("error", "secret"),
            group().put("statusCode", "ABORTED"), group().apply { getJSONObject("result").getJSONObject("id").put("id", "43") })) {
            val api = PrivatePeopleApi(HttpTransport { _, _, _ -> groupReply(raw) }, context, { true })
            val error = assertThrows(IOException::class.java) { api.updateGroup(remoteGroup(), "Renamed") }
            assertFalse(error.toString().contains("secret"))
        }
    }
    private class GroupStoreMemory : GroupStore {
        var row = LocalGroup(1, 1, null, null, "Synthetic", null, true, false)
        var token: String? = null
        override fun groupCheckpoint() = token
        override fun groupCheckpoint(token: String) { this.token = token }
        override fun groups() = listOf(row)
        override fun applyGroup(group: ContactGroup, existing: LocalGroup?) { error("Recovery must use the journal owner") }
        override fun removeGroup(existing: LocalGroup) { error("Not a delete test") }
        override fun beginGroup(existing: LocalGroup, pending: JSONObject) = existing.copy(version = existing.version + 1, pending = pending).also { row = it }
        override fun rejectGroup(existing: LocalGroup) { row = row.copy(pending = null) }
        override fun acknowledgeGroup(existing: LocalGroup, remote: ContactGroup?) {
            row = row.copy(resource = remote!!.resource, etag = remote.etag, baseline = remote.name, dirty = false, pending = null)
        }
    }
    @Test fun lostGroupCreateReplyUsesMarkerAndNeverReposts() {
        val store = GroupStoreMemory(); var remote: JSONObject? = null; var posts = 0
        val transport = HttpTransport { method, url, body ->
            if (method == "GET") HttpReply(200, JSONObject().put("groups", JSONArray().apply { remote?.let { put(it) } }).put("nextSyncToken", "groups-next").toString())
            else {
                posts++; assertEquals("POST", method); assertEquals("create", store.row.pending!!.getString("kind"))
                val marker = JSONObject(body!!).getJSONArray("groups").getJSONObject(0).getJSONObject("group").getJSONArray("clientData").getJSONObject(0).getString("value")
                remote = group(marker = marker); throw IOException("Reply lost")
            }
        }
        assertThrows(IOException::class.java) { GroupsSync(PrivatePeopleApi(transport, context, { true }), store) { SyncMode.TWO_WAY }.sync() }
        assertNull(store.token)
        assertEquals(1, GroupsSync(PrivatePeopleApi(transport, context, { true }), store) { SyncMode.TWO_WAY }.sync().uploaded)
        assertEquals(1, posts); assertNull(store.row.pending); assertEquals("groups-next", store.token)
    }
    private fun personRaw() = JSONObject(javaClass.getResource("/private-people/full.json")!!.readText()).getJSONArray("people").getJSONObject(0)
    private val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
    @Test fun photoUploadAndDeleteUseHexSourceIdsTheirOwnContextAndReadback() {
        var raw = personRaw(); var writes = 0
        val api = PrivatePeopleApi(HttpTransport { method, url, body ->
            when (method) {
                "PUT" -> {
                    writes++; assertEquals("/v2/people/photos", URI(url).path)
                    val input = JSONObject(body!!); assertEquals("17", input.getString("containerId")); assertEquals("CONTACT_PHOTO", input.getString("type"))
                    assertEquals("PNG", input.getString("format")); assertEquals(ContactPhotos.base64(png), input.getString("photoBytes"))
                    assertEquals(canonical(context.json()), canonical(input.getJSONObject("context")))
                    HttpReply(200, """{"photoUrl":"https://lh3.googleusercontent.com/synthetic","photoToken":"synthetic"}""")
                }
                "DELETE" -> {
                    writes++; assertEquals("/v2/people/photos/delete", URI(url).path)
                    assertEquals("17", query(url)["contactId"]); assertTrue(context.parameters().all { query(url)[it.key] == it.value })
                    raw = personRaw().apply { remove("photo") }; HttpReply(200, "{}")
                }
                else -> HttpReply(200, JSONObject().put("personResponse", JSONArray().put(JSONObject().put("personId", "c23").put("status", "SUCCESS").put("person", raw))).toString())
            }
        }, context, { true })
        assertNotNull(ContactPhotos.reference(api.writePhoto("people/fsa_17", png)))
        assertNull(ContactPhotos.reference(api.writePhoto("people/fsa_17", null))); assertEquals(2, writes)
    }
    @Test fun invalidImageAndDisabledOptInFailBeforePhotoTransport() {
        val api = PrivatePeopleApi(HttpTransport { _, _, _ -> error("No dispatch") }, context, { true })
        assertThrows(UnreadableContact::class.java) { api.writePhoto("people/fsa_17", byteArrayOf(1, 2, 3)) }
        assertThrows(UploadDisabled::class.java) { PrivatePeopleApi(HttpTransport { _, _, _ -> error("No dispatch") }, context).writePhoto("people/fsa_17", png) }
    }
    @Test fun successfulDeleteHttpWithoutRemoteAbsenceDoesNotAcknowledgeContact() {
        val api = PrivatePeopleApi(HttpTransport { method, _, _ ->
            if (method == "DELETE") HttpReply(200, "{}")
            else HttpReply(200, JSONObject().put("personResponse", JSONArray().put(JSONObject().put("personId", "c23").put("status", "SUCCESS").put("person", personRaw()))).toString())
        }, context, { true })
        assertThrows(UnreadableContact::class.java) { api.delete("people/fsa_17") }
    }
    @Test fun allExtendedWritableFieldsRoundTripTheirPrivateNamesAndDates() {
        val person = PrivatePeopleApi.normalize(personRaw()).single()
        val fields = PeopleApi.EDITABLE_FIELDS - "memberships"
        val payload = JSONObject().apply { fields.forEach { put(it, person.json.getJSONArray(it)) } }
        val api = PrivatePeopleApi(HttpTransport { _, url, body ->
            val input = JSONObject(body!!)
            assertEquals("Example", input.getJSONArray("address").getJSONObject(0).getString("locality"))
            assertEquals("Description", input.getJSONArray("organization").getJSONObject(0).getString("description"))
            assertEquals("work", input.getJSONArray("organization").getJSONObject(0).getString("stringType"))
            assertEquals("Synthetic Relation", input.getJSONArray("relation").getJSONObject(0).getString("value"))
            assertEquals("synthetic-chat", input.getJSONArray("im").getJSONObject(0).getString("value"))
            assertEquals(4, input.getJSONArray("event").getJSONObject(0).getJSONObject("calendarDay").getInt("month"))
            assertTrue(query(url)["fieldMask"]!!.contains("person.user_defined"))
            HttpReply(200, JSONObject().put("personResponse", JSONObject().put("status", "SUCCESS").put("person", personRaw())).toString())
        }, context, { true })
        api.update(person, fields, payload)
    }
    private class PhotoMemory(var row: PhotoContact, private val bytes: ByteArray?) : PhotoStore {
        var completed = 0; var installed: ByteArray? = null
        override fun photoContacts() = listOf(row)
        override fun photoBytes(existing: PhotoContact) = bytes
        override fun beginPhoto(existing: PhotoContact, pending: JSONObject): PhotoContact {
            return existing.copy(contact = existing.contact.copy(version = existing.contact.version + 1, pending = pending)).also { row = it }
        }
        override fun rejectPhoto(existing: PhotoContact) { row = row.copy(contact = row.contact.copy(pending = null)) }
        override fun completePhoto(existing: PhotoContact, remote: Person, sentToken: String, remoteHash: String?) {
            completed++; row = row.copy(contact = row.contact.copy(pending = null),
                baseline = PhotoBaseline(sentToken, ContactPhotos.reference(remote), ContactPhotos.revision(remote), remoteHash))
        }
        override fun installPhoto(existing: PhotoContact, remote: Person, bytes: ByteArray?) {
            assertNull(row.contact.pending); installed = bytes; row = row.copy(contact = row.contact.copy(dirty = false))
        }
    }
    @Test fun lostPrivatePhotoRepliesReconcileByReadbackAndTransformedImagesAdoptGooglesVersion() {
        for (action in listOf("upload", "delete", "transformed")) {
            var raw = personRaw(); var image = byteArrayOf(7); var writes = 0; var drop = true
            val initial = PrivatePeopleApi.normalize(raw).single()
            val local = PhotoContact(LocalContact(1, 1, initial.resource, initial.etag, emptyMap(), emptyMap(), true, false),
                "edited", PhotoBaseline("before", ContactPhotos.reference(initial), ContactPhotos.revision(initial), ContactPhotos.hash(image)),
                ContactPhotos.reference(initial), remoteRevision = ContactPhotos.revision(initial))
            val store = PhotoMemory(local, if (action == "delete") null else png)
            val transport = HttpTransport { method, url, _ ->
                val path = URI(url).path
                when {
                    method == "POST" -> HttpReply(200, JSONObject().put("personResponse", JSONArray().put(JSONObject().put("personId", "c23")
                        .put("status", "SUCCESS").put("person", raw))).toString())
                    method == "GET" -> HttpReply(200, JSONObject().put("format", "PNG").put("photoBytes", ContactPhotos.base64(image)).toString())
                    else -> {
                        assertEquals("photo", store.row.contact.pending!!.getString("kind")); writes++
                        if (method == "DELETE") { assertTrue(path.endsWith("/photos/delete")); raw.remove("photo") }
                        else {
                            assertEquals("PUT", method); image = if (action == "transformed") png + byteArrayOf(1) else png
                            raw.getJSONArray("photo").getJSONObject(0).put("photoToken", "after")
                        }
                        if (drop) throw IOException("Reply lost")
                        HttpReply(200, "{}")
                    }
                }
            }
            fun sync() = PrivatePeopleApi(transport, context, { true }).let { PhotosSync(it, it, store) { SyncMode.TWO_WAY }.sync() }
            assertThrows(IOException::class.java) { sync() }
            assertNotNull(store.row.contact.pending); drop = false
            val report = sync()
            assertEquals(1, writes)
            if (action == "transformed") { // Google re-encoded it: take Google's version, never replay
                assertEquals(1, report.downloaded); assertNull(store.row.contact.pending); assertArrayEquals(png + byteArrayOf(1), store.installed)
            }
            else { assertEquals(1, report.uploaded); assertNull(store.row.contact.pending); assertEquals(1, store.completed) }
        }
    }
    @Test fun groupFingerprintConflictIsHeldWithoutRetryingTheMutation() {
        val store = GroupStoreMemory().apply { row = row.copy(resource = remoteGroup().resource, etag = "before", baseline = "Synthetic", name = "Renamed") }
        var puts = 0
        val transport = HttpTransport { method, _, _ ->
            if (method == "PUT") { puts++; groupReply(group().put("statusCode", "ABORTED")) }
            else HttpReply(200, JSONObject().put("groups", JSONArray().put(group())).put("nextSyncToken", "next").toString())
        }
        repeat(2) {
            assertEquals(1, GroupsSync(PrivatePeopleApi(transport, context, { true }), store) { SyncMode.TWO_WAY }.sync().conflicts)
        }
        assertEquals(1, puts); assertEquals("conflict", store.row.pending!!.getString("kind")); assertNull(store.token)
    }

    @Test fun photoFingerprintConflictIsDurablyHeldWithoutAnotherUpload() {
        val raw = personRaw(); val person = PrivatePeopleApi.normalize(raw).single()
        val local = PhotoContact(LocalContact(1, 1, person.resource, person.etag, emptyMap(), emptyMap(), true, false),
            "edited", PhotoBaseline("before", ContactPhotos.reference(person), ContactPhotos.revision(person), "old-hash"), ContactPhotos.reference(person))
        val store = PhotoMemory(local, png); var uploads = 0
        val transport = HttpTransport { method, _, _ ->
            if (method == "PUT") { uploads++; HttpReply(200, """{"personResponse":{"responseStatus":{"code":9}}}""") }
            else HttpReply(200, JSONObject().put("personResponse", JSONArray().put(JSONObject().put("personId", "c23").put("status", "SUCCESS").put("person", raw))).toString())
        }
        repeat(2) {
            val api = PrivatePeopleApi(transport, context, { true })
            assertEquals(1, PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync().conflicts)
        }
        assertEquals(1, uploads); assertTrue(store.row.contact.pending!!.getBoolean("conflict"))
    }

    @Test fun mutationValidationRejectionClearsGroupJournalAndCorrectedNameCanSync() {
        val store = GroupStoreMemory(); var reject = true; var writes = 0
        val transport = HttpTransport { method, _, body ->
            if (method == "GET") HttpReply(200, """{"nextSyncToken":"next"}""")
            else {
                writes++
                if (reject) HttpReply(400, """{"error":{"status":"INVALID_ARGUMENT","message":"Invalid group name"}}""")
                else groupReply(group(JSONObject(body!!).getJSONArray("groups").getJSONObject(0).getJSONObject("group").getJSONObject("name").getString("value")))
            }
        }
        fun sync() = GroupsSync(PrivatePeopleApi(transport, context, { true }), store) { SyncMode.TWO_WAY }.sync()
        assertEquals(1, sync().conflicts); assertNull(store.row.pending); assertTrue(store.row.dirty); assertNull(store.token)
        reject = false; store.row = store.row.copy(name = "Corrected name")
        assertEquals(1, sync().uploaded); assertNull(store.row.pending); assertFalse(store.row.dirty); assertEquals(2, writes)
    }

}
