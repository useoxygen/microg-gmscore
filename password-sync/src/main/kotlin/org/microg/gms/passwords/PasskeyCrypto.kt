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
    /** Only existing authenticated field2 can supply PRF; legacy or missing-secret records are unsupported. */
    fun prf(request: PasskeyPrfRequest, credentialId: ByteArray): PasskeyPrfResult {
        val secret = encrypted?.let { Proto.decode(it).optional(2) } ?: throw VaultLockedException()
        return try { request.evaluate(secret, credentialId) } finally { secret.fill(0) }
    }
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

    /** Authenticate the outer envelope against current recovery authority without opening a device-protected secret. */
    fun requireDomainAuthority(data: SyncedPasskey, domains: List<PasskeyDomainKey>) {
        for (candidate in domains.filter { data.keyVersion == 0 || it.version == data.keyVersion }.asReversed()) {
            val key = candidate.derive(DOMAIN_INFO)
            val opened = try { open(key, data.ciphertext, when (data.encryptedField) {
                9 -> ""; 12 -> ENCRYPTED_AAD; else -> DOMAIN_AAD
            }) } catch (_: ProtocolException) { null } finally { key.fill(0) }
            if (opened != null) { opened.fill(0); return }
        }
        throw VaultLockedException()
    }

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
    /** Rewrap only an authenticated existing secret, retaining its envelope versions and opaque wrapper fields. */
    fun updateSecret(data: SyncedPasskey, before: ByteArray, after: ByteArray, domains: List<PasskeyDomainKey>,
        devices: List<PasskeyDomainKey> = emptyList(), random: SecureRandom = SecureRandom()): SyncedPasskey {
        if (after.size > 128 * 1024) throw ProtocolException()
        val old = Proto.decode(before); val next = Proto.decode(after)
        // Public callers cannot supply an unrelated "before" key and rebind this credential.
        decrypt(data, domains, devices).use { authenticated ->
            val actual = authenticated.encode()
            try { if (!java.security.MessageDigest.isEqual(actual, before)) throw ConflictException() }
            finally { actual.fill(0) }
        }
        fun unchanged(number: Int) = java.security.MessageDigest.isEqual(old.optional(number) ?: byteArrayOf(), next.optional(number) ?: byteArrayOf())
        // The RP operation can add PRF or replace largeBlob, never rotate the credential's private key/credBlob.
        if (!unchanged(1) || !unchanged(3) || next.optional(2)?.size?.let { it != 0 && it != 32 } == true ||
            old.optional(2)?.size == 32 && !unchanged(2)) throw ProtocolException()
        fun opaque(value: Proto) = (1..5).fold(value) { p, field -> p.replace(field, null) }.encode()
        if (!java.security.MessageDigest.isEqual(opaque(old), opaque(next))) throw ProtocolException()
        // Version0 requests trial decryption. A newer retained epoch is not evidence that
        // it encrypted this record; preserve the domain key that actually authenticates it.
        val domain = domains.filter { data.keyVersion == 0 || it.version == data.keyVersion }.asReversed().firstOrNull { candidate ->
            val key = candidate.derive(DOMAIN_INFO)
            val opened = try { open(key, data.ciphertext, when (data.encryptedField) {
                9 -> ""; 12 -> ENCRYPTED_AAD; else -> DOMAIN_AAD
            }) } catch (_: ProtocolException) { null } finally { key.fill(0) }
            if (opened == null) false else try { when (data.encryptedField) {
                9 -> java.security.MessageDigest.isEqual(opened, old.one(1))
                12 -> java.security.MessageDigest.isEqual(opened, before)
                else -> true // GCM authenticates the wrapper; decrypt() above also verified its device-bound secret.
            } } finally { opened.fill(0) }
        } ?: throw VaultLockedException()
        val source = Proto.decode(data.encode())
        val field = if (data.encryptedField == 9) 12 else data.encryptedField
        val encrypted = if (field == 12) encrypt(after, domain, random) else {
            val device = devices.singleOrNull { it.version == data.deviceKeyVersion } ?: throw VaultLockedException()
            val outerKey = domain.derive(DOMAIN_INFO)
            val outer = try { open(outerKey, data.ciphertext, DOMAIN_AAD) } finally { outerKey.fill(0) }
            try {
                val deviceKey = device.derive(DEVICE_INFO)
                val inner = try { seal(deviceKey, after, DEVICE_AAD, random) } finally { deviceKey.fill(0) }
                val wrapped = try { Proto.decode(outer).replace(1, inner).encode() } finally { inner.fill(0) }
                val key = domain.derive(DOMAIN_INFO)
                try { seal(key, wrapped, DOMAIN_AAD, random) } finally { key.fill(0); wrapped.fill(0) }
            } finally { outer.fill(0) }
        }
        return try { SyncedPasskey.decode(source.replace(9, null).replace(12, null).replace(19, null)
            .replace(field, encrypted).replaceInteger(14, domain.version.toLong()).encode()) }
        finally { encrypted.fill(0) }
    }
    private fun decodedSecret(raw: ByteArray): PasskeySecret {
        val proto = Proto.decode(raw)
        val pkcs8 = proto.one(1)
        // large_blob's 2KiB bound applies to its uncompressed output. Even a valid
        // 2KiB raw DEFLATE stored block needs five additional bytes. Malformed blob
        // data is handled by the optional read extension, not by key decoding.
        if (pkcs8.size !in 32..4096 || proto.optional(2)?.size?.let { it != 0 && it != 32 } == true) {
            pkcs8.fill(0); throw ProtocolException()
        }
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
