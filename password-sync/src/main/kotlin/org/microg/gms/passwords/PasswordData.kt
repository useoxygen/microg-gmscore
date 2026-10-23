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
    val createdWindowsMicros: Long get() = proto.integer(11) ?: 0
    val hasIssues: Boolean get() = proto.optional(19) != null
    fun encode(): ByteArray = proto.encode()
    fun withPassword(value: String, nowMillis: Long): PasswordData = if (value == password) this else PasswordData(
        proto.replace(8, value.toByteArray(Charsets.UTF_8)).replace(19, null)
            .replaceInteger(20, windowsMicros(nowMillis)))

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
                it.username.length > 100_000 || it.password.length > 100_000) throw ProtocolException()
        }
        fun create(realm: String, origin: String, username: String, password: String, nowMillis: Long): PasswordData = decode(
            Proto.integer(1, 0) + Proto.field(2, realm) + Proto.field(3, origin) + Proto.field(4, origin) +
                Proto.field(5, "") + Proto.field(6, username) + Proto.field(7, "") + Proto.field(8, password) +
                Proto.integer(11, windowsMicros(nowMillis)) + Proto.integer(13, 3) + Proto.integer(20, windowsMicros(nowMillis)))

        fun webRealm(url: String): String {
            val uri = try { URI(url) } catch (_: Exception) { throw ProtocolException() }
            if (uri.scheme?.lowercase() !in setOf("https", "http") || uri.host.isNullOrEmpty() || uri.userInfo != null) throw ProtocolException()
            val scheme = uri.scheme.lowercase()
            val port = if (uri.port == -1 || uri.port == (if (scheme == "https") 443 else 80)) "" else ":${uri.port}"
            return "$scheme://${uri.host.lowercase()}$port/"
        }
        private fun windowsMicros(nowMillis: Long): Long {
            if (nowMillis < 0 || nowMillis > (Long.MAX_VALUE / 1000 - 11_644_473_600_000L)) throw ProtocolException()
            return (nowMillis + 11_644_473_600_000L) * 1000
        }
        private fun escapePath(value: String): String {
            val out = StringBuilder()
            val escaped = " \"#%:<>?[\\]^`{|}'"
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val n = byte.toInt() and 255
                val ch = n.toChar()
                if (n < 32 || n >= 127 || ch in escaped) out.append("%%%02X".format(n)) else out.append(ch)
            }
            return out.toString()
        }
    }
}
