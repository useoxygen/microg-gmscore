/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PeopleExtrasApiTest {
    private fun group() = JSONObject().put("resourceName", "contactGroups/a").put("name", "Label").put("etag", "etag").put("groupType", "USER_CONTACT_GROUP")
    private fun person() = JSONObject().put("resourceName", "people/a").put("metadata", JSONObject().put("sources",
        JSONArray().put(JSONObject().put("type", "CONTACT").put("etag", "etag"))))
    @Test fun unidentifiedPhotoIsQuarantinedRatherThanReadAsDeletion() {
        val malformed = person().put("photos", JSONArray().put(JSONObject().put("url", "https://lh3.googleusercontent.com/photo")))
        val api = PeopleApi(HttpTransport { _, _, _ -> HttpReply(200, JSONObject().put("connections", JSONArray().put(malformed)).put("nextSyncToken", "next").toString()) }) { false }
        val page = api.list(null, null)
        assertEquals(1, page.skipped); assertTrue(page.people.isEmpty()); assertEquals(setOf("people/a"), page.unreadableResources)
    }
    @Test fun invalidPhotoOriginDoesNotAbortAContactsPage() {
        val bad = person().put("photos", JSONArray().put(JSONObject().put("url", "https://not-google.example.invalid/photo")
            .put("metadata", JSONObject().put("source", JSONObject().put("type", "CONTACT")))))
        val api = PeopleApi(HttpTransport { _, _, _ -> HttpReply(200, JSONObject().put("connections", JSONArray().put(bad))
            .put("nextSyncToken", "next").toString()) }) { false }
        assertEquals(1, api.list(null, null).skipped)
    }
    @Test fun groupEndpointsUseMarkersEtagsNarrowMasksAndNeverDeleteContacts() {
        val requests = mutableListOf<Triple<String, String, JSONObject?>>()
        val api = PeopleApi(HttpTransport { method, url, body ->
            requests.add(Triple(method, url, body?.let(::JSONObject)))
            HttpReply(200, if (method == "DELETE") "" else group().toString())
        }) { true }
        api.createGroup("Label", "unique")
        val create = requests.last().third!!
        assertEquals("unique", create.getJSONObject("contactGroup").getJSONArray("clientData").getJSONObject(0).getString("value"))
        api.updateGroup(ContactGroup.parse(group()), "Renamed")
        val update = requests.last()
        assertEquals("PUT", update.first); assertEquals("name", update.third!!.getString("updateGroupFields"))
        assertEquals("etag", update.third!!.getJSONObject("contactGroup").getString("etag"))
        assertFalse(update.third!!.getJSONObject("contactGroup").has("clientData"))
        api.deleteGroup("contactGroups/a"); assertTrue(requests.last().second.contains("deleteContacts=false"))
        assertThrows(IllegalArgumentException::class.java) { api.deleteGroup(MY_CONTACTS) }
        assertThrows(IllegalArgumentException::class.java) { api.deleteGroup(STARRED) }
    }
    @Test fun allExtraMutationsAreGuardedBeforeTransport() {
        var count = 0
        val api = PeopleApi(HttpTransport { _, _, _ -> count++; error("Unexpected request") }) { false }
        assertThrows(UploadDisabled::class.java) { api.createGroup("Label", "marker") }
        assertThrows(UploadDisabled::class.java) { api.updateGroup(ContactGroup.parse(group()), "Label") }
        assertThrows(UploadDisabled::class.java) { api.deleteGroup("contactGroups/a") }
        assertThrows(UploadDisabled::class.java) { api.writePhoto("people/a", byteArrayOf(1)) }
        assertThrows(UploadDisabled::class.java) { api.writePhoto("people/a", null) }
        assertEquals(0, count)
    }
    @Test fun listGroupsQuarantinesOnlyBadEntriesAndKeepsPagingConfiguration() {
        var requested = ""
        val api = PeopleApi(HttpTransport { _, url, _ -> requested = url; HttpReply(200, JSONObject()
            .put("contactGroups", JSONArray().put(group()).put(JSONObject().put("resourceName", "contactGroups/b")))
            .put("nextPageToken", "p").put("nextSyncToken", "s").toString()) }) { false }
        val page = api.listGroups("a&b", "p?x")
        assertEquals(1, page.groups.size); assertEquals(1, page.skipped); assertEquals(setOf("contactGroups/b"), page.unreadable)
        assertTrue(requested.contains("syncToken=a%26b")); assertTrue(requested.contains("pageToken=p%3Fx")); assertTrue(requested.contains("clientData"))
    }
    @Test fun malformedGroupEnvelopeAndMismatchedDirectGroupAreNotAbsence() {
        val bad = PeopleApi(HttpTransport { _, _, _ -> HttpReply(200, """{"contactGroups":{}}""") }) { false }
        assertThrows(org.json.JSONException::class.java) { bad.listGroups(null, null) }
        val other = PeopleApi(HttpTransport { _, _, _ -> HttpReply(200, group().toString()) }) { false }
        assertThrows(UnreadableContact::class.java) { other.getGroup("contactGroups/b") }
    }
    @Test fun photoEndpointsUsePatchBodyAndDeleteQueryWithContactOnlyReadback() {
        var method = ""; var url = ""; var body: JSONObject? = null
        val api = PeopleApi(HttpTransport { m, u, b -> method = m; url = u; body = b?.let(::JSONObject)
            HttpReply(200, JSONObject().put("person", person()).toString()) }) { true }
        api.writePhoto("people/a", byteArrayOf(0, 1, 2, -1))
        assertEquals("PATCH", method); assertTrue(url.contains("people/a:updateContactPhoto"))
        assertEquals("AAEC/w==", body!!.getString("photoBytes")); assertTrue(body!!.getString("personFields").contains("photos"))
        assertEquals("READ_SOURCE_TYPE_CONTACT", body!!.getJSONArray("sources").getString(0))
        api.writePhoto("people/a", null)
        assertEquals("DELETE", method); assertNull(body); assertTrue(url.contains("deleteContactPhoto")); assertTrue(url.contains("personFields="))
    }
    @Test fun base64MatchesRfcEncodingAcrossPaddingBoundaries() {
        for (length in 0..32) {
            val bytes = ByteArray(length) { (it * 41).toByte() }
            assertEquals(Base64.getEncoder().encodeToString(bytes), ContactPhotos.base64(bytes))
        }
    }
    @Test fun photoDownloadRejectsForeignOriginsAndBoundsBytes() {
        var reads = 0
        val api = PeopleApi(HttpTransport { _, _, _ -> error("Unexpected API request") }, { false }, PhotoTransport { reads++; byteArrayOf(1) })
        for (url in listOf("http://lh3.googleusercontent.com/photo", "https://lh3.googleusercontent.com.evil.invalid/photo",
            "https://user@lh3.googleusercontent.com/photo", "https://localhost/photo", "https://lh3.googleusercontent.com:444/photo")) {
            assertThrows(IllegalArgumentException::class.java) { api.readPhoto(url) }
        }
        assertEquals(0, reads); assertArrayEquals(byteArrayOf(1), api.readPhoto("https://lh3.googleusercontent.com/photo"))
        val huge = PeopleApi(HttpTransport { _, _, _ -> error("Unexpected request") }, { false }, PhotoTransport { ByteArray(ContactPhotos.MAX_BYTES + 1) })
        assertThrows(UnreadableContact::class.java) { huge.readPhoto("https://lh3.googleusercontent.com/photo") }
    }
    @Test fun membershipMaskUsesOnlyGroupResourcesAndRejectsEmptyMemberships() {
        val remote = Person.parse(person().put("memberships", JSONArray()
            .put(JSONObject().put("domainMembership", JSONObject().put("inViewerDomain", true)))
            .put(JSONObject().put("contactGroupMembership", JSONObject().put("contactGroupResourceName", MY_CONTACTS).put("contactGroupId", "ignored")))))
        val body = PeopleApi.retainUnmapped(remote, setOf("memberships"), JSONObject().put("memberships", Memberships.json(setOf(MY_CONTACTS, STARRED))))
        assertEquals(setOf(MY_CONTACTS, STARRED), Memberships.resources(Person(remote.resource, body)))
        assertFalse(body.toString().contains("domainMembership")); assertFalse(body.toString().contains("contactGroupId"))
        assertThrows(IllegalArgumentException::class.java) { PeopleApi.retainUnmapped(remote, setOf("memberships"), JSONObject().put("memberships", JSONArray())) }
    }
}
