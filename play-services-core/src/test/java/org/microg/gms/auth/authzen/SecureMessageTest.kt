/* SPDX-FileCopyrightText: 2020 Google LLC, 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
// The four TEST_VECTOR_* byte arrays and their keys are copied unchanged from Google's
// SecureMessageSimpleTestVectorTest (github.com/google/ukey2 @10fc737a, Apache-2.0).
package org.microg.gms.auth.authzen

import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import securegcm.GcmMetadata
import securegcm.Type
import securemessage.EcP256PublicKey
import securemessage.EncScheme
import securemessage.GenericPublicKey
import securemessage.Header
import securemessage.HeaderAndBody
import securemessage.HeaderAndBodyInternal
import securemessage.PublicKeyType
import securemessage.SecureMessage
import securemessage.SigScheme
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

/** Returns [fixed] for every request; makes IVs (and so HMAC signcryption) deterministic for known-answer tests. */
internal class FixedRandom(private val fixed: ByteArray) : SecureRandom() {
    override fun nextBytes(bytes: ByteArray) {
        for (i in bytes.indices) bytes[i] = fixed[i % fixed.size]
    }
}

class SecureMessageTest {
    private val associatedData = bytes(11, 22, 33, 44, 55)
    private val metadata = bytes(10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28)
    private val verificationKeyId = bytes(0, 0, 1)
    private val decryptionKeyId = bytes(-1, -1, 0)
    private val message = bytes(0, 99, 1, 98, 2, 97, 3, 96, 4, 95, 5, 94, 6, 93, 7, 92, 8, 91, 9, 90)

