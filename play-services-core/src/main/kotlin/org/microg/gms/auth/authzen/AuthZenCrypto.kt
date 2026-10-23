/*
 * SPDX-FileCopyrightText: 2020 Google LLC, 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */
// Kotlin port of Google's open source SecureMessage and SecureGcm transport code, github.com/google/ukey2 @10fc737a:
// src/main/java/com/google/security/cryptauth/lib/securemessage/{CryptoOps,SecureMessageBuilder,SecureMessageParser}
// and .../securegcm/{TransportCryptoOps,SecureGcmConstants}, Apache-2.0. Wire format and cryptography are unchanged;
// Cyclon removed RSA and NONE-as-encryption, unified failures into one exception and validates P-256 points
// (useoxygen/cyclon#842). Google's test vectors are in SecureMessageTest.

package org.microg.gms.auth.authzen

import okio.ByteString
import okio.ByteString.Companion.toByteString
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
import org.microg.gms.cryptauth.toTwosComplement
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Every SecureMessage failure: malformed bytes, wrong key, bad MAC or signature, broken binding, unsupported scheme.
 * One type and one message, so neither callers nor logs can tell an attacker which check failed.
 */
class SecureMessageException : GeneralSecurityException("SecureMessage verification failed")

/** Signature schemes this port accepts. RSA2048_SHA256 exists on the wire but is never accepted. */
enum class SigType(val scheme: SigScheme, val jcaName: String, val isPublicKeyScheme: Boolean) {
    HMAC_SHA256(SigScheme.HMAC_SHA256, "HmacSHA256", false),
    ECDSA_P256_SHA256(SigScheme.ECDSA_P256_SHA256, "SHA256withECDSA", true),
}

/** A SecureMessage whose signature, header checks and (for signcrypted messages) binding tag all verified. */
class VerifiedSecureMessage(
    val header: Header,
    /** Verified, decrypted body. */
    val body: ByteArray,
    /** The exact `header_and_body` bytes the signature covers, never reserialized. */
    val signedBytes: ByteString,
)

internal object SecureMessageCrypto {
    /** Length of the truncated SHA-256 tag that binds a ciphertext to its header. */
    const val DIGEST_LENGTH = 20
    private const val AES_CBC = "AES/CBC/PKCS5Padding"
    private const val AES_BLOCK = 16

    /** SHA-256("SecureMessage"): HKDF salt and the prefix of every public key signature. */
    private val SALT = sha256("SecureMessage".toByteArray())

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun digest(data: ByteArray): ByteArray = sha256(data).copyOf(DIGEST_LENGTH)

    /** No early exit on the first differing byte. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].toInt() xor b[i].toInt())
        return result == 0
    }

    fun purpose(sigType: SigType) = "SIG:" + sigType.scheme.value
    fun purposeAes256Cbc() = "ENC:" + EncScheme.AES_256_CBC.value

    /** RFC 5869 HKDF with SHA-256. */
    fun hkdf(inputKeyMaterial: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        require(length in 0..255 * 32)
        val prk = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, "HmacSHA256"))
            doFinal(inputKeyMaterial)
        }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        val output = ByteArray((length + 31) / 32 * 32)
        var block = ByteArray(0)
        for (i in 0 until output.size / 32) {
            mac.reset()
            mac.update(block)
            mac.update(info)
            mac.update((i + 1).toByte())
            block = mac.doFinal()
            System.arraycopy(block, 0, output, i * 32, 32)
        }
        return output.copyOf(length)
    }

    fun deriveAes256Key(masterKey: Key, purpose: String): SecretKey {
        val raw = (masterKey as? SecretKey)?.encoded ?: throw SecureMessageException()
        return SecretKeySpec(hkdf(raw, SALT, purpose.toByteArray(Charsets.UTF_8)), "AES")
    }

    fun sign(sigType: SigType, signingKey: Key, rng: SecureRandom, data: ByteArray): ByteArray = if (sigType.isPublicKeyScheme) {
        Signature.getInstance(sigType.jcaName).run {
            initSign(signingKey as? PrivateKey ?: throw SecureMessageException(), rng)
            update(SALT)
            update(data)
            sign()
        }
    } else {
        Mac.getInstance(sigType.jcaName).run {
            init(deriveAes256Key(signingKey, purpose(sigType)))
            doFinal(data)
        }
    }

    fun verify(verificationKey: Key, sigType: SigType, signature: ByteArray, data: ByteArray): Boolean = if (sigType.isPublicKeyScheme) {
        Signature.getInstance(sigType.jcaName).run {
            initVerify(verificationKey as? PublicKey ?: throw SecureMessageException())
            update(SALT)
            update(data)
            try {
                verify(signature)
            } catch (e: java.security.SignatureException) {
                false
            }
        }
    } else {
        val expected = Mac.getInstance(sigType.jcaName).run {
            init(deriveAes256Key(verificationKey, purpose(sigType)))
            doFinal(data)
        }
        constantTimeEquals(signature, expected)
    }

    fun encrypt(encryptionKey: Key, iv: ByteArray, plaintext: ByteArray): ByteArray = Cipher.getInstance(AES_CBC).run {
        init(Cipher.ENCRYPT_MODE, deriveAes256Key(encryptionKey, purposeAes256Cbc()), IvParameterSpec(iv))
        doFinal(plaintext)
    }

    fun decrypt(decryptionKey: Key, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        if (iv.size != AES_BLOCK) throw SecureMessageException()
        return try {
            Cipher.getInstance(AES_CBC).run {
                init(Cipher.DECRYPT_MODE, deriveAes256Key(decryptionKey, purposeAes256Cbc()), IvParameterSpec(iv))
                doFinal(ciphertext)
            }
        } catch (e: GeneralSecurityException) {
            throw SecureMessageException()
        }
    }

    fun newIv(rng: SecureRandom): ByteArray = ByteArray(AES_BLOCK).also { rng.nextBytes(it) }

    /** Same rule as the reference: a tag is needed unless one symmetric key both signs and encrypts. */
    fun taggedPlaintextRequired(signingKey: Key, sigType: SigType, encryptionKey: Key): Boolean =
        sigType.isPublicKeyScheme || !MessageDigest.isEqual(signingKey.encoded, encryptionKey.encoded)
}

