/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable owner backup. No account identity, server IDs, OAuth or Chrome-local metadata. */
object PasswordArchive {
    private val magic = byteArrayOf(67, 89, 67, 80, 87, 77, 1)
    const val MAX_BYTES = 16 * 1024 * 1024
    private const val ROUNDS = 210_000
    fun encrypt(passwords: List<PasswordData>, passphrase: CharArray): ByteArray {
        if (passphrase.size < 12 || passphrase.size > 1024 || passwords.size > 1000) throw ProtocolException()
        var size = 0
        val records = passwords.map { it.encode().also { raw -> size += raw.size + 16; if (size > MAX_BYTES / 2) throw ProtocolException() } }
        val plain = Proto.integer(1, 1) + records.fold(byteArrayOf()) { bytes, raw -> bytes + Proto.field(2, raw) }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val header = magic + salt + iv
        val key = key(passphrase, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); cipher.updateAAD(header)
            header + cipher.doFinal(plain)
        } finally { key.fill(0); plain.fill(0); records.forEach { it.fill(0) } }
    }
    fun decrypt(archive: ByteArray, passphrase: CharArray): List<PasswordData> {
        if (archive.size !in (magic.size + 16 + 12 + 16)..MAX_BYTES || passphrase.size !in 12..1024 ||
            !archive.copyOfRange(0, magic.size).contentEquals(magic)) throw ProtocolException()
        val headerSize = magic.size + 28
        val key = key(passphrase, archive.copyOfRange(magic.size, magic.size + 16))
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, archive.copyOfRange(magic.size + 16, headerSize)))
            cipher.updateAAD(archive, 0, headerSize)
            cipher.doFinal(archive, headerSize, archive.size - headerSize)
        } catch (_: Exception) { throw VaultLockedException() } finally { key.fill(0) }
        return try {
            val proto = Proto.decode(plain)
            if (proto.integer(1) != 1L) throw ProtocolException()
            val records = proto.bytes(2)
            if (records.size > 1000) throw ProtocolException()
            records.map(PasswordData::decode).also { if (it.map(PasswordData::tag).toSet().size != it.size) throw ConflictException() }
        } finally { plain.fill(0) }
    }
    private fun key(passphrase: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, ROUNDS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
}
