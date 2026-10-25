/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/** RFC 9180 authenticated HPKE: X25519 / HKDF-SHA256 / ChaCha20-Poly1305. */
object SharingCrypto {
    private fun hpke() = HPKE(HPKE.mode_auth, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_CHACHA20_POLY1305)
    private fun pair(raw: ByteArray): AsymmetricCipherKeyPair {
        if (raw.size != 32) throw ProtocolException()
        val privateKey = X25519PrivateKeyParameters(raw)
        return AsymmetricCipherKeyPair(privateKey.generatePublicKey(), privateKey)
    }
    fun publicKey(privateKey: ByteArray): ByteArray = (pair(privateKey).public as X25519PublicKeyParameters).encoded
    fun seal(plaintext: ByteArray, senderPrivate: ByteArray, recipientPublic: ByteArray): ByteArray {
        if (plaintext.size > 512 * 1024 || recipientPublic.size != 32) throw ProtocolException()
        return try {
            val context = hpke().setupAuthS(X25519PublicKeyParameters(recipientPublic), byteArrayOf(), pair(senderPrivate))
            context.encapsulation + context.seal(byteArrayOf(), plaintext)
        } catch (_: Exception) { throw ProtocolException() }
    }
    fun open(ciphertext: ByteArray, recipientPrivate: ByteArray, senderPublic: ByteArray): ByteArray =
        openContext(ciphertext, recipientPrivate, senderPublic, byteArrayOf(), byteArrayOf())

    internal fun openContext(ciphertext: ByteArray, recipientPrivate: ByteArray, senderPublic: ByteArray,
        info: ByteArray, aad: ByteArray): ByteArray {
        if (ciphertext.size !in 48..(512 * 1024 + 48) || senderPublic.size != 32) throw ProtocolException()
        return try {
            hpke().setupAuthR(ciphertext.copyOfRange(0, 32), pair(recipientPrivate), info,
                X25519PublicKeyParameters(senderPublic)).open(aad, ciphertext.copyOfRange(32, ciphertext.size))
        } catch (_: Exception) { throw ProtocolException() }
    }
}

class SharingPrivateKey(val version: Long, raw: ByteArray) {
    private val raw = raw.copyOf()
    init { if (version !in 0..Int.MAX_VALUE.toLong() || raw.size != 32) throw ProtocolException() }
    fun seal(plaintext: ByteArray, recipient: SharingRecipient) = SharingCrypto.seal(plaintext, raw, recipient.publicKey())
    fun open(ciphertext: ByteArray, sender: SharingRecipient) = SharingCrypto.open(ciphertext, raw, sender.publicKey())
    fun destroy() { raw.fill(0) }
    override fun toString() = "SharingPrivateKey[redacted]"
}

/** The server-committed Nigori keybag is the only source of account sharing private keys. */
class PasswordSharingKeys private constructor(private val keys: Map<Long, SharingPrivateKey>) {
    fun latest(): SharingPrivateKey = keys.maxByOrNull { it.key }?.value ?: throw VaultLockedException()
    fun version(version: Long): SharingPrivateKey = keys[version] ?: throw VaultLockedException()
    fun destroy() = keys.values.forEach { it.destroy() }
    override fun toString() = "PasswordSharingKeys[redacted]"
    companion object {
        fun read(metadata: ByteArray, vaultKeys: VaultKeys): PasswordSharingKeys {
            val encrypted = Proto.decode(Proto.decode(metadata).one(3)).one(1)
            val plaintext = vaultKeys.decrypt(encrypted)
            val imported = ArrayList<SharingPrivateKey>()
            try {
                val bag = Proto.decode(plaintext)
                if (bag.bytes(3).size > 64) throw ProtocolException()
                for (raw in bag.bytes(3)) {
                    val key = Proto.decode(raw)
                    imported.add(SharingPrivateKey(key.integer(1) ?: 0, key.one(2)))
                }
                if (imported.isEmpty()) throw VaultLockedException()
                if (imported.map { it.version }.toSet().size != imported.size) throw ProtocolException()
                return PasswordSharingKeys(imported.associateBy { it.version })
            } catch (failure: Exception) { imported.forEach { it.destroy() }; throw failure }
            finally { plaintext.fill(0) }
        }
    }
}
