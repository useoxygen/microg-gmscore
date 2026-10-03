/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PeopleApiTest {
    private fun person() = Person.parse(JSONObject("""{"resourceName":"people/a","metadata":{"sources":[{"type":"CONTACT","id":"a","etag":"contact-etag"}]},"etag":"person-etag"}"""))
    @Test fun readOnlyRejectsEveryMutationBeforeTransport() {
        var requests = 0
        val api = PeopleApi(HttpTransport { _, _, _ -> requests++; HttpReply(200, "{}") }) { false }
        assertThrows(IllegalStateException::class.java) { api.create(JSONObject()) }
        assertThrows(IllegalStateException::class.java) { api.update(person(), setOf("names"), JSONObject().put("names", JSONArray())) }
        assertThrows(IllegalStateException::class.java) { api.delete("people/a") }
        assertEquals(0, requests)
    }
    @Test fun updateUsesContactSourceEtagAndNarrowMask() {
        var request: JSONObject? = null; var url = ""; var method = ""
        val api = PeopleApi(HttpTransport { m, u, b -> method = m; url = u; request = JSONObject(b!!); HttpReply(200, person().json.toString()) }) { true }
        api.update(person(), setOf("phoneNumbers"), JSONObject().put("phoneNumbers", JSONArray()))
        assertEquals("PATCH", method); assertTrue(url.contains("updatePersonFields=phoneNumbers"))
        assertEquals("contact-etag", request!!.getJSONObject("metadata").getJSONArray("sources").getJSONObject(0).getString("etag"))
        assertFalse(request!!.has("names"))
    }
    @Test fun listEncodesTokensAndRequestsOnlyContactSources() {
        var url = ""
        val api = PeopleApi(HttpTransport { method, u, _ -> assertEquals("GET", method); url = u; HttpReply(200, "{\"nextSyncToken\":\"next\"}") }) { false }
        assertEquals("next", api.list("a&b", "p?x").nextSync)
        assertTrue(url.contains("syncToken=a%26b")); assertTrue(url.contains("pageToken=p%3Fx"))
        assertTrue(url.contains("sources=READ_SOURCE_TYPE_CONTACT")); assertTrue(url.contains("requestSyncToken=true"))
    }
    @Test fun expiredReasonIsRecognizedAndRetryDelayRetained() {
        val api = PeopleApi(HttpTransport { _, _, _ -> HttpReply(410,
            "{\"error\":{\"details\":[{\"reason\":\"EXPIRED_SYNC_TOKEN\"}]}}", 60) }) { false }
        val error = assertThrows(ApiException::class.java) { api.list("old", null) }
        assertTrue(error.expired); assertEquals(60, error.retryAfterSeconds)
    }
    @Test fun unsafeResourceAndUnsupportedFieldMaskAreRejected() {
        val api = PeopleApi(HttpTransport { _, _, _ -> error("Unexpected transport") }) { true }
        assertThrows(IllegalArgumentException::class.java) { api.delete("people/a?access_token=x") }
        assertThrows(IllegalArgumentException::class.java) { api.update(person(), setOf("memberships"), JSONObject()) }
    }
    @Test fun profileOnlyOrMissingEtagIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Person.parse(JSONObject("""{"resourceName":"people/a","metadata":{"sources":[{"type":"PROFILE","etag":"p"}]}}"""))
        }
    }
    @Test fun singletonUpdatePreservesUnmappedAttributes() {
        val remote = person().also { it.json.put("names", JSONArray().put(JSONObject()
            .put("givenName", "Alice").put("phoneticFullName", "original pronunciation"))) }
        val body = PeopleApi.retainUnmapped(remote, setOf("names"), JSONObject().put("names",
            JSONArray().put(JSONObject().put("givenName", "Alicia"))))
        assertEquals("original pronunciation", body.getJSONArray("names").getJSONObject(0).getString("phoneticFullName"))
        assertEquals("Alicia", body.getJSONArray("names").getJSONObject(0).getString("givenName"))
    }
    @Test fun ambiguousArrayUpdateCannotDiscardUnmappedAttributes() {
        val remote = person().also { it.json.put("organizations", JSONArray()
            .put(JSONObject().put("name", "First").put("current", true)).put(JSONObject().put("name", "Second"))) }
        assertThrows(UnsupportedContactEdit::class.java) { PeopleApi.retainUnmapped(remote, setOf("organizations"),
            JSONObject().put("organizations", JSONArray().put(JSONObject().put("name", "Changed")))) }
    }
    @Test fun explicitlyClearingAFieldIsAllowed() {
        val remote = person().also { it.json.put("organizations", JSONArray().put(JSONObject().put("name", "First").put("current", true))) }
        assertEquals(0, PeopleApi.retainUnmapped(remote, setOf("organizations"),
            JSONObject().put("organizations", JSONArray())).getJSONArray("organizations").length())
    }
    @Test fun changedCountryCannotRetainAnIncompatibleUnmappedCountryCode() {
        val remote = person().also { it.json.put("addresses", JSONArray().put(JSONObject().put("country", "Germany").put("countryCode", "DE"))) }
        assertThrows(UnsupportedContactEdit::class.java) { PeopleApi.retainUnmapped(remote, setOf("addresses"),
            JSONObject().put("addresses", JSONArray().put(JSONObject().put("country", "France")))) }
    }
}
