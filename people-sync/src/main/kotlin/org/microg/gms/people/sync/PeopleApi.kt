/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

enum class SyncMode { OFF, DOWNLOAD, TWO_WAY }

// JSON arrays are canonicalized before comparing provider snapshots. No token or
// contact content belongs in exceptions, logs, or diagnostics.
fun canonical(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
        JSONObject.quote(it) + ":" + canonical(value.get(it))
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}

data class Person(val resource: String, val json: JSONObject) {
    val deleted: Boolean get() = json.optJSONObject("metadata")?.optBoolean("deleted") == true
    val source: JSONObject? get() = json.optJSONObject("metadata")?.optJSONArray("sources")?.let { sources ->
        (0 until sources.length()).map { sources.getJSONObject(it) }.firstOrNull { it.optString("type") == "CONTACT" }
    }
    val etag: String get() = source?.optString("etag").orEmpty()
    fun creationId(): String? = json.optJSONArray("clientData")?.let { values ->
        (0 until values.length()).map { values.getJSONObject(it) }
            .firstOrNull { it.optString("key") == CREATE_KEY }?.optString("value")
    }
    companion object {
        const val CREATE_KEY = "microg.contacts.creation-id"
        fun parse(json: JSONObject): Person {
            val resource = json.getString("resourceName")
            require(resource.matches(Regex("people/[A-Za-z0-9_-]+"))) { "Invalid contact resource" }
            // Validate field containers before any provider projection. A bad
            // contact is isolated by list(), never mistaken for an empty field.
            PeopleApi.validateFields(json)
            return Person(resource, json).also {
                require(it.deleted || (it.source?.opt("etag") is String && it.etag.isNotBlank())) { "Missing contact source or etag" }
            }
        }
    }
}

data class Page(val people: List<Person>, val nextPage: String?, val nextSync: String?,
    val skipped: Int = 0, val unreadableResources: Set<String> = emptySet())
data class HttpReply(val status: Int, val body: String, val retryAfterSeconds: Long = 0)
fun interface HttpTransport {
    fun request(method: String, url: String, body: String?): HttpReply
}

class ApiException(val status: Int, val expired: Boolean = false, val retryAfterSeconds: Long = 0, val reason: String? = null, val code: String? = null) :
    IOException("Contacts API request failed ($status)") {
    val definitelyRejected get() = status in listOf(400, 401, 403, 404, 409, 412, 429)
}
class UploadDisabled : IllegalStateException("Contact uploads are disabled")
class UnreadableContact : IOException("Contact entry could not be read")
class UnsupportedContactEdit : Exception("Contact field contains unmapped values")

interface ContactsApi {
    fun list(syncToken: String?, pageToken: String?): Page
    fun get(resource: String): Person?
    fun create(body: JSONObject): Person
    fun update(person: Person, fields: Set<String>, body: JSONObject): Person
    fun delete(resource: String)
}

