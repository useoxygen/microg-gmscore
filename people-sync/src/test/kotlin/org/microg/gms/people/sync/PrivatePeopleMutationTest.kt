/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.net.URLDecoder

/** Synthetic protocol server behind the actual private client and journal engine. */
class PrivatePeopleMutationTest {
    private val context = PrivatePeopleContext("25.24.32")
    private fun fixture() = JSONObject(javaClass.getResource("/private-people/full.json")!!.readText()).getJSONArray("people").getJSONObject(0)
    private fun query(url: String) = URI(url).rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.associate {
        it.split('=', limit = 2).let { p -> URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p[1], "UTF-8") }
    }
    private fun receipt(raw: JSONObject) = JSONObject().put("personResponse", JSONObject().put("status", "SUCCESS")
        .put("responseStatus", JSONObject().put("code", 0)).put("person", raw)).toString()
    private fun normalized() = PrivatePeopleApi.normalize(fixture()).single()
    private fun names(value: String) = JSONObject().put("names", JSONArray().put(JSONObject().put("givenName", value)))
    private class Store : ContactStore {
        var row: LocalContact? = null; var token: String? = null; var commits = 0
        override fun checkpoint() = token
        override fun checkpoint(token: String) { this.token = token; commits++ }
        override fun contacts() = listOfNotNull(row)
        override fun project(person: Person): Fields = PeopleApi.EDITABLE_FIELDS.filter { person.json.has(it) }.associateWith { field ->
            val values = JSONArray(person.json.getJSONArray(field).toString())
            for (i in 0 until values.length()) values.getJSONObject(i).remove("metadata")
            canonical(values)
        }
        override fun apply(person: Person, existing: LocalContact?, readOnly: Boolean) {
            row = LocalContact(1, (existing?.version ?: 0) + 1, person.resource, person.etag, project(person), project(person), false, false)
        }
        override fun remove(existing: LocalContact) { row = null }
        override fun begin(existing: LocalContact, pending: JSONObject): LocalContact {
            check(row!!.version == existing.version)
            return existing.copy(version = existing.version + 1, pending = JSONObject(pending.toString())).also { row = it }
        }
        override fun rejected(existing: LocalContact) { row = row!!.copy(pending = null) }
        override fun acknowledge(existing: LocalContact, remote: Person?) {
            if (remote == null) row = null
            else {
                val current = row!!; val after = project(remote)
                row = current.copy(resource = remote.resource, etag = remote.etag, baseline = after,
                    fields = if (existing.version == current.version) after else current.fields,
                    dirty = existing.version != current.version, pending = null)
            }
        }
        fun edit(field: String, value: Any) { row = row!!.copy(version = row!!.version + 1, dirty = true, fields = row!!.fields + (field to canonical(value))) }
    }
    private inner class Server : HttpTransport {
        var raw: JSONObject? = fixture(); var writes = 0; var drop = false; var conflict = false; var marker = true
        var beforeWrite: (() -> Unit)? = null
        var rejection: HttpReply? = null
        override fun request(method: String, url: String, body: String?): HttpReply {
            val path = URI(url).path; val params = query(url)
            if (method == "GET" && path.endsWith("/sync")) return HttpReply(200,
                JSONObject().put("people", JSONArray().apply { raw?.let { put(it) } }).put("nextSyncToken", "next").toString())
            if (method == "POST" && path.endsWith(":batchGet")) {
                val requestId = JSONObject(body!!).getJSONArray("personId").getString(0)
                return HttpReply(200, JSONObject().put("personResponse", JSONArray().put(JSONObject().put("personId", requestId).apply {
                    if (raw == null) put("status", "NOT_FOUND").put("responseStatus", JSONObject().put("code", 5))
                    else put("status", "SUCCESS").put("person", raw)
                })).toString())
            }
            beforeWrite?.invoke(); writes++
            assertFalse(params.keys.any { it.startsWith("context.") } && method in setOf("POST", "PUT"))
            rejection?.let { return it }
            if (conflict) return HttpReply(200, """{"personResponse":{"responseStatus":{"code":9,"message":"FingerprintMismatch secret"}}}""")
            when (method) {
                "PUT" -> {
                    val input = JSONObject(body!!)
                    assertEquals("CONTACT", params["container"])
                    assertEquals("synthetic-etag", input.getJSONObject("metadata").getJSONObject("identityInfo").getJSONArray("sourceIds").getJSONObject(0).getString("sourceEtag"))
                    for (key in input.keys().asSequence().filter { it !in setOf("metadata", "personId") }) raw!!.put(key, input.get(key))
                    raw!!.getJSONObject("metadata").getJSONObject("identityInfo").getJSONArray("sourceIds").getJSONObject(0).put("sourceEtag", "fresh")
                }
                "POST" -> {
                    val input = JSONObject(body!!)
                    assertFalse(input.has("contactId")); assertFalse(input.has("personId"))
                    raw = fixture().apply {
                        for (key in keys().asSequence().toList()) if (key !in setOf("personId", "metadata")) remove(key)
                        for (key in input.keys().asSequence()) put(key, input.get(key))
                        if (!marker) remove("clientData")
                    }
                }
                "DELETE" -> { assertEquals("17", params["contactId"]); assertEquals("ANDROID_FSA2", params["deletionContext.mutationContext.source"]); raw = null }
                else -> error("Unexpected method")
            }
            if (drop) throw IOException("Reply lost")
            return HttpReply(200, raw?.let(::receipt) ?: "{}")
        }
        fun api(canUpload: () -> Boolean = { true }) = PrivatePeopleApi(this, context, canUpload)
    }
    @Test fun updateUsesNestedContextPrivatePersonIdSourceEtagAndSnakeCaseMask() {
        val raw = fixture().put("personId", "c999") // Never substitute a hex source ID as the person ID.
        val person = PrivatePeopleApi.normalize(raw).single()
        val api = PrivatePeopleApi(HttpTransport { method, url, body ->
            assertEquals("PUT", method); assertEquals("/v2/people/c999", URI(url).path)
            val q = query(url)
            assertEquals("person.name,person.phone,person.sip_address", q["fieldMask"])
            assertEquals("CONTACT", q["container"]); assertFalse(q.keys.any { it.startsWith("context.") })
            for ((k, v) in context.parameters()) assertEquals(v, q["getPeopleRequest.$k"])
            val input = JSONObject(body!!); assertEquals("c999", input.getString("personId"))
            val metadata = input.getJSONObject("metadata")
            assertEquals("23", metadata.getJSONArray("contactId").getString(0))
            val source = metadata.getJSONObject("identityInfo").getJSONArray("sourceIds").getJSONObject(0)
            assertEquals("17", source.getString("id")); assertEquals("synthetic-etag", source.getString("sourceEtag"))
            assertEquals(0, input.getJSONArray("phone").length())
            assertFalse(input.has("email")) // Unmasked fields are left untouched.
            assertEquals("CONTACT", input.getJSONArray("name").getJSONObject(0).getJSONObject("metadata").getString("containerType"))
            HttpReply(200, receipt(raw))
        }, context, { true })
        api.update(person, setOf("names", "phoneNumbers", "sipAddresses"), names("Changed").put("phoneNumbers", JSONArray())
            .put("sipAddresses", JSONArray().put(JSONObject().put("value", "sip:test@example.invalid"))))
    }
    @Test fun membershipAndStarUseCompleteArraysOnUpdatePerson() {
        val selected = setOf("myContacts", "starred", "42").map(PrivatePeopleApi::groupResource).toSet()
        val api = PrivatePeopleApi(HttpTransport { method, url, body ->
            assertEquals("PUT", method); assertTrue(URI(url).path.endsWith("/people/c23"))
            assertEquals("person.contact_group_membership", query(url)["fieldMask"])
            val groups = JSONObject(body!!).getJSONArray("contactGroupMembership")
            assertEquals(setOf("myContacts", "starred", "42"), (0 until groups.length()).map { groups.getJSONObject(it).getString("contactGroupId") }.toSet())
            HttpReply(200, receipt(fixture()))
        }, context, { true })
        api.update(normalized(), setOf("memberships"), JSONObject().put("memberships", Memberships.json(selected)))
    }
    @Test fun journalExistsBeforeDispatchAndLostUpdateReplyReconcilesWithoutAnotherPut() {
        val server = Server(); val store = Store()
        ContactsSync(server.api(), store) { SyncMode.DOWNLOAD }.sync()
        store.edit("names", names("Changed").getJSONArray("names"))
        server.beforeWrite = { assertEquals("update", store.row!!.pending!!.getString("kind")) }
        server.drop = true
        assertThrows(IOException::class.java) { ContactsSync(server.api(), store) { SyncMode.TWO_WAY }.sync() }
        assertEquals("update", store.row!!.pending!!.getString("kind")); val commits = store.commits
        server.drop = false
        val report = ContactsSync(server.api(), store) { SyncMode.TWO_WAY }.sync()
        assertEquals(1, server.writes); assertEquals(1, report.uploaded); assertNull(store.row!!.pending); assertTrue(store.commits > commits)
    }
    @Test fun fingerprintMismatchIsDurablyHeldAcrossNewEngineAndClientInstances() {
        val server = Server(); val store = Store()
        ContactsSync(server.api(), store) { SyncMode.DOWNLOAD }.sync()
        store.edit("names", names("Changed").getJSONArray("names")); server.conflict = true
        repeat(2) { assertEquals(1, ContactsSync(server.api(), store) { SyncMode.TWO_WAY }.sync().conflicts) }
        assertEquals(1, server.writes); assertEquals("conflict", store.row!!.pending!!.getString("kind"))
        assertTrue(store.row!!.dirty)
    }
    @Test fun lostCreateReplyFindsClientMarkerWithoutPostingAgain() {
        val server = Server().apply { raw = null; drop = true }; val store = Store()
        store.row = LocalContact(1, 1, null, null, mapOf("names" to "[{\"givenName\":\"Synthetic\"}]"), emptyMap(), true, false)
        server.beforeWrite = { assertEquals("create", store.row!!.pending!!.getString("kind")) }
        assertThrows(IOException::class.java) { ContactsSync(server.api(), store) { SyncMode.TWO_WAY }.sync() }
        server.drop = false
        assertEquals(1, ContactsSync(server.api(), store) { SyncMode.TWO_WAY }.sync().uploaded)
        assertEquals(1, server.writes); assertNull(store.row!!.pending); assertEquals("people/fsa_17", store.row!!.resource)
    }
    @Test fun unobservableCreateMarkerRemainsUncertainAndNeverBlindlyRecreates() {
        val server = Server().apply { raw = null; drop = true; marker = false }; val store = Store()
        store.row = LocalContact(1, 1, null, null, mapOf("names" to "[{\"givenName\":\"Synthetic\"}]"), emptyMap(), true, false)
        assertThrows(IOException::class.java) { ContactsSync(server.api(), store) { SyncMode.TWO_WAY }.sync() }
        // Keep a single-row synthetic store: list results are enough to prove the
        // missing marker cannot authorize replay, without importing another row.
        server.raw = null; server.drop = false
        repeat(2) { assertEquals(1, ContactsSync(server.api(), store) { SyncMode.TWO_WAY }.sync().uncertain) }
        assertEquals(1, server.writes); assertNotNull(store.row!!.pending); assertNull(store.token)
    }
    @Test fun lostDeleteReplyIsAcknowledgedOnlyAfterNotFoundReadback() {
        val server = Server(); val store = Store()
        ContactsSync(server.api(), store) { SyncMode.DOWNLOAD }.sync()
        store.row = store.row!!.copy(dirty = true, deleted = true); server.drop = true
        assertThrows(IOException::class.java) { ContactsSync(server.api(), store) { SyncMode.TWO_WAY }.sync() }
        server.drop = false
        ContactsSync(server.api(), store) { SyncMode.TWO_WAY }.sync()
        assertEquals(1, server.writes); assertNull(store.row)
    }
    @Test fun unsupportedPrivateAttributesAndMergedSourcesHoldEditsBeforeJournaling() {
        for (key in listOf("unrepresented", "_merged")) {
            val server = Server(); val store = Store()
            if (key == "_merged") {
                server.raw!!.getJSONObject("metadata").getJSONArray("contactId").put("24")
                server.raw!!.getJSONObject("metadata").getJSONObject("identityInfo").getJSONArray("sourceIds").put(JSONObject()
                    .put("containerType", "CONTACT").put("id", "18").put("sourceEtag", "other"))
            } else server.raw!!.getJSONArray("name").getJSONObject(0).put(key, "must survive")
            val person = PrivatePeopleApi.normalize(server.raw!!).first(); store.apply(person, null, false)
            store.edit("names", names("Changed").getJSONArray("names"))
            // Use incremental empty list so this single-row store doesn't import a sibling.
            val api = object : ContactsApi by server.api() { override fun list(syncToken: String?, pageToken: String?) = Page(emptyList(), null, "next") }
            store.token = "old"
            assertEquals(1, ContactsSync(api, store) { SyncMode.TWO_WAY }.sync().conflicts)
            assertEquals(0, server.writes); assertNull(store.row!!.pending); assertEquals("old", store.token)
        }
    }
    @Test fun modeChangeAfterJournalBeforeTransportCannotUpload() {
        val server = Server(); val store = Store(); var mode = SyncMode.TWO_WAY
        ContactsSync(server.api(), store) { SyncMode.DOWNLOAD }.sync(); store.edit("names", names("Changed").getJSONArray("names"))
        val api = server.api { mode == SyncMode.TWO_WAY }
        mode = SyncMode.DOWNLOAD
        assertEquals(1, ContactsSync(api, store) { SyncMode.TWO_WAY }.sync().conflicts)
        assertEquals(0, server.writes); assertNull(store.row!!.pending)
    }
    @Test fun embeddedRejectionsAndMalformedMutationReceiptsAreNeverSuccessful() {
        for (body in listOf("{}", """{"personResponse":{"status":"ERROR"}}""",
            """{"personResponse":{"responseStatus":{"code":7,"message":"secret"}}}""")) {
            val api = PrivatePeopleApi(HttpTransport { _, _, _ -> HttpReply(200, body) }, context, { true })
            val error = assertThrows(IOException::class.java) { api.create(names("Synthetic")) }
            assertFalse(error.toString().contains("secret"))
        }
    }
    @Test fun generatedReadbackFieldsDoNotBlockEditsOrEnterMutationBodies() {
        val generated = mapOf(
            "name" to setOf("displayNameLastFirst", "shortDisplayName", "formattedName", "displayNameSource"),
            "phone" to setOf("canonicalizedForm", "uri", "formattedType"),
            "email" to setOf("formattedType"), "address" to setOf("formattedType"),
            "organization" to setOf("formattedStringType"), "relation" to setOf("canonicalValue", "formattedType"),
            "im" to setOf("formattedProtocol", "formattedType"), "website" to setOf("formattedType"),
            "event" to setOf("formattedType"), "sipAddress" to setOf("formattedType"))
        val targets = mapOf("name" to "names", "phone" to "phoneNumbers", "email" to "emailAddresses", "address" to "addresses",
            "organization" to "organizations", "relation" to "relations", "im" to "imClients", "website" to "urls", "event" to "events", "sipAddress" to "sipAddresses")
        val raw = fixture()
        for ((kind, keys) in generated) for (key in keys) raw.getJSONArray(kind).getJSONObject(0).put(key,
            if (key == "displayNameSource") JSONObject().put("synthetic", true) else "Synthetic generated")
        val person = PrivatePeopleApi.normalize(raw).single()
        val payload = JSONObject().apply { for (target in targets.values) put(target, person.json.getJSONArray(target)) }
        // Display names on email and free-form postal addresses remain writable.
        payload.getJSONArray("emailAddresses").getJSONObject(0).put("displayName", "Edited mail label")
        payload.getJSONArray("addresses").getJSONObject(0).put("formattedValue", "Edited postal address")
        var writes = 0
        val api = PrivatePeopleApi(HttpTransport { _, _, body ->
            writes++; val input = JSONObject(body!!)
            for ((kind, keys) in generated) for (key in keys) assertFalse("$kind.$key", input.getJSONArray(kind).getJSONObject(0).has(key))
            assertEquals("Edited mail label", input.getJSONArray("email").getJSONObject(0).getString("displayName"))
            assertEquals("Edited postal address", input.getJSONArray("address").getJSONObject(0).getString("formatted"))
            HttpReply(200, receipt(raw))
        }, context, { true })
        api.update(person, targets.values.toSet(), api.prepareUpdate(person, targets.values.toSet(), payload))
        assertEquals(1, writes)
        raw.getJSONArray("phone").getJSONObject(0).put("unknownWritableAttribute", "must not be lost")
        assertThrows(UnsupportedContactEdit::class.java) {
            api.prepareUpdate(PrivatePeopleApi.normalize(raw).single(), setOf("phoneNumbers"), payload)
        }
    }
    @Test fun mutationValidationRejectionClearsJournalAndCorrectedContactCanSync() {
        val server = Server(); val store = Store()
        ContactsSync(server.api(), store) { SyncMode.DOWNLOAD }.sync(); store.token = "old"
        store.edit("names", names("Rejected value").getJSONArray("names"))
        server.rejection = HttpReply(400, """{"error":{"status":"INVALID_ARGUMENT","message":"Invalid contact field"}}""")
        val report = ContactsSync(server.api(), store) { SyncMode.TWO_WAY }.sync()
        assertEquals(1, report.conflicts); assertEquals(0, report.uncertain)
        assertNull(store.row!!.pending); assertTrue(store.row!!.dirty); assertEquals("old", store.token)
        server.rejection = null
        store.edit("names", names("Corrected value").getJSONArray("names"))
        assertEquals(1, ContactsSync(server.api(), store) { SyncMode.TWO_WAY }.sync().uploaded)
        assertEquals(2, server.writes); assertNull(store.row!!.pending); assertFalse(store.row!!.dirty); assertEquals("next", store.token)
    }

}
