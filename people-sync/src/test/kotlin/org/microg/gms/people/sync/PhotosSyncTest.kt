/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PhotosSyncTest {
    private fun person(url: String? = null, etag: String = "e1"): Person = Person.parse(JSONObject()
        .put("resourceName", "people/a").put("metadata", JSONObject().put("sources", JSONArray().put(JSONObject().put("type", "CONTACT").put("etag", etag))))
        .apply { if (url != null) put("photos", JSONArray().put(JSONObject().put("url", url).put("metadata", JSONObject()
            .put("source", JSONObject().put("type", "CONTACT"))))) })
    private class Store : PhotoStore, ContactStore {
        var saved: String? = null
        override fun checkpoint() = saved
        override fun checkpoint(token: String) { saved = token }
        override fun contacts() = listOf(value.contact)
        override fun project(person: Person): Fields = person.json.optJSONArray("names")?.let {
            mapOf("names" to canonical(it))
        }.orEmpty()
        override fun apply(person: Person, existing: LocalContact?, readOnly: Boolean) {
            check(existing != null && value.contact.version == existing.version)
            value = value.copy(contact = existing.copy(version = existing.version + 1, etag = person.etag,
                fields = project(person), baseline = project(person), dirty = false),
                remoteHint = ContactPhotos.reference(person), remoteRevision = ContactPhotos.revision(person))
        }
        override fun remove(existing: LocalContact) = error("Unexpected metadata deletion")
        override fun begin(existing: LocalContact, pending: JSONObject): LocalContact = error("Unexpected metadata upload")
        override fun rejected(existing: LocalContact) = error("Unexpected metadata rejection")
        override fun acknowledge(existing: LocalContact, remote: Person?) = error("Unexpected metadata acknowledgment")
        var value = PhotoContact(LocalContact(1, 1, "people/a", "e1", emptyMap(), emptyMap(), false, false), ContactPhotos.NONE, null, null)
        var bytes: ByteArray? = null; var installs = 0; var afterBegin: (() -> Unit)? = null
        // Synthetic "picture" = first byte; re-encoding keeps it, a different picture changes it.
        override fun photoSignature(bytes: ByteArray) = if (bytes.isEmpty()) null else "%02x".format((bytes[0] * 37) and 255).repeat(4)
        override fun photoContacts() = listOf(value)
        override fun photoBytes(existing: PhotoContact) = bytes
        override fun beginPhoto(existing: PhotoContact, pending: JSONObject): PhotoContact {
            check(value.contact.version == existing.contact.version)
            return existing.copy(contact = existing.contact.copy(version = existing.contact.version + 1, pending = pending)).also { value = it; afterBegin?.invoke() }
        }
        override fun rejectPhoto(existing: PhotoContact) { value = value.copy(contact = value.contact.copy(pending = null)) }
        override fun completePhoto(existing: PhotoContact, remote: Person, sentToken: String, remoteHash: String?) {
            val ref = ContactPhotos.reference(remote)
            value = value.copy(baseline = PhotoBaseline(sentToken, ref, ContactPhotos.revision(remote), remoteHash), remoteHint = ref, remoteRevision = ContactPhotos.revision(remote),
                contact = value.contact.copy(version = value.contact.version + 1, pending = null, dirty = value.contact.deleted || value.token != sentToken || value.contact.fields != value.contact.baseline))
        }
        override fun installPhoto(existing: PhotoContact, remote: Person, bytes: ByteArray?) {
            check(value.contact.version == existing.contact.version)
            installs++; this.bytes = bytes
            val token = if (bytes == null) ContactPhotos.NONE else "installed-$installs"
            value = value.copy(token = token, baseline = PhotoBaseline(token, ContactPhotos.reference(remote), ContactPhotos.revision(remote), bytes?.let(ContactPhotos::hash)), remoteHint = ContactPhotos.reference(remote), remoteRevision = ContactPhotos.revision(remote),
                contact = value.contact.copy(version = value.contact.version + 1, dirty = value.contact.fields != value.contact.baseline))
        }
        fun edit(bytes: ByteArray?) {
            this.bytes = bytes
            value = value.copy(token = if (bytes == null) ContactPhotos.NONE else "edit-${value.contact.version}",
                contact = value.contact.copy(version = value.contact.version + 1, dirty = true))
        }
    }
    private inner class Api : ContactsApi, PhotosApi {
        var remote: Person? = person(); val images = mutableMapOf<String, ByteArray>(); var writes = 0; var reads = 0
        var afterWrite: (() -> Unit)? = null; var loseReply = false; var reject: ApiException? = null
        var reencode: ((ByteArray) -> ByteArray)? = null
        var page: ((String?, String?) -> Page)? = null
        override fun get(resource: String): Person? { reads++; return remote }
        override fun list(syncToken: String?, pageToken: String?) = page?.invoke(syncToken, pageToken) ?: Page(listOfNotNull(remote), null, "checkpoint")
        override fun create(body: JSONObject): Person = error("Unexpected contact create")
        override fun update(person: Person, fields: Set<String>, body: JSONObject): Person = error("Unexpected text mutation")
        override fun delete(resource: String) = error("Unexpected contact delete")
        override fun readPhoto(url: String) = images.getValue(url)
        override fun writePhoto(resource: String, bytes: ByteArray?): Person {
            reject?.let { throw it }; writes++
            val url = bytes?.let { "https://lh3.googleusercontent.com/new$writes".also { url -> images[url] = reencode?.invoke(it) ?: it } }
            val result = person(url, "updated-$writes"); remote = result; afterWrite?.invoke()
            if (loseReply) throw IOException("Lost reply")
            return result
        }
        fun cloud(url: String, bytes: ByteArray) { images[url] = bytes; remote = person(url, "cloud") }
    }
    private fun setup(): Pair<Store, Api> {
        val store = Store(); val api = Api()
        PhotosSync(api, api, store) { SyncMode.DOWNLOAD }.sync()
        return store to api
    }
    @Test fun photoLessContactsNeedNoGoogleReads() {
        val store = Store(); val api = Api()
        store.value = store.value.copy(remoteKnownNone = true)
        assertEquals(0, PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync().downloaded)
        assertEquals(0, api.reads); assertEquals(0, store.installs)
        // A local photo removal (baseline had one) or an unknown remote state still checks Google.
        store.value = store.value.copy(baseline = PhotoBaseline("old-token", null))
        PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync()
        assertEquals(1, api.reads)
        store.value = store.value.copy(baseline = null, remoteKnownNone = false)
        PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync()
        assertEquals(2, api.reads)
    }
    @Test fun fingerprintsToleratesReencodingNoiseButNotASmallBadge() {
        fun sig(pixels: (Int) -> Triple<Int, Int, Int>) = (0 until 32 * 32).joinToString("") { i -> pixels(i).let { (r, g, b) -> "%02x%02x%02x".format(r, g, b) } }
        val white = sig { Triple(255, 255, 255) }
        val noisy = sig { i -> val n = (i * 7919) % 9; Triple(255 - n, 250 - n, 252 - n) } // JPEG-like small noise everywhere
        fun badge(r: Int, b: Int) = sig { i -> if (i % 32 in 12..19 && i / 32 in 12..19) Triple(r, 0, b) else Triple(255, 255, 255) }
        assertTrue(ContactPhotos.similar(white, noisy))
        assertFalse(ContactPhotos.similar(badge(255, 0), badge(0, 255))) // same white photo, red vs blue badge
        assertFalse(ContactPhotos.similar(white, badge(255, 0)))
        assertFalse(ContactPhotos.similar(white, null)); assertFalse(ContactPhotos.similar(white, white.take(12)))
    }
    @Test fun lostReplyForAReencodedPhotoIsConfirmedByLookNotBytes() {
        val (store, api) = setup()
        store.edit(byteArrayOf(7, 1, 2, 3)); api.loseReply = true
        api.reencode = { byteArrayOf(it[0], 9, 9, 9, 9) } // Google stores the same picture, different bytes (seen live: PNG -> JPEG)
        assertThrows(IOException::class.java) { PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync() }
        api.loseReply = false
        val report = PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync()
        assertEquals(1, report.uploaded); assertEquals(0, report.uncertain)
        assertNull(store.value.contact.pending); assertEquals(1, api.writes) // never replayed
    }
    @Test fun lostReplyWithADifferentCloudPictureStaysUncertain() {
        val (store, api) = setup()
        store.edit(byteArrayOf(7, 1, 2, 3)); api.loseReply = true
        api.reencode = { byteArrayOf(42, 9, 9) } // someone else's picture won
        assertThrows(IOException::class.java) { PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync() }
        api.loseReply = false
        assertEquals(1, PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync().uncertain)
        assertNotNull(store.value.contact.pending); assertEquals(1, api.writes)
    }
    @Test fun lostPhotoReplyHoldsMetadataCheckpointUntilCloudNameIsApplied() {
        val (store, api) = setup()
        fun named(person: Person, name: String) = Person.parse(JSONObject(person.json.toString()).put("names",
            JSONArray().put(JSONObject().put("givenName", name))))
        val before = named(api.remote!!, "Before")
        store.value = store.value.copy(contact = store.value.contact.copy(fields = store.project(before), baseline = store.project(before)))
        store.saved = "old"
        store.edit(byteArrayOf(1, 2, 3)); api.loseReply = true
        assertThrows(IOException::class.java) { PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync() }
        api.remote = named(api.remote!!, "CloudEdit")
        api.page = { token, _ -> Page(if (token == "old") listOf(api.remote!!) else emptyList(), null, "new") }

        val blocked = ContactsSync(api, store) { SyncMode.TWO_WAY }.sync()
        assertEquals("old", store.saved)
        assertEquals(1, blocked.uncertain)
        assertEquals(store.project(before), store.value.contact.fields)
        assertNotNull(store.value.contact.pending)
        assertEquals(1, PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync().uploaded)
        assertNull(store.value.contact.pending); assertFalse(store.value.contact.dirty)

        // A fresh engine must revisit the old incremental range after recovery,
        // even though photo completion deliberately keeps the text baseline.
        ContactsSync(api, store) { SyncMode.TWO_WAY }.sync()
        assertEquals(store.project(api.remote!!), store.value.contact.fields)
        assertEquals(store.value.contact.fields, store.value.contact.baseline)
        assertEquals("new", store.saved)
        ContactsSync(api, store) { SyncMode.TWO_WAY }.sync()
        assertEquals(store.project(api.remote!!), store.value.contact.fields)
        assertEquals(1, api.writes) // Recovery never replays the photo upload.
    }
    @Test fun confirmedMutationIsAcknowledgedEvenIfOptionalImageReadFails() {
        val (store, api) = setup(); store.edit(byteArrayOf(1)); var fail = true
        val images = object : PhotosApi by api {
            override fun readPhoto(url: String): ByteArray { if (fail) throw IOException("Image unavailable"); return api.readPhoto(url) }
        }
        assertEquals(1, PhotosSync(api, images, store) { SyncMode.TWO_WAY }.sync().uploaded)
        assertNull(store.value.contact.pending); assertFalse(store.value.contact.dirty); assertNull(store.value.baseline!!.hash)
        fail = false
        PhotosSync(api, images, store) { SyncMode.TWO_WAY }.sync()
        assertNotNull(store.value.baseline!!.hash); assertEquals(1, api.writes)
    }
    @Test fun changedStateAfterConfirmedPhotoReplyIsRevisitedWithoutMutationReplay() {
        val (store, api) = setup(); store.edit(byteArrayOf(1))
        api.afterWrite = { api.cloud("https://lh3.googleusercontent.com/later", byteArrayOf(2)) }
        PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync()
        assertNull(store.value.contact.pending); assertNull(store.value.baseline!!.hash)
        PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync()
        assertArrayEquals(byteArrayOf(2), store.bytes); assertEquals(1, api.writes)
    }
    @Test fun independentlyDeletedPhotoIsAcknowledgedWithoutAnotherMutation() {
        val (store, api) = setup(); val url = "https://lh3.googleusercontent.com/image"
        api.cloud(url, byteArrayOf(1)); store.value = store.value.copy(remoteHint = url)
        PhotosSync(api, api, store) { SyncMode.DOWNLOAD }.sync(); store.edit(null)
        api.remote = person(etag = "cloud-delete")
        val report = PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync()
        assertEquals(0, report.conflicts); assertEquals(0, api.writes); assertFalse(store.value.contact.dirty)
    }
    @Test fun unchangedUrlWithNewRevisionDownloadsChangedImage() {
        val (store, api) = setup(); val url = "https://lh3.googleusercontent.com/stable"
        api.cloud(url, byteArrayOf(1)); store.value = store.value.copy(remoteHint = url, remoteRevision = "cloud")
        PhotosSync(api, api, store) { SyncMode.DOWNLOAD }.sync()
        api.images[url] = byteArrayOf(2); api.remote = person(url, "new-image")
        store.value = store.value.copy(remoteRevision = "new-image")
        assertEquals(1, PhotosSync(api, api, store) { SyncMode.DOWNLOAD }.sync().downloaded)
        assertArrayEquals(byteArrayOf(2), store.bytes)
    }
    @Test fun unrelatedTextRevisionDoesNotRewriteImageOrBlockNextPhotoEdit() {
        val (store, api) = setup(); val url = "https://lh3.googleusercontent.com/stable"
        api.cloud(url, byteArrayOf(1)); store.value = store.value.copy(remoteHint = url, remoteRevision = "cloud")
        PhotosSync(api, api, store) { SyncMode.DOWNLOAD }.sync(); val installs = store.installs
        api.remote = person(url, "text-only"); store.value = store.value.copy(remoteRevision = "text-only")
        PhotosSync(api, api, store) { SyncMode.DOWNLOAD }.sync(); assertEquals(installs, store.installs)
        store.edit(byteArrayOf(2)); api.remote = person(url, "more-text")
        assertEquals(1, PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync().uploaded)
    }
    @Test fun cloudImageChangeAtSameUrlConflictsWithLocalEdit() {
        val (store, api) = setup(); val url = "https://lh3.googleusercontent.com/stable"
        api.cloud(url, byteArrayOf(1)); store.value = store.value.copy(remoteHint = url, remoteRevision = "cloud")
        PhotosSync(api, api, store) { SyncMode.DOWNLOAD }.sync()
        store.edit(byteArrayOf(2)); api.images[url] = byteArrayOf(3); api.remote = person(url, "new-image")
        assertEquals(1, PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync().conflicts); assertEquals(0, api.writes)
    }
    @Test fun pendingPhotoRecoveryPreservesAConcurrentContactTombstone() {
        val (store, api) = setup(); store.edit(byteArrayOf(1)); api.loseReply = true
        assertThrows(IOException::class.java) { PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync() }
        store.value = store.value.copy(contact = store.value.contact.copy(deleted = true, dirty = true))
        PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync()
        assertNull(store.value.contact.pending); assertTrue(store.value.contact.deleted); assertTrue(store.value.contact.dirty)
    }
    @Test fun malformedLocalPhotoDoesNotDispatchOrReadRemote() {
        val store = Store(); val api = Api(); store.value = store.value.copy(unreadable = true)
        assertEquals(1, PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync().skipped)
        assertEquals(0, api.reads); assertEquals(0, api.writes)
    }
    @Test fun offDoesNothingAndUnchangedPhotoDoesNotRefetch() {
        val store = Store(); val api = Api()
        PhotosSync(api, api, store) { SyncMode.OFF }.sync(); assertEquals(0, api.reads)
        PhotosSync(api, api, store) { SyncMode.DOWNLOAD }.sync()
        val reads = api.reads; PhotosSync(api, api, store) { SyncMode.DOWNLOAD }.sync(); assertEquals(reads, api.reads)
    }
    @Test fun downloadsAndRemovesOnlyAfterDirectRead() {
        val (store, api) = setup(); val url = "https://lh3.googleusercontent.com/cloud"
        api.cloud(url, byteArrayOf(1, 2)); store.value = store.value.copy(remoteHint = url)
        assertEquals(1, PhotosSync(api, api, store) { SyncMode.DOWNLOAD }.sync().downloaded)
        assertArrayEquals(byteArrayOf(1, 2), store.bytes)
        api.remote = person(); store.value = store.value.copy(remoteHint = null)
        assertEquals(1, PhotosSync(api, api, store) { SyncMode.DOWNLOAD }.sync().downloaded)
        assertNull(store.bytes); assertEquals(0, api.writes)
    }
    @Test fun stalePhotoHintDoesNotReplaceCurrentCloudState() {
        val (store, api) = setup(); store.value = store.value.copy(remoteHint = "https://lh3.googleusercontent.com/stale")
        PhotosSync(api, api, store) { SyncMode.DOWNLOAD }.sync()
        assertEquals(0, store.installs); assertNull(store.value.remoteHint)
    }
    @Test fun uploadAndDeleteAreJournaledAndBecomeClean() {
        val (store, api) = setup(); store.edit(byteArrayOf(3, 4))
        api.afterWrite = { assertEquals("photo", store.value.contact.pending!!.getString("kind")) }
        assertEquals(1, PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync().uploaded)
        assertFalse(store.value.contact.dirty); assertNull(store.value.contact.pending)
        store.edit(null)
        assertEquals(1, PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync().uploaded)
        assertNull(ContactPhotos.reference(api.remote!!)); assertEquals(2, api.writes)
    }
    @Test fun readOnlyKeepsEditedPhotoAndNeverDispatches() {
        val (store, api) = setup(); store.edit(byteArrayOf(5))
        assertEquals(1, PhotosSync(api, api, store) { SyncMode.DOWNLOAD }.sync().conflicts)
        assertArrayEquals(byteArrayOf(5), store.bytes); assertEquals(0, api.writes)
    }
    @Test fun simultaneousCloudPhotoEditIsAConflict() {
        val (store, api) = setup(); store.edit(byteArrayOf(5))
        api.cloud("https://lh3.googleusercontent.com/other", byteArrayOf(6))
        assertEquals(1, PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync().conflicts)
        assertArrayEquals(byteArrayOf(5), store.bytes); assertEquals(0, api.writes)
    }
    @Test fun coldStartDoesNotOverwriteExistingPhotoAndCanAdoptExactMatch() {
        val store = Store(); val api = Api(); store.edit(byteArrayOf(1))
        api.cloud("https://lh3.googleusercontent.com/cloud", byteArrayOf(2))
        assertEquals(1, PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync().conflicts)
        api.images[ContactPhotos.reference(api.remote!!)!!] = byteArrayOf(1)
        PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync()
        assertNotNull(store.value.baseline); assertFalse(store.value.contact.dirty); assertEquals(0, api.writes)
    }
    @Test fun lostUploadReplyRecoversAfterRestartWithoutAnotherMutation() {
        val (store, api) = setup(); store.edit(byteArrayOf(7, 8)); api.loseReply = true
        assertThrows(IOException::class.java) { PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync() }
        assertNotNull(store.value.contact.pending)
        assertEquals(1, PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync().uploaded)
        assertEquals(1, api.writes); assertNull(store.value.contact.pending); assertFalse(store.value.contact.dirty)
    }
    @Test fun transformedOrDifferentReadbackRemainsUncertainAndIsNeverReplayed() {
        val (store, api) = setup(); store.edit(byteArrayOf(7)); api.loseReply = true
        assertThrows(IOException::class.java) { PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync() }
        api.images[ContactPhotos.reference(api.remote!!)!!] = byteArrayOf(9)
        repeat(2) { assertEquals(1, PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync().uncertain) }
        assertEquals(1, api.writes); assertNotNull(store.value.contact.pending); assertArrayEquals(byteArrayOf(7), store.bytes)
    }
    @Test fun laterLocalPhotoEditSurvivesUploadAndRecovery() {
        val (store, api) = setup(); store.edit(byteArrayOf(1)); api.loseReply = true
        api.afterWrite = { store.edit(byteArrayOf(2)) }
        assertThrows(IOException::class.java) { PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync() }
        PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync()
        assertArrayEquals(byteArrayOf(2), store.bytes); assertTrue(store.value.contact.dirty); assertNull(store.value.contact.pending)
        api.afterWrite = null; api.loseReply = false
        PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync(); assertEquals(2, api.writes); assertFalse(store.value.contact.dirty)
    }
    @Test fun lostDeleteReplyConfirmsAbsenceWithoutReplay() {
        val (store, api) = setup(); val url = "https://lh3.googleusercontent.com/cloud"
        api.cloud(url, byteArrayOf(1)); store.value = store.value.copy(remoteHint = url)
        PhotosSync(api, api, store) { SyncMode.DOWNLOAD }.sync(); store.edit(null); api.loseReply = true
        assertThrows(IOException::class.java) { PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync() }
        PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync(); assertEquals(1, api.writes); assertNull(store.value.contact.pending)
    }
    @Test fun modeChangeBeforeDispatchClearsUnsentPhotoJournal() {
        val (store, api) = setup(); store.edit(byteArrayOf(1)); var mode = SyncMode.TWO_WAY
        store.afterBegin = { mode = SyncMode.OFF }
        PhotosSync(api, api, store) { mode }.sync(); assertEquals(0, api.writes); assertNull(store.value.contact.pending)
    }
    @Test fun definiteRejectionClearsJournalButServerFailureRetainsIt() {
        val (store, api) = setup(); store.edit(byteArrayOf(1)); api.reject = ApiException(429)
        assertThrows(ApiException::class.java) { PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync() }
        assertNull(store.value.contact.pending)
        api.reject = ApiException(503)
        assertThrows(ApiException::class.java) { PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync() }
        assertNotNull(store.value.contact.pending)
    }
    @Test fun oversizedLocalImageIsRejectedBeforeJournalingOrDispatch() {
        val (store, api) = setup(); store.edit(ByteArray(ContactPhotos.MAX_BYTES + 1))
        assertEquals(1, PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync().skipped)
        assertNull(store.value.contact.pending); assertEquals(0, api.writes)
    }
    @Test fun concurrentTextEditSurvivesPhotoAcknowledgment() {
        val (store, api) = setup(); store.edit(byteArrayOf(1))
        api.afterWrite = { store.value = store.value.copy(contact = store.value.contact.copy(fields = mapOf("names" to "[]"), dirty = true)) }
        PhotosSync(api, api, store) { SyncMode.TWO_WAY }.sync()
        assertTrue(store.value.contact.dirty); assertEquals(mapOf("names" to "[]"), store.value.contact.fields)
    }
    @Test fun profileAndDefaultImagesAreNotContactPhotos() {
        val default = person("https://lh3.googleusercontent.com/default").also { it.json.getJSONArray("photos").getJSONObject(0).put("default", true) }
        assertNull(ContactPhotos.reference(default))
        val profile = person("https://lh3.googleusercontent.com/profile").also { it.json.getJSONArray("photos").getJSONObject(0).getJSONObject("metadata").getJSONObject("source").put("type", "PROFILE") }
        assertNull(ContactPhotos.reference(profile))
    }
}
