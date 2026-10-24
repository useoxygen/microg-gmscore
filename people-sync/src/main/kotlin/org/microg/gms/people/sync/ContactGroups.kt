/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

const val MY_CONTACTS = "contactGroups/myContacts"
const val STARRED = "contactGroups/starred"

object Memberships {
    fun resources(person: Person): Set<String> {
        val values = person.json.optJSONArray("memberships") ?: return emptySet()
        return (0 until values.length()).mapNotNull { index ->
            values.getJSONObject(index).optJSONObject("contactGroupMembership")?.getString("contactGroupResourceName")
        }.toSet()
    }
    fun json(resources: Set<String>) = JSONArray().apply {
        for (resource in resources.sorted()) put(JSONObject().put("contactGroupMembership",
            JSONObject().put("contactGroupResourceName", resource)))
    }
    fun field(resources: Set<String>) = canonical(json(resources))
    fun fromField(value: String?): Set<String> = resources(Person("", JSONObject().put("memberships", JSONArray(value ?: "[]"))))
    fun merge(baseline: String?, local: String?, remote: String?): String {
        val before = fromField(baseline); val edited = fromField(local)
        val merged = (fromField(remote) - (before - edited)) + (edited - before)
        if (merged.isEmpty()) throw UnsupportedContactEdit()
        return field(merged)
    }
}

data class ContactGroup(val resource: String, val json: JSONObject) {
    val deleted get() = json.optJSONObject("metadata")?.optBoolean("deleted") == true
    val system get() = resource in setOf(MY_CONTACTS, STARRED) || json.optString("groupType") == "SYSTEM_CONTACT_GROUP"
    val name get() = json.optString("name")
    val etag get() = json.optString("etag")
    fun creationId(): String? = json.optJSONArray("clientData")?.let { values ->
        (0 until values.length()).map { values.getJSONObject(it) }.singleOrNull { it.optString("key") == CREATE_KEY }?.optString("value")
    }
    companion object {
        const val CREATE_KEY = "microg.contacts.group-creation-id"
        fun validResource(value: String) = value.matches(Regex("contactGroups/[A-Za-z0-9_-]+"))
        fun parse(json: JSONObject): ContactGroup {
            val resource = json.getString("resourceName")
            require(validResource(resource)) { "Invalid group resource" }
            val metadata = if (json.has("metadata")) json.getJSONObject("metadata") else null
            if (metadata?.has("deleted") == true) require(metadata.get("deleted") is Boolean) { "Invalid group tombstone" }
            val group = ContactGroup(resource, json)
            if (!group.deleted) {
                require(json.getString("etag").isNotBlank() && json.getString("name").isNotBlank()) { "Missing group fields" }
                require(json.getString("groupType") in setOf("SYSTEM_CONTACT_GROUP", "USER_CONTACT_GROUP")) { "Invalid group type" }
            }
            if (json.has("clientData")) {
                val values = json.getJSONArray("clientData")
                for (index in 0 until values.length()) {
                    values.getJSONObject(index).getString("key"); values.getJSONObject(index).getString("value")
                }
            }
            return group
        }
    }
}
data class GroupPage(val groups: List<ContactGroup>, val nextPage: String?, val nextSync: String?,
    val skipped: Int = 0, val unreadable: Set<String> = emptySet())
interface GroupsApi {
    fun listGroups(syncToken: String?, pageToken: String?): GroupPage
    fun getGroup(resource: String): ContactGroup?
    fun createGroup(name: String, creationId: String): ContactGroup
    fun updateGroup(group: ContactGroup, name: String): ContactGroup
    fun deleteGroup(resource: String)
}
data class LocalGroup(val id: Long, val version: Long, val resource: String?, val etag: String?,
    val name: String, val baseline: String?, val dirty: Boolean, val deleted: Boolean,
    val system: Boolean = false, val pending: JSONObject? = null)
class GroupInUse : Exception("Group has protected or locally edited members")
interface GroupStore {
    fun groupCheckpoint(): String?
    fun groupCheckpoint(token: String)
    fun groups(): List<LocalGroup>
    fun applyGroup(group: ContactGroup, existing: LocalGroup?)
    fun removeGroup(existing: LocalGroup)
    fun beginGroup(existing: LocalGroup, pending: JSONObject): LocalGroup
    fun holdGroupConflict(existing: LocalGroup) {
        beginGroup(existing, JSONObject(existing.pending!!.toString()).put("kind", "conflict"))
    }
    fun rejectGroup(existing: LocalGroup)
    fun acknowledgeGroup(existing: LocalGroup, remote: ContactGroup?)
}

