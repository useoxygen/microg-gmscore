/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

fun interface PhotoTransport { fun read(url: String): ByteArray }
interface PhotosApi {
    fun readPhoto(url: String): ByteArray
    fun writePhoto(resource: String, bytes: ByteArray?): Person
}
object ContactPhotos {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val NONE = "none"
    fun safeUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        value.length <= 8192 && uri.scheme == "https" && uri.rawUserInfo == null && uri.fragment == null &&
            uri.port in setOf(-1, 443) && (uri.host == "googleusercontent.com" ||
            uri.host?.endsWith(".googleusercontent.com") == true || uri.host == "lh3.google.com")
    }.getOrDefault(false)
    private fun selected(person: Person): JSONObject? {
        val values = person.json.optJSONArray("photos") ?: return null
        val candidates = (0 until values.length()).map { values.getJSONObject(it) }.filter {
            !it.optBoolean("default") && it.optJSONObject("metadata")?.optJSONObject("source")?.optString("type") == "CONTACT"
        }
        if (candidates.isEmpty()) return null
        val photo = if (candidates.size == 1) candidates.single() else candidates.singleOrNull {
            it.optJSONObject("metadata")?.optBoolean("sourcePrimary") == true
        } ?: throw UnreadableContact()
        return photo
    }
    fun reference(person: Person): String? = selected(person)?.getString("url")?.also { if (!safeUrl(it) && !PrivatePeopleApi.isPhotoReference(it)) throw UnreadableContact() }
    fun revision(person: Person): String = hash(canonical(JSONObject().put("personEtag", person.json.optString("etag"))
        .put("contactEtag", person.etag).put("photo", selected(person) ?: JSONObject.NULL)).toByteArray(Charsets.UTF_8))
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    fun base64(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        return buildString((bytes.size + 2) / 3 * 4) {
            var i = 0
            while (i < bytes.size) {
                val left = bytes.size - i
                val a = bytes[i++].toInt() and 255
                val b = if (left > 1) bytes[i++].toInt() and 255 else 0
                val c = if (left > 2) bytes[i++].toInt() and 255 else 0
                append(alphabet[a shr 2]); append(alphabet[(a and 3) * 16 + (b shr 4)])
                append(if (left > 1) alphabet[(b and 15) * 4 + (c shr 6)] else '=')
                append(if (left > 2) alphabet[c and 63] else '=')
            }
        }
    }
}
data class PhotoBaseline(val token: String, val remote: String?, val revision: String? = null, val hash: String? = null)
data class PhotoContact(val contact: LocalContact, val token: String, val baseline: PhotoBaseline?, val remoteHint: String?, val unreadable: Boolean = false, val remoteRevision: String? = null,
    /** The last metadata read recorded that Google has no photo for this contact. */
    val remoteKnownNone: Boolean = false)
interface PhotoStore {
    fun photoContacts(): List<PhotoContact>
    fun photoBytes(existing: PhotoContact): ByteArray?
    fun beginPhoto(existing: PhotoContact, pending: JSONObject): PhotoContact
    fun holdPhotoConflict(existing: PhotoContact) {
        beginPhoto(existing, JSONObject(existing.contact.pending!!.toString()).put("conflict", true))
    }
    fun rejectPhoto(existing: PhotoContact)
    /** Keep a concurrent local photo/text edit; acknowledge only the sent token. */
    fun completePhoto(existing: PhotoContact, remote: Person, sentToken: String, remoteHash: String? = null)
    /** Atomically install bounded image bytes plus baseline and close any photo journal, under a row-version assertion. */
    fun installPhoto(existing: PhotoContact, remote: Person, bytes: ByteArray?)
}

