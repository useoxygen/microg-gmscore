/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class PasswordDataTest {
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
