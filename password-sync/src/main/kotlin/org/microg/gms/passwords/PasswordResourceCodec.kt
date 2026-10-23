/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

/** Password resource, encrypted notes backup and searchable metadata must describe one edit. */
object PasswordResourceCodec {
    fun plaintext(entity: ByteArray, keys: VaultKeys): Proto {
        val record = Proto.decode(entity)
        val bytes = keys.decrypt(record.one(6))
        var plain = try { Proto.decode(bytes) } finally { bytes.fill(0) }
        // Explicitly empty notes are authoritative, including when an older backup exists.
        if (plain.optional(22) == null) record.optional(10)?.let { encrypted ->
            val notes = keys.decrypt(encrypted)
            try { plain = plain.replace(22, Proto.decode(notes).encode()) } finally { notes.fill(0) }
        }
        return plain
    }

    fun read(entity: ByteArray, keys: VaultKeys): PasswordData = PasswordData.decode(plaintext(entity, keys).encode())

    fun encrypt(data: PasswordData, keys: VaultKeys, base: ByteArray? = null): ByteArray {
        if (data.blocked && data.username.isNotEmpty()) throw ProtocolException()
        val record = base?.let(Proto::decode) ?: Proto.decode(Proto.field(2, data.tag()))
        if (record.string(2) != data.tag()) throw ConflictException()
        return write(record, Proto.decode(data.encode()), keys)
    }

    fun reencrypt(entity: ByteArray, keys: VaultKeys): ByteArray = write(Proto.decode(entity), plaintext(entity, keys), keys)

    private fun write(record: Proto, plain: Proto, keys: VaultKeys): ByteArray {
        val metadata = Proto.decode(record.optional(8) ?: byteArrayOf())
            .replace(3, plain.optionalString(2).orEmpty().toByteArray(Charsets.UTF_8))
            .replaceInteger(5, plain.integer(12) ?: 0)
            .replaceInteger(6, plain.integer(18) ?: 0)
            .replace(7, plain.optional(19))
            .replaceInteger(8, plain.integer(13) ?: 0)
        val output = record.replace(6, keys.encrypt(plain.encode())).replace(8, metadata.encode())
            .replace(10, plain.optional(22)?.let(keys::encrypt))
        return output.encode()
    }
}