/** Parsing and verification, as `SecureMessageParser`. Nothing from a message is trusted before a parse succeeds. */
internal object SecureMessageParser {

    fun decode(bytes: ByteArray): SecureMessage = try {
        SecureMessage.ADAPTER.decode(bytes)
    } catch (e: Exception) {
        throw SecureMessageException()
    }

    /**
     * The header, NOT verified. Only to pick a key: callers must treat every field as an untrusted lookup hint until
     * [parseSignCrypted] or [parseSignedCleartext] succeeds.
     */
    fun unverifiedHeader(message: SecureMessage): Header = try {
        HeaderAndBody.ADAPTER.decode(message.header_and_body).header_
    } catch (e: Exception) {
        throw SecureMessageException()
    }

    fun parseSignedCleartext(message: SecureMessage, verificationKey: Key, sigType: SigType, associatedData: ByteArray? = null): VerifiedSecureMessage {
        val parsed = verifyHeaderAndBody(message, verificationKey, sigType, EncScheme.NONE, associatedData, suppressAssociatedData = false)
        return VerifiedSecureMessage(parsed.header_, parsed.body.toByteArray(), message.header_and_body)
    }

    /** Verifies, then decrypts, then checks the tag binding ciphertext and header when keys differ. */
    fun parseSignCrypted(
        message: SecureMessage,
        verificationKey: Key,
        sigType: SigType,
        decryptionKey: SecretKey,
        associatedData: ByteArray? = null,
    ): VerifiedSecureMessage {
        val tagRequired = SecureMessageCrypto.taggedPlaintextRequired(verificationKey, sigType, decryptionKey)
        val parsed = verifyHeaderAndBody(message, verificationKey, sigType, EncScheme.AES_256_CBC, associatedData, suppressAssociatedData = tagRequired)
        val iv = parsed.header_.iv ?: throw SecureMessageException()
        val decrypted = SecureMessageCrypto.decrypt(decryptionKey, iv.toByteArray(), parsed.body.toByteArray())
        if (!tagRequired) return VerifiedSecureMessage(parsed.header_, decrypted, message.header_and_body)

        // The tag covers the header exactly as it was sent, taken from the signed bytes, never re-encoded.
        val headerBytes = try {
            HeaderAndBodyInternal.ADAPTER.decode(message.header_and_body).header_.toByteArray()
        } catch (e: Exception) {
            throw SecureMessageException()
        }
        val expectedTag = SecureMessageCrypto.digest(headerBytes + (associatedData ?: ByteArray(0)))
        if (decrypted.size < SecureMessageCrypto.DIGEST_LENGTH) throw SecureMessageException()
        val actualTag = decrypted.copyOfRange(0, SecureMessageCrypto.DIGEST_LENGTH)
        if (!SecureMessageCrypto.constantTimeEquals(actualTag, expectedTag)) throw SecureMessageException()
        return VerifiedSecureMessage(parsed.header_, decrypted.copyOfRange(SecureMessageCrypto.DIGEST_LENGTH, decrypted.size), message.header_and_body)
    }

