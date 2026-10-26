/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PasskeyPrfTest {
    private val credential = ByteArray(16) { (it + 1).toByte() }
    private val secret = ByteArray(32) { (it + 65).toByte() }
    private fun hmac(key: ByteArray, input: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(input)
    }
    private fun normalize(raw: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest("WebAuthn PRF\u0000".toByteArray(Charsets.UTF_8) + raw)
    @Test fun knownSha256HmacVectorAndLegacyInputsAreNotHashedTwice() {
        // RFC8448 section3 early-secret: HMAC-SHA256(zero32, zero32).
        val expected = "33ad0a1c607ec03b09e6cd9893680ce210adf300aa1f2660e1b22e10f170f92a"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val request = PasskeyPrfRequest.legacy(listOf(null, ByteArray(32)), false)
        request.evaluate(ByteArray(32), credential).use { result -> assertArrayEquals(expected, result.first); assertNull(result.second); assertFalse(result.enabled) }
        val raw = PasskeyPrfRequest.normalized(PasskeyPrfValues.raw(ByteArray(32)), create = false)
        raw.evaluate(ByteArray(32), credential).use { assertFalse(expected.contentEquals(it.first)) }
    }
    @Test fun rawWebAuthnFirstAndSecondUseIndependentDomainSeparatedHmacInputs() {
        val first = "synthetic-first".toByteArray(); val second = "synthetic-second".toByteArray()
        val request = PasskeyPrfRequest.normalized(PasskeyPrfValues.raw(first, second), create = true)
        request.evaluate(secret, credential).use { result ->
            assertTrue(result.enabled)
            assertArrayEquals(hmac(secret, normalize(first)), result.first)
            assertArrayEquals(hmac(secret, normalize(second)), result.second)
            assertFalse(hmac(secret, first).contentEquals(result.first))
            assertFalse(result.first.contentEquals(result.second))
        }
        // Empty BufferSource is valid and must still get the domain prefix and hash.
        PasskeyPrfRequest.normalized(PasskeyPrfValues.raw(byteArrayOf()), create = false).evaluate(secret, credential).use {
            assertArrayEquals(hmac(secret, normalize(byteArrayOf())), it.first)
        }
    }
    @Test fun actualCredentialSpecificInputsOverrideFallbackWithoutUsingAnotherAccountsCredential() {
        val fallback = ByteArray(32) { 1 }; val selected = ByteArray(32) { 2 }
        val request = PasskeyPrfRequest.legacy(listOf(null, fallback, credential, selected + selected), false)
        request.evaluate(secret, credential).use { assertArrayEquals(hmac(secret, selected), it.first); assertArrayEquals(hmac(secret, selected), it.second) }
        request.evaluate(secret, byteArrayOf(99)).use { assertArrayEquals(hmac(secret, fallback), it.first); assertNull(it.second) }
        val specificOnly = PasskeyPrfRequest.legacy(listOf(credential, selected), false)
        specificOnly.evaluate(secret, byteArrayOf(99)).use { assertNull(it.first); assertNull(it.second) }
    }
    @Test fun malformedLegacyEntriesAndCreationSelectorsCannotProduceAnOutput() {
        val invalid = listOf(listOf<ByteArray?>(null), listOf(null, null), listOf(null, ByteArray(31)), listOf(null, ByteArray(33)),
            listOf(null, ByteArray(63)), listOf(null, ByteArray(65)), listOf(null, ByteArray(32), null, ByteArray(32)),
            listOf(byteArrayOf(), ByteArray(32)), listOf(ByteArray(1025), ByteArray(32)),
            listOf(credential, ByteArray(32), credential.copyOf(), ByteArray(32)))
        for (entry in invalid) assertThrows(ProtocolException::class.java) { PasskeyPrfRequest.legacy(entry, false) }
        assertThrows(ProtocolException::class.java) { PasskeyPrfRequest.legacy(listOf(credential, ByteArray(32)), true) }
        val request = PasskeyPrfRequest.legacy(emptyList(), true)
        request.evaluate(secret, credential).use { assertTrue(it.enabled); assertNull(it.first) }
        assertThrows(ProtocolException::class.java) { request.evaluate(ByteArray(31), credential) }
        assertThrows(ProtocolException::class.java) { request.evaluate(secret, byteArrayOf()) }
    }
    @Test fun realSecretDeterminesOutputAndAPublicCredentialIdCannotSubstituteForIt() {
        val request = PasskeyPrfRequest.legacy(listOf(null, ByteArray(32)), false)
        val actual = request.evaluate(secret, credential)
        val otherId = request.evaluate(secret, ByteArray(16) { 42 })
        val fakePublicSecret = MessageDigest.getInstance("SHA-256").digest(credential)
        try {
            assertArrayEquals(actual.first, otherId.first)
            assertFalse(hmac(fakePublicSecret, ByteArray(32)).contentEquals(actual.first))
            request.evaluate(ByteArray(32) { 9 }, credential).use { assertFalse(actual.first.contentEquals(it.first)) }
            assertEquals("PasskeyPrfResult[redacted]", actual.toString())
        } finally { actual.close(); otherId.close(); fakePublicSecret.fill(0) }
        assertTrue(actual.first!!.all { it == 0.toByte() })
    }
    @Test fun onlyAuthenticatedExistingEncryptedFieldTwoCanSupplyPrfAndUnknownFieldsSurvive() {
        val domain = PasskeyDomainKey(7, ByteArray(32) { 8 })
        fun item(field: Int, ciphertext: ByteArray) = SyncedPasskey.decode(Proto.field(1, ByteArray(16)) +
            Proto.field(2, credential) + Proto.field(3, "example.invalid") + Proto.field(4, byteArrayOf(1)) +
            Proto.integer(14, 7) + Proto.field(field, ciphertext))
        val keyPair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
        val private = keyPair.private.encoded
        val plain = Proto.field(1, private) + Proto.field(2, secret) + Proto.field(93, "opaque-future")
        val request = PasskeyPrfRequest.legacy(listOf(null, ByteArray(32)), false)
        try {
            PasskeyCrypto.decrypt(item(12, PasskeyCrypto.encrypt(plain, domain)), listOf(domain)).use { opened ->
                val restored = opened.privateKey()
                try { assertEquals("EC", restored.algorithm) } finally { runCatching { restored.destroy() } }
                opened.prf(request, credential).use { assertArrayEquals(hmac(secret, ByteArray(32)), it.first) }
                assertEquals("opaque-future", Proto.decode(opened.encode()).string(93))
            }
            PasskeyCrypto.decrypt(item(12, PasskeyCrypto.encrypt(Proto.field(1, private), domain)), listOf(domain)).use { missing ->
                assertThrows(VaultLockedException::class.java) { missing.prf(request, credential) }
            }
            // A legacy private-key-only record can never invent a PRF seed on retrieval.
            PasskeySecret(private.copyOf(), null).use { legacy -> assertThrows(VaultLockedException::class.java) { legacy.prf(request, credential) } }
        } finally { private.fill(0); plain.fill(0); runCatching { keyPair.private.destroy() }; domain.close() }
    }
}
