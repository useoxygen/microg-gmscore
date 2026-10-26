/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.ECPoint
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** anvx/anyr/hzgr: existing-PIN renewal's AES-GCM, HKDF and SecureBox v2 encodings. */
object GpmPinCryptography : GpmPinRenewalCrypto {
    private val params: ECParameterSpec get() = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1")); getParameterSpec(ECParameterSpec::class.java)
    }
    private fun coordinate(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return try { val raw = if (bytes.size == 33 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, 33) else bytes
            if (raw.size > 32) throw ProtocolException()
            ByteArray(32 - raw.size) + raw
        } finally { bytes.fill(0) }
    }
    private fun encodedPoint(key: ECPublicKey): ByteArray {
        if (key.params.order != params.order || key.params.curve != params.curve) throw ProtocolException()
        return byteArrayOf(4) + coordinate(key.w.affineX) + coordinate(key.w.affineY)
    }
    private fun point(raw: ByteArray): ECPublicKey {
        if (raw.size != 65 || raw[0] != 4.toByte()) throw ProtocolException()
        val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, raw.copyOfRange(1, 33)),
            BigInteger(1, raw.copyOfRange(33, 65))), params)) as ECPublicKey
        // EnrollmentWire validates point and scalar consistency, including points outside the curve.
        val curve = org.bouncycastle.asn1.sec.SECNamedCurves.getByName("secp256r1")
        try { curve.curve.decodePoint(raw).normalize().also { if (it.isInfinity || !it.isValid) throw ProtocolException() } }
        catch (_: Exception) { throw ProtocolException() }
        return key
    }
    override fun pair(): ByteArray {
        val pair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
        return try { coordinate((pair.private as ECPrivateKey).s) + encodedPoint(pair.public as ECPublicKey) }
        finally { runCatching { pair.private.destroy() } }
    }
    override fun random(size: Int): ByteArray {
        if (size !in 1..4096) throw ProtocolException()
        return ByteArray(size).also { SecureRandom().nextBytes(it) }
    }
    private fun hmac(key: ByteArray, input: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(input)
    }
    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, size: Int): ByteArray {
        val prk = hmac(salt, ikm)
        return try { val block = hmac(prk, info + byteArrayOf(1)); try { block.copyOf(size) } finally { block.fill(0) } }
            finally { prk.fill(0) }
    }
    private fun metadataKey(raw: ByteArray) = hkdf(raw, ByteArray(32),
        "KeychainApplicationKey:chrome:GPM PIN data wrapping key".toByteArray(Charsets.UTF_8), 32)
    private fun encrypt(key: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val nonce = random(12)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad)
            nonce + doFinal(plain)
        }
    }
    private fun decrypt(key: ByteArray, aad: ByteArray, cipher: ByteArray): ByteArray {
        if (cipher.size < 28 || cipher.size > 128 * 1024) throw ProtocolException()
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, cipher.copyOfRange(0, 12)))
            updateAAD(aad); doFinal(cipher.copyOfRange(12, cipher.size))
        }
    }
    override fun openMetadata(encrypted: ByteArray, domainKey: ByteArray): ByteArray {
        val key = metadataKey(domainKey)
        return try { decrypt(key, byteArrayOf(), encrypted) } finally { key.fill(0) }
    }
    override fun sealMetadata(plain: ByteArray, domainKey: ByteArray): ByteArray {
        val key = metadataKey(domainKey)
        return try { encrypt(key, byteArrayOf(), plain) } finally { key.fill(0) }
    }
    override fun aes(key: ByteArray, plain: ByteArray): ByteArray = encrypt(key, byteArrayOf(), plain)
    override fun secureBox(public: ByteArray?, shared: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        if (shared.size > 4096 || aad.size > 65536 || plain.size > 65536 || public == null && shared.isEmpty()) throw ProtocolException()
        val recipient = public?.let { bytes ->
            if (bytes.size == 65) point(bytes) else (KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes)) as? ECPublicKey
                ?: throw ProtocolException()).also { point(encodedPoint(it)) }
        }
        val ephemeral = recipient?.let { KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() } }
        val ecdh = if (recipient == null) byteArrayOf() else KeyAgreement.getInstance("ECDH").run {
            init(ephemeral!!.private); doPhase(recipient, true); generateSecret()
        }
        val combined = ecdh + shared
        val key = hkdf(combined, "SECUREBOX".toByteArray() + byteArrayOf(2, 0),
            (if (recipient == null) "SHARED" else "P256").toByteArray() + " HKDF-SHA-256 AES-128-GCM".toByteArray(), 16)
        return try { byteArrayOf(2, 0) + (ephemeral?.let { encodedPoint(it.public as ECPublicKey) } ?: byteArrayOf()) + encrypt(key, aad, plain) }
        finally { ecdh.fill(0); combined.fill(0); key.fill(0); ephemeral?.let { runCatching { it.private.destroy() } } }
    }
    override fun wrapMember(public: ByteArray, key: ByteArray) = secureBox(public, byteArrayOf(), "V1 shared_key".toByteArray(), key)
    override fun unwrapMember(pair: ByteArray, wrapped: ByteArray): ByteArray {
        EnrollmentWire.checkPair(pair)
        if (wrapped.size < 95 || wrapped[0] != 2.toByte() || wrapped[1] != 0.toByte()) throw ProtocolException()
        val scalar = pair.copyOfRange(0, 32)
        val key = try { KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), params)) }
            finally { scalar.fill(0) }
        val shared = try { KeyAgreement.getInstance("ECDH").run { init(key); doPhase(point(wrapped.copyOfRange(2, 67)), true); generateSecret() } }
            finally { runCatching { key.destroy() } }
        val derived = hkdf(shared, "SECUREBOX".toByteArray() + byteArrayOf(2, 0), "P256 HKDF-SHA-256 AES-128-GCM".toByteArray(), 16)
        return try { decrypt(derived, "V1 shared_key".toByteArray(), wrapped.copyOfRange(67, wrapped.size)) }
            finally { shared.fill(0); derived.fill(0) }
    }
}
