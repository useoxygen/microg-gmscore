/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class PasswordDataTest {
    @Test fun fillRequiresBothWebAuthoritiesButStillSupportsAndroidFacets() {
        val item = PasswordData.create("https://example.invalid/", "https://EXAMPLE.invalid:443/login", "synthetic", "synthetic", 1234L)
        assertTrue(item.canFillRealm("https://example.invalid/"))
        assertFalse(item.canFillRealm("http://example.invalid/"))
        assertFalse(item.canFillRealm("https://example.invalid:8443/"))
        for (origin in listOf("", "https://other.invalid/", "https://user@example.invalid/", "https://example.invalid:999999/")) {
            val changed = PasswordData.decode(Proto.decode(item.encode()).replace(3, origin.toByteArray()).encode())
            assertFalse(changed.canFillRealm(item.realm))
        }
        val blocked = PasswordData.decode(Proto.decode(item.encode()).replaceInteger(12, 1).encode())
        assertFalse(blocked.canFillRealm(item.realm))
        val facet = "android://AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA@app.example/"
        assertTrue(PasswordData.create(facet, "", "synthetic", "synthetic", 1234L).canFillRealm(facet))
    }

    @Test fun aLegacyIvChangeCannotMoveACredentialToADifferentWebsite() {
        val key = NigoriKey.derive("synthetic fixture key".toByteArray())
        try {
            val good = "https://good.invalid/"
            val evil = "https://evil.invalid/"
            val original = PasswordData.create(good, good, "synthetic", "synthetic password", 1234L)
            val encrypted = Proto.decode(key.encrypt(original.encode()))
            val blob = encrypted.one(2)
            // Scheme field takes 2 bytes; realm tag and length take another 2. Its first
            // hostname characters are in block 1. The legacy MAC does not cover the IV.
            for (i in 8..11) blob[4 + i] = (blob[4 + i].toInt() xor good[i].code xor evil[i].code).toByte()
            val plaintext = key.decrypt(encrypted.replace(2, blob).encode())
            val changed = try { PasswordData.decode(plaintext) } finally { plaintext.fill(0) }
            assertEquals(evil, changed.realm)
            assertEquals(good, changed.origin)
            assertFalse(changed.canFillRealm(evil))
            assertTrue(original.canFillRealm(good))
        } finally { key.destroy() }
    }

    @Test fun apostrophesKeepTheSameIdentityAsChromiumAndGoogleServices() {
        // Golden derived independently from Chromium EscapePath's published character map.
        // GMS 26.11 berz/berh also preserve apostrophes in each client-tag component.
        val item = PasswordData.create("https://example.invalid/", "https://example.invalid/a'b",
            "o'connor: a|é%?", "synthetic", 1234L)
        assertEquals("KG3E7tJ49J6YW0DzQ7EBDU2QWbo=", item.tag())
    }

    private fun data(created: Long = 1234L, blocked: Boolean = false, issues: Boolean = false): PasswordData {
        val original = PasswordData.create("https://example.invalid/", "https://example.invalid/login", "synthetic", "test", created)
        var proto = Proto.decode(original.encode())
        if (blocked) proto = proto.replaceInteger(12, 1)
        if (issues) proto = proto.replace(19, byteArrayOf())
        return PasswordData.decode(proto.encode())
    }
    @Test fun chromiumPublishedPasswordFormTag() {
        // Chromium password_specifics.proto published web-form example; SHA-1 ClientTagHash format.
        val data = PasswordData.decode(Proto.field(2, "https://rsolomakhin.github.io/") +
            Proto.field(3, "https://rsolomakhin.github.io/autofill/") + Proto.field(5, "username") +
            Proto.field(6, "Superman") + Proto.field(7, "password") + Proto.field(8, "pwd"))
        assertEquals("Bkwp+YggKhJzUg3hRblMB1OQGGc=", data.tag())
    }
    @Test fun filtersFollowGoogleUpperThenLowerDateBounds() {
        val item = data()
        assertTrue(PasswordFilter("EXAMPLE", 1, 1, 1234L, 1234L).accepts(item))
        assertFalse(PasswordFilter(null, 0, 0, 1233L, null).accepts(item))
        assertFalse(PasswordFilter(null, 0, 0, null, 1235L).accepts(item))
        assertTrue(PasswordFilter(null, 2, 2, null, null).accepts(data(blocked = true, issues = true)))
        assertFalse(PasswordFilter(null, 1, 0, null, null).accepts(data(blocked = true)))
        assertFalse(PasswordFilter(null, 0, 1, null, null).accepts(data(issues = true)))
        assertThrows(ProtocolException::class.java) { PasswordFilter(null, 3, 0, null, null) }
    }
    @Test fun passwordEditPreservesUnknownFieldsAndClearsIssuesOnlyWhenChanged() {
        val original = Proto.decode(data(issues = true).encode()).replace(500, byteArrayOf(1, 2, 3)).encode()
        val item = PasswordData.decode(original)
        assertArrayEquals(original, item.withPassword("test", 2345L).encode())
        val changed = Proto.decode(item.withPassword("changed", 2345L).encode())
        assertArrayEquals(byteArrayOf(1, 2, 3), changed.one(500))
        assertNull(changed.optional(19))
        assertEquals((2345L + 11_644_473_600_000L) * 1000, changed.integer(20))
        assertEquals(item.tag(), PasswordData.decode(changed.encode()).tag())
    }
    @Test fun canonicalRealmsDoNotBroadenOriginAuthority() {
        assertEquals("https://example.invalid/", PasswordData.webRealm("https://EXAMPLE.invalid:443/login?q=x"))
        assertEquals("https://example.invalid:8443/", PasswordData.webRealm("https://example.invalid:8443/"))
        assertThrows(ProtocolException::class.java) { PasswordData.webRealm("https://user@example.invalid/") }
        assertThrows(ProtocolException::class.java) { PasswordData.webRealm("javascript:example.invalid") }
    }
}
