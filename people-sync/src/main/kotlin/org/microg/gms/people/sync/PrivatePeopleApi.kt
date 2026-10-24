/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.URLEncoder

class PeopleClientUpdateRequired : IOException("Contacts client needs an update")
class ContactsTwoWayUnavailable : IllegalStateException("Two-way contacts sync is not available")

/** One context definition for sync, direct reads and non-enabling probes. */
class PrivatePeopleContext(val version: String) {
    init { require(version.matches(Regex("[0-9]{1,3}\\.[0-9]{1,2}\\.[0-9]{1,2}"))) }
    // Canonical system IDs must match group list/get. GDATA compatibility
    // projects starred/My Contacts into different legacy IDs. Never mix cursors.
    val binding get() = "fsa2-normal-v2:$version:${PrivatePeopleApi.READ_MASK}"
    fun json() = JSONObject().put("clientVersion", JSONObject().put("clientType", CLIENT_TYPE).put("clientVersion", version))
        .put("migrationOptions", JSONObject().put("useNewRequestMaskBehavior", true))
    fun parameters() = linkedMapOf("context.clientVersion.clientType" to CLIENT_TYPE,
        "context.clientVersion.clientVersion" to version, "context.migrationOptions.useNewRequestMaskBehavior" to "true")
    fun syncParameters(mask: String, pageSize: Int, syncToken: String? = null, pageToken: String? = null) = parameters().apply {
        put("pageSize", pageSize.toString())
        put("syncParameters.fullSyncOption", if (syncToken == null) "FULL_SYNC_OK" else "FULL_SYNC_UNDESIRABLE")
        put("requestMask.includeField", mask); put("requestMask.productContactGroup", "PRODUCT_BESTIES")
        put("mergedPersonSourceOptions.personModelParams.personModel", "CONTACT_CENTRIC")
        put("mergedPersonSourceOptions.includedProfileStates", "CORE_ID")
        syncToken?.let { put("syncParameters.syncToken", it) }; pageToken?.let { put("pageToken", it) }
    }
    companion object {
        const val CLIENT_TYPE = "GMS FSA2"
        fun fromGmsVersionCode(code: Int): PrivatePeopleContext {
            require(code >= 10_000_000)
            return PrivatePeopleContext("${code / 10_000_000}.${code / 100_000 % 100}.${code / 1000 % 100}")
        }
    }
}

