/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class PrivatePeopleReadExtrasTest {
    private val context = PrivatePeopleContext("25.24.32")
    private fun rawPerson(): JSONObject = JSONObject(javaClass.getResource("/private-people/full.json")!!.readText()).getJSONArray("people").getJSONObject(0)
    private fun query(url: String) = URI(url).rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
        it.split('=', limit = 2).let { values -> URLDecoder.decode(values[0], "UTF-8") to URLDecoder.decode(values[1], "UTF-8") }
    }
    private fun group(id: String, system: Boolean = false) = JSONObject().put("result", JSONObject()
        .put("id", JSONObject().put("id", id)).put("name", JSONObject().put("value", if (system) id else "Synthetic label").put("formattedValue", "Synthetic display"))
        .put("groupType", JSONObject().put("type", if (system) "SYSTEM_CONTACT_GROUP" else "CONTACT_GROUP"))).apply {
        if (!system) put("fingerprint", "synthetic-fingerprint")
    }
    @Test fun groupsMapAllSystemIdsLabelsAndIndependentPageAndSyncTokens() {
        var requests = 0
        val api = PrivatePeopleApi(HttpTransport { method, url, body ->
            requests++; assertEquals("GET", method); assertNull(body)
            val params = query(url); assertTrue(context.parameters().all { params[it.key] == it.value })
            assertEquals("100", params["pageSize"])
            when (requests) {
                1 -> HttpReply(200, JSONObject().put("groups", JSONArray().apply { for (id in PrivatePeopleApi.SYSTEM_GROUPS) put(group(id, true)) }).put("nextPageToken", "groups-page").toString())
                2 -> { assertEquals("groups-page", params["pageToken"]); HttpReply(200, JSONObject().put("groups", JSONArray().put(group("42"))).put("nextSyncToken", "groups-sync").toString()) }
                else -> { assertEquals("groups-sync", params["syncToken"]); assertEquals("INCLUDE_DELETED", params["options"])
                    HttpReply(200, """{"groups":[{"id":{"id":"42"},"statusCode":"NOT_FOUND"}],"nextSyncToken":"next-groups"}""") }
            }
        }, context)
        val first = api.listGroups(null, null)
        assertEquals(8, first.groups.size); assertTrue(first.groups.all { it.system && it.etag.isNotBlank() }); assertNull(first.nextSync)
        val last = api.listGroups(null, first.nextPage)
        assertEquals(PrivatePeopleApi.groupResource("42"), last.groups.single().resource); assertEquals("groups-sync", last.nextSync)
        assertTrue(api.listGroups(last.nextSync, null).groups.single().deleted)
    }
    @Test fun membershipsUseActualPrivateGroupIdsAndNeverInventMyContacts() {
        val raw = rawPerson()
        val meta = raw.getJSONArray("contactGroupMembership").getJSONObject(0).getJSONObject("metadata")
        raw.getJSONArray("contactGroupMembership").put(JSONObject().put("metadata", meta).put("contactGroupId", "42"))
        assertEquals(setOf("starred", "myContacts", "42").map(PrivatePeopleApi::groupResource).toSet(),
            Memberships.resources(PrivatePeopleApi.normalize(raw).single()))
        raw.put("contactGroupMembership", JSONArray())
        assertTrue(Memberships.resources(PrivatePeopleApi.normalize(raw).single()).isEmpty())
        assertFalse(PrivatePeopleApi.normalize(raw).single().json.getBoolean("_privateStarred"))
    }
    @Test fun embeddedGroupErrorsAreQuarantinedNotTurnedIntoDeletions() {
        val api = PrivatePeopleApi(HttpTransport { _, _, _ -> HttpReply(200,
            """{"groups":[{"id":{"id":"42"},"statusCode":"INVALID_ARGUMENT","error":"secret group data"}],"nextSyncToken":"new"}""") }, context)
        val page = api.listGroups("old", null)
        assertEquals(1, page.skipped); assertTrue(page.groups.isEmpty()); assertEquals(setOf(PrivatePeopleApi.groupResource("42")), page.unreadable)
        val error = assertThrows(UnreadableContact::class.java) { api.getGroup(PrivatePeopleApi.groupResource("42")) }
        assertFalse(error.toString().contains("secret"))
    }
    @Test fun groupLookupUsesAFullFreshListBecauseDirectGroupReadsAreRefused() {
        val label = { id: String -> """{"result":{"id":{"id":"$id"},"name":{"value":"Label $id"},"groupType":{"type":"CONTACT_GROUP"}},"fingerprint":"f$id"}""" }
        for ((second, expected) in listOf(label("42") to true, "" to false, """{"id":{"id":"9"},"statusCode":"INTERNAL"}""" to null)) {
            val pages = mutableListOf<String?>()
            val api = PrivatePeopleApi(HttpTransport { method, url, _ ->
                assertEquals("GET", method); assertTrue(URI(url).path.endsWith("contactGroups/list"))
                val params = query(url); assertNull(params["syncToken"]); pages.add(params["pageToken"])
                if (params["pageToken"] == null) HttpReply(200, """{"groups":[${label("41")}],"nextPageToken":"p2","nextSyncToken":"early"}""")
                else HttpReply(200, """{"groups":[$second],"nextSyncToken":"final"}""")
            }, context)
            if (expected == null) { assertThrows(UnreadableContact::class.java) { api.getGroup(PrivatePeopleApi.groupResource("42")) }; continue }
            val group = api.getGroup(PrivatePeopleApi.groupResource("42"))
            assertEquals(listOf(null, "p2"), pages)
            if (expected) assertEquals("Label 42", group!!.name) else assertNull(group)
        }
    }
    @Test fun intermediatePagesWithASyncTokenAreFollowedAndOnlyTheLastTokenIsKept() {
        val api = PrivatePeopleApi(HttpTransport { _, url, _ ->
            if (query(url)["pageToken"] == null) HttpReply(200, """{"groups":[],"nextPageToken":"p2","nextSyncToken":"early"}""")
            else HttpReply(200, """{"groups":[],"nextSyncToken":"final"}""")
        }, context)
        val first = api.listGroups("old", null)
        assertEquals("p2", first.nextPage); assertNull(first.nextSync)
        assertEquals("final", api.listGroups("old", first.nextPage).nextSync)
    }
    @Test fun photosUseTheHexContactSourceEncodedBindingAndDecodeBoundedBytes() {
        val bytes = byteArrayOf(0, 1, 2, 3, 127, -1)
        val reference = ContactPhotos.reference(PrivatePeopleApi.normalize(rawPerson()).single())!!
        var reads = 0
        val api = PrivatePeopleApi(HttpTransport { method, url, body ->
            reads++; assertEquals("GET", method); assertNull(body); assertTrue(URI(url).path.endsWith("photosEncoded"))
            val params = query(url); assertEquals("17", params["containerId"]); assertEquals("CONTACT_PHOTO", params["type"])
            assertEquals("true", params["useBytes"]); assertTrue(context.parameters().all { params[it.key] == it.value })
            HttpReply(200, JSONObject().put("format", "PNG").put("photoBytes", ContactPhotos.base64(bytes)).toString())
        }, context)
        assertArrayEquals(bytes, api.readPhoto(reference)); assertEquals(1, reads)
        assertThrows(UnreadableContact::class.java) { api.readPhoto("https://foreign.invalid/photo") }
    }
    @Test fun photoErrorsOrMalformedBytesDoNotLookLikePhotoDeletion() {
        val reference = ContactPhotos.reference(PrivatePeopleApi.normalize(rawPerson()).single())!!
        for (response in listOf("{}", """{"format":"PNG","photoBytes":"secret!"}""",
            """{"format":"PNG","photoBytes":"AB=="}""", """{"format":"UNKNOWN_FORMAT","photoBytes":"AA=="}""")) {
            val api = PrivatePeopleApi(HttpTransport { _, _, _ -> HttpReply(200, response) }, context)
            assertThrows(UnreadableContact::class.java) { api.readPhoto(reference) }
        }
    }
    @Test fun photoAuthorizationFailureKeepsItsAllowlistedStatusAndReason() {
        val reference = ContactPhotos.reference(PrivatePeopleApi.normalize(rawPerson()).single())!!
        val api = PrivatePeopleApi(HttpTransport { _, _, _ -> HttpReply(403,
            """{"error":{"status":"PERMISSION_DENIED","details":[{"reason":"ACCESS_TOKEN_SCOPE_INSUFFICIENT"}]}}""") }, context)
        val error = assertThrows(ApiException::class.java) { api.readPhoto(reference) }
        assertEquals(403, error.status); assertEquals("ACCESS_TOKEN_SCOPE_INSUFFICIENT", error.reason)
    }
    @Test fun unrelatedContactEtagsDoNotChangeThePhotoLocatorButPhotoTokensDo() {
        val raw = rawPerson(); val first = PrivatePeopleApi.normalize(raw).single()
        raw.getJSONObject("metadata").getJSONObject("identityInfo").getJSONArray("sourceIds").getJSONObject(0).put("sourceEtag", "new-text-etag")
        val textEdit = PrivatePeopleApi.normalize(raw).single()
        assertEquals(ContactPhotos.reference(first), ContactPhotos.reference(textEdit))
        assertNotEquals(ContactPhotos.revision(first), ContactPhotos.revision(textEdit))
        raw.getJSONArray("photo").getJSONObject(0).put("photoToken", "new-photo-token")
        assertNotEquals(ContactPhotos.reference(first), ContactPhotos.reference(PrivatePeopleApi.normalize(raw).single()))
    }
    @Test fun allGroupMutationsAreRejectedLocally() {
        val api = PrivatePeopleApi(HttpTransport { _, _, _ -> error("Read-only client must not send writes") }, context)
        val remote = ContactGroup.parse(JSONObject().put("resourceName", PrivatePeopleApi.groupResource("42"))
            .put("etag", "synthetic").put("name", "Synthetic").put("groupType", "USER_CONTACT_GROUP"))
        assertThrows(UploadDisabled::class.java) { api.createGroup("Synthetic", "id") }
        assertThrows(UploadDisabled::class.java) { api.updateGroup(remote, "Changed") }
        assertThrows(UploadDisabled::class.java) { api.deleteGroup(remote.resource) }
    }
    @Test fun fullSync251ContactsUsesOnlyTheFinalCheckpointAndIdenticalPagingParameters() {
        var requests = 0; val rows = linkedMapOf<String, Person>(); var checkpoint: String? = null
        val store = object : ContactStore {
            override fun checkpoint() = checkpoint
            override fun checkpoint(token: String) { assertEquals(251, rows.size); checkpoint = token }
            override fun contacts() = rows.values.mapIndexed { index, p -> LocalContact(index.toLong(), 1, p.resource, p.etag, project(p), project(p), false, false) }
            override fun project(person: Person) = mapOf("names" to canonical(person.json.getJSONArray("names")))
            override fun apply(person: Person, existing: LocalContact?, readOnly: Boolean) { assertTrue(readOnly); rows[person.resource] = person }
            override fun remove(existing: LocalContact) = error("No deletes")
            override fun begin(existing: LocalContact, pending: JSONObject): LocalContact = error("No writes")
            override fun rejected(existing: LocalContact) = error("No writes")
            override fun acknowledge(existing: LocalContact, remote: Person?) = error("No writes")
        }
        var firstParams: Map<String, String>? = null
        val api = PrivatePeopleApi(HttpTransport { _, url, _ ->
            val params = query(url); requests++
            assertEquals("100", params["pageSize"])
            if (firstParams == null) firstParams = params else assertEquals(firstParams, params - "pageToken")
            assertEquals(if (requests == 1) null else "page-$requests", params["pageToken"])
            val count = if (requests < 3) 100 else 51; val people = JSONArray()
            repeat(count) { offset ->
                val id = (requests - 1) * 100 + offset + 1; val hex = id.toString(16)
                people.put(JSONObject().put("personId", "c$id").put("metadata", JSONObject().put("contactId", JSONArray().put(id.toString()))
                    .put("identityInfo", JSONObject().put("sourceIds", JSONArray().put(JSONObject().put("containerType", "CONTACT").put("id", hex).put("sourceEtag", "e$id")))))
                    .put("name", JSONArray().put(JSONObject().put("givenName", "Synthetic $id").put("metadata", JSONObject().put("containerType", "CONTACT").put("encodedContainerId", hex)))))
            }
            val response = JSONObject().put("people", people)
            if (requests < 3) response.put("nextPageToken", "page-${requests + 1}") else response.put("nextSyncToken", "final-sync")
            HttpReply(200, response.toString())
        }, context)
        ContactsSync(api, store) { SyncMode.DOWNLOAD }.sync()
        assertEquals(3, requests); assertEquals(251, rows.size); assertEquals("final-sync", checkpoint)
    }
}
