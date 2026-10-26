/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import com.google.security.cryptauth.lib.securegcm.Ukey2Handshake
import java.io.Closeable
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Fresh UKEY2 bound to the shared secret from an already verified bootstrap.
 * No public setter, random fallback, or persistence of secrets is provided. */
internal class PostSetupTargetHandshake(sharedSecret: ByteArray) : Closeable {
    private val secret = sharedSecret.copyOf().also { require(it.size >= 16) }
    private var handshake: Ukey2Handshake? = null
    private var state = State.NEW
    private enum class State { NEW, STARTED, FINISHED, AUTHENTICATED, CLOSED }

    @Synchronized fun start(): ByteArray {
        check(state == State.NEW)
        val candidate = Ukey2Handshake.forInitiator(Ukey2Handshake.HandshakeCipher.P256_SHA512)
        val message = candidate.nextHandshakeMessage
        handshake = candidate
        state = State.STARTED
        return message
    }

    @Synchronized fun finish(sourceInit: ByteArray): Pair<ByteArray, ByteArray> {
        check(state == State.STARTED)
        require(sourceInit.size in 1..MAX_HANDSHAKE)
        try {
            val active = requireNotNull(handshake)
            active.parseHandshakeMessage(sourceInit)
            val message = active.nextHandshakeMessage
            val verification = active.getVerificationString(32)
            state = State.FINISHED
            return message to hmac(secret, verification)
        } catch (e: Exception) { close(); throw e }
    }

    @Synchronized fun authenticate(message: ByteArray, sourceHmac: ByteArray): ByteArray {
        check(state == State.FINISHED)
        require(message.size in 1..MAX_HANDSHAKE && sourceHmac.size == 32)
        if (!MessageDigest.isEqual(sourceHmac, hmac(secret, message))) {
            close()
            throw SecurityException("Post-setup peer authentication failed")
        }
        val active = requireNotNull(handshake)
        active.verifyHandshake()
        val sessionUnique = active.toConnectionContext().sessionUnique
        state = State.AUTHENTICATED
        // GMS CryptoOps HKDF: salt = retained secret, IKM = UKEY2 sessionUnique.
        return hkdf(sessionUnique, secret, "smartdevice.postsetup.key".toByteArray(Charsets.UTF_8))
    }

    @Synchronized override fun close() {
        secret.fill(0)
        handshake = null
        state = State.CLOSED
    }

    companion object {
        private const val MAX_HANDSHAKE = 65536
        internal fun hmac(key: ByteArray, message: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); doFinal(message)
        }
        private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
            val prk = hmac(salt, ikm)
            return try { hmac(prk, info + byteArrayOf(1)) } finally { prk.fill(0) }
        }
    }
}
