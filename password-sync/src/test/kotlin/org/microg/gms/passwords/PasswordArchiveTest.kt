/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class PasswordArchiveTest {
    private fun item() = PasswordData.create("https://fixture.invalid/", "https://fixture.invalid/", "fixture-user", "fixture-secret", 1234L)
        .withDefaultNote("fixture note", 1234L)
    @Test fun encryptedBackupRoundTripsNotesUnknownFieldsAndBlockedSitesWithoutPlaintext() {
        val data = PasswordData.decode(Proto.decode(item().encode()).replace(500, byteArrayOf(1, 2)).encode())
        val blocked = PasswordData.blockedSite("https://blocked.invalid/", 1234L)
        val passphrase = "synthetic archive passphrase".toCharArray()
        val archive = PasswordArchive.encrypt(listOf(data, blocked), passphrase)
        assertFalse(String(archive, Charsets.ISO_8859_1).contains("fixture-secret"))
        assertFalse(String(archive, Charsets.ISO_8859_1).contains("fixture-user"))
        val restored = PasswordArchive.decrypt(archive, passphrase)
        assertArrayEquals(data.encode(), restored[0].encode()); assertArrayEquals(blocked.encode(), restored[1].encode())
        assertFalse(archive.contentEquals(PasswordArchive.encrypt(listOf(data, blocked), passphrase)))
    }
    @Test fun wrongPassphraseAndHeaderOrCiphertextTamperingCannotProduceAnImport() {
        val passphrase = "synthetic archive passphrase".toCharArray()
        val archive = PasswordArchive.encrypt(listOf(item()), passphrase)
        assertThrows(VaultLockedException::class.java) { PasswordArchive.decrypt(archive, "a different fixture password".toCharArray()) }
        for (index in listOf(7, 23, archive.size - 1)) {
            val changed = archive.copyOf(); changed[index] = (changed[index].toInt() xor 1).toByte()
            assertThrows(VaultLockedException::class.java) { PasswordArchive.decrypt(changed, passphrase) }
        }
        assertThrows(ProtocolException::class.java) { PasswordArchive.encrypt(listOf(item()), "short".toCharArray()) }
        assertThrows(ProtocolException::class.java) { PasswordArchive.decrypt(ByteArray(20), passphrase) }
    }
    @Test fun duplicateIdentitiesAreRefusedInsteadOfSilentlyDroppingAPassword() {
        val passphrase = "synthetic archive passphrase".toCharArray()
        val archive = PasswordArchive.encrypt(listOf(item(), item().withPassword("other fixture", 2345L)), passphrase)
        assertThrows(ConflictException::class.java) { PasswordArchive.decrypt(archive, passphrase) }
    }
}