class PeopleApi(private val transport: HttpTransport, private val canUpload: () -> Boolean) : ContactsApi {
    companion object {
        const val ORIGIN = "https://people.googleapis.com/v1/"
        val EDITABLE_FIELDS = setOf("names", "phoneNumbers", "emailAddresses", "addresses", "organizations",
            "biographies", "birthdays", "nicknames", "urls", "relations", "events", "imClients", "sipAddresses", "userDefined")
        val READ_FIELDS = (EDITABLE_FIELDS + setOf("metadata", "clientData")).sorted().joinToString(",")
        private val mappedKeys = mapOf(
            "names" to setOf("givenName", "middleName", "familyName", "honorificPrefix", "honorificSuffix", "unstructuredName",
                "phoneticGivenName", "phoneticMiddleName", "phoneticFamilyName"),
            "phoneNumbers" to setOf("value", "type"), "emailAddresses" to setOf("value", "type", "displayName"),
            "addresses" to setOf("formattedValue", "streetAddress", "poBox", "extendedAddress", "city", "region", "postalCode", "country", "type"),
            "organizations" to setOf("name", "title", "department", "jobDescription", "symbol", "phoneticName", "type"),
            "biographies" to setOf("value", "contentType"), "birthdays" to setOf("date"),
            "nicknames" to setOf("value"), "urls" to setOf("value", "type"),
            "relations" to setOf("person", "type"), "events" to setOf("date", "type"),
            "imClients" to setOf("username", "protocol", "type"), "sipAddresses" to setOf("value", "type"),
            "userDefined" to setOf("key", "value"))
        private val generatedKeys = setOf("metadata", "formattedType", "canonicalForm")

        fun validateFields(json: JSONObject) {
            if (json.has("metadata")) {
                val metadata = json.getJSONObject("metadata")
                if (metadata.has("deleted")) require(metadata.get("deleted") is Boolean) { "Invalid contact tombstone" }
                if (metadata.has("sources")) {
                    val sources = metadata.getJSONArray("sources")
                    for (index in 0 until sources.length()) sources.getJSONObject(index).getString("type")
                }
            }
            for (field in EDITABLE_FIELDS + "clientData") {
                if (!json.has(field)) continue
                val array = json.getJSONArray(field)
                for (index in 0 until array.length()) {
                    val value = array.getJSONObject(index)
                    val keys = if (field == "clientData") setOf("key", "value") else mappedKeys.getValue(field)
                    for (key in keys) {
                        if (!value.has(key)) continue
                        if (key == "date") {
                            val date = value.getJSONObject(key)
                            for (part in listOf("year", "month", "day")) if (date.has(part)) {
                                require(date.get(part) is Int) { "Invalid contact date" }
                            }
                        } else require(value.get(key) is String) { "Invalid contact field value" }
                    }
                    if (value.has("metadata")) {
                        val metadata = value.getJSONObject("metadata")
                        if (metadata.has("sourcePrimary")) require(metadata.get("sourcePrimary") is Boolean) { "Invalid preferred value" }
                    }
                }
            }
        }

        fun retainUnmapped(person: Person, changed: Set<String>, payload: JSONObject): JSONObject {
            val result = JSONObject(payload.toString())
            for (field in changed) {
                val old = person.json.optJSONArray(field) ?: continue
                val next = result.getJSONArray(field)
                // Partial/text-only dates cannot be represented by the Android
                // editor. Editing another event must not discard those dates.
                if (field == "events" || field == "birthdays") {
                    if ((0 until old.length()).any { index ->
                        old.getJSONObject(index).optJSONObject("date")?.let { ContactDates.format(it) } == null
                    }) throw UnsupportedContactEdit()
                }
                if (next.length() == 0) continue // Explicitly removing the entire field.
                val known = mappedKeys.getValue(field) + generatedKeys +
                    (if (field == "names") setOf("displayName", "displayNameLastFirst") else emptySet())
                val extras = (0 until old.length()).any { index ->
                    old.getJSONObject(index).keys().asSequence().any { it !in known }
                }
                if (!extras) continue
                // Singleton fields can retain unmapped attributes unambiguously.
                // With multiple values, field identity is ambiguous: hold the
                // edit for review instead of replacing unrepresented data.
                if (old.length() != 1 || next.length() != 1) throw UnsupportedContactEdit()
                if (field == "addresses" && old.getJSONObject(0).has("countryCode") &&
                    old.getJSONObject(0).optString("country") != next.getJSONObject(0).optString("country")) throw UnsupportedContactEdit()
                val merged = JSONObject(old.getJSONObject(0).toString())
                for (key in known) merged.remove(key)
                val edited = next.getJSONObject(0)
                for (key in edited.keys().asSequence().toList()) merged.put(key, edited.get(key))
                result.put(field, JSONArray().put(merged))
            }
            return result
        }
    }

