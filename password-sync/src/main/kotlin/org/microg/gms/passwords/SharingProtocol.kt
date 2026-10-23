/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest
import java.util.UUID

class SharingRecipient private constructor(private val proto: Proto, val userId: String, val name: String, val email: String,
    val version: Long, private val key: ByteArray?) {
    val eligible get() = key != null
    val profileImageUrl: String get() = proto.optional(2)?.let(Proto::decode)?.optionalString(3).orEmpty()
    fun publicKey() = key?.copyOf() ?: throw VaultLockedException()
    fun encode() = proto.encode()
    fun sameKey(other: SharingRecipient) = key != null && other.key != null && userId == other.userId && version == other.version && MessageDigest.isEqual(key, other.key)
    override fun toString() = "SharingRecipient[redacted]"
    companion object {
        fun decode(raw: ByteArray): SharingRecipient {
            val proto = Proto.decode(raw)
            val id = proto.string(1)
            val display = proto.optional(2)?.let(Proto::decode)
            val name = display?.optionalString(2).orEmpty(); val email = display?.optionalString(1).orEmpty()
            val public = proto.optional(3)?.let(Proto::decode)
            val version = public?.integer(1) ?: 0; val key = public?.one(2)
            if (id.isBlank() || id.length > 1024 || listOf(id, name, email).any { it.length > 4096 || it.any(Char::isISOControl) } ||
                version !in 0..Int.MAX_VALUE.toLong() || key != null && (key.size != 32 || key.all { it == 0.toByte() })) throw ProtocolException()
            return SharingRecipient(proto, id, name, email, version, key)
        }
    }
}

class SharingInvitation private constructor(val name: String, val sender: SharingRecipient, val guid: String,
    val recipientVersion: Long, private val ciphertext: ByteArray, private val raw: ByteArray) {
    fun encode() = raw.copyOf()
    fun passwords(keys: PasswordSharingKeys, nowMillis: Long): List<PasswordData> {
        val plaintext = keys.version(recipientVersion).open(ciphertext, sender)
        return try { SharingPasswordGroup.decode(plaintext, sender, nowMillis) } finally { plaintext.fill(0) }
    }
    fun passwordBatch(keys: PasswordSharingKeys, nowMillis: Long): SharingPasswordBatch {
        val plaintext = keys.version(recipientVersion).open(ciphertext, sender)
        return try { SharingPasswordGroup.decodeBatch(plaintext, sender, nowMillis) } finally { plaintext.fill(0) }
    }
    override fun toString() = "SharingInvitation[redacted]"
    companion object {
        fun decode(raw: ByteArray, parent: String): SharingInvitation {
            val resource = Proto.decode(raw)
            val name = childName(resource.string(1), parent)
            val invitation = Proto.decode(resource.one(5))
            // Network-supplied client-only plaintext must never bypass HPKE authentication.
            if (invitation.optional(4) != null) throw ProtocolException()
            val guid = invitation.string(1)
            if (guid.length > 128 || runCatching { UUID.fromString(guid) }.isFailure) throw ProtocolException()
            val version = invitation.integer(6) ?: 0
            val cipher = invitation.one(3)
            if (version !in 0..Int.MAX_VALUE.toLong() || cipher.size !in 48..(512 * 1024 + 48)) throw ProtocolException()
            val sender = SharingRecipient.decode(invitation.one(2))
            if (!sender.eligible) throw ProtocolException()
            return SharingInvitation(name, sender, guid, version, cipher, raw.copyOf())
        }
        internal fun childName(value: String, parent: String): String {
            PasswordApi.checkedParent(parent)
            if (!value.startsWith("$parent/") || value.length > 4096 || value.any(Char::isISOControl)) throw ProtocolException()
            val child = value.removePrefix("$parent/").split('/')
            if (child.size != 2 || child.any { it.isEmpty() || it == "." || it == ".." }) throw ProtocolException()
            return value
        }
    }
}

