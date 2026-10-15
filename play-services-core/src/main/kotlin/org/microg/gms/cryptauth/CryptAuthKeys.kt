/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */
// Modified by Cyclon in 2026: key file kept out of backups, okio Base64 for host tests, serialized key creation.

package org.microg.gms.cryptauth

import android.content.Context
import android.os.Build.VERSION.SDK_INT
import android.util.AtomicFile
import android.util.Log
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import securemessage.EcP256PublicKey
import securemessage.GenericPublicKey
import securemessage.PublicKeyType
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

private const val TAG = "CryptAuthKeys"

private const val KEY_DIRECTORY = "cryptauth_keys"

/**
 * Salt prepended to the random session id when computing the proof of an enrolled key.
 * See Chromium's `CryptAuthKeyProofComputerImpl` and `kCryptAuthKeyProofSalt`.
 */
internal const val KEY_PROOF_SALT = "CryptAuth Key Proof"

/**
 * P256 user key pair enrolled in the `PublicKey` key bundle. Google expects this key to exist
 * after answering a SyncKeys request with `keyCreation: ACTIVE`.
 */
internal class CryptAuthUserKey(val keyPair: KeyPair) {

    /** Serialized SecureMessage `GenericPublicKey`, the format CryptAuth expects for P256 keys. */
    val keyMaterial: ByteArray by lazy {
        val point = (keyPair.public as ECPublicKey).w
        GenericPublicKey(
            type = PublicKeyType.EC_P256,
            ec_p256_public_key = EcP256PublicKey(
                x = point.affineX.toTwosComplement().toByteString(),
                y = point.affineY.toTwosComplement().toByteString()
            )
        ).encode()
    }

    val handle: ByteArray by lazy { MessageDigest.getInstance("SHA-256").digest(keyMaterial) }

    /** ECDSA (SHA-256, DER encoded) signature over salt || random session id. */
    fun computeKeyProof(randomSessionId: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keyPair.private)
        update(KEY_PROOF_SALT.toByteArray())
        update(randomSessionId)
        sign()
    }

    /** Two Base64 lines: X.509 public key, then PKCS#8 private key. */
    fun serialize(): String =
        keyPair.public.encoded.toByteString().base64() + "\n" + keyPair.private.encoded.toByteString().base64() + "\n"

    companion object {
        fun generate(): CryptAuthUserKey =
            CryptAuthUserKey(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair())

        /** @return `null` if [serialized] is not a key written by [serialize]. */
        fun deserialize(serialized: String): CryptAuthUserKey? = try {
            val lines = serialized.lines().filter { it.isNotBlank() }
            if (lines.size != 2) null else {
                val publicKey = lines[0].decodeBase64() ?: return null
                val privateKey = lines[1].decodeBase64() ?: return null
                val keyFactory = KeyFactory.getInstance("EC")
                CryptAuthUserKey(
                    KeyPair(
                        keyFactory.generatePublic(X509EncodedKeySpec(publicKey.toByteArray())),
                        keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKey.toByteArray()))
                    )
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** P256 coordinates are encoded as fixed 33 byte two's complement, as done by SecureMessage. */
internal fun BigInteger.toTwosComplement(): ByteArray {
    val bytes = toByteArray()
    return if (bytes.size >= 33) bytes.copyOfRange(bytes.size - 33, bytes.size) else ByteArray(33 - bytes.size) + bytes
}

/**
 * The key file lives outside auto backup: a restored private key on another device must not
 * claim this device's enrollment. Losing it is harmless, CryptAuth asks for a new key.
 */
private fun Context.cryptAuthKeyFile(accountName: String): AtomicFile {
    val base = if (SDK_INT >= 21) noBackupFilesDir else filesDir
    val directory = File(base, KEY_DIRECTORY).apply { mkdirs() }
    return AtomicFile(File(directory, accountName.encodeUtf8().sha256().hex()))
}

internal fun Context.getCryptAuthUserKey(accountName: String): CryptAuthUserKey? {
    val file = cryptAuthKeyFile(accountName)
    if (!file.baseFile.exists()) return null
    return try {
        CryptAuthUserKey.deserialize(String(file.readFully())).also {
            if (it == null) Log.w(TAG, "Stored CryptAuth user key is unreadable; a new key will be created if requested.")
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read CryptAuth user key", e)
        null
    }
}


/**
 * Reuses the account's existing key, as Chromium does for the user key pair, so that a repeated
 * creation request never rotates an already enrolled key. Creation holds the account's key lock in
 * every GmsCore process, so two first proofs can't each write a different key.
 */
internal fun Context.getOrCreateCryptAuthUserKey(accountName: String): CryptAuthUserKey = withCryptAuthLock("key", accountName, wait = true) {
    getCryptAuthUserKey(accountName) ?: CryptAuthUserKey.generate().also { key ->
        val file = cryptAuthKeyFile(accountName)
        val stream = file.startWrite()
        try {
            stream.write(key.serialize().toByteArray())
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }
}!!
