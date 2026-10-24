/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

typealias Fields = Map<String, String>
data class LocalContact(val id: Long, val version: Long, val resource: String?, val etag: String?,
    val fields: Fields, val baseline: Fields, val dirty: Boolean, val deleted: Boolean,
    val pending: JSONObject? = null, val unsupported: Boolean = false, val hasPhoto: Boolean = false, val photoRevision: String? = null)

interface ContactStore {
    fun checkpoint(): String?
    fun checkpoint(token: String)
    fun contacts(): List<LocalContact>
    fun project(person: Person): Fields
    fun apply(person: Person, existing: LocalContact?, readOnly: Boolean)
    fun remove(existing: LocalContact)
    // Persist before any outbound request. Returns the provider version after
    // recording the journal, without changing the contact's dirty/deleted state.
    fun begin(existing: LocalContact, pending: JSONObject): LocalContact
    fun holdConflict(existing: LocalContact) {
        begin(existing, JSONObject(existing.pending!!.toString()).put("kind", "conflict"))
    }
    fun rejected(existing: LocalContact)
    // Retain concurrent local edits and their dirty flag when acknowledging.
    fun acknowledge(existing: LocalContact, remote: Person?)
}

data class SyncReport(val downloaded: Int, val uploaded: Int, val conflicts: Int, val uncertain: Int, val skipped: Int = 0)