    private fun url(path: String, params: Map<String, String>) = ORIGIN + path + params.entries.joinToString("&", "?") {
        URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
    }
    private fun resourcePath(resource: String): String {
        require(resource.matches(Regex("people/[A-Za-z0-9_-]+"))) { "Invalid contact resource" }
        return resource
    }
    private fun call(method: String, path: String, params: Map<String, String>, body: JSONObject? = null): JSONObject {
        if (method != "GET" && !canUpload()) throw UploadDisabled()
        val reply = transport.request(method, url(path, params), body?.toString())
        if (reply.status !in 200..299) {
            val expired = reply.status == 410 && runCatching {
                JSONObject(reply.body).getJSONObject("error").getJSONArray("details").let { details ->
                    (0 until details.length()).any { details.getJSONObject(it).optString("reason") == "EXPIRED_SYNC_TOKEN" }
                }
            }.getOrDefault(false)
            throw ApiException(reply.status, expired, reply.retryAfterSeconds, ContactsDiagnostics.apiReason(reply.body), ContactsDiagnostics.apiStatus(reply.body))
        }
        return if (reply.body.isBlank()) JSONObject() else JSONObject(reply.body)
    }
    private fun readParams() = linkedMapOf("personFields" to READ_FIELDS, "sources" to "READ_SOURCE_TYPE_CONTACT")
    /** Authorization probe: no checkpoint request, mutation or contact projection. */
    fun checkAccess() {
        call("GET", "people/me/connections", mapOf("personFields" to "metadata",
            "sources" to "READ_SOURCE_TYPE_CONTACT", "pageSize" to "1"))
    }
    override fun list(syncToken: String?, pageToken: String?): Page {
        val params = readParams().apply {
            put("pageSize", "1000")
            put("requestSyncToken", "true")
            syncToken?.let { put("syncToken", it) }
            pageToken?.let { put("pageToken", it) }
        }
        val result = call("GET", "people/me/connections", params)
        val array = if (result.has("connections")) result.getJSONArray("connections") else JSONArray()
        val people = mutableListOf<Person>()
        val unreadable = mutableSetOf<String>()
        var skipped = 0
        for (index in 0 until array.length()) {
            try { people.add(Person.parse(array.getJSONObject(index))) }
            catch (e: org.json.JSONException) { skipped++; resourceHint(array.optJSONObject(index))?.let(unreadable::add) }
            catch (e: IllegalArgumentException) { skipped++; resourceHint(array.optJSONObject(index))?.let(unreadable::add) }
        }
        return Page(people, optionalToken(result, "nextPageToken"), optionalToken(result, "nextSyncToken"), skipped, unreadable)
    }
    private fun resourceHint(json: JSONObject?): String? = json?.optString("resourceName")
        ?.takeIf { it.matches(Regex("people/[A-Za-z0-9_-]+")) }
    private fun optionalToken(json: JSONObject, key: String): String? =
        if (!json.has(key)) null else json.getString(key).takeIf { it.isNotBlank() }
    override fun get(resource: String): Person? = try {
        val json = call("GET", resourcePath(resource), readParams())
        try { Person.parse(json).also { require(it.resource == resource) { "Mismatched contact resource" } } }
        catch (e: org.json.JSONException) { throw UnreadableContact() }
        catch (e: IllegalArgumentException) { throw UnreadableContact() }
    } catch (e: ApiException) { if (e.status == 404) null else throw e }
    override fun create(body: JSONObject): Person = Person.parse(call("POST", "people:createContact", readParams(), body))
    override fun update(person: Person, fields: Set<String>, body: JSONObject): Person {
        require(fields.isNotEmpty() && EDITABLE_FIELDS.containsAll(fields)) { "Invalid update field mask" }
        val request = JSONObject(body.toString()).put("resourceName", person.resource)
            .put("metadata", JSONObject().put("sources", JSONArray().put(person.source)))
        return Person.parse(call("PATCH", resourcePath(person.resource) + ":updateContact",
            readParams().apply { put("updatePersonFields", fields.sorted().joinToString(",")) }, request))
    }
    override fun delete(resource: String) {
        call("DELETE", resourcePath(resource) + ":deleteContact", emptyMap())
    }
}