    private val ecPublicKey = GenericPublicKey.ADAPTER.decode(bytes(
        8, 1, 18, 70, 10, 33, 0, -109, 9, 5, 8, -89, -3, -68, -86, -19, 17,
        -126, -11, -95, 35, 101, 102, -57, -84, -118, 73, 83, 66, -62, -49, -91, 71, -19,
        52, 123, 113, 119, 45, 18, 33, 0, -65, -19, 83, -66, -12, 62, 102, -67, 116,
        64, 42, 55, -84, -101, 90, -106, 113, -89, -30, 57, -112, 96, -99, -126, 14, 83,
        41, 95, -24, -114, 23, -5)).toP256PublicKey()
    private val ecPrivateKey: PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes(
        48, 65, 2, 1, 0, 48, 19, 6, 7, 42, -122, 72, -50, 61, 2, 1, 6,
        8, 42, -122, 72, -50, 61, 3, 1, 7, 4, 39, 48, 37, 2, 1, 1, 4,
        32, 26, -82, -61, -86, -59, -8, 2, -62, -17, -20, 122, 3, 85, -102, -76, 81,
        51, 39, -9, 12, 99, -117, 127, 19, 121, 109, -31, -49, 110, 121, 76, -107)))
    private val key1: SecretKey = SecretKeySpec(bytes(
        -89, 105, 62, -41, -75, 78, 70, 110, -62, -58, -80, -81, -99, -62, 39, 38, 37,
        -7, -112, -83, 81, 23, 125, -72, -100, 103, -34, -23, -68, 21, -46, -104), "AES")
    private val key2: SecretKey = SecretKeySpec(bytes(
        -6, 48, 107, 61, -99, -89, 111, 33, 70, 54, -13, 111, 81, -120, 50, 89, -119,
        -113, -114, 63, 12, -68, 40, 42, -77, -58, -49, 18, 69, 91, -20, -65), "AES")

    private val vectorEcdsaOnly = bytes(
        10, 56, 10, 32, 8, 2, 16, 1, 26, 3, 0, 0, 1, 50, 19, 10, 11,
        12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28,
        56, 5, 18, 20, 0, 99, 1, 98, 2, 97, 3, 96, 4, 95, 5, 94, 6,
        93, 7, 92, 8, 91, 9, 90, 18, 72, 48, 70, 2, 33, 0, -79, 59, 50,
        21, 54, 61, -92, 77, -34, -77, -45, -105, 107, -28, -19, 91, -78, 120, 68, 33,
        11, -76, -1, 50, 64, -127, -78, 6, 108, 115, -13, 126, 2, 33, 0, -72, -44,
        52, 93, 105, 109, -127, -111, 11, 33, -111, 97, -114, 9, 117, -68, -45, 64, 63,
        43, 60, -44, -89, -107, -59, -45, 56, 100, -66, -40, 46, -60)
    private val vectorEcdsaAndAes = bytes(
        10, 107, 10, 55, 8, 2, 16, 2, 26, 3, 0, 0, 1, 34, 3, -1, -1,
        0, 42, 16, -86, 16, 55, -8, -85, -47, -77, -36, -127, 44, -10, -44, -63, 115,
        -111, 26, 50, 19, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22,
        23, 24, 25, 26, 27, 28, 56, 5, 18, 48, -110, 23, -67, 122, -118, 96, -4,
        32, -113, -104, -107, -16, 76, 37, -61, -67, -63, 90, 38, 96, -47, -105, 56, -34,
        50, -30, 82, 25, 100, 36, 69, 50, 68, 60, 38, 96, -108, -49, -73, -10, -62,
        -76, -45, -105, -86, 93, 28, 34, 18, 70, 48, 68, 2, 33, 0, -87, -103, 11,
        -70, 34, 33, -41, 90, -83, -74, 19, -13, 127, -43, -116, -32, 88, -13, 125, -122,
        56, -21, 79, 47, 101, 89, -80, -43, 102, 92, 4, -15, 2, 31, 109, -69, 35,
        21, 44, -27, -77, 32, 17, -90, -68, 113, 55, -24, -122, 40, 81, 51, 0, -84,
        -29, -12, -26, 73, 105, -32, 116, -28, 84, -116, -117)
    private val vectorHmacAndAesSameKeys = bytes(
        10, 91, 10, 55, 8, 1, 16, 2, 26, 3, 0, 0, 1, 34, 3, -1, -1,
        0, 42, 16, -110, 48, 67, 67, -31, 24, -42, 13, -44, -109, 6, 113, 34, -70,
        121, 6, 50, 19, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22,
        23, 24, 25, 26, 27, 28, 56, 5, 18, 32, -44, -102, -16, 123, 113, -75, 88,
        -33, 118, 25, 60, -65, 109, 26, -70, -123, 58, -114, 126, 8, 106, -28, 65, -38,
        -4, 68, -78, -91, 49, -13, 22, -122, 18, 32, 20, -120, -113, -76, 85, -35, -53,
        37, -18, 66, -38, 32, 10, 30, 89, 112, -39, -27, 24, 93, -36, -100, -127, -79,
        94, -7, -19, -41, -47, -29, 1, 12)
    private val vectorHmacAndAesDifferentKeys = bytes(
        10, 107, 10, 55, 8, 1, 16, 2, 26, 3, 0, 0, 1, 34, 3, -1, -1,
        0, 42, 16, -96, -7, 39, 79, -37, 40, 1, -30, 97, 0, 123, -7, -124, -75,
        -127, -18, 50, 19, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22,
        23, 24, 25, 26, 27, 28, 56, 5, 18, 48, 90, 40, -48, -113, 84, -32, 47,
        98, 54, -128, 127, 115, 32, 87, -86, 4, -26, 99, 9, -88, 13, 77, 127, 114,
        -48, -117, -94, 96, -86, -105, -123, 11, 116, -69, -83, -110, 3, -10, 0, -34, 72,
        10, -58, 3, -119, -94, 23, -114, 18, 32, -25, -126, 95, 125, -110, -62, -36, -78,
        97, 72, -54, -114, 97, -68, -46, 107, 53, 55, -57, 88, 127, -20, -23, 80, -9,
        -91, 115, 42, 24, 49, -76, -111)

    private fun parse(bytes: ByteArray) = SecureMessageParser.decode(bytes)

    private fun assertHeader(verified: VerifiedSecureMessage, decryptionKeyId: ByteArray?) {
        assertArrayEquals(message, verified.body)
        assertEquals(associatedData.size, verified.header.associated_data_length)
        assertArrayEquals(metadata, verified.header.public_metadata!!.toByteArray())
        assertArrayEquals(verificationKeyId, verified.header.verification_key_id!!.toByteArray())
        assertArrayEquals(decryptionKeyId, verified.header.decryption_key_id?.toByteArray())
    }

    @Test
    fun googleVectorEcdsaOnly() {
        val verified = SecureMessageParser.parseSignedCleartext(parse(vectorEcdsaOnly), ecPublicKey, SigType.ECDSA_P256_SHA256, associatedData)
        assertHeader(verified, null)
    }

    @Test
    fun googleVectorEcdsaAndAes() {
        val verified = SecureMessageParser.parseSignCrypted(parse(vectorEcdsaAndAes), ecPublicKey, SigType.ECDSA_P256_SHA256, key1, associatedData)
        assertHeader(verified, decryptionKeyId)
    }

    @Test
    fun googleVectorHmacAndAesSameKeys() {
        val verified = SecureMessageParser.parseSignCrypted(parse(vectorHmacAndAesSameKeys), key1, SigType.HMAC_SHA256, key1, associatedData)
        assertHeader(verified, decryptionKeyId)
    }

    @Test
    fun googleVectorHmacAndAesDifferentKeys() {
        val verified = SecureMessageParser.parseSignCrypted(parse(vectorHmacAndAesDifferentKeys), key1, SigType.HMAC_SHA256, key2, associatedData)
        assertHeader(verified, decryptionKeyId)
    }

    private fun ivOf(vector: ByteArray) = HeaderAndBody.ADAPTER.decode(parse(vector).header_and_body).header_.iv!!.toByteArray()

    /** HMAC signcryption is deterministic for a given IV: rebuilding must give Google's bytes exactly. */
    @Test
    fun builderReproducesGoogleVectorsByteForByte() {
        for ((vector, encryptionKey) in listOf(vectorHmacAndAesSameKeys to key1, vectorHmacAndAesDifferentKeys to key2)) {
            val built = SecureMessageBuilder(FixedRandom(ivOf(vector)), metadata, verificationKeyId, decryptionKeyId, associatedData)
                .buildSignCrypted(key1, SigType.HMAC_SHA256, encryptionKey, message)
            assertArrayEquals(vector, built.encode())
        }
    }

    @Test
    fun builderOutputVerifiesForEcdsa() {
        val signed = SecureMessageBuilder(SecureRandom(), metadata, verificationKeyId, null, associatedData).buildSignedCleartext(ecPrivateKey, SigType.ECDSA_P256_SHA256, message)
        assertArrayEquals(message, SecureMessageParser.parseSignedCleartext(signed, ecPublicKey, SigType.ECDSA_P256_SHA256, associatedData).body)
        val signcrypted = SecureMessageBuilder(SecureRandom(), metadata, verificationKeyId, decryptionKeyId, associatedData).buildSignCrypted(ecPrivateKey, SigType.ECDSA_P256_SHA256, key1, message)
        assertArrayEquals(message, SecureMessageParser.parseSignCrypted(signcrypted, ecPublicKey, SigType.ECDSA_P256_SHA256, key1, associatedData).body)
    }

    /** RFC 5869 test case 1 (SHA-256). */
    @Test
    fun hkdfKnownAnswer() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = SecureMessageCrypto.hkdf(ikm, salt, info, 42).toByteString().hex()
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm)
    }

    @Test
    fun purposesMatchTheReference() {
        assertEquals("SIG:1", SecureMessageCrypto.purpose(SigType.HMAC_SHA256))
        assertEquals("SIG:2", SecureMessageCrypto.purpose(SigType.ECDSA_P256_SHA256))
        assertEquals("ENC:2", SecureMessageCrypto.purposeAes256Cbc())
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("accepted a tampered message")
        } catch (e: SecureMessageException) {
            assertEquals("SecureMessage verification failed", e.message)
        }
    }

    private fun hmacSameKeys(message: SecureMessage, key: SecretKey = key1, associatedData: ByteArray? = this.associatedData) =
        SecureMessageParser.parseSignCrypted(message, key, SigType.HMAC_SHA256, key, associatedData)

    @Test
    fun everyFlippedBitIsRejected() {
        val original = parse(vectorHmacAndAesDifferentKeys)
        val headerAndBody = original.header_and_body.toByteArray()
        for (i in headerAndBody.indices) {
            val tampered = headerAndBody.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertRejected { SecureMessageParser.parseSignCrypted(SecureMessage(tampered.toByteString(), original.signature), key1, SigType.HMAC_SHA256, key2, associatedData) }
        }
        val signature = original.signature.toByteArray()
        for (i in signature.indices) {
            val tampered = signature.copyOf().also { it[i] = (it[i].toInt() xor 0x80).toByte() }
            assertRejected { SecureMessageParser.parseSignCrypted(SecureMessage(original.header_and_body, tampered.toByteString()), key1, SigType.HMAC_SHA256, key2, associatedData) }
        }
    }

    @Test
    fun wrongKeysAssociatedDataAndSchemesAreRejected() {
        val same = parse(vectorHmacAndAesSameKeys)
        assertRejected { hmacSameKeys(same, key2) }
        assertRejected { hmacSameKeys(same, associatedData = null) }
        assertRejected { hmacSameKeys(same, associatedData = bytes(11, 22, 33, 44, 56)) }
        // Signed cleartext parsing of an encrypted message, and HMAC parsing of an ECDSA message.
        assertRejected { SecureMessageParser.parseSignedCleartext(same, key1, SigType.HMAC_SHA256, associatedData) }
        assertRejected { SecureMessageParser.parseSignCrypted(parse(vectorEcdsaAndAes), key1, SigType.HMAC_SHA256, key1, associatedData) }
        // A public key where a secret key is required, and the reverse.
        assertRejected { SecureMessageParser.parseSignCrypted(same, ecPublicKey, SigType.HMAC_SHA256, key1, associatedData) }
        assertRejected { SecureMessageParser.parseSignedCleartext(parse(vectorEcdsaOnly), key1, SigType.ECDSA_P256_SHA256, associatedData) }
    }

    /** Re-signed messages whose header lies about its schemes or IV still fail the header checks. */
    @Test
    fun headerChecksRejectSchemeIvAndKeyIdMismatches() {
        fun signed(header: Header, body: ByteArray): SecureMessage {
            val headerAndBody = HeaderAndBodyInternal(header.encode().toByteString(), body.toByteString()).encode()
            return SecureMessage(headerAndBody.toByteString(), SecureMessageCrypto.sign(SigType.HMAC_SHA256, key1, SecureRandom(), headerAndBody).toByteString())
        }
        val good = Header(SigScheme.HMAC_SHA256, EncScheme.AES_256_CBC, iv = ByteArray(16).toByteString())
        val body = SecureMessageCrypto.encrypt(key1, ByteArray(16), message)
        assertArrayEquals(message, SecureMessageParser.parseSignCrypted(signed(good, body), key1, SigType.HMAC_SHA256, key1).body)
        assertRejected { SecureMessageParser.parseSignCrypted(signed(good.copy(encryption_scheme = EncScheme.NONE), body), key1, SigType.HMAC_SHA256, key1) }
        assertRejected { SecureMessageParser.parseSignCrypted(signed(good.copy(signature_scheme = SigScheme.ECDSA_P256_SHA256), body), key1, SigType.HMAC_SHA256, key1) }
        assertRejected { SecureMessageParser.parseSignCrypted(signed(good.copy(iv = null), body), key1, SigType.HMAC_SHA256, key1) }
        assertRejected { SecureMessageParser.parseSignCrypted(signed(good.copy(iv = ByteArray(8).toByteString()), body), key1, SigType.HMAC_SHA256, key1) }
        assertRejected { SecureMessageParser.parseSignCrypted(signed(good.copy(associated_data_length = 3), body), key1, SigType.HMAC_SHA256, key1) }
        assertRejected { SecureMessageParser.parseSignedCleartext(signed(Header(SigScheme.HMAC_SHA256, EncScheme.NONE, decryption_key_id = bytes(1).toByteString()), message), key1, SigType.HMAC_SHA256) }
        // RSA exists on the wire but is never accepted.
        assertRejected { SecureMessageParser.parseSignCrypted(signed(good.copy(signature_scheme = SigScheme.RSA2048_SHA256), body), key1, SigType.HMAC_SHA256, key1) }
    }

    @Test
    fun malformedBytesAreRejected() {
        assertRejected { parse(bytes(0x0a, 0x05, 1)) }
        assertRejected { parse(ByteArray(0)) }
        assertRejected { parse(vectorHmacAndAesSameKeys.copyOf(40)) }
        assertRejected { hmacSameKeys(SecureMessage(bytes(0x12, 0x00).toByteString(), ByteArray(32).toByteString())) }
        assertRejected { SecureMessageParser.unverifiedHeader(SecureMessage(bytes(0x0a, 0x00).toByteString(), ByteArray(0).toByteString())) }
    }

    /**
     * The signature covers the bytes as sent. A header with an unknown field and a non-minimal varint would change if
     * reserialized; it must verify, and the bytes kept for binding must be the original ones.
     */
    @Test
    fun signedBytesArePreservedExactly() {
        val iv = ByteArray(16) { 7 }
        // signature_scheme as a two-byte varint (0x81 0x00 = 1) and unknown field 15.
        val header = bytes(0x08, 0x81, 0x00, 0x10, 0x02, 0x2a, 0x10) + iv + bytes(0x78, 0x2a)
        assertFalse(header.contentEquals(Header.ADAPTER.decode(header).encode()))
        val tagged = SecureMessageCrypto.digest(header) + message
        val ciphertext = SecureMessageCrypto.encrypt(key2, iv, tagged)
        val headerAndBody = HeaderAndBodyInternal(header.toByteString(), ciphertext.toByteString()).encode()
        val signature = SecureMessageCrypto.sign(SigType.HMAC_SHA256, key1, SecureRandom(), headerAndBody)
        val verified = SecureMessageParser.parseSignCrypted(SecureMessage(headerAndBody.toByteString(), signature.toByteString()), key1, SigType.HMAC_SHA256, key2)
        assertArrayEquals(message, verified.body)
        assertArrayEquals(headerAndBody, verified.signedBytes.toByteArray())
    }

    @Test
    fun invalidCurvePointsAreRejected() {
        val valid = ecPublicKey.toGenericPublicKey()
        val point = valid.ec_p256_public_key!!
        val offCurve = valid.copy(ec_p256_public_key = EcP256PublicKey(point.x, (point.y.toByteArray().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }).toByteString()))
        assertRejected { offCurve.toP256PublicKey() }
        assertRejected { valid.copy(type = PublicKeyType.RSA2048).toP256PublicKey() }
        assertRejected { valid.copy(ec_p256_public_key = null).toP256PublicKey() }
        assertRejected { valid.copy(ec_p256_public_key = EcP256PublicKey(bytes(-1).toByteString(), point.y)).toP256PublicKey() }
        assertEquals(ecPublicKey.w, valid.toP256PublicKey().w)
    }

    private val masterKey: SecretKey = SecretKeySpec(ByteArray(32) { (it * 3 + 1).toByte() }, "AES")
    private val handle = ByteArray(32) { (200 - it).toByte() }

    @Test
    fun secureGcmServerMessageRoundTrip() {
        val sent = SecureGcm.signcryptServerMessage(Type.TX_REQUEST, message, masterKey, handle)
        assertArrayEquals(handle, SecureGcm.unverifiedKeyHandle(sent))
        val payload = SecureGcm.verifyDecryptServerMessage(SecureMessageParser.decode(sent.encode()), masterKey)
        assertEquals(Type.TX_REQUEST, payload.type)
        assertEquals(1, payload.version)
        assertArrayEquals(message, payload.body)
        assertRejected { SecureGcm.verifyDecryptServerMessage(sent, SecretKeySpec(ByteArray(32), "AES")) }
    }

    /** Authentic messages with an unknown type or a newer version are reported as such, for the decoder to refuse. */
    @Test
    fun secureGcmUnknownTypeAndVersionAreReported() {
        fun withMetadata(metadata: ByteArray) = SecureMessageBuilder(SecureRandom(), metadata, handle).buildSignCrypted(masterKey, SigType.HMAC_SHA256, masterKey, message)
        val newer = SecureGcm.verifyDecryptServerMessage(withMetadata(GcmMetadata(Type.TX_REQUEST, 2).encode()), masterKey)
        assertEquals(2, newer.version)
        val unknownType = SecureGcm.verifyDecryptServerMessage(withMetadata(bytes(0x08, 0x63, 0x10, 0x01)), masterKey)
        assertNull(unknownType.type)
        assertTrue(AuthZenDecoder(emptyList()).decode(newer.type, newer.version, newer.body) is Decoded.Unsupported)
        assertTrue(AuthZenDecoder(emptyList()).decode(unknownType.type, unknownType.version, unknownType.body) is Decoded.Unsupported)
    }

    @Test
    fun secureGcmClientMessageIsVerifiableByTheServer() {
        val userKey = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val material = (userKey.public as ECPublicKey).toGenericPublicKey().encode()
        val reply = SecureGcm.signcryptClientMessage(Type.TX_REPLY, message, userKey, material, masterKey)
        assertArrayEquals(material, SecureMessageParser.unverifiedHeader(reply).verification_key_id!!.toByteArray())
        val received = SecureGcm.verifyDecryptClientMessage(reply, userKey.public as ECPublicKey, masterKey)
        assertEquals(Type.TX_REPLY, received.type)
        assertArrayEquals(message, received.body)
        // The server's own key cannot pass as the user's signature.
        assertRejected { SecureGcm.verifyDecryptServerMessage(reply, masterKey) }
    }
}