class GroupsSync(private val api: GroupsApi, private val store: GroupStore, private val mode: () -> SyncMode) {
    private data class Snapshot(val groups: List<ContactGroup>, val token: String, val full: Boolean,
        val skipped: Int, val unreadable: Set<String>)
    private fun read(token: String?): Snapshot {
        val groups = linkedMapOf<String, ContactGroup>(); val pages = mutableSetOf<String>()
        val unreadable = mutableSetOf<String>(); var skipped = 0; var page: String? = null
        do {
            check(mode() != SyncMode.OFF)
            val result = api.listGroups(token, page)
            skipped += result.skipped; unreadable.addAll(result.unreadable)
            for (group in result.groups) {
                check(groups.put(group.resource, group) == null) { "Duplicate group identifier" }
                check(groups.size <= 100_000) { "Too many groups" }
            }
            page = result.nextPage
            if (page == null) return Snapshot(groups.values.toList(), result.nextSync ?: throw IOException("Missing group checkpoint"), token == null, skipped, unreadable)
            check(pages.add(page) && pages.size <= 1000) { "Invalid group pagination" }
        } while (true)
    }
    private fun recover(local: LocalGroup, remote: ContactGroup?) {
        val pending = local.pending!!
        val unchanged = local.name == pending.getString("localName") && local.deleted == (pending.getString("kind") == "delete")
        store.acknowledgeGroup(if (unchanged) local else local.copy(version = Long.MIN_VALUE), remote)
    }
    fun sync(): SyncReport {
        if (mode() == SyncMode.OFF) return SyncReport(0, 0, 0, 0)
        val token = store.groupCheckpoint()
        val snapshot = try { read(token) } catch (error: ApiException) {
            if (token != null && error.expired) read(null) else throw error
        }
        var skipped = snapshot.skipped; var downloaded = 0; var uploaded = 0; var conflicts = 0; var uncertain = 0
        val local = store.groups(); val byResource = local.filter { it.resource != null }.associateBy { it.resource!! }
        check(byResource.size == local.count { it.resource != null }) { "Duplicate local group source" }
        val creates = local.filter { it.pending?.optString("kind") == "create" }
        val removals = mutableListOf<LocalGroup>()
        for (remote in snapshot.groups) try {
            if (remote.resource in snapshot.unreadable) continue
            val owner = creates.singleOrNull { it.pending!!.getString("creationId") == remote.creationId() }
            if (owner != null) continue // Resolve only against a complete fresh list below.
            val existing = byResource[remote.resource]
            if (existing?.dirty == true || existing?.pending != null) continue
            if (remote.deleted) { if (existing != null) removals.add(existing) }
            else {
                val current = if (existing != null && existing.etag != remote.etag) api.getGroup(remote.resource) else remote
                if (current == null || current.deleted) { if (existing != null) removals.add(existing) }
                else { store.applyGroup(current, existing); downloaded++ }
            }
        } catch (error: UnreadableContact) { skipped++ }
        if (snapshot.full && skipped == 0) for (existing in local) try {
            if (existing.resource == null || snapshot.groups.any { it.resource == existing.resource } || existing.dirty || existing.pending != null) continue
            val current = api.getGroup(existing.resource)
            if (current == null || current.deleted) removals.add(existing) else store.applyGroup(current, existing)
        } catch (error: UnreadableContact) { skipped++ }
        if (skipped == 0) for (existing in removals) try { store.removeGroup(existing) }
        catch (error: GroupInUse) { conflicts++ }
        for (existing in store.groups().filter { it.dirty || it.pending != null }) try {
            if (existing.resource in snapshot.unreadable || existing.deleted && skipped > 0) continue
            if (existing.pending != null) {
                val pending = existing.pending
                if (pending.optString("kind") == "conflict") { conflicts++; continue }
                val resolved = when (pending.getString("kind")) {
                    "create" -> {
                        val all = if (snapshot.full) snapshot else read(null)
                        if (!snapshot.full) skipped += all.skipped
                        val matches = all.groups.filter { !it.deleted && it.creationId() == pending.getString("creationId") }
                        if (all.skipped == 0 && matches.size == 1) { recover(existing, matches.single()); true } else false
                    }
                    "update" -> api.getGroup(pending.getString("resource"))?.let {
                        if (it.name == pending.getString("localName")) { recover(existing, it); true } else false
                    } ?: false
                    "delete" -> if (api.getGroup(pending.getString("resource")) == null) { recover(existing, null); true } else false
                    else -> false
                }
                if (resolved) uploaded++ else uncertain++
                continue
            }
            if (mode() != SyncMode.TWO_WAY || existing.system) { conflicts++; continue }
            if (existing.deleted && skipped > 0) continue
            if (existing.resource == null && existing.deleted) { store.removeGroup(existing); continue }
            val latest = existing.resource?.let(api::getGroup)
            if (existing.resource != null && latest == null) {
                if (existing.deleted) store.removeGroup(existing) else conflicts++
                continue
            }
            if (latest?.system == true || !existing.deleted && existing.name.isBlank()) { conflicts++; continue }
            if (latest != null && (existing.deleted && latest.etag != existing.etag ||
                !existing.deleted && latest.name != existing.baseline && latest.name != existing.name)) { conflicts++; continue }
            if (latest != null && !existing.deleted && latest.name == existing.name) {
                store.acknowledgeGroup(existing, latest); continue
            }
            val kind = if (existing.deleted) "delete" else if (latest == null) "create" else "update"
            val creationId = UUID.randomUUID().toString()
            val journal = JSONObject().put("kind", kind).put("resource", existing.resource)
                .put("creationId", creationId).put("localName", existing.name)
            val saved = store.beginGroup(existing, journal)
            try {
                if (mode() != SyncMode.TWO_WAY) throw UploadDisabled()
                val result = when (kind) {
                    "create" -> api.createGroup(existing.name, creationId)
                    "update" -> api.updateGroup(latest!!, existing.name)
                    else -> { api.deleteGroup(existing.resource!!); null }
                }
                store.acknowledgeGroup(saved, result); uploaded++
            } catch (error: UploadDisabled) { store.rejectGroup(saved); conflicts++; break }
            catch (error: ApiException) {
                if (error.status in setOf(409, 412)) store.holdGroupConflict(saved)
                else if (error.definitelyRejected) store.rejectGroup(saved)
                if (error.status in listOf(400, 409, 412)) { conflicts++; continue }
                throw error
            }
        } catch (error: UnreadableContact) { skipped++ }
        catch (error: GroupInUse) { conflicts++ }
        if (skipped == 0 && conflicts == 0 && uncertain == 0 && mode() != SyncMode.OFF) store.groupCheckpoint(snapshot.token)
        return SyncReport(downloaded, uploaded, conflicts, uncertain, skipped)
    }
}
