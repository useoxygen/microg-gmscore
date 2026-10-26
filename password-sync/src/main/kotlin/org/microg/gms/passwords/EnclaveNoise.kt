/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Chromium145 caBLE Noise_NK_P256_AESGCM_SHA256, including its public-key MixKey and traffic framing. */
class EnclaveNoise(private val ephemeral: ByteArray = GpmPinCryptography.pair()) : AutoCloseable {
    private var hash = "Noise_NK_P256_AESGCM_SHA256".toByteArray().copyOf(32)
    private var chain = hash.copyOf()
    private var cipher = ByteArray(32)
    private var write: ByteArray? = null
    private var read: ByteArray? = null
    private var writeSequence = 0
    private var readSequence = 0
    private var started = false
    private var finished = false
    private var closed = false
    init { EnrollmentWire.checkPair(ephemeral) }
    fun start(peer: ByteArray = PRODUCTION_KEY): ByteArray {
        if (started || closed) throw ProtocolException()
        started = true; mixHash(byteArrayOf(0)); mixHash(peer)
        val public = EnrollmentWire.publicKey(ephemeral)
        mixHash(public); mixKey(public)
        val shared = ecdh(ephemeral, peer)
        try { mixKey(shared) } finally { shared.fill(0) }
        return public + encryptAndHash(byteArrayOf())
    }
    /** Only the exact81-byte Noise response belongs here; the caller separately verifies its attestation. */
    fun finish(response: ByteArray): ByteArray {
        if (!started || finished || closed || response.size != 81) throw ProtocolException()
        val public = response.copyOfRange(0, 65)
        mixHash(public); mixKey(public)
        val shared = ecdh(ephemeral, public)
        try { mixKey(shared) } finally { shared.fill(0) }
        val tag = response.copyOfRange(65, 81)
        val plain = aes(false, cipher, ByteArray(12), hash, tag)
        try { if (plain.isNotEmpty()) throw ProtocolException(); mixHash(tag) } finally { plain.fill(0) }
        val split = hkdf(chain, byteArrayOf()); write = split.first; read = split.second
        finished = true; ephemeral.fill(0); chain.fill(0); cipher.fill(0)
        return hash.copyOf()
    }
    fun seal(plain: ByteArray): ByteArray {
        if (!finished || closed || plain.size > EnclaveCbor.MAX || writeSequence > MAX_SEQUENCE) throw ProtocolException()
        val zeroes = (32 - ((plain.size + 1) % 32)) % 32
        val padded = plain + ByteArray(zeroes) + byteArrayOf(zeroes.toByte())
        return try { aes(true, write!!, nonce(writeSequence++), byteArrayOf(), padded) } finally { padded.fill(0) }
    }
    fun open(encrypted: ByteArray): ByteArray {
        if (!finished || closed || encrypted.size !in 48..EnclaveCbor.MAX + 48 || readSequence > MAX_SEQUENCE) throw ProtocolException()
        val padded = aes(false, read!!, nonce(readSequence), byteArrayOf(), encrypted)
        try {
            if (padded.isEmpty() || padded.size % 32 != 0) throw ProtocolException()
            val padding = padded.last().toInt() and 255
            if (padding > 31 || padding >= padded.size) throw ProtocolException()
            readSequence++
            return padded.copyOfRange(0, padded.size - padding - 1)
        } finally { padded.fill(0) }
    }
    private fun mixHash(input: ByteArray) { val next = sha(hash + input); hash.fill(0); hash = next }
    private fun mixKey(input: ByteArray) {
        val next = hkdf(chain, input); chain.fill(0); cipher.fill(0); chain = next.first; cipher = next.second
    }
    private fun encryptAndHash(plain: ByteArray): ByteArray = aes(true, cipher, ByteArray(12), hash, plain).also(::mixHash)
    override fun close() { closed = true; ephemeral.fill(0); hash.fill(0); chain.fill(0); cipher.fill(0); write?.fill(0); read?.fill(0) }
    override fun toString() = "EnclaveNoise[redacted]"
    companion object {
        const val MAX_SEQUENCE = 0xffffff
        val PRODUCTION_KEY: ByteArray get() = hex("04fe2c8876ec1a5a644440fe11c12ad8486ef8ed5135304ccdaf a14cedb016fd59593c160686ded29fba117713661e7e2378d341a3668b6b46faffe961d0fb4a12".replace(" ", ""))
        internal fun hex(raw: String): ByteArray = raw.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        fun sha(raw: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(raw)
        private fun hmac(key: ByteArray, input: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(input) }
        private fun hkdf(salt: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray> {
            val prk = hmac(salt, input)
            return try { val first = hmac(prk, byteArrayOf(1)); first to hmac(prk, first + byteArrayOf(2)) } finally { prk.fill(0) }
        }
        private fun nonce(sequence: Int) = ByteArray(8) + byteArrayOf((sequence ushr 24).toByte(), (sequence ushr 16).toByte(), (sequence ushr 8).toByte(), sequence.toByte())
        private fun aes(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(input)
        }
        fun publicKey(raw: ByteArray): ECPublicKey {
            if (raw.size != 65 || raw[0] != 4.toByte()) throw ProtocolException()
            val curve = org.bouncycastle.asn1.sec.SECNamedCurves.getByName("secp256r1")
            val point = try { curve.curve.decodePoint(raw).normalize() } catch (_: Exception) { throw ProtocolException() }
            if (point.isInfinity || !point.isValid) throw ProtocolException()
            val params = AlgorithmParameters.getInstance("EC").run { init(ECGenParameterSpec("secp256r1")); getParameterSpec(ECParameterSpec::class.java) }
            return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65))), params)) as ECPublicKey
        }
        private fun ecdh(pair: ByteArray, public: ByteArray): ByteArray {
            val peer = publicKey(public); val scalar = pair.copyOfRange(0, 32)
            val key = try { KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), peer.params)) as ECPrivateKey } finally { scalar.fill(0) }
            return try { KeyAgreement.getInstance("ECDH").run { init(key); doPhase(peer, true); generateSecret() } } finally { runCatching { key.destroy() } }
        }
    }
}
