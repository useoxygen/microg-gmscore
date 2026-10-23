/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.net.URI
import java.security.MessageDigest
import java.util.Base64

/** PasswordSpecificsData, with unknown fields retained instead of reconstructed. */
class PasswordData private constructor(private val proto: Proto) {
    val realm: String get() = proto.string(2)
    val origin: String get() = proto.optionalString(3).orEmpty()
    val username: String get() = proto.optionalString(6).orEmpty()
    val password: String get() = proto.optionalString(8).orEmpty()
    val blocked: Boolean get() = proto.integer(12) == 1L
    val displayName: String get() = proto.optionalString(15).orEmpty()
    val notes: List<PasswordNote> get() = proto.optional(22)?.let { Proto.decode(it).bytes(1).map(PasswordNote::decode) } ?: emptyList()
    val defaultNote: PasswordNote? get() = notes.singleOrNull { it.name.isEmpty() }
    val encodedNotes: ByteArray? get() = proto.optional(22)
    val createdWindowsMicros: Long get() = proto.integer(11) ?: 0
    val hasIssues: Boolean get() = proto.optional(19) != null
    fun canFillRealm(requested: String): Boolean = !blocked && password.isNotEmpty() && matchesRealmAuthority(requested)
    fun matchesRealmAuthority(requested: String): Boolean {
        if (realm != requested) return false
        return when {
            requested.startsWith("https://") || requested.startsWith("http://") ->
                runCatching { webRealm(origin) == requested && webRealm(realm) == requested }.getOrDefault(false)
            requested.startsWith("android://") && requested.endsWith("/") -> true
            else -> false
        }
    }
    fun encode(): ByteArray = proto.encode()
    fun withPassword(value: String, nowMillis: Long): PasswordData = if (value == password) this else PasswordData(
        proto.replace(8, value.toByteArray(Charsets.UTF_8)).replace(19, null)
            .replaceInteger(20, windowsMicros(nowMillis)))
    fun withDisplayName(value: String): PasswordData {
        if (value.length > 100_000) throw ProtocolException()
        return if (value == displayName) this else PasswordData(proto.replace(15, value.toByteArray(Charsets.UTF_8)))
    }
    fun withDefaultNote(value: String, nowMillis: Long): PasswordData {
        if (value.length > 100_000) throw ProtocolException()
        val container = Proto.decode(proto.optional(22) ?: byteArrayOf())
        val existing = container.bytes(1)
        val defaults = existing.filter { PasswordNote.decode(it).name.isEmpty() }
        if (defaults.size > 1) throw ProtocolException()
        val old = defaults.singleOrNull()?.let(PasswordNote::decode)
        if (old?.value.orEmpty() == value) return this
        val replacement = if (value.isEmpty()) null else old?.withValue(value)?.encode() ?:
            (Proto.field(1, "") + Proto.field(2, value) + Proto.integer(3, windowsMicros(nowMillis)))
        val updated = ArrayList<ByteArray>()
        for (raw in existing) {
            if (PasswordNote.decode(raw).name.isNotEmpty()) updated.add(raw)
            else replacement?.let(updated::add)
        }
        if (defaults.isEmpty() && replacement != null) updated.add(replacement)
        // Keep an authoritative empty Notes message after deletion; removing field 22 can
        // resurrect an older encrypted_notes_backup on another client.
        return PasswordData(proto.replace(22, container.replaceRepeated(1, updated).encode()))
    }
    fun withBlocked(value: Boolean): PasswordData = if (value == blocked) this else PasswordData(proto.replaceInteger(12, if (value) 1 else 0))
    fun matchesSearch(query: String): Boolean = query.isEmpty() || listOf(realm, origin, username, displayName).any { it.contains(query, ignoreCase = true) }
    fun mergeFrom(incoming: PasswordData): PasswordData {
        if (tag() != incoming.tag()) throw ConflictException()
        var merged = proto.overlay(incoming.proto)
        val issues = incoming.proto.optional(19)
        val retainedIssues = if (password != incoming.password) issues?.let { raw ->
            Proto.decode(raw).optional(4)?.let { Proto.field(4, it) }
        } else issues
        merged = merged.replace(19, retainedIssues)
        incoming.encodedNotes?.let { raw ->
            val oldNotes = Proto.decode(encodedNotes ?: byteArrayOf())
            val nextNotes = Proto.decode(raw)
            val oldByName = oldNotes.bytes(1).groupBy { PasswordNote.decode(it).name }
            val next = nextNotes.bytes(1)
            if (next.map { PasswordNote.decode(it).name }.toSet().size != next.size) throw ProtocolException()
            val combined = next.map { note ->
                val old = oldByName[PasswordNote.decode(note).name]?.singleOrNull()
                if (old == null) note else Proto.decode(old).overlay(Proto.decode(note)).encode()
            }
            merged = merged.replace(22, oldNotes.overlay(nextNotes).replaceRepeated(1, combined).encode())
        }
        return PasswordData(merged)
    }

