/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class PasskeyBlobReadTest {
    private fun hex(raw: String) = raw.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val hello = "hello".toByteArray(Charsets.US_ASCII)
    // Independent Python zlib level9/wbits=-15 fixtures; no production encoder roundtrip.
    private val compressedHello = hex("cb48cdc9c90700")
    private val compressedLimit = hex("73741c05a360148c8251300a46c1480300") // 2048 ASCII 'A'.
    private val compressedBomb = hex("edc1010d000000c2a06cef5fca1e0e28000000e0dd00") // 4096 ASCII 'A'.
    private fun secret(compressed: ByteArray = compressedHello, size: Long = 5): ByteArray =
        Proto.field(3, byteArrayOf(0, 1, 2, 0xff.toByte())) + Proto.field(4, compressed) + Proto.integer(5, size) +
            Proto.field(99, "opaque future secret metadata")

    @Test fun existingCredentialReadsReturnOnlyRequestedIndependentCopiesAndPreserveTheInput() {
        val encoded = secret(); val before = encoded.copyOf()
        PasskeyBlobReadRequest(credBlob = true, largeBlob = true).read(encoded).use { result ->
            assertArrayEquals(byteArrayOf(0, 1, 2, 0xff.toByte()), result.credBlob)
            assertArrayEquals(hello, result.largeBlob)
            result.credBlob!![0] = 9; result.largeBlob!![0] = 9
            assertArrayEquals(before, encoded)
            assertFalse(result.toString().contains("hello"))
        }
        assertArrayEquals(before, encoded)
    }

    @Test fun absentCredentialBlobIsAnEmptyRequestedValueWithoutAStorageCapabilityClaim() {
        PasskeyBlobReadRequest(true, true).read(Proto.field(1, "untouched private-key placeholder")).use {
            assertNotNull(it.credBlob); assertEquals(0, it.credBlob!!.size); assertNull(it.largeBlob)
        }
        PasskeyBlobReadRequest(false, false).read(secret()).use { assertNull(it.credBlob); assertNull(it.largeBlob) }
    }

    @Test fun unrequestedBlobFieldsAreNotDecodedOrExposed() {
        val malformedLarge = Proto.field(3, hello) + Proto.field(4, byteArrayOf(0xff.toByte())) + Proto.integer(5, -1)
        PasskeyBlobReadRequest(true, false).read(malformedLarge).use {
            assertArrayEquals(hello, it.credBlob); assertNull(it.largeBlob)
        }
        val duplicateCredential = secret() + Proto.field(3, "unrequested duplicate")
        PasskeyBlobReadRequest(false, true).read(duplicateCredential).use {
            assertNull(it.credBlob); assertArrayEquals(hello, it.largeBlob)
        }
    }

    @Test fun rawStoredBlockAndCompressedHuffmanFixturesHaveIndependentExpectedOutputs() {
        // RFC1951 stored-block oracle: final block, little-endian LEN/NLEN, literal bytes.
        val stored = hex("010500faff68656c6c6f")
        for (raw in listOf(stored, compressedHello)) PasskeyBlobReadRequest(false, true).read(secret(raw)).use {
            assertArrayEquals(hello, it.largeBlob)
        }
        PasskeyBlobReadRequest(false, true).read(secret(compressedLimit, 2048)).use {
            assertArrayEquals(ByteArray(2048) { 'A'.code.toByte() }, it.largeBlob)
        }
    }

    @Test fun validTwoKibibyteStoredDeflateBlockCanExceedTwoKibibytesOfCompressedInput() {
        val payload = ByteArray(2048) { (it * 73).toByte() }
        // RFC1951 final stored block: LEN=0x0800, NLEN=0xf7ff, then literal data.
        val stored = hex("010008fff7") + payload
        assertEquals(2053, stored.size)
        PasskeyBlobReadRequest(false, true).read(secret(stored, payload.size.toLong())).use {
            assertArrayEquals(payload, it.largeBlob)
        }
    }

    @Test fun actualEncryptedCredentialRemainsUsableWithFramingOverheadOrInvalidOptionalBlobData() {
        val pair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
        }
        val private = pair.private.encoded
        val domain = PasskeyDomainKey(7, ByteArray(32) { (it + 7).toByte() })
        val payload = ByteArray(2048) { (it * 73).toByte() }
        val stored = hex("010008fff7") + payload
        fun encrypted(compressed: ByteArray): SyncedPasskey {
            val plain = Proto.field(1, private) + secret(compressed, 2048)
            return try {
                SyncedPasskey.decode(Proto.field(1, ByteArray(16)) + Proto.field(2, byteArrayOf(1)) +
                    Proto.field(3, "example.invalid") + Proto.field(4, byteArrayOf(2)) + Proto.integer(14, 7) +
                    Proto.field(12, PasskeyCrypto.encrypt(plain, domain)))
            } finally { plain.fill(0) }
        }
        try {
            for ((compressed, expected) in listOf(stored to payload, ByteArray(8193) to null)) {
                PasskeyCrypto.decrypt(encrypted(compressed), listOf(domain)).use { opened ->
                    val key = opened.privateKey()
                    try { assertArrayEquals(private, key.encoded) } finally { runCatching { key.destroy() } }
                    val encoded = opened.encode()
                    try {
                        PasskeyBlobReadRequest(true, true).read(encoded).use {
                            assertArrayEquals(byteArrayOf(0, 1, 2, -1), it.credBlob)
                            if (expected == null) assertNull(it.largeBlob) else assertArrayEquals(expected, it.largeBlob)
                        }
                    } finally { encoded.fill(0) }
                }
            }
        } finally { domain.close(); private.fill(0); runCatching { pair.private.destroy() } }
    }

    @Test fun explicitlyEmptyRawDeflateBlobIsDifferentFromAnAbsentOrInvalidBlob() {
        PasskeyBlobReadRequest(false, true).read(secret(hex("0300"), 0)).use {
            assertNotNull(it.largeBlob); assertEquals(0, it.largeBlob!!.size)
        }
        for (encoded in listOf(Proto.integer(5, 0), secret(byteArrayOf(), 0), Proto.field(4, hex("0300")))) {
            PasskeyBlobReadRequest(false, true).read(encoded).use { assertNull(it.largeBlob) }
        }
    }

    @Test fun declaredSizeMustBeExplicitNonnegativeAndAtMostTwoKibibytes() {
        for (size in listOf(-1L, Long.MAX_VALUE, 2049L, 4L, 6L, 0L)) {
            PasskeyBlobReadRequest(true, true).read(secret(size = size)).use {
                assertNotNull(it.credBlob); assertNull(it.largeBlob)
            }
        }
        PasskeyBlobReadRequest(false, true).read(Proto.field(4, compressedHello)).use { assertNull(it.largeBlob) }
    }

    @Test fun expansionBombCannotExceedTheDeclaredBufferOrReportAPartialBlob() {
        for (size in listOf(0L, 1L, 2048L, 4096L)) PasskeyBlobReadRequest(false, true).read(secret(compressedBomb, size)).use {
            assertNull(it.largeBlob)
        }
    }

    @Test fun trailingBytesConcatenatedStreamsTruncationAndMalformedBlocksOmitOnlyTheLargeBlob() {
        val bad = listOf(compressedHello + byteArrayOf(0), compressedHello + compressedHello,
            compressedHello.copyOf(compressedHello.size - 1), byteArrayOf(7), hex("010500fbff68656c6c6f"))
        for (raw in bad) PasskeyBlobReadRequest(true, true).read(secret(raw)).use {
            assertNotNull(it.credBlob); assertNull(it.largeBlob)
        }
    }

    @Test fun zlibGzipAndExternalDictionaryStreamsAreNotAcceptedAsRawStandaloneDeflate() {
        val bad = listOf(hex("789ccb48cdc9c90700062c0215"),
            hex("1f8b0800000000000003cb48cdc9c9070086a6103605000000"),
            hex("cbc62f0d00")) // Independent Python raw stream using a preset dictionary.
        for (raw in bad) PasskeyBlobReadRequest(false, true).read(secret(raw)).use { assertNull(it.largeBlob) }
    }

    @Test fun duplicateLargeBlobFieldsCannotSelectAnAmbiguousValueOrSize() {
        for (encoded in listOf(secret() + Proto.field(4, compressedHello), secret() + Proto.integer(5, 5))) {
            PasskeyBlobReadRequest(true, true).read(encoded).use {
                assertNotNull(it.credBlob); assertNull(it.largeBlob)
            }
        }
        assertThrows(ProtocolException::class.java) { PasskeyBlobReadRequest(true, false).read(secret() + Proto.field(3, hello)) }
    }

    @Test fun malformedCompressedOrOversizeSecretInputsCannotAllocateUnboundedResults() {
        PasskeyBlobReadRequest(false, true).read(secret(ByteArray(PasskeyBlobReadRequest.MAX_SECRET_BYTES - 64), 2048))
            .use { assertNull(it.largeBlob) }
        assertThrows(ProtocolException::class.java) {
            PasskeyBlobReadRequest(true, true).read(ByteArray(PasskeyBlobReadRequest.MAX_SECRET_BYTES + 1))
        }
    }

    @Test fun closingTheResultWipesOwnedOutputBytesWithoutChangingTheSecret() {
        val encoded = secret(); val before = encoded.copyOf()
        val result = PasskeyBlobReadRequest(true, true).read(encoded)
        val credential = result.credBlob!!; val large = result.largeBlob!!
        result.close(); result.close()
        assertTrue(credential.all { it == 0.toByte() }); assertTrue(large.all { it == 0.toByte() })
        assertArrayEquals(before, encoded)
    }
}
