/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class PasswordResourceCodecTest {
    private fun item() = PasswordData.create("https://example.invalid/", "https://example.invalid/", "fixture", "before", 1234L)
    private fun <T> withKeys(action: (VaultKeys) -> T): T {
        val key = NigoriKey.derive("fixture key".toByteArray())
        val exported = key.export()
        val metadata = Proto.field(1, "users/me/birthdays/fixture") + Proto.field(3,
            Proto.field(1, key.encrypt(Proto.field(2, exported))) + Proto.integer(2, 4))
        val keys = VaultKeys.unlock(metadata, customKey = exported)
        exported.fill(0); key.destroy()
        return try { action(keys) } finally { keys.destroy() }
    }
    @Test fun notesBackupAndServerMetadataFollowTheSameEditWithoutReplacingVersions() = withKeys { keys ->
        val original = item()
        val data = PasswordData.decode(Proto.decode(original.encode()).replaceInteger(18, 777)
            .replace(19, Proto.field(1, Proto.integer(1, 1))).encode()).withDefaultNote("fixture note", 2345L)
        val base = Proto.field(1, "users/me/birthdays/fixture/passwords/1") + Proto.field(2, data.tag()) +
            Proto.field(5, byteArrayOf(7)) + Proto.field(8, Proto.field(500, byteArrayOf(8))) + Proto.field(600, byteArrayOf(9))
        val record = PasswordResourceCodec.encrypt(data, keys, base)
        val proto = Proto.decode(record)
        assertArrayEquals(data.encodedNotes, keys.decrypt(proto.one(10)))
        val metadata = Proto.decode(proto.one(8))
        assertEquals(data.realm, metadata.string(3)); assertEquals(0L, metadata.integer(5))
        assertEquals(777L, metadata.integer(6)); assertEquals(3L, metadata.integer(8))
        assertArrayEquals(Proto.decode(data.encode()).one(19), metadata.one(7))
        assertArrayEquals(byteArrayOf(8), metadata.one(500)); assertArrayEquals(byteArrayOf(9), proto.one(600))
        assertArrayEquals(byteArrayOf(7), proto.one(5))
        val changed = PasswordResourceCodec.encrypt(data.withPassword("after", 3456L), keys, record)
        assertNull(Proto.decode(Proto.decode(changed).one(8)).optional(7))
        assertEquals("fixture note", PasswordResourceCodec.read(changed, keys).defaultNote!!.value)
    }
    @Test fun absentNotesUseBackupButExplicitEmptyNotesCannotResurrectIt() = withKeys { keys ->
        val original = item()
        val notes = original.withDefaultNote("legacy note", 2345L).encodedNotes!!
        val record = Proto.field(2, original.tag()) + Proto.field(6, keys.encrypt(original.encode())) + Proto.field(10, keys.encrypt(notes))
        assertEquals("legacy note", PasswordResourceCodec.read(record, keys).defaultNote!!.value)
        val empty = Proto.decode(original.encode()).replace(22, byteArrayOf()).encode()
        val explicit = Proto.decode(record).replace(6, keys.encrypt(empty)).encode()
        assertTrue(PasswordResourceCodec.read(explicit, keys).notes.isEmpty())
        val rotated = Proto.decode(PasswordResourceCodec.reencrypt(explicit, keys))
        assertEquals(0, keys.decrypt(rotated.one(10)).size)
        val exact = PasswordResourceCodec.encrypt(original, keys, record)
        assertNull(Proto.decode(exact).optional(10))
        assertArrayEquals(original.encode(), PasswordResourceCodec.read(exact, keys).encode())
    }
    @Test fun olderPartialUpdatesKeepUnsupportedFieldsAndOmittedNotesWhileExplicitDeletionWins() {
        val oldNote = Proto.field(1, "") + Proto.field(2, "old note") + Proto.integer(3, 8) + Proto.integer(4, 1) + Proto.field(500, byteArrayOf(9))
        val previous = PasswordData.decode(Proto.decode(item().encode()).replace(22, Proto.field(1, oldNote)).replace(600, byteArrayOf(7)).encode())
        val incoming = item().withPassword("after", 2345L)
        val merged = previous.mergeFrom(incoming)
        assertEquals("old note", merged.defaultNote!!.value)
        assertArrayEquals(byteArrayOf(7), Proto.decode(merged.encode()).one(600))
        val partialNote = Proto.field(1, "") + Proto.field(2, "new note")
        val edited = previous.mergeFrom(PasswordData.decode(Proto.decode(item().encode()).replace(22, Proto.field(1, partialNote)).encode()))
        assertTrue(edited.defaultNote!!.hiddenByDefault)
        assertEquals(8L, edited.defaultNote!!.createdWindowsMicros)
        assertArrayEquals(byteArrayOf(9), Proto.decode(edited.defaultNote!!.encode()).one(500))
        val deleted = previous.mergeFrom(PasswordData.decode(Proto.decode(item().encode()).replace(22, byteArrayOf()).encode()))
        assertTrue(deleted.notes.isEmpty()); assertNotNull(deleted.encodedNotes)
    }
    @Test fun changedPasswordsKeepOnlyExplicitIncomingPhishingIssuesAndBlockedWritesRequireEmptyUsername() = withKeys { keys ->
        val issues = Proto.field(1, Proto.integer(1, 1)) + Proto.field(4, Proto.integer(1, 2))
        val previous = PasswordData.decode(Proto.decode(item().encode()).replace(19, issues).encode())
        val incoming = PasswordData.decode(Proto.decode(previous.encode()).replace(8, "after".toByteArray()).encode())
        val merged = Proto.decode(previous.mergeFrom(incoming).encode())
        assertNull(Proto.decode(merged.one(19)).optional(1))
        assertArrayEquals(Proto.integer(1, 2), Proto.decode(merged.one(19)).one(4))
        assertThrows(ProtocolException::class.java) { PasswordResourceCodec.encrypt(item().withBlocked(true), keys) }
        Unit
    }
}