    fun tag(): String {
        val text = listOf(origin, proto.optionalString(5).orEmpty(), username,
            proto.optionalString(7).orEmpty(), realm).joinToString("|") { escapePath(it) }
        val prefix = Proto.field(45873, byteArrayOf())
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(prefix + text.toByteArray(Charsets.UTF_8)))
    }
    override fun toString(): String = "PasswordData[redacted]"

    companion object {
        fun decode(data: ByteArray): PasswordData = PasswordData(Proto.decode(data)).also {
            if (it.realm.isEmpty() || it.realm.length > 4096 || it.origin.length > 4096 ||
                it.username.length > 100_000 || it.password.length > 100_000 || it.displayName.length > 100_000) throw ProtocolException()
        }
        fun create(realm: String, origin: String, username: String, password: String, nowMillis: Long): PasswordData = decode(
            Proto.integer(1, 0) + Proto.field(2, realm) + Proto.field(3, origin) + Proto.field(4, origin) +
                Proto.field(5, "") + Proto.field(6, username) + Proto.field(7, "") + Proto.field(8, password) +
                Proto.integer(11, windowsMicros(nowMillis)) + Proto.integer(13, 3) + Proto.integer(20, windowsMicros(nowMillis)))
        fun blockedSite(url: String, nowMillis: Long): PasswordData = webRealm(url).let { realm ->
            create(realm, realm, "", "", nowMillis).withBlocked(true)
        }

        fun webRealm(url: String): String {
            val uri = try { URI(url) } catch (_: Exception) { throw ProtocolException() }
            if (uri.scheme?.lowercase() !in setOf("https", "http") || uri.host.isNullOrEmpty() || uri.userInfo != null || uri.port !in -1..65535) throw ProtocolException()
            val scheme = uri.scheme.lowercase()
            val port = if (uri.port == -1 || uri.port == (if (scheme == "https") 443 else 80)) "" else ":${uri.port}"
            return "$scheme://${uri.host.lowercase()}$port/"
        }
        private fun windowsMicros(nowMillis: Long): Long {
            if (nowMillis < 0 || nowMillis > (Long.MAX_VALUE / 1000 - 11_644_473_600_000L)) throw ProtocolException()
            return (nowMillis + 11_644_473_600_000L) * 1000
        }
        internal fun escapePath(value: String): String {
            val out = StringBuilder()
            val escaped = " \"#%:<>?[\\]^`{|}"
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val n = byte.toInt() and 255
                val ch = n.toChar()
                if (n < 32 || n >= 127 || ch in escaped) out.append("%%%02X".format(n)) else out.append(ch)
            }
            return out.toString()
        }
    }
}

/** Keep named notes and future fields intact when the owner edits the default note. */
class PasswordNote private constructor(private val proto: Proto) {
    val name get() = proto.optionalString(1).orEmpty()
    val value get() = proto.optionalString(2).orEmpty()
    val createdWindowsMicros get() = proto.integer(3) ?: 0
    val hiddenByDefault get() = proto.integer(4) == 1L
    fun encode() = proto.encode()
    fun withValue(value: String): PasswordNote {
        if (value.length > 100_000) throw ProtocolException()
        return PasswordNote(proto.replace(2, value.toByteArray(Charsets.UTF_8)))
    }
    override fun toString(): String = "PasswordNote[redacted]"
    companion object { fun decode(raw: ByteArray) = PasswordNote(Proto.decode(raw)).also {
        if (it.name.length > 4096 || it.value.length > 100_000) throw ProtocolException()
    } }
}
