/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class LeakCheckCryptoTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Independent Chromium145 encryption_utils_unittest.cc server/Java interoperability fixtures. */
    @Test fun publishedUsernameBucketAndScryptVectorsMatch() {
        assertArrayEquals(hex("3d70d37bfc1a3d8145e6c7a3a4d7927661c1e8df82bd0c9f619aa3c996ec4cb3"), LeakCheckCrypto.usernameHash("jonsnow"))
        assertArrayEquals(hex("3d70d340"), LeakCheckCrypto.bucket("jonsnow"))
        assertArrayEquals(hex("997ef676074ccdb4c8aeda1f723df9674c5b34cc2fea6b4d767bf283855573fd"),
            LeakCheckCrypto.passwordHash("user", "password123"))
    }

    @Test fun publishedFixedScalarCiphertextMatchesTheIndependentJavaVector() {
        val cipher = LeakCheckCipher.fromKey(hex("fdb02c8fffbd3188a536f1fe0da95f559b0baf6697f208850124b6ed586de89a"))
        try {
            val encrypted = cipher.encrypt("very_secret".toByteArray())
            assertArrayEquals(hex("0245136ada04ebc76e5f6e6f339cc8f6e84790c03ac04cdd8be99c193f25724a58"), encrypted)
            assertArrayEquals(LeakCheckCrypto.hashToPoint("very_secret".toByteArray()).getEncoded(true), cipher.unblind(encrypted))
        } finally { cipher.destroy() }
        assertThrows(LeakCheckCancelledException::class.java) { cipher.encrypt(byteArrayOf(1)) }
    }

    @Test fun canonicalizationFollowsGmsEmailValidationAndAsciiCaseRules() {
        assertEquals("foobar", LeakCheckCrypto.canonicalize("Foo.Bar@Example.COM"))
        assertEquals("foobar@com", LeakCheckCrypto.canonicalize("Foo.Bar@COM")) // Not a valid haps email.
        assertEquals("te@st@mailcom", LeakCheckCrypto.canonicalize("Te@St@Mail.COM"))
        assertEquals("user+tag", LeakCheckCrypto.canonicalize("User+Tag@Example.COM"))
        assertEquals("\"te@st\"", LeakCheckCrypto.canonicalize("\"Te@St\"@Example.COM"))
        assertEquals("Äbc", LeakCheckCrypto.canonicalize("Ä.BC"))
        assertEquals("", LeakCheckCrypto.canonicalize("..."))
    }

    @Test fun invalidScalarInfinityOffCurveAndNoncanonicalPointsAreRejected() {
        assertThrows(ProtocolException::class.java) { LeakCheckCipher.fromKey(ByteArray(32)) }
        assertThrows(ProtocolException::class.java) { LeakCheckCipher.fromKey(ByteArray(32) { 0xff.toByte() }) }
        val cipher = LeakCheckCipher.fromKey(byteArrayOf(1))
        try {
            for (raw in listOf(byteArrayOf(0), ByteArray(65).also { it[0] = 4 }, ByteArray(33) { 0xff.toByte() }, byteArrayOf(2) + ByteArray(32) { 0xff.toByte() }))
                assertThrows(ProtocolException::class.java) { cipher.unblind(raw) }
            val point = LeakCheckCrypto.parameters.g
            assertArrayEquals(point.getEncoded(true), cipher.unblind(point.getEncoded(false)))
        } finally { cipher.destroy() }
    }
}
