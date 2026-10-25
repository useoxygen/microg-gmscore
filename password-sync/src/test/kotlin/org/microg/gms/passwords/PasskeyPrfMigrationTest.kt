/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PasskeyPrfMigrationTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun publicP256ScalarOnePkcs8HasAnIndependentLiteralMigrationVector() {
        // Public test key d=1 and the standard P-256 generator, not a real credential.
        val raw = hex("308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420" +
            "00".repeat(31) + "01a14403420004" +
            "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
            "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")
        // Computed independently with Python stdlib hmac/hashlib, not the implementation's JCA path.
        val expected = hex("1329b8f608bd4a4abe1f8a90cf1e6991defe1780b28b27978562deb3b38ecf3a")
        assertArrayEquals(expected, Proto.decode(PasskeyPrfMigration.upgrade(Proto.field(1, raw))!!).one(2))
    }
    private fun key() = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
    private fun expected(pkcs8: ByteArray): ByteArray {
        // Independent HKDF extraction and one expand block, matching pinned iskq/cged constants.
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = extract.doFinal(pkcs8)
        return try {
            val expand = Mac.getInstance("HmacSHA256")
            expand.init(SecretKeySpec(prk, "HmacSHA256"))
            expand.update("derived PRF HMAC secret".toByteArray(Charsets.UTF_8)); expand.doFinal(byteArrayOf(1))
        } finally { prk.fill(0) }
    }
    @Test fun actualPkcs8DerivesTheSourceSecretAndRetainsEveryOtherEncryptedField() {
        val pair = key(); val raw = pair.private.encoded
        val before = Proto.field(1, raw) + Proto.field(3, byteArrayOf(4, 5)) + Proto.field(4, byteArrayOf(6, 7)) +
            Proto.integer(5, 99) + Proto.field(91, "opaque-future") + Proto.integer(92, 1234)
        try {
            val migrated = PasskeyPrfMigration.upgrade(before)!!
            val parsed = Proto.decode(migrated)
            assertArrayEquals(expected(raw), parsed.one(2))
            assertArrayEquals(before, parsed.replace(2, null).encode())
            assertNull(PasskeyPrfMigration.upgrade(migrated))
            assertArrayEquals(migrated, Proto.decode(migrated).encode())
        } finally { raw.fill(0); before.fill(0); runCatching { pair.private.destroy() } }
    }
    @Test fun emptyAndAbsentFieldsProduceTheSameStableSecretAcrossRetriesWithoutARandomReplacement() {
        val pair = key(); val raw = pair.private.encoded
        try {
            val absent = PasskeyPrfMigration.upgrade(Proto.field(1, raw))!!
            val empty = PasskeyPrfMigration.upgrade(Proto.field(1, raw) + Proto.field(2, byteArrayOf()))!!
            val reopened = PasskeyPrfMigration.upgrade(Proto.decode(Proto.field(1, raw)).encode())!!
            assertArrayEquals(Proto.decode(absent).one(2), Proto.decode(empty).one(2))
            assertArrayEquals(Proto.decode(absent).one(2), Proto.decode(reopened).one(2))
            val other = key()
            try { assertFalse(Proto.decode(absent).one(2).contentEquals(Proto.decode(PasskeyPrfMigration.upgrade(Proto.field(1, other.private.encoded))!!).one(2))) }
            finally { runCatching { other.private.destroy() } }
        } finally { raw.fill(0); runCatching { pair.private.destroy() } }
    }
    @Test fun existingSecretsAreNeverReplacedAndMalformedKeysOrFieldsAreRefused() {
        val pair = key(); val raw = pair.private.encoded
        try {
            val known = ByteArray(32) { 9 }
            assertNull(PasskeyPrfMigration.upgrade(Proto.field(1, raw) + Proto.field(2, known)))
            for (size in listOf(1, 31, 33, 64)) assertThrows(ProtocolException::class.java) {
                PasskeyPrfMigration.upgrade(Proto.field(1, raw) + Proto.field(2, ByteArray(size)))
            }
            for (invalid in listOf(byteArrayOf(), ByteArray(31), ByteArray(64), ByteArray(4097)))
                assertThrows(ProtocolException::class.java) { PasskeyPrfMigration.upgrade(Proto.field(1, invalid)) }
            assertThrows(ProtocolException::class.java) { PasskeyPrfMigration.upgrade(Proto.field(1, raw) + Proto.field(1, raw)) }
            assertThrows(ProtocolException::class.java) { PasskeyPrfMigration.upgrade(Proto.field(1, raw) + Proto.field(2, known) + Proto.field(2, known)) }
        } finally { raw.fill(0); runCatching { pair.private.destroy() } }
    }
}
