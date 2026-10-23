/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.net.IDN
import java.security.MessageDigest

/** Chromium WebauthnCredentialSpecifics. Retain the original proto, including fields this consumer does not edit. */
class SyncedPasskey private constructor(private val proto: Proto) {
    val syncId: ByteArray get() = proto.one(1)
    val credentialId: ByteArray get() = proto.one(2)
    val rpId: String get() = proto.string(3)
    val userId: ByteArray get() = proto.one(4)
    val username: String get() = proto.optionalString(7).orEmpty()
    val displayName: String get() = proto.optionalString(8).orEmpty()
    val createdMillis: Long get() = proto.integer(6) ?: 0
    val hidden: Boolean get() = proto.integer(16) == 1L
    val keyVersion: Int get() = (proto.integer(14) ?: 0).toInt()
    val deviceKeyVersion: Int get() = (proto.integer(18) ?: 0).toInt()
    val encryptedField: Int get() = listOf(9, 12, 19).single { proto.optional(it) != null }
    val ciphertext: ByteArray get() = proto.one(encryptedField)
    // GMS 26.11 beri uses base16().lowerCase(), not the credential ID or a new hash.
    val tag: String get() = hex(syncId)
    fun shadows(other: SyncedPasskey): Boolean = rpId == other.rpId && MessageDigest.isEqual(userId, other.userId) &&
        proto.bytes(5).any { MessageDigest.isEqual(it, other.credentialId) }
    fun encode(): ByteArray = proto.encode()
    fun withLastUsed(windowsMicros: Long): SyncedPasskey = decode(proto.replaceInteger(13, windowsMicros).encode())
    fun withShadows(ids: List<ByteArray>): SyncedPasskey = decode(proto.replaceRepeated(5,
        (proto.bytes(5) + ids).distinctBy(::hex)).encode())
    /** Proto serialization order is not identity; a server may canonically reserialize the same immutable credential. */
    fun sameRegistration(other: SyncedPasskey): Boolean = MessageDigest.isEqual(syncId, other.syncId) &&
        MessageDigest.isEqual(credentialId, other.credentialId) && rpId == other.rpId && MessageDigest.isEqual(userId, other.userId) &&
        createdMillis == other.createdMillis && keyVersion == other.keyVersion && deviceKeyVersion == other.deviceKeyVersion &&
        encryptedField == other.encryptedField && MessageDigest.isEqual(ciphertext, other.ciphertext) &&
        proto.bytes(5).map(::hex).toSet() == other.proto.bytes(5).map(::hex).toSet() && hidden == other.hidden
    override fun toString(): String = "SyncedPasskey[redacted]"

    companion object {
        fun decode(raw: ByteArray): SyncedPasskey {
            if (raw.size > 128 * 1024) throw ProtocolException()
            val p = Proto.decode(raw)
            if (p.one(1).size != 16 || p.one(2).size !in 1..1024 || p.one(4).size !in 1..64 ||
                p.bytes(5).size > 1000 || p.bytes(5).any { it.size !in 1..1024 } ||
                p.optionalString(7).orEmpty().length > 1024 || p.optionalString(8).orEmpty().length > 1024 ||
                p.integer(14)?.let { it !in 0..Int.MAX_VALUE } == true ||
                p.integer(18)?.let { it !in 0..Int.MAX_VALUE } == true ||
                listOf(9, 12, 19).count { p.optional(it) != null } != 1) throw ProtocolException()
            checkedRp(p.string(3))
            return SyncedPasskey(p)
        }
        fun checkedRp(value: String): String {
            if (value.isEmpty() || value.length > 253 || value != value.lowercase() || value.endsWith('.') ||
                value.any { it.isISOControl() || it in "/:@\\?#" } || runCatching { IDN.toASCII(value) }.getOrNull() != value)
                throw ProtocolException()
            return value
        }
        fun visible(values: List<SyncedPasskey>): List<SyncedPasskey> {
            if (values.map { it.tag }.toSet().size != values.size) throw ProtocolException()
            val eligible = values.filter { !it.hidden && values.none { newer -> newer !== it && newer.shadows(it) } }
            // An ambiguous ID is never silently assigned to an account/user by ordering.
            val unambiguous = eligible.filter { item -> eligible.count { it.rpId == item.rpId &&
                MessageDigest.isEqual(it.credentialId, item.credentialId) } == 1 }
            return unambiguous.groupBy { it.rpId to hex(it.userId) }.values.mapNotNull { group ->
                val newest = group.maxOf { it.createdMillis }
                group.singleOrNull { it.createdMillis == newest }
            }
        }
        internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** GMS ialc: id1, client_tag_hash2, version3, encrypted_specifics4 or plaintext_specifics7, update_time8. */
object PasskeyResourceCodec {
    const val ENTITY_TYPE = 895275
    fun read(resource: ByteArray, keys: VaultKeys?): SyncedPasskey {
        val p = Proto.decode(resource)
        val encrypted = p.optional(4)
        val plaintext = p.optional(7)
        if ((encrypted == null) == (plaintext == null)) throw ProtocolException()
        val raw = if (plaintext != null) plaintext else {
            val decrypted = (keys ?: throw VaultLockedException()).decrypt(encrypted!!)
            try { Proto.decode(decrypted).one(ENTITY_TYPE) } finally { decrypted.fill(0) }
        }
        return try { SyncedPasskey.decode(raw).also { if (it.tag != p.string(2)) throw ProtocolException() } }
        finally { raw.fill(0) }
    }
    fun write(data: SyncedPasskey, keys: VaultKeys, base: ByteArray? = null): ByteArray {
        val p = base?.let(Proto::decode) ?: Proto.decode(Proto.field(2, data.tag))
        if (p.string(2) != data.tag) throw ProtocolException()
        val plain = Proto.field(ENTITY_TYPE, data.encode())
        return try { p.replace(7, null).replace(4, keys.encrypt(plain)).encode() } finally { plain.fill(0) }
    }
}