class PhotosSync(private val api: ContactsApi, private val photos: PhotosApi,
    private val store: PhotoStore, private val now: () -> Long = System::currentTimeMillis, private val mode: () -> SyncMode) {
    companion object {
        /** A write still unseen this long after sending is treated as lost and sent again. */
        const val RETRY_AFTER_MILLIS = 10 * 60 * 1000L
    }
    fun sync(): SyncReport {
        if (mode() == SyncMode.OFF) return SyncReport(0, 0, 0, 0)
        var downloaded = 0; var uploaded = 0; var conflicts = 0; var uncertain = 0; var skipped = 0
        for (local in store.photoContacts()) try {
            val contact = local.contact
            if (local.unreadable) { skipped++; continue }
            if (contact.deleted && contact.pending == null || contact.resource == null || contact.pending != null && contact.pending.optString("kind") != "photo") continue
            val pending = contact.pending
            // No photo here, none removed here, and none on Google as of the metadata pass:
            // nothing to do. Avoids one Google read per photo-less contact on a fresh import.
            if (pending == null && local.token == ContactPhotos.NONE && (local.baseline == null || local.baseline.token == ContactPhotos.NONE) &&
                local.remoteKnownNone) continue
            if (pending == null && local.baseline != null && local.token == local.baseline.token && local.remoteHint == local.baseline.remote && local.remoteRevision == local.baseline.revision &&
                (local.remoteHint == null || local.baseline.hash != null)) continue
            val current = api.get(contact.resource)
            if (current == null || current.deleted) { if (pending != null) store.rejectPhoto(local); conflicts++; continue }
            val remote = ContactPhotos.reference(current)
            if (pending != null) {
                if (pending.optBoolean("conflict")) { conflicts++; continue }
                val remoteBytes = remote?.let(photos::readPhoto)
                val hash = remoteBytes?.let(ContactPhotos::hash)
                val matches = if (pending.getString("action") == "delete") remote == null else hash == pending.getString("hash")
                if (matches) {
                    store.completePhoto(local, current, pending.getString("sentToken"), hash); uploaded++
                    continue
                }
                // A lost reply can't be confirmed by content: Google re-encodes uploads, and no image comparison
                // tells a re-encode from a small edit. Decide by whether the cloud photo changed at all.
                val before = pending.opt("beforeRemote").takeUnless { it == JSONObject.NULL } as String?
                // The photo reference changes only when the photo does (not on other contact edits).
                // The write may still be in flight: until the grace ends, keep the journal so nothing newer is sent
                // that it could land over. After it, a photo write sets the same image again, so a late original
                // and a retry converge.
                if (now() - pending.optLong("sentAt") < RETRY_AFTER_MILLIS) { uncertain++; continue }
                if (remote == before) { store.rejectPhoto(local); uncertain++; continue } // Not landed: sent again next pass.
                val editedSince = local.token != pending.getString("sentToken")
                // The cloud photo changed (our upload, re-encoded, or someone else's newer photo): Google's version
                // wins and is installed together with closing the journal. If the photo here was edited again since
                // it was sent, keep that edit: take Google's photo as the baseline so the edit uploads next pass.
                if (!editedSince) { store.installPhoto(local, current, remoteBytes); downloaded++ }
                else store.completePhoto(local, current, pending.getString("sentToken"), hash)
                continue
            }
            val changed = local.token != (local.baseline?.token ?: ContactPhotos.NONE)
            if (!changed) {
                val bytes = remote?.let(photos::readPhoto)
                val hash = bytes?.let(ContactPhotos::hash)
                if (remote == local.baseline?.remote && (ContactPhotos.revision(current) == local.baseline?.revision || hash == local.baseline?.hash))
                    store.completePhoto(local, current, local.token, hash)
                else { store.installPhoto(local, current, bytes); downloaded++ }
                continue
            }
            if (mode() != SyncMode.TWO_WAY) { conflicts++; continue }
            val bytes = store.photoBytes(local)
            if (bytes != null && (bytes.isEmpty() || bytes.size > ContactPhotos.MAX_BYTES)) throw UnreadableContact()
            val hash = bytes?.let(ContactPhotos::hash)
            if (bytes == null && remote == null) { store.completePhoto(local, current, local.token); continue }
            if (remote != local.baseline?.remote || remote != null && ContactPhotos.revision(current) != local.baseline?.revision) {
                // Check content when the source revision changes, even at an
                // unchanged URL. Text-only edits must not masquerade as photos.
                val cloudHash = remote?.let { ContactPhotos.hash(photos.readPhoto(it)) }
                if (remote != null && hash == cloudHash) {
                    store.completePhoto(local, current, local.token, cloudHash); continue
                }
                if (remote != local.baseline?.remote || cloudHash != local.baseline?.hash || local.baseline?.hash == null) {
                    conflicts++; continue
                }
            }
            val journal = JSONObject().put("kind", "photo").put("action", if (bytes == null) "delete" else "upload")
                .put("sentToken", local.token).put("hash", hash).put("beforeRemote", remote).put("sentAt", now())

            val saved = store.beginPhoto(local, journal)
            try {
                if (mode() != SyncMode.TWO_WAY) throw UploadDisabled()
                val response = photos.writePhoto(contact.resource, bytes)
                val responsePhoto = ContactPhotos.reference(response)
                if ((bytes == null) != (responsePhoto == null)) throw UnreadableContact()
                // The mutation receipt is authoritative even if an optional
                // canonical-image read fails. Never turn that read failure into
                // a replay of the confirmed mutation. Cache a hash only while
                // the returned source revision is still current.
                val canonicalHash = if (responsePhoto == null || mode() == SyncMode.OFF) null else try {
                    val imageHash = ContactPhotos.hash(photos.readPhoto(responsePhoto))
                    val verified = api.get(contact.resource)
                    imageHash.takeIf { verified != null && ContactPhotos.revision(verified) == ContactPhotos.revision(response) && ContactPhotos.reference(verified) == responsePhoto }
                } catch (error: java.io.IOException) { null }
                store.completePhoto(saved, response, local.token, canonicalHash); uploaded++
            } catch (error: UploadDisabled) { store.rejectPhoto(saved); conflicts++; break }
            catch (error: ApiException) {
                if (error.status in setOf(409, 412)) store.holdPhotoConflict(saved)
                else if (error.definitelyRejected) store.rejectPhoto(saved)
                if (error.status in listOf(400, 409, 412)) { conflicts++; continue }
                throw error
            }
        } catch (error: UnreadableContact) { skipped++ }
        return SyncReport(downloaded, uploaded, conflicts, uncertain, skipped)
    }
}

operator fun SyncReport.plus(other: SyncReport) = SyncReport(downloaded + other.downloaded, uploaded + other.uploaded,
    conflicts + other.conflicts, uncertain + other.uncertain, skipped + other.skipped)
