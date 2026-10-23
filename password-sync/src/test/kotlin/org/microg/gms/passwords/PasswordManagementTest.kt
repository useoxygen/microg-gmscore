/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class PasswordManagementTest {
    private fun item() = PasswordData.create("https://example.invalid/", "https://example.invalid/login", "fixture-user", "fixture-password", 1234L)
    @Test fun noteAndDisplayNameEditsKeepIdentityIssuesTimestampsAndUnknownFields() {
        val named = Proto.field(1, "other-note") + Proto.field(2, "other-value") + Proto.field(500, byteArrayOf(9))
        val unnamed = Proto.field(1, "") + Proto.field(2, "old-value") + Proto.integer(3, 7) + Proto.integer(4, 1) + Proto.field(501, byteArrayOf(8))
        val container = Proto.field(1, named) + Proto.field(1, unnamed) + Proto.field(600, byteArrayOf(6))
        val raw = Proto.decode(item().encode()).replace(19, Proto.field(1, byteArrayOf())).replace(22, container).replace(700, byteArrayOf(7)).encode()
        val before = PasswordData.decode(raw)
        val after = before.withDisplayName("Fixture name").withDefaultNote("new-value", 2345L)
        assertEquals(before.tag(), after.tag())
        assertEquals(before.password, after.password)
        assertEquals(Proto.decode(raw).integer(20), Proto.decode(after.encode()).integer(20))
        assertArrayEquals(Proto.decode(raw).one(19), Proto.decode(after.encode()).one(19))
        assertArrayEquals(byteArrayOf(7), Proto.decode(after.encode()).one(700))
        val notes = Proto.decode(Proto.decode(after.encode()).one(22))
        assertArrayEquals(named, notes.bytes(1).first())
        assertArrayEquals(byteArrayOf(6), notes.one(600))
        assertArrayEquals(byteArrayOf(8), Proto.decode(notes.bytes(1).last()).one(501))
        assertEquals(7L, after.defaultNote!!.createdWindowsMicros)
        assertTrue(after.defaultNote!!.hiddenByDefault)
        assertEquals("Fixture name", after.displayName)
    }
    @Test fun clearingLastNoteLeavesAuthoritativeEmptyMessageAndNoOpEditsAreByteExact() {
        val before = item()
        assertArrayEquals(before.encode(), before.withDisplayName("").withDefaultNote("", 2345L).encode())
        val noted = before.withDefaultNote("fixture note", 2345L)
        assertEquals((2345L + 11_644_473_600_000L) * 1000, noted.defaultNote!!.createdWindowsMicros)
        assertArrayEquals(noted.encode(), noted.withDefaultNote("fixture note", 3456L).encode())
        val cleared = noted.withDefaultNote("", 3456L)
        assertNotNull(Proto.decode(cleared.encode()).optional(22))
        assertEquals(0, Proto.decode(cleared.encode()).one(22).size)
        assertTrue(cleared.notes.isEmpty())
        assertEquals(before.tag(), cleared.tag())
    }
    @Test fun ambiguousNotesCannotBeSilentlyOverwrittenAndNotesNeverEnterSearch() {
        val note = Proto.field(1, "") + Proto.field(2, "fixture-note-secret")
        val bad = PasswordData.decode(Proto.decode(item().encode()).replace(22, Proto.field(1, note) + Proto.field(1, note)).encode())
        assertThrows(ProtocolException::class.java) { bad.withDefaultNote("changed", 2345L) }
        val data = item().withDisplayName("Fixture Name").withDefaultNote("fixture-note-secret", 2345L)
        assertTrue(data.matchesSearch("EXAMPLE")); assertTrue(data.matchesSearch("fixture-user")); assertTrue(data.matchesSearch("NAME"))
        assertFalse(data.matchesSearch("fixture-password")); assertFalse(data.matchesSearch("fixture-note-secret"))
    }
    @Test fun blockedSitesHaveCanonicalIdentityNoSecretAndCanNeverFill() {
        val data = PasswordData.blockedSite("https://EXAMPLE.invalid:443/path", 1234L)
        assertEquals("https://example.invalid/", data.realm)
        assertEquals(data.realm, data.origin)
        assertTrue(data.blocked); assertEquals("", data.password); assertEquals("", data.username)
        assertFalse(data.canFillRealm(data.realm))
        assertFalse(data.withBlocked(false).canFillRealm(data.realm))
        assertThrows(ProtocolException::class.java) { PasswordData.blockedSite("javascript:example.invalid", 1234L) }
    }
}