object SharingPasswordGroup {
    fun encode(data: PasswordData): ByteArray {
        if (!data.canFillRealm(data.realm)) throw ProtocolException()
        val source = Proto.decode(data.encode())
        if ((source.integer(1) ?: 0) != 0L) throw ProtocolException()
        var element = Proto.integer(2, source.integer(1) ?: 0) + Proto.field(3, data.realm) + Proto.field(4, data.origin) +
            Proto.field(5, source.optionalString(5).orEmpty()) + Proto.field(7, source.optionalString(7).orEmpty()) +
            Proto.field(8, data.displayName)
        // Avatar URLs are metadata. They are never fetched or permitted to carry a secret.
        source.optionalString(16)?.let { value -> if (value.length <= 4096) element += Proto.field(9, value) }
        return Proto.field(2, Proto.field(1, data.username) + Proto.field(2, data.password) + Proto.field(3, element))
    }
    fun decode(raw: ByteArray, sender: SharingRecipient, nowMillis: Long): List<PasswordData> {
        val batch = decodeBatch(raw, sender, nowMillis)
        // The explicit owner picker lists only credentials with a verified native fill contract.
        // Automatic processing can store other schemes without granting them fill authority.
        if (batch.invalidCount != 0 || batch.passwords.any { !it.canFillRealm(it.realm) }) throw ProtocolException()
        return batch.passwords
    }
    fun decodeBatch(raw: ByteArray, sender: SharingRecipient, nowMillis: Long): SharingPasswordBatch {
        val group = Proto.decode(Proto.decode(raw).optional(2) ?: byteArrayOf())
        val username = group.optionalString(1).orEmpty(); val password = group.optionalString(2).orEmpty()
        val elements = group.bytes(3)
        if (elements.size > 100 || nowMillis < 0 || nowMillis > Long.MAX_VALUE / 1000 - 11_644_473_600_000L) throw ProtocolException()
        val received = (nowMillis + 11_644_473_600_000L) * 1000
        val valid = ArrayList<PasswordData>(); var invalid = 0
        for (encoded in elements) {
            val element = Proto.decode(encoded)
            val scheme = element.integer(2) ?: 0
            if (scheme !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throw ProtocolException()
            val proto = Proto.decode(Proto.integer(1, scheme) + Proto.field(2, element.optionalString(3).orEmpty()) +
                Proto.field(3, element.optionalString(4).orEmpty()) + Proto.field(5, element.optionalString(5).orEmpty()) +
                Proto.field(6, username) + Proto.field(7, element.optionalString(7).orEmpty()) + Proto.field(8, password) +
                Proto.integer(11, received) + Proto.integer(13, 5) + Proto.field(15, element.optionalString(8).orEmpty()) +
                Proto.field(16, element.optionalString(9).orEmpty()) + Proto.field(23, sender.email) + Proto.field(24, sender.name) +
                Proto.integer(25, received) + Proto.integer(26, 0) + Proto.field(27, sender.profileImageUrl))
            if (SharingIncomingPolicy.valid(proto)) valid.add(PasswordData.decode(proto.encode())) else invalid++
        }
        return SharingPasswordBatch(valid, invalid)
    }
}

class PasswordSharingApi(private val transport: PasswordTransport) {
    fun metadata() = transport.invoke(PasswordMethod.GET_METADATA, Proto.field(1, "users/me"))
    fun recipients(): List<SharingRecipient> {
        val response = Proto.decode(transport.invoke(PasswordMethod.LIST_SHARING_RECIPIENTS, byteArrayOf()))
        if (response.bytes(1).size > 100) throw ProtocolException()
        return response.bytes(1).map(SharingRecipient::decode).also { values ->
            if (values.map { it.userId }.toSet().size != values.size) throw ProtocolException()
        }
    }
    fun incoming(parent: String): List<SharingInvitation> {
        val response = Proto.decode(transport.invoke(PasswordMethod.LIST_INCOMING_SHARING, Proto.field(1, PasswordApi.checkedParent(parent))))
        if (response.bytes(1).size > 1000) throw ProtocolException()
        return response.bytes(1).map { SharingInvitation.decode(it, parent) }.also { values ->
            if (values.map { it.name }.toSet().size != values.size || values.map { it.guid }.toSet().size != values.size) throw ProtocolException()
        }
    }
    fun outgoingRequest(parent: String, invitation: ByteArray, clientToken: ByteArray): ByteArray =
        Proto.field(1, PasswordApi.checkedParent(parent)) + Proto.field(3, clientToken) + Proto.field(4, Proto.field(1, invitation))
    fun send(request: ByteArray): ByteArray = transport.invoke(PasswordMethod.CREATE_OUTGOING_SHARING, request)
    fun delete(parent: String, name: String, clientToken: ByteArray) {
        transport.invoke(PasswordMethod.DELETE_INCOMING_SHARING, Proto.field(1, SharingInvitation.childName(name, parent)) + Proto.field(3, clientToken))
    }
}