    private fun verifyHeaderAndBody(
        message: SecureMessage,
        verificationKey: Key,
        sigType: SigType,
        encScheme: EncScheme,
        associatedData: ByteArray?,
        suppressAssociatedData: Boolean,
    ): HeaderAndBody {
        val data = message.header_and_body.toByteArray()
        val signedData = if (suppressAssociatedData || associatedData == null) data else data + associatedData
        // Like the reference, evaluate every check before failing, and fail the same way for all of them.
        var verified = try {
            SecureMessageCrypto.verify(verificationKey, sigType, message.signature.toByteArray(), signedData)
        } catch (e: SecureMessageException) {
            throw e
        } catch (e: GeneralSecurityException) {
            false
        }
        val result = try {
            HeaderAndBody.ADAPTER.decode(message.header_and_body)
        } catch (e: Exception) {
            null
        }
        if (result == null) throw SecureMessageException()
        val header = result.header_
        verified = verified and (header.signature_scheme == sigType.scheme)
        verified = verified and (header.encryption_scheme == encScheme)
        verified = verified and (encScheme != EncScheme.NONE || header.decryption_key_id == null)
        verified = verified and (encScheme == EncScheme.NONE || !sigType.isPublicKeyScheme || header.verification_key_id != null)
        verified = verified and ((header.associated_data_length ?: 0) == (associatedData?.size ?: 0))
        if (!verified) throw SecureMessageException()
        return result
    }
}

/** Message creation, as `SecureMessageBuilder`. [rng] supplies IVs and ECDSA nonces; tests pass a fixed one. */
internal class SecureMessageBuilder(
    private val rng: SecureRandom = SecureRandom(),
    var publicMetadata: ByteArray? = null,
    var verificationKeyId: ByteArray? = null,
    var decryptionKeyId: ByteArray? = null,
    var associatedData: ByteArray? = null,
) {
    fun buildSignedCleartext(signingKey: Key, sigType: SigType, body: ByteArray): SecureMessage {
        check(decryptionKeyId == null) { "Cannot set decryptionKeyId for a cleartext message" }
        val header = buildHeader(sigType, EncScheme.NONE, null).encode()
        return signed(signingKey, sigType, headerAndBody(header, body), associatedData)
    }

    fun buildSignCrypted(signingKey: Key, sigType: SigType, encryptionKey: SecretKey, body: ByteArray): SecureMessage {
        check(!sigType.isPublicKeyScheme || verificationKeyId != null) { "Must set a verificationKeyId when using public key signature with encryption" }
        val iv = SecureMessageCrypto.newIv(rng)
        val header = buildHeader(sigType, EncScheme.AES_256_CBC, iv).encode()
        val tagged: ByteArray
        val associatedDataToSign: ByteArray?
        if (SecureMessageCrypto.taggedPlaintextRequired(signingKey, sigType, encryptionKey)) {
            tagged = SecureMessageCrypto.digest(header + (associatedData ?: ByteArray(0))) + body
            associatedDataToSign = null
        } else {
            tagged = body
            associatedDataToSign = associatedData
        }
        val encrypted = SecureMessageCrypto.encrypt(encryptionKey, iv, tagged)
        return signed(signingKey, sigType, headerAndBody(header, encrypted), associatedDataToSign)
    }

    private fun buildHeader(sigType: SigType, encScheme: EncScheme, iv: ByteArray?) = Header(
        signature_scheme = sigType.scheme,
        encryption_scheme = encScheme,
        verification_key_id = verificationKeyId?.toByteString(),
        decryption_key_id = decryptionKeyId?.toByteString(),
        iv = iv?.toByteString(),
        public_metadata = publicMetadata?.toByteString(),
        associated_data_length = associatedData?.size,
    )

    private fun headerAndBody(header: ByteArray, body: ByteArray) =
        HeaderAndBodyInternal(header_ = header.toByteString(), body = body.toByteString()).encode()

    private fun signed(signingKey: Key, sigType: SigType, headerAndBody: ByteArray, associatedData: ByteArray?): SecureMessage {
        val signature = SecureMessageCrypto.sign(sigType, signingKey, rng, headerAndBody + (associatedData ?: ByteArray(0)))
        return SecureMessage(header_and_body = headerAndBody.toByteString(), signature = signature.toByteString())
    }
}

/**
 * SecureGcm transport, as `TransportCryptoOps`: server messages are HMAC-SHA256 signed and AES-256-CBC encrypted
 * with one shared master key; client messages are signed with the user's P-256 key and encrypted with the master key.
 */
internal object SecureGcm {
    /** `SecureGcmConstants.SENDER_ID`, the GCM sender of the SecureGcm library (PUBLIC). */
    const val SENDER_ID = "745476177629"

    /** `SecureGcmConstants.MESSAGE_KEY`, the app-data key that carries a SecureGcm payload (PUBLIC). */
    const val MESSAGE_KEY = "P"

