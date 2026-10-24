/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/** Public Password Leak Detection algorithms; GMS hzut/ipts and Chromium encryption_utils vectors. */
object LeakCheckCrypto {
    private val usernameSalt = hex("c494a395f8c0e23ea9230478702c7218565499b3e921186c211a01223c454afa")
    private val passwordSalt = hex("30762ad23f7ba19bf8e342fca1a78d06e66be4dbb84f8153c503c8dbbddea520")
    internal val parameters = SECNamedCurves.getByName("secp256r1")
    private val whitespace = " \t\n\r\u000c\u000b\u0085\u2028\u2029\u200d\uffef\ufffd\ufffe\uffff"

    /** Unlike Chromium's helper, GMS strips a domain only when haps accepts the email. */
    fun canonicalize(username: String): String {
        val at = username.lastIndexOf('@')
        val value = if (at > 0 && at < username.lastIndex && validEmail(username.substring(0, at), username.substring(at + 1)))
            username.substring(0, at) else username
        return value.replace(".", "").map { if (it in 'A'..'Z') it + 32 else it }.joinToString("")
    }
    private fun validEmail(local: String, domain: String): Boolean {
        fun forbidden(char: Char, extra: String) = char.code < 32 || char.code == 127 || char in extra
        val dot = domain.indexOf('.')
        if (domain.length < 4 || dot < 0 || domain.startsWith('.') || domain.contains("..") ||
            domain.any { it in whitespace || forbidden(it, " @,:<>+%") } ||
            domain.endsWith('.') && domain.indexOf('.', dot + 1) < 0) return false
        if (!local.startsWith('"')) return !local.contains("..") && local.none { it in whitespace || forbidden(it, " @,:<>") }
        if (local.length < 2 || !local.endsWith('"')) return false
        var i = 1
        while (i < local.lastIndex) {
            val char = local[i]
            if (char == '"' || char.code == 127 || char.code < 32 && char !in whitespace) return false
            if (char == '\\' && ++i >= local.lastIndex) return false
            i++
        }
        return true
    }

    fun usernameHash(canonicalized: String): ByteArray = sha256(canonicalized.toByteArray(Charsets.UTF_8) + usernameSalt)
    fun bucket(canonicalized: String): ByteArray = usernameHash(canonicalized).let { hash ->
        try { hash.copyOf(4).also { it[3] = (it[3].toInt() and 0xc0).toByte() } } finally { hash.fill(0) }
    }
    fun passwordHash(canonicalized: String, password: String): ByteArray {
        val username = canonicalized.toByteArray(Charsets.UTF_8)
        val input = username + password.toByteArray(Charsets.UTF_8); val salt = username + passwordSalt
        return try { SCrypt.generate(input, salt, 4096, 8, 1, 32) }
        finally { username.fill(0); input.fill(0); salt.fill(0) }
    }
    internal fun sha256(raw: ByteArray) = MessageDigest.getInstance("SHA-256").digest(raw)
    internal fun point(raw: ByteArray): ECPoint {
        if (!(raw.size == 33 && raw[0].toInt() in 2..3 || raw.size == 65 && raw[0] == 4.toByte())) throw ProtocolException()
        return try { parameters.curve.decodePoint(raw).normalize().also {
            if (it.isInfinity || !it.isValid || !it.getEncoded(raw.size == 33).contentEquals(raw)) throw ProtocolException()
        } } catch (_: IllegalArgumentException) { throw ProtocolException() }
    }
    internal fun hashToPoint(input: ByteArray): ECPoint {
        val prime = parameters.curve.field.characteristic
        fun hashToField(data: ByteArray): BigInteger {
            val expanded = sha256(byteArrayOf(1) + data) + sha256(byteArrayOf(2) + data)
            return try { BigInteger(1, expanded).mod(prime) } finally { expanded.fill(0) }
        }
        var x = hashToField(input)
        repeat(256) {
            val bytes = x.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
            val encoded = byteArrayOf(2) + ByteArray(32 - bytes.size) + bytes
            try { return point(encoded) } catch (_: ProtocolException) { x = hashToField(bytes) }
        }
        throw ProtocolException()
    }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/** A request-local P-256 scalar. Only blinded points reach the provider; the scalar is never persisted. */
class LeakCheckCipher private constructor(private val secret: ByteArray) {
    private var destroyed = false
    private fun key(): BigInteger {
        if (destroyed) throw LeakCheckCancelledException()
        return BigInteger(1, secret)
    }
    fun encrypt(hash: ByteArray): ByteArray = LeakCheckCrypto.hashToPoint(hash).multiply(key()).normalize().getEncoded(true)
    fun unblind(response: ByteArray): ByteArray = LeakCheckCrypto.point(response)
        .multiply(key().modInverse(LeakCheckCrypto.parameters.n)).normalize().getEncoded(true)
    fun destroy() { destroyed = true; secret.fill(0) }
    override fun toString() = "LeakCheckCipher[redacted]"
    companion object {
        fun create(): LeakCheckCipher {
            val random = SecureRandom(); val n = LeakCheckCrypto.parameters.n
            var value: BigInteger
            do { value = BigInteger(n.bitLength(), random) } while (value.signum() == 0 || value >= n)
            val raw = value.toByteArray()
            return try { fromKey(raw.let { if (it.size == 33) it.copyOfRange(1, it.size) else it }) } finally { raw.fill(0) }
        }
        internal fun fromKey(raw: ByteArray): LeakCheckCipher {
            if (raw.size !in 1..32 || BigInteger(1, raw).signum() == 0 || BigInteger(1, raw) >= LeakCheckCrypto.parameters.n) throw ProtocolException()
            return LeakCheckCipher(raw.copyOf())
        }
    }
}