class ContactsSync(private val api: ContactsApi, private val store: ContactStore,
    private val mode: () -> SyncMode) {
    private data class Snapshot(val people: List<Person>, val token: String, val full: Boolean, val skipped: Int, val unreadable: Set<String>)
    private fun acknowledgeReadback(existing: LocalContact, person: Person?) {
        val pending = existing.pending!!
        val sent = jsonFields(pending.getJSONObject("local"))
        val unchanged = existing.fields == sent && existing.deleted == (pending.getString("kind") == "delete")
        // Recovery reads the current provider version, which may include edits
        // made after dispatch. Do not mistake that version for the sent one.
        store.acknowledge(if (unchanged) existing else existing.copy(version = Long.MIN_VALUE), person)
    }

    private fun download(token: String?): Snapshot {
        val people = linkedMapOf<String, Person>()
        val pages = mutableSetOf<String>()
        var pageToken: String? = null
        var skipped = 0
        val unreadable = mutableSetOf<String>()
        do {
            check(mode() != SyncMode.OFF) { "Contacts sync disabled" }
            val page = api.list(token, pageToken)
            skipped += page.skipped
            unreadable.addAll(page.unreadableResources)
            for (person in page.people) {
                check(people.put(person.resource, person) == null) { "Duplicate remote contact" }
                check(people.size <= 100_000) { "Contact snapshot too large" }
            }
            pageToken = page.nextPage
            if (pageToken == null) return Snapshot(people.values.toList(),
                page.nextSync ?: throw IOException("Missing contacts checkpoint"), token == null, skipped, unreadable)
            check(pages.add(pageToken) && pages.size <= 1_000) { "Invalid contact pagination" }
        } while (true)
    }

    fun sync(): SyncReport {
        if (mode() == SyncMode.OFF) return SyncReport(0, 0, 0, 0)
        val saved = store.checkpoint()
        val snapshot = try { download(saved) } catch (e: ApiException) {
            if (saved != null && e.expired) download(null) else throw e
        }
        var skipped = snapshot.skipped
        var downloaded = 0
        var uploaded = 0
        var conflicts = 0
        var uncertain = 0
        val local = store.contacts()
        val byResource = local.filter { it.resource != null }.associateBy { it.resource!! }
        check(byResource.size == local.count { it.resource != null }) { "Duplicate local remote identifier" }
        val seen = snapshot.people.map { it.resource }.toSet()
        val removals = mutableListOf<LocalContact>()
        val pendingCreates = local.filter { it.pending?.optString("kind") == "create" }

        for (remote in snapshot.people) try {
            if (remote.resource in snapshot.unreadable || remote.deleted && skipped > 0) continue
            val createOwner = pendingCreates.singleOrNull { it.pending!!.optString("creationId") == remote.creationId() }
            if (createOwner != null) {
                if (!remote.deleted && snapshot.skipped == 0 && snapshot.people.count { it.creationId() == remote.creationId() } == 1) {
                    acknowledgeReadback(createOwner, remote)
                    uploaded++
                }
                continue
            }
            val existing = byResource[remote.resource]
            // Never overwrite pending requests or local edits with a pull.
            if (existing?.pending != null || existing?.dirty == true) continue
            if (remote.deleted) {
                if (existing != null) removals.add(existing)
            } else {
                val current = if (existing != null && existing.etag != remote.etag) api.get(remote.resource) else remote
                if (current == null || current.deleted) {
                    if (existing != null) removals.add(existing)
                } else store.apply(current, existing, mode() != SyncMode.TWO_WAY)
                downloaded++
            }
        } catch (e: UnreadableContact) { skipped++ }
        if (snapshot.full && skipped == 0) for (existing in local) try {
            if (existing.resource == null || existing.resource in seen || existing.dirty || existing.pending != null) continue
            // List reads lag behind writes. Verify absence using a direct read
            // before removing a row after a full snapshot or token reset.
            val current = api.get(existing.resource)
            if (current == null || current.deleted) removals.add(existing)
            else store.apply(current, existing, mode() != SyncMode.TWO_WAY)
        } catch (e: UnreadableContact) { skipped++ }

        // Validate all pull reads before deleting anything. A late unreadable
        // direct read must also preserve earlier deletion candidates.
        if (skipped == 0) for (existing in removals) store.remove(existing)

        for (existing in store.contacts().filter { it.dirty || it.pending != null }) try {
            if (existing.resource in snapshot.unreadable || existing.deleted && skipped > 0) continue
            if (existing.pending?.optString("kind") == "photo") {
                // Photo recovery does not apply metadata skipped above. Hold the
                // checkpoint so a later pass revisits it after the journal clears.
                uncertain++; continue
            }
            if (existing.pending != null) {
                val pending = existing.pending
                if (pending.optString("kind") == "conflict") { conflicts++; continue }
                val resolved = when (pending.getString("kind")) {
                    "create" -> {
                        // A lost create reply must never trigger a second POST.
                        // The unique clientData marker allows eventual readback.
                        val recovery = if (snapshot.full) snapshot else download(null)
                        if (!snapshot.full) skipped += recovery.skipped
                        val all = if (recovery.skipped == 0) recovery.people else emptyList()
                        val matches = all.filter { !it.deleted && it.creationId() == pending.getString("creationId") }
                        if (matches.size == 1) { acknowledgeReadback(existing, matches.single()); true } else false
                    }
                    "update" -> {
                        val current = api.get(pending.getString("resource"))
                        val expected = jsonFields(pending.getJSONObject("expected"))
                        if (current != null && expected.all { (key, value) -> (store.project(current)[key] ?: "[]") == value }) {
                            acknowledgeReadback(existing, current); true
                        } else false
                    }
                    "delete" -> {
                        if (api.get(pending.getString("resource")) == null) {
                            acknowledgeReadback(existing, null); true
                        } else false
                    }
                    else -> false
                }
                if (resolved) uploaded++ else uncertain++
                continue
            }
            if (mode() != SyncMode.TWO_WAY) { conflicts++; continue }
            if (existing.unsupported && !existing.deleted) { conflicts++; continue }
            if (!existing.deleted && existing.fields.filterKeys { it != "memberships" }.values.all { it == "[]" } && !existing.hasPhoto &&
                (existing.resource == null || existing.baseline.filterKeys { it != "memberships" }.values.any { it != "[]" })) { conflicts++; continue }
            if (existing.resource == null && existing.deleted) {
                store.remove(existing) // A never-uploaded local tombstone.
                continue
            }
            val latest = existing.resource?.let { api.get(it) }
            if (existing.resource != null && latest == null) {
                if (existing.deleted) store.remove(existing) else conflicts++
                continue
            }
            val changed = PeopleApi.EDITABLE_FIELDS.filter {
                (existing.fields[it] ?: "[]") != (existing.baseline[it] ?: "[]")
            }.toSet()
            if (latest != null) {
                val remoteFields = store.project(latest)
                if (existing.deleted && (latest.etag != existing.etag || existing.photoRevision != null &&
                    ContactPhotos.revision(latest) != existing.photoRevision) || changed.any {
                    it != "memberships" && (remoteFields[it] ?: "[]") != (existing.baseline[it] ?: "[]") &&
                        (remoteFields[it] ?: "[]") != (existing.fields[it] ?: "[]")
                }) { conflicts++; continue }
                if (!existing.deleted && changed.isEmpty()) {
                    store.acknowledge(existing, latest)
                    continue
                }
            }
            val kind = if (existing.deleted) "delete" else if (latest == null) "create" else "update"
            var payload = fieldsJson(existing.fields, if (kind == "create") existing.fields.keys else changed)
            if (kind == "update") {
                try {
                    if ("memberships" in changed) payload.put("memberships", JSONArray(Memberships.merge(
                        existing.baseline["memberships"], existing.fields["memberships"], store.project(latest!!)["memberships"])))
                    payload = api.prepareUpdate(latest!!, changed, payload)
                } catch (e: UnsupportedContactEdit) { conflicts++; continue }
            }
            val creationId = UUID.randomUUID().toString()
            if (kind == "create") payload.put("clientData", JSONArray().put(JSONObject()
                .put("key", Person.CREATE_KEY).put("value", creationId)))
            val expected = existing.fields.toMutableMap()
            if ("memberships" in changed && payload.has("memberships")) expected["memberships"] = Memberships.field(Memberships.resources(Person("", payload)))
            val pending = JSONObject().put("kind", kind).put("creationId", creationId)
                .put("resource", existing.resource).put("expected", fieldsJson(expected, changed))
                .put("local", fieldsJson(existing.fields, existing.fields.keys))
            val journaled = store.begin(existing, pending)
            try {
                // Recheck the current opt-in immediately before cloud effects.
                if (mode() != SyncMode.TWO_WAY) throw UploadDisabled()
                val response = when (kind) {
                    "create" -> api.create(payload)
                    "update" -> api.update(latest!!, changed, payload)
                    else -> { api.delete(existing.resource!!); null }
                }
                store.acknowledge(journaled, response)
                uploaded++
            } catch (e: UploadDisabled) {
                // The guard rejected dispatch; no remote effect is ambiguous.
                store.rejected(journaled)
                conflicts++
                break
            } catch (e: ApiException) {
                if (e.status in setOf(409, 412)) store.holdConflict(journaled)
                else if (e.definitelyRejected) store.rejected(journaled)
                if (e.status in listOf(400, 409, 412)) { conflicts++; continue }
                throw e
            }
        } catch (e: UnreadableContact) { skipped++ }
        // Retrying the old checkpoint revisits any pull skipped for dirty rows.
        if (conflicts == 0 && uncertain == 0 && skipped == 0 && mode() != SyncMode.OFF) store.checkpoint(snapshot.token)
        return SyncReport(downloaded, uploaded, conflicts, uncertain, skipped)
    }

    companion object {
        fun jsonFields(json: JSONObject): Fields = json.keys().asSequence().associateWith { canonical(json.get(it)) }
        fun fieldsJson(fields: Fields, keys: Set<String>): JSONObject = JSONObject().apply {
            for (key in keys) {
                require(key in PeopleApi.EDITABLE_FIELDS) { "Unsupported contact field" }
                put(key, JSONArray(fields[key] ?: "[]"))
            }
        }
    }
}
