/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.bouncycastle.crypto.generators.SCrypt
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Nigori V1 interoperability, independently implemented from Chromium's published format. */
class NigoriKey(encryption: ByteArray, mac: ByteArray) {
    private val encryption = encryption.copyOf()
    private val mac = mac.copyOf()
    init { if (encryption.size != 16 || mac.size != 16) throw ProtocolException() }

    fun name(): ByteArray {
        val name = "nigori-key".toByteArray(Charsets.UTF_8)
        val plain = ByteBuffer.allocate(12 + name.size).putInt(4).putInt(1).putInt(name.size).put(name).array()
        val ciphertext = aes(Cipher.ENCRYPT_MODE, ByteArray(16), plain)
        return Base64.getEncoder().encode(ciphertext + hmac(ciphertext))
    }

    fun encrypt(plain: ByteArray, authenticateIv: Boolean = false): ByteArray {
        val iv = ByteArray(16).also { RANDOM.nextBytes(it) }
        val ciphertext = aes(Cipher.ENCRYPT_MODE, iv, plain)
        val signed = if (authenticateIv) iv + ciphertext else ciphertext
        return Proto.field(1, name()) + Proto.field(2, iv + ciphertext + hmac(signed))
    }

    fun decrypt(encrypted: ByteArray): ByteArray {
        val proto = Proto.decode(encrypted)
        if (!MessageDigest.isEqual(proto.one(1), name())) throw VaultLockedException()
        // GMS EncryptedData uses raw bytes here; Chromium's sync wrapper base64-encodes them.
        val blob = proto.one(2)
        if (blob.size < 64 || (blob.size - 48) % 16 != 0) throw ProtocolException()
        val iv = blob.copyOfRange(0, 16)
        val ciphertext = blob.copyOfRange(16, blob.size - 32)
        val received = blob.copyOfRange(blob.size - 32, blob.size)
        val modern = MessageDigest.isEqual(received, hmac(iv + ciphertext))
        val legacy = MessageDigest.isEqual(received, hmac(ciphertext))
        if (!modern && !legacy) throw ProtocolException()
        return try { aes(Cipher.DECRYPT_MODE, iv, ciphertext) } catch (_: Exception) { throw ProtocolException() }
    }

    fun export(): ByteArray = Proto.field(3, encryption) + Proto.field(4, mac)
    fun destroy() { encryption.fill(0); mac.fill(0) }
    override fun toString(): String = "NigoriKey[redacted]"

    private fun hmac(data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(mac, "HmacSHA256")); doFinal(data)
    }
    private fun aes(mode: Int, iv: ByteArray, value: ByteArray): ByteArray = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
        init(mode, SecretKeySpec(encryption, "AES"), IvParameterSpec(iv)); doFinal(value)
    }

    companion object {
        private val RANDOM = SecureRandom()
        private val SALT = byteArrayOf(0xc7.toByte(), 0xca.toByte(), 0xfb.toByte(), 0x23, 0xec.toByte(), 0x2a, 0x9d.toByte(), 0x4c,
            0x03, 0x5a, 0x90.toByte(), 0xae.toByte(), 0xed.toByte(), 0x8b.toByte(), 0xa4.toByte(), 0x98.toByte())
        fun fromProto(data: ByteArray): NigoriKey = Proto.decode(data).let { NigoriKey(it.one(3), it.one(4)) }

        fun derive(password: ByteArray): NigoriKey = NigoriKey(pbkdf2(password, 1003), pbkdf2(password, 1004))
        fun deriveScrypt(password: ByteArray, salt: ByteArray): NigoriKey {
            if (salt.size !in 1..1024) throw ProtocolException()
            val derived = SCrypt.generate(password, salt, 8192, 8, 11, 32)
            return NigoriKey(derived.copyOfRange(0, 16), derived.copyOfRange(16, 32)).also { derived.fill(0) }
        }
        private fun pbkdf2(password: ByteArray, rounds: Int): ByteArray {
            val prf = Mac.getInstance("HmacSHA1")
            // An empty HMAC key has the same block padding as one zero byte.
            prf.init(SecretKeySpec(if (password.isEmpty()) byteArrayOf(0) else password, "HmacSHA1"))
            var u = prf.doFinal(SALT + byteArrayOf(0, 0, 0, 1))
            val result = u.copyOf()
            repeat(rounds - 1) {
                val next = prf.doFinal(u)
                u.fill(0); u = next
                for (i in result.indices) result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
            }
            u.fill(0)
            return result.copyOf(16).also { result.fill(0) }
        }
    }
}