/** Read-only private REST client. Mutations fail locally, before transport. */
class PrivatePeopleApi(private val transport: HttpTransport, val context: PrivatePeopleContext) : ContactsApi, PhotosApi, GroupsApi {
    companion object {
        const val ORIGIN = "https://people-pa.googleapis.com/v2/"
        const val OWNER = "microg-people-fsa2-read-v1"
        private val fields = linkedMapOf("name" to "names", "phone" to "phoneNumbers", "email" to "emailAddresses",
            "address" to "addresses", "organization" to "organizations", "about" to "biographies", "birthday" to "birthdays",
            "nickname" to "nicknames", "website" to "urls", "relation" to "relations", "event" to "events", "im" to "imClients",
            "sipAddress" to "sipAddresses", "userDefined" to "userDefined", "photo" to "photos")
        // Private field masks use singular snake_case paths, not public v1 names.
        val READ_MASK = (listOf("metadata", "name", "phone", "email", "address", "organization", "about", "birthday",
            "nickname", "website", "relation", "event", "im", "sip_address", "user_defined", "photo", "contact_group_membership"))
            .joinToString(",") { "person.$it" }
        val SYSTEM_GROUPS = setOf("starred", "friends", "family", "coworkers", "myContacts", "chatBuddies", "all", "blocked")
        fun groupResource(id: String): String = if (id in SYSTEM_GROUPS) "contactGroups/fsa_s_$id" else "contactGroups/fsa_g_${hex(id)}"
        private fun groupId(resource: String): String {
            val id = when {
                resource.startsWith("contactGroups/fsa_s_") -> resource.removePrefix("contactGroups/fsa_s_").also { require(it in SYSTEM_GROUPS) }
                resource.startsWith("contactGroups/fsa_g_") -> hex(resource.removePrefix("contactGroups/fsa_g_"))
                else -> throw IllegalArgumentException("Invalid private group resource")
            }
            require(groupResource(id) == resource); return id
        }
        fun isPhotoReference(value: String) = value.matches(Regex("fsa2-photo:[0-9a-f]{1,16}:[0-9a-f]{64}"))
        private fun decodePhoto(value: String): ByteArray {
            require(value.length <= (ContactPhotos.MAX_BYTES + 2) / 3 * 4 && value.length % 4 == 0)
            require(value.matches(Regex("[A-Za-z0-9+/]*={0,2}")))
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val padding = value.takeLastWhile { it == '=' }.length
            val bytes = ByteArray(value.length / 4 * 3 - padding)
            var out = 0
            for (i in value.indices step 4) {
                val a = alphabet.indexOf(value[i]); val b = alphabet.indexOf(value[i + 1])
                val c = if (value[i + 2] == '=') 0 else alphabet.indexOf(value[i + 2])
                val d = if (value[i + 3] == '=') 0 else alphabet.indexOf(value[i + 3])
                require(a >= 0 && b >= 0 && c >= 0 && d >= 0)
                if (value[i + 2] == '=') require(i + 4 == value.length && value[i + 3] == '=' && b and 15 == 0)
                if (value[i + 3] == '=') require(i + 4 == value.length && c and 3 == 0)
                if (out < bytes.size) bytes[out++] = ((a shl 2) or (b shr 4)).toByte()
                if (out < bytes.size) bytes[out++] = ((b shl 4) or (c shr 2)).toByte()
                if (out < bytes.size) bytes[out++] = ((c shl 6) or d).toByte()
            }
            require(bytes.isNotEmpty() && bytes.size <= ContactPhotos.MAX_BYTES)
            return bytes
        }
        private fun text(json: JSONObject, key: String): String {
            val value = json.get(key); require(value is String); return value
        }
        private fun hex(value: String): String {
            require(value.matches(Regex("[0-9a-fA-F]{1,16}")))
            val number = BigInteger(value, 16)
            require(number.signum() > 0 && number.bitLength() <= 63)
            return number.toString(16)
        }
        private fun decimal(value: Any): String {
            require(value is String || value is Long || value is Int)
            val text = value.toString(); require(text.matches(Regex("[0-9]{1,19}")))
            val number = BigInteger(text); require(number.signum() > 0 && number.bitLength() <= 63)
            return number.toString(16)
        }
        private fun resource(id: String) = "people/fsa_${hex(id)}"
        private fun container(meta: JSONObject): String {
            val modern = if (meta.has("containerType")) text(meta, "containerType") else null
            val legacy = if (meta.has("container")) text(meta, "container") else null
            require(modern == null || legacy == null || modern == legacy)
            return modern ?: legacy ?: throw UnreadableContact()
        }
        private fun sourceIds(raw: JSONObject): List<Pair<String, JSONObject>> {
            val meta = raw.getJSONObject("metadata")
            val ids = meta.getJSONArray("contactId").let { a -> (0 until a.length()).map { decimal(a.get(it)) } }
            require(ids.isNotEmpty() && ids.distinct().size == ids.size)
            val values = meta.getJSONObject("identityInfo").getJSONArray("sourceIds")
            val sources = (0 until values.length()).map { values.getJSONObject(it) }.filter { container(it) == "CONTACT" }
                .map { hex(text(it, "id")) to it }
            require(sources.map { it.first }.toSet() == ids.toSet() && sources.size == ids.size)
            return sources
        }
        fun resourceHints(raw: JSONObject?): Set<String> = buildSet {
            val meta = raw?.optJSONObject("metadata") ?: return@buildSet
            meta.optJSONArray("contactId")?.let { ids ->
                for (i in 0 until ids.length()) runCatching { resource(decimal(ids.get(i))) }.getOrNull()?.let(::add)
            }
            meta.optJSONObject("identityInfo")?.optJSONArray("sourceIds")?.let { sources ->
                for (i in 0 until sources.length()) runCatching {
                    val source = sources.getJSONObject(i); require(container(source) == "CONTACT"); resource(text(source, "id"))
                }.getOrNull()?.let(::add)
            }
        }
        private fun belongs(value: JSONObject, id: String, all: Set<String>): Boolean {
            val meta = value.getJSONObject("metadata")
            if (meta.has("otherDedupedContainers")) {
                val deduped = meta.getJSONArray("otherDedupedContainers")
                for (i in 0 until deduped.length()) if (container(deduped.getJSONObject(i)) == "CONTACT") throw UnreadableContact()
            }
            if (container(meta) != "CONTACT") return false
            val source = when {
                meta.has("encodedContainerId") -> hex(text(meta, "encodedContainerId"))
                meta.has("containerId") -> decimal(meta.get("containerId"))
                else -> { require(all.size == 1); id }
            }
            require(source in all)
            return source == id
        }
        private fun field(value: JSONObject, kind: String): JSONObject {
            val output = JSONObject()
            val aliases = when (kind) {
                "name" -> mapOf("yomiGivenName" to "phoneticGivenName", "yomiMiddleName" to "phoneticMiddleName", "yomiFamilyName" to "phoneticFamilyName")
                "address" -> mapOf("formatted" to "formattedValue", "locality" to "city")
                "organization" -> mapOf("description" to "jobDescription", "yomiName" to "phoneticName")
                "relation" -> mapOf("value" to "person")
                "im" -> mapOf("value" to "username")
                else -> emptyMap()
            }
            // Only mapped values enter the normalized model, never private
            // lookup tokens, fingerprints, owner/profile data or debug info.
            val keys = when (kind) {
                "name" -> listOf("givenName", "middleName", "familyName", "honorificPrefix", "honorificSuffix", "unstructuredName", "displayName", "yomiGivenName", "yomiMiddleName", "yomiFamilyName")
                "address" -> listOf("formatted", "streetAddress", "poBox", "extendedAddress", "locality", "region", "postalCode", "country", "type")
                "organization" -> listOf("name", "title", "department", "description", "symbol", "yomiName", "stringType")
                "email" -> listOf("value", "type", "displayName")
                "about" -> listOf("value", "contentType")
                "im" -> listOf("value", "type", "protocol")
                "userDefined" -> listOf("key", "value")
                "relation", "phone", "website", "sipAddress" -> listOf("value", "type")
                "nickname" -> listOf("value")
                "photo" -> listOf("url", "photoToken")
                "birthday", "event" -> listOf("type")
                else -> emptyList()
            }
            for (key in keys) if (value.has(key)) output.put(aliases[key] ?: key, text(value, key))
            if (kind == "organization") {
                val type = if (value.has("stringType")) text(value, "stringType") else when (value.optString("type")) {
                    "WORK" -> "work"; "SCHOOL" -> "school"; else -> "other"
                }
                output.remove("stringType"); output.put("type", type)
            }
            if (kind == "birthday" || kind == "event") {
                val date = if (value.has("calendarDay")) value.getJSONObject("calendarDay")
                    else if (kind == "birthday" && value.has("value")) ContactDates.parse(text(value, "value")) ?: throw UnreadableContact()
                    else throw UnreadableContact()
                for (key in listOf("year", "month", "day")) if (date.has(key)) require(date.get(key) is Int)
                if (ContactDates.format(date) == null) throw UnreadableContact()
                output.put("date", JSONObject(date.toString()))
            }
            val meta = value.getJSONObject("metadata")
            if (meta.has("containerPrimary")) require(meta.get("containerPrimary") is Boolean)
            output.put("metadata", JSONObject().put("sourcePrimary", meta.optBoolean("containerPrimary"))
                .put("source", JSONObject().put("type", "CONTACT")))
            if (kind == "photo") {
                if (value.has("isDefault")) require(value.get("isDefault") is Boolean)
                if (value.has("isMonogram")) require(value.get("isMonogram") is Boolean)
                output.put("default", value.optBoolean("isDefault") || value.optBoolean("isMonogram"))
            }
            return output
        }
        fun normalize(raw: JSONObject): List<Person> {
            val sources = sourceIds(raw)
            return sources.map { (id, source) ->
                if (source.has("deleted")) require(source.get("deleted") is Boolean)
                val deleted = source.optBoolean("deleted")
                val normalized = JSONObject().put("resourceName", resource(id)).put("metadata", JSONObject()
                    .put("deleted", deleted).put("sources", JSONArray().put(JSONObject().put("type", "CONTACT")
                        .put("id", id).put("etag", if (deleted) "" else text(source, "sourceEtag")))))
                if (!deleted) {
                    for ((kind, target) in fields) if (raw.has(kind)) {
                        val values = raw.getJSONArray(kind); val result = JSONArray()
                        for (i in 0 until values.length()) {
                            val value = values.getJSONObject(i)
                            if (belongs(value, id, sources.map { it.first }.toSet())) {
                                if (kind == "photo") {
                                    for (key in listOf("isDefault", "isMonogram")) if (value.has(key)) require(value.get(key) is Boolean)
                                    if (value.optBoolean("isDefault") || value.optBoolean("isMonogram")) continue
                                }
                                val mapped = field(value, kind)
                                if (kind == "photo") {
                                    val revision = ContactPhotos.hash(canonical(mapped).toByteArray(Charsets.UTF_8))
                                    mapped.put("url", "fsa2-photo:$id:$revision")
                                }
                                result.put(mapped)
                            }
                        }
                        normalized.put(target, result)
                    }
                    val memberships = linkedSetOf<String>()
                    if (raw.has("contactGroupMembership")) {
                        val groups = raw.getJSONArray("contactGroupMembership")
                        for (i in 0 until groups.length()) {
                            val value = groups.getJSONObject(i)
                            if (!belongs(value, id, sources.map { it.first }.toSet())) continue
                            val ids = mutableSetOf<String>()
                            if (value.has("contactGroupId")) ids.add(groupResource(text(value, "contactGroupId")))
                            if (value.has("systemContactGroupId")) {
                                val system = when (text(value, "systemContactGroupId")) {
                                    "MY_CONTACTS" -> "myContacts"; "STARRED" -> "starred"; "FRIENDS" -> "friends"
                                    "FAMILY" -> "family"; "COWORKERS" -> "coworkers"; else -> throw UnreadableContact()
                                }
                                ids.add(groupResource(system))
                            }
                            require(ids.size <= 1)
                            memberships.addAll(ids) // Product groups are not user labels.
                        }
                    }
                    normalized.put("memberships", Memberships.json(memberships))
                    normalized.put("_privateStarred", groupResource("starred") in memberships)
                }
                Person.parse(normalized)
            }
        }
    }
    private fun url(path: String, params: Map<String, String>) = ORIGIN + path + params.entries.joinToString("&", "?") {
        URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
    }
    private fun call(method: String, path: String, params: Map<String, String>, body: JSONObject? = null): JSONObject {
        check(method == "GET" && path in setOf("people/sync", "people/photosEncoded", "people/contactGroups/list", "people/contactGroups/get") || method == "POST" && path == "people:batchGet")
        val reply = transport.request(method, url(path, params), body?.toString())
        if (reply.status !in 200..299) {
            val status = ContactsDiagnostics.apiStatus(reply.body)
            val reason = ContactsDiagnostics.apiReason(reply.body)
            val expired = reply.status in setOf(400, 409, 410, 412) && runCatching {
                val error = JSONObject(reply.body).getJSONObject("error")
                val text = error.optString("message")
                val details = error.optJSONArray("details") ?: JSONArray()
                listOf("EXPIRED_SYNC_TOKEN", "INVALID_PAGE_TOKEN").any { marker ->
                    text.contains(marker) || (0 until details.length()).any { details.optJSONObject(it)?.optString("reason") == marker }
                }
            }.getOrDefault(false)
            if (!expired && (reply.status == 426 || reply.status == 400 && status == "INVALID_ARGUMENT")) throw PeopleClientUpdateRequired()
            throw ApiException(reply.status, expired, reply.retryAfterSeconds, reason, status)
        }
        try { return JSONObject(reply.body) } catch (error: Exception) { throw IOException("Invalid private contacts response") }
    }
    private fun params(syncToken: String?, pageToken: String?, probe: Boolean = false) =
        context.syncParameters(if (probe) "person.metadata" else READ_MASK, if (probe) 1 else 100, syncToken, pageToken).apply {
            if (probe) put("fields", "nextPageToken,nextSyncToken")
        }
    fun checkAccess() {
        val result = call("GET", "people/sync", params(null, null, true))
        if (token(result, "nextPageToken") == null && token(result, "nextSyncToken") == null) throw IOException("Invalid private contacts access response")
    }
    private fun token(json: JSONObject, key: String): String? {
        if (!json.has(key)) return null
        val value = json.get(key); require(value is String && value.isNotBlank())
        return value
    }
    override fun list(syncToken: String?, pageToken: String?): Page {
        val json = call("GET", "people/sync", params(syncToken, pageToken))
        val values = if (json.has("people")) json.getJSONArray("people") else JSONArray()
        val people = mutableListOf<Person>(); val unreadable = mutableSetOf<String>()
        var skipped = if (json.has("partialFailureStates") && json.getJSONArray("partialFailureStates").length() > 0) 1 else 0
        for (i in 0 until values.length()) try { people.addAll(normalize(values.getJSONObject(i))) }
        catch (error: UnreadableContact) { skipped++; unreadable.addAll(resourceHints(values.optJSONObject(i))) }
        catch (error: org.json.JSONException) { skipped++; unreadable.addAll(resourceHints(values.optJSONObject(i))) }
        catch (error: IllegalArgumentException) { skipped++; unreadable.addAll(resourceHints(values.optJSONObject(i))) }
        val nextPage = token(json, "nextPageToken")
        val nextSync = token(json, "nextSyncToken")
        if (nextPage != null && nextSync != null) throw IOException("Premature contacts checkpoint")
        return Page(people, nextPage, nextSync, skipped, unreadable)
    }
    override fun get(resource: String): Person? {
        val suffix = resource.removePrefix("people/fsa_")
        require(resource == resource(suffix))
        val personId = "c" + BigInteger(suffix, 16).toString()
        val result = call("POST", "people:batchGet", emptyMap(), JSONObject().put("personId", JSONArray().put(personId))
            .put("requestMask", JSONObject().put("includeField", READ_MASK).put("productContactGroup", JSONArray().put("PRODUCT_BESTIES")))
            .put("mergedPersonSourceOptions", JSONObject().put("personModelParams", JSONObject().put("personModel", "CONTACT_CENTRIC"))
                .put("includedProfileStates", JSONArray().put("CORE_ID"))).put("context", context.json()))
        try {
            val responses = result.getJSONArray("personResponse"); require(responses.length() == 1)
            val response = responses.getJSONObject(0); require(text(response, "personId") == personId)
            val status = if (response.has("status")) text(response, "status") else null
            val code = response.optJSONObject("responseStatus")?.let { if (it.has("code")) it.getInt("code") else 0 } ?: 0
            if ((status == "NOT_FOUND" && code in setOf(0, 5)) || status == null && code == 5) return null
            require((status == null || status == "SUCCESS") && code == 0)
            return normalize(response.getJSONObject("person")).singleOrNull { it.resource == resource } ?: throw UnreadableContact()
        } catch (error: org.json.JSONException) { throw UnreadableContact() }
        catch (error: IllegalArgumentException) { throw UnreadableContact() }
    }
    override fun create(body: JSONObject): Person = throw UploadDisabled()
    override fun update(person: Person, fields: Set<String>, body: JSONObject): Person = throw UploadDisabled()
    override fun delete(resource: String): Unit = throw UploadDisabled()
    override fun writePhoto(resource: String, bytes: ByteArray?): Person = throw UploadDisabled()
    override fun readPhoto(url: String): ByteArray {
        if (!isPhotoReference(url)) throw UnreadableContact()
        val id = hex(url.split(':')[1])
        val result = try { call("GET", "people/photosEncoded", context.parameters().apply {
            put("type", "CONTACT_PHOTO"); put("containerId", id); put("useBytes", "true")
        }) } catch (error: ApiException) { if (error.status == 404) throw UnreadableContact() else throw error }
        try {
            require(text(result, "format") in setOf("PNG", "JPEG"))
            return decodePhoto(text(result, "photoBytes"))
        } catch (error: org.json.JSONException) { throw UnreadableContact() }
        catch (error: IllegalArgumentException) { throw UnreadableContact() }
    }
    private fun parseGroup(raw: JSONObject, allowDeleted: Boolean): ContactGroup {
        val group = raw.optJSONObject("result")
        val id = group?.getJSONObject("id")?.let { text(it, "id") }
            ?: raw.optJSONObject("id")?.let { text(it, "id") } ?: if (raw.has("groupId")) text(raw, "groupId") else throw UnreadableContact()
        val resource = groupResource(id)
        raw.optJSONObject("id")?.let { require(groupResource(text(it, "id")) == resource) }
        val status = if (raw.has("statusCode")) text(raw, "statusCode") else "OK"
        val error = if (raw.has("error")) text(raw, "error") else ""
        if (status == "NOT_FOUND" && group == null && allowDeleted) return ContactGroup.parse(JSONObject().put("resourceName", resource)
            .put("metadata", JSONObject().put("deleted", true)))
        if (status != "OK" || error.isNotBlank() || group == null) throw UnreadableContact()
        val type = text(group.getJSONObject("groupType"), "type")
        require(type in setOf("CONTACT_GROUP", "SYSTEM_CONTACT_GROUP"))
        require((id in SYSTEM_GROUPS) == (type == "SYSTEM_CONTACT_GROUP"))
        val name = group.getJSONObject("name")
        val value = text(name, "value"); require(value.isNotBlank())
        val normalized = JSONObject().put("resourceName", resource).put("name", value)
            .put("formattedName", if (name.has("formattedValue")) text(name, "formattedValue") else value)
            .put("groupType", if (type == "SYSTEM_CONTACT_GROUP") "SYSTEM_CONTACT_GROUP" else "USER_CONTACT_GROUP")
        if (id in SYSTEM_GROUPS) normalized.put("_privateSystemId", id)
        val etag = if (raw.has("fingerprint")) text(raw, "fingerprint").also { require(it.isNotBlank()) }
            else "read-only:" + ContactPhotos.hash(canonical(normalized).toByteArray(Charsets.UTF_8))
        return ContactGroup.parse(normalized.put("etag", etag))
    }
    override fun listGroups(syncToken: String?, pageToken: String?): GroupPage {
        val result = call("GET", "people/contactGroups/list", context.parameters().apply {
            put("pageSize", "100"); syncToken?.let { put("syncToken", it); put("options", "INCLUDE_DELETED") }
            pageToken?.let { put("pageToken", it) }
        })
        val values = if (result.has("groups")) result.getJSONArray("groups") else JSONArray()
        val groups = mutableListOf<ContactGroup>(); val unreadable = mutableSetOf<String>(); var skipped = 0
        for (i in 0 until values.length()) try { groups.add(parseGroup(values.getJSONObject(i), syncToken != null)) }
        catch (error: UnreadableContact) { skipped++; groupHint(values.optJSONObject(i))?.let(unreadable::add) }
        catch (error: org.json.JSONException) { skipped++; groupHint(values.optJSONObject(i))?.let(unreadable::add) }
        catch (error: IllegalArgumentException) { skipped++; groupHint(values.optJSONObject(i))?.let(unreadable::add) }
        val nextPage = token(result, "nextPageToken")
        val nextSync = token(result, "nextSyncToken")
        if (nextPage != null && nextSync != null) throw IOException("Premature group checkpoint")
        return GroupPage(groups, nextPage, nextSync, skipped, unreadable)
    }
    private fun groupHint(raw: JSONObject?): String? = runCatching {
        val id = raw!!.optJSONObject("result")?.optJSONObject("id") ?: raw.optJSONObject("id")
        groupResource(id?.let { text(it, "id") } ?: text(raw, "groupId"))
    }.getOrNull()
    override fun getGroup(resource: String): ContactGroup? {
        val result = call("GET", "people/contactGroups/get", context.parameters().apply { put("groupId", groupId(resource)) })
        try {
            val groups = result.getJSONArray("groups"); require(groups.length() == 1)
            val group = parseGroup(groups.getJSONObject(0), true); require(group.resource == resource)
            return group.takeUnless { it.deleted }
        } catch (error: org.json.JSONException) { throw UnreadableContact() }
        catch (error: IllegalArgumentException) { throw UnreadableContact() }
    }
    override fun createGroup(name: String, creationId: String): ContactGroup = throw UploadDisabled()
    override fun updateGroup(group: ContactGroup, name: String): ContactGroup = throw UploadDisabled()
    override fun deleteGroup(resource: String): Unit = throw UploadDisabled()
}
