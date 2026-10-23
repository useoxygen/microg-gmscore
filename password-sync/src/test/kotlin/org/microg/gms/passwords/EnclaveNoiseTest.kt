/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Independent deterministic responder: BC scalar multiplication/HKDF versus production JCA ECDH/Mac. */
class EnclaveNoiseTest {
    private val curve = SECNamedCurves.getByName("secp256r1")
    private fun pair(n: Int) = ByteArray(31) + byteArrayOf(n.toByte()) + curve.g.multiply(BigInteger.valueOf(n.toLong())).normalize().getEncoded(false)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun aes(encrypt: Boolean, key: ByteArray, aad: ByteArray, input: ByteArray, sequence: Int = 0) = Cipher.getInstance("AES/GCM/NoPadding").run {
        val nonce = ByteArray(12); nonce[11] = sequence.toByte()
        init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(input)
    }
    private inner class Responder {
        var h = "Noise_NK_P256_AESGCM_SHA256".toByteArray().copyOf(32)
        var ck = h.copyOf(); var key = byteArrayOf(); lateinit var incoming: ByteArray; lateinit var outgoing: ByteArray
        fun hash(bytes: ByteArray) { h = sha(h + bytes) }
        fun derive(input: ByteArray): ByteArray = ByteArray(64).also { out ->
            HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(input, ck, byteArrayOf())); generateBytes(out, 0, out.size) }
        }
        fun mix(input: ByteArray) { val bytes = derive(input); ck = bytes.copyOfRange(0, 32); key = bytes.copyOfRange(32, 64) }
        fun dh(point: ByteArray, scalar: Int): ByteArray = curve.curve.decodePoint(point).multiply(BigInteger.valueOf(scalar.toLong())).normalize()
            .affineXCoord.encoded
        fun respond(initial: ByteArray): ByteArray {
            hash(byteArrayOf(0)); hash(pair(3).copyOfRange(32, 97))
            val clientPublic = initial.copyOfRange(0, 65); hash(clientPublic); mix(clientPublic); mix(dh(clientPublic, 3))
            assertTrue(aes(false, key, h, initial.copyOfRange(65, 81)).isEmpty()); hash(initial.copyOfRange(65, 81))
            val ephemeral = pair(5).copyOfRange(32, 97); hash(ephemeral); mix(ephemeral); mix(dh(clientPublic, 5))
            val tag = aes(true, key, h, byteArrayOf()); hash(tag)
            val traffic = derive(byteArrayOf()); incoming = traffic.copyOfRange(0, 32); outgoing = traffic.copyOfRange(32, 64)
            return ephemeral + tag
        }
        fun encryptedMessage(bytes: ByteArray, sequence: Int = 0): ByteArray {
            val count = (31 - bytes.size % 32); return aes(true, outgoing, byteArrayOf(), bytes + ByteArray(count) + byteArrayOf(count.toByte()), sequence)
        }
    }
    @Test fun independentResponderAgreesHandshakeHashTrafficDirectionPaddingAndSequence() {
        EnclaveNoise(pair(7)).use { client ->
            val server = Responder(); val response = server.respond(client.start(pair(3).copyOfRange(32, 97)))
            assertArrayEquals(server.h, client.finish(response))
            for (size in listOf(0, 1, 30, 31, 32, 65)) {
                val plain = ByteArray(size) { 9 }; val encrypted = client.seal(plain)
                val padded = aes(false, server.incoming, byteArrayOf(), encrypted, listOf(0, 1, 30, 31, 32, 65).indexOf(size))
                assertEquals(0, padded.size % 32); assertArrayEquals(plain, padded.copyOfRange(0, padded.size - (padded.last().toInt() and 255) - 1))
            }
            assertArrayEquals(byteArrayOf(4, 5), client.open(server.encryptedMessage(byteArrayOf(4, 5))))
            val invalid = server.encryptedMessage(byteArrayOf(6), 1).also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertThrows(Exception::class.java) { client.open(invalid) }
            assertArrayEquals(byteArrayOf(6), client.open(server.encryptedMessage(byteArrayOf(6), 1)))
            assertThrows(Exception::class.java) { client.open(server.encryptedMessage(byteArrayOf(4, 5))) }
        }
    }
    @Test fun wrongStaticPinAlteredResponseAndReuseFailClosed() {
        EnclaveNoise(pair(7)).use { client ->
            val initial = client.start(pair(3).copyOfRange(32, 97)); val response = Responder().respond(initial)
            assertThrows(Exception::class.java) { client.start() }
            assertThrows(Exception::class.java) { client.finish(response.also { it[80] = (it[80].toInt() xor 1).toByte() }) }
        }
        EnclaveNoise(pair(7)).use { client -> val first = client.start(pair(2).copyOfRange(32, 97))
            assertThrows(Exception::class.java) { Responder().respond(first) } }
    }
}
