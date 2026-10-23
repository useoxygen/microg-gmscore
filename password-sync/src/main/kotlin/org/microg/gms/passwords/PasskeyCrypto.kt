/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A versioned recovered key. Never put raw key bytes in an exception, data-class string, or Android Intent. */
class PasskeyDomainKey(val version: Int, raw: ByteArray) : AutoCloseable {
    private val raw = raw.copyOf()
    init { if (version < 0 || raw.size != 32) throw ProtocolException() }
    internal fun derive(info: String): ByteArray = PasskeyCrypto.hkdf(raw, info)
    override fun close() { raw.fill(0) }
    override fun toString(): String = "PasskeyDomainKey[redacted]"
}

class PasskeySecret(private val pkcs8: ByteArray, private val encrypted: ByteArray?) : AutoCloseable {
    fun privateKey(): PrivateKey = try { KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8)) }
        catch (_: Exception) { throw ProtocolException() }
    /** Preserve PRF/credBlob/largeBlob and unknown Encrypted fields when rewrapping. */
    fun encode(): ByteArray = encrypted?.copyOf() ?: Proto.field(1, pkcs8)
    override fun close() { pkcs8.fill(0); encrypted?.fill(0) }
    override fun toString(): String = "PasskeySecret[redacted]"
}

/** Independently mirrors GMS ajgu/ajgx/ajhb and Chromium's public WebauthnCredentialSpecifics. */
object PasskeyCrypto {
    private const val DOMAIN_INFO = "KeychainApplicationKey:gmscore_module:com.google.android.gms.fido"
    private const val DEVICE_INFO = "DeviceAuthorizationKey:WebauthnCredentialSpecifics"
    private const val ENCRYPTED_AAD = "WebauthnCredentialSpecifics.Encrypted"
    private const val DOMAIN_AAD = "WebauthnCredentialSpecifics.SecurityDomainEncrypted"
    private const val DEVICE_AAD = "WebauthnCredentialSpecifics.DeviceAuthorizationEncrypted"

    fun decrypt(data: SyncedPasskey, domains: List<PasskeyDomainKey>, devices: List<PasskeyDomainKey> = emptyList()): PasskeySecret {
        val candidates = domains.filter { data.keyVersion == 0 || it.version == data.keyVersion }.asReversed()
        for (candidate in candidates) {
            val key = candidate.derive(DOMAIN_INFO)
            val outer = try { open(key, data.ciphertext, when (data.encryptedField) { 9 -> ""; 12 -> ENCRYPTED_AAD; else -> DOMAIN_AAD }) }
                catch (_: ProtocolException) { null } finally { key.fill(0) }
            if (outer == null) continue
            try {
                if (data.encryptedField == 9) return PasskeySecret(outer.copyOf(), null)
                if (data.encryptedField == 12) return decodedSecret(outer)
                val inner = Proto.decode(outer).one(1)
                try {
                    val device = devices.singleOrNull { it.version == data.deviceKeyVersion } ?: throw VaultLockedException()
                    val deviceKey = device.derive(DEVICE_INFO)
                    val plain = try { open(deviceKey, inner, DEVICE_AAD) } finally { deviceKey.fill(0) }
                    try { return decodedSecret(plain) } finally { plain.fill(0) }
                } finally { inner.fill(0) }
            } finally { outer.fill(0) }
        }
        throw VaultLockedException()
    }
    /** Field12 creation is supported by the pinned Google consumer. Device-key enrollment is a separate operation. */
    fun encrypt(secret: ByteArray, key: PasskeyDomainKey, random: SecureRandom = SecureRandom()): ByteArray {
        val derived = key.derive(DOMAIN_INFO)
        return try { seal(derived, secret, ENCRYPTED_AAD, random) } finally { derived.fill(0) }
    }
    fun encryptWithDevice(secret: ByteArray, domain: PasskeyDomainKey, device: PasskeyDomainKey,
        random: SecureRandom = SecureRandom()): ByteArray {
        if (device.version <= 0) throw ProtocolException()
        val dk = device.derive(DEVICE_INFO)
        val inner = try { seal(dk, secret, DEVICE_AAD, random) } finally { dk.fill(0) }
        val wrapped = Proto.field(1, inner)
        inner.fill(0)
        val key = domain.derive(DOMAIN_INFO)
        return try { seal(key, wrapped, DOMAIN_AAD, random) } finally { key.fill(0); wrapped.fill(0) }
    }
    private fun decodedSecret(raw: ByteArray): PasskeySecret {
        val proto = Proto.decode(raw)
        val pkcs8 = proto.one(1)
        if (pkcs8.size !in 32..4096 || proto.optional(2)?.size?.let { it != 32 } == true ||
            proto.optional(4)?.size?.let { it > 2048 } == true) { pkcs8.fill(0); throw ProtocolException() }
        return PasskeySecret(pkcs8, raw.copyOf())
    }
    internal fun hkdf(raw: ByteArray, info: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(raw)
        return try { mac.init(SecretKeySpec(prk, "HmacSHA256")); mac.doFinal(info.toByteArray(Charsets.US_ASCII) + byteArrayOf(1)) }
        finally { prk.fill(0) }
    }
    internal fun open(key: ByteArray, ciphertext: ByteArray, aad: String): ByteArray {
        if (key.size != 32 || ciphertext.size !in 28..(128 * 1024)) throw ProtocolException()
        return try { Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
            updateAAD(aad.toByteArray(Charsets.UTF_8)); doFinal(ciphertext, 12, ciphertext.size - 12)
        } } catch (_: Exception) { throw ProtocolException() }
    }
    internal fun seal(key: ByteArray, plain: ByteArray, aad: String, random: SecureRandom): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad.toByteArray(Charsets.UTF_8)); nonce + doFinal(plain)
        }
    }
}
