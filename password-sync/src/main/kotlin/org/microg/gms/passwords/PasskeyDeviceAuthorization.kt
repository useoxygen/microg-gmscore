/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest
import java.util.Base64

/** GMS beua/iajn/iajt. RAPT is acquired by Google's owner reauth flow, never synthesized from a password. */
object PasskeyDeviceAuthorization {
    const val FLOW = "chromesync_get_device_authorization_key"
    fun signals(salt: ByteArray, available: Boolean, rapt: String? = null): Map<String, String> {
        if (salt.size != 16 || rapt?.length?.let { it > 16_384 } == true) throw ProtocolException()
        fun hash(raw: ByteArray) = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(raw))
        return buildMap {
            if (rapt != null) put("rapt_token", hash(rapt.toByteArray(Charsets.UTF_8) + salt))
            put("trusted_vault_key_availability_0", hash("hw_protected".toByteArray(Charsets.UTF_8) + byteArrayOf(if (available) 1 else 0) + salt))
            put("salt", hash(salt))
        }
    }
    fun request(salt: ByteArray, available: Boolean, droidGuard: ByteArray, rapt: String? = null): ByteArray {
        if (salt.size != 16 || droidGuard.isEmpty() || droidGuard.size > 1024 * 1024) throw ProtocolException()
        return (rapt?.let { Proto.field(1, it) } ?: byteArrayOf()) +
            Proto.field(2, Proto.field(1, "hw_protected") + Proto.integer(2, if (available) 1 else 0)) +
            Proto.field(3, Proto.field(1, droidGuard) + Proto.field(2, salt))
    }
    fun response(raw: ByteArray, required: Set<Int>): List<PasskeyDomainKey> {
        if (required.any { it <= 0 }) throw ProtocolException()
        val p = Proto.decode(raw)
        if (p.optional(1) != null && p.optional(2) != null) throw ProtocolException()
        if (p.optional(2) != null) throw PasskeyReauthRequiredException()
        val entries = Proto.decode(p.one(1)).bytes(1).map(Proto::decode)
        if (entries.isEmpty() || entries.size > 100 || entries.map { it.integer(1) }.toSet().size != entries.size ||
            entries.any { it.integer(1)?.let { version -> version !in 1..Int.MAX_VALUE } != false || it.one(2).size != 32 } ||
            !entries.map { it.integer(1)!!.toInt() }.containsAll(required)) throw ProtocolException()
        return entries.map { value -> val key = value.one(2)
            try { PasskeyDomainKey(value.integer(1)!!.toInt(), key) } finally { key.fill(0) }
        }
    }
}
class PasskeyReauthRequiredException : Exception("Passkey device authorization requires Google account reauthentication")