    /** `SecureGcmConstants.SECURE_GCM_VERSION`. */
    const val VERSION = 1

    /**
     * An authenticated payload. Unlike the reference, an unknown [type] or a newer [version] is returned instead of
     * thrown, so that diagnostics can tell "authentic but unsupported" from "forged"; the decoder rejects both.
     */
    class Payload(val type: Type?, val version: Int, val body: ByteArray, val signedBytes: ByteString)

    /** The key handle a server message names. NOT verified: a bounded lookup hint only. */
    fun unverifiedKeyHandle(message: SecureMessage): ByteArray? =
        SecureMessageParser.unverifiedHeader(message).verification_key_id?.toByteArray()

    fun verifyDecryptServerMessage(message: SecureMessage, masterKey: SecretKey): Payload {
        val verified = SecureMessageParser.parseSignCrypted(message, masterKey, SigType.HMAC_SHA256, masterKey)
        val metadata = verified.header.public_metadata?.let {
            try {
                GcmMetadata.ADAPTER.decode(it)
            } catch (e: Exception) {
                null
            }
        }
        return Payload(metadata?.type, metadata?.version ?: -1, verified.body, verified.signedBytes)
    }

    /** What Google's server does; used for synthetic test fixtures. */
    fun signcryptServerMessage(type: Type, body: ByteArray, masterKey: SecretKey, keyHandle: ByteArray, rng: SecureRandom = SecureRandom()): SecureMessage =
        SecureMessageBuilder(rng, publicMetadata = metadata(type), verificationKeyId = keyHandle)
            .buildSignCrypted(masterKey, SigType.HMAC_SHA256, masterKey, body)

    /**
     * A client message signed by [userKey] and encrypted with [masterKey]. [verificationKeyId] is the encoded user
     * public key; the reference uses `KeyEncoding.encodeUserPublicKey`, here it is the exact `PublicKey` key material
     * enrolled with CryptAuth (INFERRED to be what the server matches; unverified on device).
     */
    fun signcryptClientMessage(type: Type, body: ByteArray, userKey: KeyPair, verificationKeyId: ByteArray, masterKey: SecretKey, rng: SecureRandom = SecureRandom()): SecureMessage {
        if (userKey.public !is ECPublicKey) throw SecureMessageException()
        return SecureMessageBuilder(rng, publicMetadata = metadata(type), verificationKeyId = verificationKeyId)
            .buildSignCrypted(userKey.private, SigType.ECDSA_P256_SHA256, masterKey, body)
    }

    /** What Google's server does with a client message; used to check [signcryptClientMessage]. */
    fun verifyDecryptClientMessage(message: SecureMessage, userPublicKey: ECPublicKey, masterKey: SecretKey): Payload {
        val verified = SecureMessageParser.parseSignCrypted(message, userPublicKey, SigType.ECDSA_P256_SHA256, masterKey)
        val metadata = verified.header.public_metadata?.let { GcmMetadata.ADAPTER.decode(it) } ?: throw SecureMessageException()
        return Payload(metadata.type, metadata.version ?: 0, verified.body, verified.signedBytes)
    }

    private fun metadata(type: Type) = GcmMetadata(type = type, version = VERSION).encode()
}

/** NIST P-256 parameters, from the platform provider. */
private val P256: ECParameterSpec by lazy {
    AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }
}

/**
 * A SecureMessage `GenericPublicKey` as a P-256 key, after checking that the point lies on the curve: providers do
 * not all reject invalid points, and accepting one would allow invalid-curve attacks.
 */
internal fun GenericPublicKey.toP256PublicKey(): ECPublicKey {
    val key = ec_p256_public_key
    if (type != PublicKeyType.EC_P256 || key == null) throw SecureMessageException()
    val x = BigInteger(key.x.toByteArray())
    val y = BigInteger(key.y.toByteArray())
    val field = (P256.curve.field as java.security.spec.ECFieldFp).p
    if (x.signum() < 0 || y.signum() < 0 || x >= field || y >= field) throw SecureMessageException()
    val rhs = x.pow(3).add(P256.curve.a.multiply(x)).add(P256.curve.b).mod(field)
    if (y.pow(2).mod(field) != rhs) throw SecureMessageException()
    return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), P256)) as ECPublicKey
}

/** The same encoding as the enrolled `PublicKey` key material in `CryptAuthUserKey`. */
internal fun ECPublicKey.toGenericPublicKey(): GenericPublicKey = GenericPublicKey(
    type = PublicKeyType.EC_P256,
    ec_p256_public_key = EcP256PublicKey(
        x = w.affineX.toTwosComplement().toByteString(),
        y = w.affineY.toTwosComplement().toByteString(),
    ),
)
