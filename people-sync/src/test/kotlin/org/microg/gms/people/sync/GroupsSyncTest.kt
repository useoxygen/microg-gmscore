/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GroupsSyncTest {
    private fun group(id: String = "a", name: String = "Friends", etag: String = "e1", marker: String? = null, system: Boolean = false) =
        ContactGroup.parse(JSONObject().put("resourceName", "contactGroups/$id").put("name", name).put("etag", etag)
            .put("groupType", if (system) "SYSTEM_CONTACT_GROUP" else "USER_CONTACT_GROUP").apply {
                if (marker != null) put("clientData", JSONArray().put(JSONObject().put("key", ContactGroup.CREATE_KEY).put("value", marker)))
            })
    private class Store : GroupStore {
        val rows = linkedMapOf<Long, LocalGroup>(); var token: String? = null; var next = 1L
        var afterBegin: (() -> Unit)? = null
        var protected = false
        override fun groupCheckpoint() = token
        override fun groupCheckpoint(token: String) { this.token = token }
        override fun groups() = rows.values.toList()
        override fun applyGroup(group: ContactGroup, existing: LocalGroup?) {
            if (existing != null) check(rows.getValue(existing.id).version == existing.version)
            val id = existing?.id ?: next++
            rows[id] = LocalGroup(id, (existing?.version ?: 0) + 1, group.resource, group.etag, group.name, group.name, false, false, group.system)
        }
        override fun removeGroup(existing: LocalGroup) { if (protected) throw GroupInUse(); check(rows.getValue(existing.id).version == existing.version); rows.remove(existing.id) }
        override fun beginGroup(existing: LocalGroup, pending: JSONObject): LocalGroup {
            check(rows.getValue(existing.id).version == existing.version)
            return existing.copy(version = existing.version + 1, pending = pending).also { rows[existing.id] = it; afterBegin?.invoke() }
        }
        override fun rejectGroup(existing: LocalGroup) { rows[existing.id] = rows.getValue(existing.id).copy(pending = null) }
        override fun acknowledgeGroup(existing: LocalGroup, remote: ContactGroup?) {
            val current = rows.getValue(existing.id)
            if (remote == null) removeGroup(existing)
            else if (current.version == existing.version) applyGroup(remote, current)
            else {
                val sent = existing.pending?.optString("localName") ?: existing.name
                val name = if (current.name != sent) current.name else remote.name
                rows[existing.id] = current.copy(resource = remote.resource, etag = remote.etag, name = name,
                    baseline = remote.name, dirty = current.deleted || name != remote.name, pending = null)
            }
        }
        fun edit(name: String = "Edited", deleted: Boolean = false) { rows[1] = rows.getValue(1).let { it.copy(name = name, deleted = deleted, dirty = true, version = it.version + 1) } }
        fun fresh() { rows[1] = LocalGroup(1, 1, null, null, "New", null, true, false); next = 2 }
    }
    private inner class Api : GroupsApi {
        val remote = linkedMapOf<String, ContactGroup>(); var writes = 0; var creates = 0
        var page: ((String?, String?) -> GroupPage)? = null; var unreadable: String? = null
        var loseReply = false; var reject: ApiException? = null; var afterWrite: (() -> Unit)? = null
        override fun listGroups(syncToken: String?, pageToken: String?) = page?.invoke(syncToken, pageToken) ?: GroupPage(remote.values.toList(), null, "checkpoint")
        override fun getGroup(resource: String): ContactGroup? { if (resource == unreadable) throw UnreadableContact(); return remote[resource] }
        override fun createGroup(name: String, creationId: String): ContactGroup {
            reject?.let { throw it }; writes++; creates++
            return group("created", name, "created", creationId).also { remote[it.resource] = it; afterWrite?.invoke(); if (loseReply) throw IOException("Lost") }
        }
        override fun updateGroup(group: ContactGroup, name: String): ContactGroup {
            reject?.let { throw it }; assertEquals(remote.getValue(group.resource).etag, group.etag); writes++
            return group(group.resource.substringAfter('/'), name, "updated", group.creationId()).also { remote[it.resource] = it; afterWrite?.invoke(); if (loseReply) throw IOException("Lost") }
        }
        override fun deleteGroup(resource: String) { reject?.let { throw it }; writes++; remote.remove(resource); afterWrite?.invoke(); if (loseReply) throw IOException("Lost") }
    }
    private fun setup(): Pair<Store, Api> {
        val store = Store(); val api = Api(); api.remote["contactGroups/a"] = group()
        GroupsSync(api, store) { SyncMode.DOWNLOAD }.sync(); return store to api
    }
    @Test fun protectedGroupMembersHoldDeletionAndCheckpoint() {
        val (store, api) = setup(); store.token = null; store.protected = true; api.remote.clear()
        val report = GroupsSync(api, store) { SyncMode.DOWNLOAD }.sync()
        assertEquals(1, report.conflicts); assertEquals(1, store.rows.size); assertNull(store.token)
    }
    @Test fun partialSnapshotDoesNotAcknowledgePendingGroupDeletion() {
        val (store, api) = setup(); store.edit(deleted = true); api.loseReply = true
        assertThrows(IOException::class.java) { GroupsSync(api, store) { SyncMode.TWO_WAY }.sync() }
        api.page = { _, _ -> GroupPage(emptyList(), null, "new", 1) }
        GroupsSync(api, store) { SyncMode.TWO_WAY }.sync()
        assertEquals(1, store.rows.size); assertNotNull(store.rows.getValue(1).pending); assertEquals(1, api.writes)
    }
    @Test fun offDoesNothingAndDownloadNeverUploads() {
        val store = Store().apply { fresh() }; val api = Api()
        GroupsSync(api, store) { SyncMode.OFF }.sync(); assertEquals(0, api.writes)
        assertEquals(1, GroupsSync(api, store) { SyncMode.DOWNLOAD }.sync().conflicts); assertEquals(0, api.writes)
    }
    @Test fun pagingCommitsFinalTokenAndReplayKeepsIds() {
        val store = Store(); val api = Api(); val pages = mutableListOf<String?>()
        api.page = { _, page -> pages.add(page); if (page == null) GroupPage(listOf(group()), "p", null)
            else GroupPage(listOf(group("myContacts", "myContacts", system = true)), null, "last") }
        GroupsSync(api, store) { SyncMode.DOWNLOAD }.sync(); GroupsSync(api, store) { SyncMode.DOWNLOAD }.sync()
        assertEquals(2, store.rows.size); assertEquals("last", store.token); assertEquals(listOf(null, "p", null, "p"), pages)
    }
    @Test fun failedPageDoesNotApplyOrDelete() {
        val (store, api) = setup(); store.token = null
        api.page = { _, page -> if (page == null) GroupPage(emptyList(), "p", null) else throw IOException("Offline") }
        assertThrows(IOException::class.java) { GroupsSync(api, store) { SyncMode.DOWNLOAD }.sync() }
        assertEquals(1, store.rows.size); assertNull(store.token)
    }
    @Test fun partialFullReadPreservesAbsentRowsAndTombstones() {
        val (store, api) = setup(); store.token = null; api.remote.clear()
        val tombstone = ContactGroup.parse(JSONObject().put("resourceName", "contactGroups/a").put("metadata", JSONObject().put("deleted", true)))
        api.page = { _, _ -> GroupPage(listOf(tombstone, group("b")), null, "next", 1) }
        assertEquals(1, GroupsSync(api, store) { SyncMode.DOWNLOAD }.sync().skipped)
        assertEquals(2, store.rows.size); assertNull(store.token)
    }
    @Test fun quarantinedIdentifierCannotOverwriteReadableDuplicate() {
        val (store, api) = setup()
        api.page = { _, page -> if (page == null) GroupPage(listOf(group(name = "Wrong")), "p", null)
            else GroupPage(emptyList(), null, "next", 1, setOf("contactGroups/a")) }
        GroupsSync(api, store) { SyncMode.DOWNLOAD }.sync()
        assertEquals("Friends", store.rows.getValue(1).name); assertEquals("checkpoint", store.token)
    }
    @Test fun expiredTokenRebuildsFullSnapshot() {
        val (store, api) = setup()
        api.page = { token, _ -> if (token != null) throw ApiException(410, expired = true) else GroupPage(listOf(group()), null, "fresh") }
        GroupsSync(api, store) { SyncMode.DOWNLOAD }.sync(); assertEquals("fresh", store.token)
    }
    @Test fun fullAbsenceUsesDirectReadAndLateUnreadablePreventsDeletion() {
        val (store, api) = setup(); api.remote["contactGroups/b"] = group("b")
        GroupsSync(api, store) { SyncMode.DOWNLOAD }.sync(); store.token = null; api.remote.remove("contactGroups/a")
        api.page = { _, _ -> GroupPage(emptyList(), null, "fresh") }; api.unreadable = "contactGroups/b"
        GroupsSync(api, store) { SyncMode.DOWNLOAD }.sync(); assertEquals(2, store.rows.size); assertNull(store.token)
    }
    @Test fun renameUsesFreshEtagAndPreservesConcurrentLocalRename() {
        val (store, api) = setup(); store.edit("Local")
        api.remote["contactGroups/a"] = group(etag = "new-etag")
        api.afterWrite = { store.edit("Later") }
        GroupsSync(api, store) { SyncMode.TWO_WAY }.sync()
        assertEquals("Local", api.remote.getValue("contactGroups/a").name)
        assertEquals("Later", store.rows.getValue(1).name); assertTrue(store.rows.getValue(1).dirty)
    }
    @Test fun divergentRenameAndSystemGroupEditsAreConflicts() {
        val (store, api) = setup(); store.edit("Local"); api.remote["contactGroups/a"] = group(name = "Cloud", etag = "e2")
        assertEquals(1, GroupsSync(api, store) { SyncMode.TWO_WAY }.sync().conflicts); assertEquals(0, api.writes)
        val system = Store(); system.applyGroup(group("starred", "starred", system = true), null); system.edit()
        assertEquals(1, GroupsSync(api, system) { SyncMode.TWO_WAY }.sync().conflicts); assertEquals(0, api.writes)
    }
    @Test fun lostCreateReplyRecoversOnlyByMarkerNeverNameAndNeverPostsAgain() {
        val store = Store().apply { fresh() }; val api = Api().apply { loseReply = true }
        assertThrows(IOException::class.java) { GroupsSync(api, store) { SyncMode.TWO_WAY }.sync() }
        val created = api.remote.values.single(); api.remote.clear(); api.remote["contactGroups/unrelated"] = group("unrelated", "New")
        assertEquals(1, GroupsSync(api, store) { SyncMode.TWO_WAY }.sync().uncertain)
        assertNull(store.rows.getValue(1).resource); assertEquals(1, api.creates)
        api.remote[created.resource] = created
        GroupsSync(api, store) { SyncMode.TWO_WAY }.sync()
        assertEquals(created.resource, store.rows.getValue(1).resource); assertEquals(1, api.creates)
    }
    @Test fun partialCreateRecoveryCannotDuplicateLocalGroup() {
        val store = Store().apply { fresh() }; val api = Api().apply { loseReply = true }
        assertThrows(IOException::class.java) { GroupsSync(api, store) { SyncMode.TWO_WAY }.sync() }
        api.page = { _, _ -> GroupPage(api.remote.values.toList(), null, "next", 1) }
        assertEquals(1, GroupsSync(api, store) { SyncMode.TWO_WAY }.sync().uncertain)
        assertEquals(1, store.rows.size); assertEquals(1, api.creates); assertNotNull(store.rows.getValue(1).pending)
    }
    @Test fun lostRenameReplyAndDeleteReplyRecoverWithoutReplay() {
        val (store, api) = setup(); store.edit("Local"); api.loseReply = true
        assertThrows(IOException::class.java) { GroupsSync(api, store) { SyncMode.TWO_WAY }.sync() }
        GroupsSync(api, store) { SyncMode.TWO_WAY }.sync(); assertNull(store.rows.getValue(1).pending); assertEquals(1, api.writes)
        store.edit("Local", true)
        assertThrows(IOException::class.java) { GroupsSync(api, store) { SyncMode.TWO_WAY }.sync() }
        GroupsSync(api, store) { SyncMode.TWO_WAY }.sync(); assertTrue(store.rows.isEmpty()); assertEquals(2, api.writes)
    }
    @Test fun disablingBeforeDispatchClearsOnlyUnsentJournal() {
        val store = Store().apply { fresh() }; val api = Api(); var mode = SyncMode.TWO_WAY
        store.afterBegin = { mode = SyncMode.OFF }
        GroupsSync(api, store) { mode }.sync(); assertEquals(0, api.writes); assertNull(store.rows.getValue(1).pending)
    }
    @Test fun definiteRejectionClearsJournalAndUnknownFailureRetainsIt() {
        val store = Store().apply { fresh() }; val api = Api().apply { reject = ApiException(429) }
        assertThrows(ApiException::class.java) { GroupsSync(api, store) { SyncMode.TWO_WAY }.sync() }
        assertNull(store.rows.getValue(1).pending)
        api.reject = ApiException(503)
        assertThrows(ApiException::class.java) { GroupsSync(api, store) { SyncMode.TWO_WAY }.sync() }
        assertNotNull(store.rows.getValue(1).pending)
    }
}
