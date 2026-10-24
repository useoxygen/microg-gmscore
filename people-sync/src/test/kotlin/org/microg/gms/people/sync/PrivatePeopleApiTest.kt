/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.net.URLDecoder

class PrivatePeopleApiTest {
    private val context = PrivatePeopleContext("25.24.32")
    private fun fixture(name: String) = javaClass.getResource("/private-people/$name.json")!!.readText()
    private fun person() = JSONObject(fixture("full")).getJSONArray("people").getJSONObject(0)
    private fun query(url: String) = URI(url).rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.associate { value ->
        value.split('=', limit = 2).let { URLDecoder.decode(it[0], "UTF-8") to URLDecoder.decode(it[1], "UTF-8") }
    }
    private class Store : ContactStore {
        var saved: String? = null; var row: LocalContact? = null; var writes = 0; var removed = 0
        override fun checkpoint() = saved
        override fun checkpoint(token: String) { saved = token }
        override fun contacts() = listOfNotNull(row)
        override fun project(person: Person) = PeopleApi.EDITABLE_FIELDS.filter { person.json.has(it) }
            .associateWith { canonical(person.json.get(it)) }
        override fun apply(person: Person, existing: LocalContact?, readOnly: Boolean) {
            assertTrue(readOnly); assertTrue(existing == null || row!!.version == existing.version)
            writes++; row = LocalContact(1, (existing?.version ?: 0) + 1, person.resource, person.etag, project(person), project(person), false, false)
        }
        override fun remove(existing: LocalContact) { assertEquals(existing.version, row!!.version); row = null; removed++ }
        override fun begin(existing: LocalContact, pending: JSONObject): LocalContact = error("Read path must not upload")
        override fun rejected(existing: LocalContact) = error("Read path must not upload")
        override fun acknowledge(existing: LocalContact, remote: Person?) = error("Read path must not upload")
    }
    @Test fun fullAndPagedRequestsAndDirectReadsAllCarryTheSameAdvertisedContext() {
        assertEquals("25.24.32", PrivatePeopleContext.fromGmsVersionCode(252432048).version)
        assertEquals("26.41.31", PrivatePeopleContext.fromGmsVersionCode(264131035).version)
        assertNotEquals(context.binding, PrivatePeopleContext("26.41.31").binding)
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val api = PrivatePeopleApi(HttpTransport { method, url, body ->
            assertTrue(url.startsWith(PrivatePeopleApi.ORIGIN)); requests.add(method to query(url))
            if (method == "POST") {
                val input = JSONObject(body!!)
                assertEquals(canonical(context.json()), canonical(input.getJSONObject("context")))
                assertEquals("c23", input.getJSONArray("personId").getString(0))
                assertEquals(PrivatePeopleApi.READ_MASK, input.getJSONObject("requestMask").getString("includeField"))
                HttpReply(200, fixture("get"))
            } else {
                assertTrue(context.parameters().all { (k, v) -> query(url)[k] == v })
                HttpReply(200, fixture("full"))
            }
        }, context)
        api.list(null, null); api.list("synthetic-cursor", "synthetic-page"); api.get("people/fsa_17")
        assertEquals("FULL_SYNC_OK", requests[0].second["syncParameters.fullSyncOption"])
        assertFalse(requests[0].second.containsKey("syncParameters.syncToken"))
        assertEquals("FULL_SYNC_UNDESIRABLE", requests[1].second["syncParameters.fullSyncOption"])
        assertEquals("synthetic-cursor", requests[1].second["syncParameters.syncToken"])
        assertEquals("synthetic-page", requests[1].second["pageToken"])
        assertEquals("PRODUCT_BESTIES", requests[0].second["requestMask.productContactGroup"])
        assertEquals("CORE_ID", requests[0].second["mergedPersonSourceOptions.includedProfileStates"])
        assertEquals("GDATA_COMPATIBILITY", requests[0].second["extensionSet.extensionNames"])
    }
    @Test fun recordedShapeMapsContactFieldsExtendedFieldsPhotosAndStarredWithoutPrivateTokens() {
        val result = PrivatePeopleApi.normalize(person()).single()
        assertEquals("people/fsa_17", result.resource); assertEquals("synthetic-etag", result.etag)
        assertEquals("phonetic", result.json.getJSONArray("names").getJSONObject(0).getString("phoneticGivenName"))
        assertEquals("Example", result.json.getJSONArray("addresses").getJSONObject(0).getString("city"))
        assertEquals("Description", result.json.getJSONArray("organizations").getJSONObject(0).getString("jobDescription"))
        assertEquals("work", result.json.getJSONArray("organizations").getJSONObject(0).getString("type"))
        assertEquals("Synthetic Relation", result.json.getJSONArray("relations").getJSONObject(0).getString("person"))
        assertEquals("synthetic-chat", result.json.getJSONArray("imClients").getJSONObject(0).getString("username"))
        for (field in listOf("phoneNumbers", "emailAddresses", "biographies", "birthdays", "events", "nicknames", "urls", "sipAddresses", "userDefined"))
            assertEquals(1, result.json.getJSONArray(field).length())
        assertTrue(result.json.getBoolean("_privateStarred"))
        assertTrue(ContactPhotos.reference(result)!!.startsWith("fsa2-photo:17:"))
        assertFalse(result.json.toString().contains("must-not-be-copied"))
        assertTrue(result.json.getJSONArray("phoneNumbers").getJSONObject(0).getJSONObject("metadata").getBoolean("sourcePrimary"))
    }
    @Test fun mergedPeopleAreSplitByContactSourceAndProfileFieldsAreNotImported() {
        val raw = person()
        raw.getJSONObject("metadata").getJSONArray("contactId").put("35")
        raw.getJSONObject("metadata").getJSONObject("identityInfo").getJSONArray("sourceIds").put(JSONObject()
            .put("containerType", "CONTACT").put("id", "23").put("sourceEtag", "other-etag"))
        raw.getJSONArray("name").put(JSONObject().put("givenName", "Other").put("metadata", JSONObject().put("containerType", "CONTACT").put("encodedContainerId", "23")))
            .put(JSONObject().put("givenName", "Profile").put("metadata", JSONObject().put("containerType", "PROFILE")))
        val people = PrivatePeopleApi.normalize(raw)
        assertEquals(listOf("people/fsa_17", "people/fsa_23"), people.map { it.resource })
        assertEquals("Other", people[1].json.getJSONArray("names").getJSONObject(0).getString("givenName"))
        assertEquals(0, people[1].json.getJSONArray("emailAddresses").length())
        assertFalse(people.toString().contains("Profile"))
    }
    @Test fun unknownSourceAndMalformedScalarQuarantineTheWholeEntry() {
        for (mutate in listOf<(JSONObject) -> Unit>(
            { it.getJSONArray("name").getJSONObject(0).put("givenName", 12) },
            { it.getJSONArray("name").getJSONObject(0).getJSONObject("metadata").put("encodedContainerId", "42") },
            { it.getJSONObject("metadata").getJSONObject("identityInfo").getJSONArray("sourceIds").getJSONObject(0).remove("sourceEtag") },
            { it.getJSONObject("metadata").remove("contactId") },
            { it.getJSONArray("birthday").getJSONObject(0).getJSONObject("calendarDay").put("day", 42) },
            { it.getJSONArray("name").getJSONObject(0).getJSONObject("metadata").put("otherDedupedContainers", JSONArray().put(JSONObject().put("containerType", "CONTACT").put("id", "23"))) }
        )) {
            val raw = person(); mutate(raw)
            val api = PrivatePeopleApi(HttpTransport { _, _, _ -> HttpReply(200, JSONObject().put("people", JSONArray().put(raw)).put("nextSyncToken", "next").toString()) }, context)
            val page = api.list(null, null)
            assertTrue(page.people.isEmpty()); assertEquals(1, page.skipped); assertTrue("people/fsa_17" in page.unreadableResources)
        }
    }
    @Test fun partialSnapshotKeepsStoredCopyCheckpointAndAllDeletionCandidates() {
        val store = Store(); store.apply(PrivatePeopleApi.normalize(person()).single(), null, true); store.saved = "old"
        val bad = person(); bad.getJSONObject("metadata").getJSONObject("identityInfo").getJSONArray("sourceIds").getJSONObject(0).remove("sourceEtag")
        val reply = JSONObject(fixture("deleted")).put("people", JSONArray()
            .put(JSONObject(fixture("deleted")).getJSONArray("people").get(0)).put(bad))
        val api = PrivatePeopleApi(HttpTransport { _, _, _ -> HttpReply(200, reply.toString()) }, context)
        val report = ContactsSync(api, store) { SyncMode.DOWNLOAD }.sync()
        assertEquals(1, report.skipped); assertNotNull(store.row); assertEquals("old", store.saved); assertEquals(0, store.removed)
    }
    @Test fun sourceTombstoneWithoutFieldsDeletesOnlyAfterACompleteRead() {
        val store = Store(); store.apply(PrivatePeopleApi.normalize(person()).single(), null, true); store.saved = "old"
        val api = PrivatePeopleApi(HttpTransport { _, _, _ -> HttpReply(200, fixture("deleted")) }, context)
        ContactsSync(api, store) { SyncMode.DOWNLOAD }.sync()
        assertNull(store.row); assertEquals(1, store.removed); assertEquals("synthetic-sync-2", store.saved)
    }
    @Test fun expiredPrivateTokenRestartsFromFullSyncWithoutReusingIt() {
        val store = Store().apply { saved = "expired" }; var calls = 0
        val api = PrivatePeopleApi(HttpTransport { _, url, _ ->
            calls++
            if (calls == 1) {
                assertEquals("expired", query(url)["syncParameters.syncToken"])
                HttpReply(400, """{"error":{"status":"FAILED_PRECONDITION","details":[{"reason":"EXPIRED_SYNC_TOKEN"}]}}""")
            } else { assertFalse(query(url).containsKey("syncParameters.syncToken")); assertEquals("FULL_SYNC_OK", query(url)["syncParameters.fullSyncOption"]); HttpReply(200, fixture("full")) }
        }, context)
        ContactsSync(api, store) { SyncMode.DOWNLOAD }.sync()
        assertEquals(2, calls); assertNotNull(store.row); assertEquals("synthetic-sync-1", store.saved)
    }
    @Test fun directNotFoundIsRequiredBeforeDeletingAnAbsentContact() {
        val store = Store(); store.apply(PrivatePeopleApi.normalize(person()).single(), null, true)
        var gets = 0
        val api = PrivatePeopleApi(HttpTransport { method, _, _ ->
            if (method == "GET") HttpReply(200, """{"nextSyncToken":"new"}""")
            else { gets++; HttpReply(200, """{"personResponse":[{"personId":"c23","status":"NOT_FOUND","responseStatus":{"code":5}}]}""") }
        }, context)
        ContactsSync(api, store) { SyncMode.DOWNLOAD }.sync()
        assertEquals(1, gets); assertNull(store.row); assertEquals("new", store.saved)
    }
    @Test fun embeddedErrorsAndEndpoint404CannotMasqueradeAsDeletedContacts() {
        for (reply in listOf(HttpReply(404, "{}"), HttpReply(200, "{}"), HttpReply(200,
            """{"personResponse":[{"personId":"c23","status":"SUCCESS","responseStatus":{"code":5}}]}"""))) {
            val api = PrivatePeopleApi(HttpTransport { _, _, _ -> reply }, context)
            assertThrows(IOException::class.java) { api.get("people/fsa_17") }
        }
    }
    @Test fun contextRejectionsHaveAnExplicitNonSecretUpdateStatus() {
        var calls = 0
        val api = PrivatePeopleApi(HttpTransport { _, _, _ -> calls++; HttpReply(400, """{"error":{"status":"INVALID_ARGUMENT","message":"secret client context"}}""") }, context)
        val error = assertThrows(PeopleClientUpdateRequired::class.java) { api.list(null, null) }
        assertEquals(1, calls); assertEquals(ActivationFailure.NEEDS_UPDATE, activationFailure(ActivationStage.API, error))
        assertFalse(error.toString().contains("secret"))
    }
    @Test fun allMutationMethodsFailBeforeTransport() {
        val api = PrivatePeopleApi(HttpTransport { _, _, _ -> error("No mutation request permitted") }, context)
        val p = PrivatePeopleApi.normalize(person()).single()
        assertThrows(UploadDisabled::class.java) { api.create(JSONObject()) }
        assertThrows(UploadDisabled::class.java) { api.update(p, setOf("names"), JSONObject()) }
        assertThrows(UploadDisabled::class.java) { api.delete(p.resource) }
        assertThrows(UploadDisabled::class.java) { api.writePhoto(p.resource, byteArrayOf(1)) }
    }
    @Test fun exactInt64SourceIdsArePreservedAndRoundedNumbersRejected() {
        val raw = person(); raw.getJSONObject("metadata").put("contactId", JSONArray().put("9007199254740993"))
        raw.getJSONObject("metadata").getJSONObject("identityInfo").getJSONArray("sourceIds").getJSONObject(0).put("id", "20000000000001")
        for (key in raw.keys().asSequence().toList()) (raw.opt(key) as? JSONArray)?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.optJSONObject("metadata")?.put("encodedContainerId", "20000000000001")
        }
        assertEquals("people/fsa_20000000000001", PrivatePeopleApi.normalize(raw).single().resource)
        raw.getJSONObject("metadata").put("contactId", JSONArray().put(9007199254740992.0))
        assertThrows(IllegalArgumentException::class.java) { PrivatePeopleApi.normalize(raw) }
    }
    @Test fun defaultMonogramsDoNotRequireImageDownloads() {
        val raw = person(); raw.put("photo", JSONArray().put(JSONObject().put("metadata", JSONObject().put("containerType", "CONTACT").put("encodedContainerId", "17")).put("isMonogram", true)))
        assertNull(ContactPhotos.reference(PrivatePeopleApi.normalize(raw).single()))
    }
    @Test fun latePageFailureDoesNotWriteRowsOrAdvanceCheckpoint() {
        val store = Store(); var calls = 0
        val api = PrivatePeopleApi(HttpTransport { _, url, _ ->
            calls++
            if (calls == 1) HttpReply(200, JSONObject(fixture("full")).put("nextPageToken", "page-two").toString())
            else { assertEquals("page-two", query(url)["pageToken"]); HttpReply(200, "not-json") }
        }, context)
        assertThrows(IOException::class.java) { ContactsSync(api, store) { SyncMode.DOWNLOAD }.sync() }
        assertEquals(0, store.writes); assertNull(store.saved)
    }
    @Test fun explicitPartialFailureHoldsCheckpointAndDeletions() {
        val store = Store(); store.apply(PrivatePeopleApi.normalize(person()).single(), null, true); store.saved = "old"
        val reply = JSONObject(fixture("deleted")).put("partialFailureStates", JSONArray().put("EMAIL_LOOKUP_QUOTA_THROTTLED"))
        val api = PrivatePeopleApi(HttpTransport { _, _, _ -> HttpReply(200, reply.toString()) }, context)
        assertEquals(1, ContactsSync(api, store) { SyncMode.DOWNLOAD }.sync().skipped)
        assertNotNull(store.row); assertEquals("old", store.saved); assertEquals(0, store.removed)
    }
    @Test fun accessCheckDoesNotAcceptAnEmptyOrErrorEnvelope() {
        for (reply in listOf("{}", """{"error":{"status":"PERMISSION_DENIED"}}""")) {
            val api = PrivatePeopleApi(HttpTransport { _, _, _ -> HttpReply(200, reply) }, context)
            assertThrows(IOException::class.java) { api.checkAccess() }
        }
    }
    @Test fun privateAccessCheckIsOneCursorOnlyReadAndNeverLoadsPeople() {
        var calls = 0
        val api = PrivatePeopleApi(HttpTransport { method, url, body ->
            calls++; assertEquals("GET", method); assertNull(body)
            assertEquals("1", query(url)["pageSize"]); assertEquals("person.metadata", query(url)["requestMask.includeField"])
            assertEquals("nextPageToken,nextSyncToken", query(url)["fields"])
            HttpReply(200, """{"people":[{"malformed":true}],"nextSyncToken":"synthetic"}""")
        }, context)
        api.checkAccess(); assertEquals(1, calls)
    }
}