class VaultKeys private constructor(private val keys: Map<String, NigoriKey>, val defaultName: String) {
    fun decrypt(data: ByteArray): ByteArray {
        val name = Proto.decode(data).string(1)
        return (keys[name] ?: throw VaultLockedException()).decrypt(data)
    }
    fun encrypt(data: ByteArray): ByteArray = (keys[defaultName] ?: throw VaultLockedException()).encrypt(data)
    fun destroy() = keys.values.forEach { it.destroy() }
    override fun toString(): String = "VaultKeys[redacted]"

    companion object {
        /** Google retries key retrieval after marking stale keys; never reset the vault. */
        fun unlockTrustedVault(metadata: ByteArray, load: (forceRefresh: Boolean) -> List<ByteArray>): VaultKeys {
            if (Proto.decode(Proto.decode(metadata).one(3)).integer(2) != 5L) throw ProtocolException()
            repeat(2) { attempt ->
                val shared = load(attempt > 0)
                try { return unlock(metadata, trustedVaultKeys = shared) }
                catch (_: VaultLockedException) { /* One bounded fresh retrieval after a stale local keybag. */ }
                finally { shared.forEach { it.fill(0) } }
            }
            throw VaultLockedException()
        }

        fun keyForPassphrase(metadata: ByteArray, password: ByteArray): NigoriKey {
            val specifics = Proto.decode(Proto.decode(metadata).one(3))
            if (specifics.integer(2) !in setOf(1L, 3L, 4L)) throw VaultLockedException()
            return when (specifics.integer(5) ?: 1L) {
                1L -> NigoriKey.derive(password)
                2L -> {
                    val salt = try { Base64.getDecoder().decode(specifics.one(6)) } catch (_: Exception) { throw ProtocolException() }
                    NigoriKey.deriveScrypt(password, salt)
                }
                else -> throw VaultLockedException()
            }
        }
        /** Passphrase type and KDF are explicit; never silently reset or replace encryption metadata. */
        fun unlock(metadata: ByteArray, trustedVaultKeys: List<ByteArray> = emptyList(), customPassphrase: ByteArray? = null, customKey: ByteArray? = null): VaultKeys {
            val meta = Proto.decode(metadata)
            val specifics = Proto.decode(meta.one(3))
            val bag = specifics.one(1)
            val candidates = ArrayList<NigoriKey>()
            try {
                when (specifics.integer(2)) {
                    2L -> {
                        val token = specifics.one(3)
                        for (raw in meta.bytes(2)) {
                            val key = NigoriKey.derive(Base64.getEncoder().encode(raw))
                            candidates.add(key)
                            if (Proto.decode(token).string(1) == Proto.utf8(key.name())) {
                                val plain = key.decrypt(token)
                                candidates.add(NigoriKey.fromProto(plain))
                                plain.fill(0)
                            }
                        }
                    }
                    5L -> trustedVaultKeys.forEach { raw -> candidates.add(NigoriKey.derive(Base64.getEncoder().encode(raw))) }
                    1L, 3L, 4L -> {
                        if (customKey != null) {
                            candidates.add(NigoriKey.fromProto(customKey))
                        } else {
                        val password = customPassphrase ?: throw VaultLockedException()
                        candidates.add(keyForPassphrase(metadata, password))
                        }
                    }
                    else -> throw VaultLockedException()
                }
                val defaultName = Proto.decode(bag).string(1)
                val wrapping = candidates.firstOrNull { Proto.utf8(it.name()) == defaultName } ?: throw VaultLockedException()
                val plain = wrapping.decrypt(bag)
                val imported = try { Proto.decode(plain).bytes(2).map { NigoriKey.fromProto(it) } } finally { plain.fill(0) }
                val keyMap = imported.associateBy { Proto.utf8(it.name()) }
                if (defaultName !in keyMap || keyMap.size != imported.size) {
                    imported.forEach { it.destroy() }
                    throw ProtocolException()
                }
                return VaultKeys(keyMap, defaultName)
            } finally { candidates.forEach { it.destroy() } }
        }
    }
}
