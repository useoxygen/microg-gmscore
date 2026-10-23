/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.folsom.Keys
import org.microg.gms.auth.folsom.utils.FolsomKeyProtection
import okio.ByteString.Companion.toByteString

class FolsomKeyProtectionTest {
    private val material = ByteArray(32) { (it + 1).toByte() }
    private fun primaryEncrypt(data: ByteArray, aad: ByteArray) = byteArrayOf(1) + data.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    private fun primaryDecrypt(data: ByteArray, aad: ByteArray): ByteArray {
        if (data.firstOrNull() != 1.toByte()) throw IllegalStateException()
        return data.drop(1).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
    @Test fun chromesyncIsSensitiveAndPlaintextMigrationKeepsExactKeysWithoutPlaintextCopies() {
        val protection = FolsomKeyProtection(::primaryEncrypt, ::primaryDecrypt)
        val legacy = listOf(Keys(keyVersion = 7, keyMaterial = material.toByteString(), keyMetadata = material.toByteString()))
        val read = protection.read("chromesync", legacy)
        assertTrue(read.migrate); assertArrayEquals(material, read.keys.single().keyMaterial!!.toByteArray())
        val encrypted = protection.protect("chromesync", read.keys).single()
        assertNull(encrypted.keyMaterial); assertNull(encrypted.keyMetadata)
        assertFalse(encrypted.encryptedKeyMaterial!!.toByteArray().contentEquals(material))
        val reopened = protection.read("chromesync", listOf(encrypted))
        assertFalse(reopened.migrate); assertArrayEquals(material, reopened.keys.single().keyMaterial!!.toByteArray())
        assertTrue(FolsomKeyProtection.sensitive("users/me/securitydomains/chromesync"))
        assertTrue(FolsomKeyProtection.sensitive("users/me/securitydomains/hw_protected"))
        assertFalse(FolsomKeyProtection.sensitive("not-chromesync"))
    }
    @Test fun legacyFallbackIsReadOnlyAndMigrationFailureCannotReplaceOriginalCiphertext() {
        val legacy = listOf(Keys(keyVersion = 7, encryptedKeyMaterial = (byteArrayOf(2) + material).toByteString()))
        var stored = legacy
        val protection = FolsomKeyProtection({ _, _ -> throw IllegalStateException("fixture keystore unavailable") },
            ::primaryDecrypt, { data, _ -> data.copyOfRange(1, data.size) })
        assertThrows(IllegalStateException::class.java) {
            val read = protection.read("chromesync", stored)
            assertTrue(read.migrate)
            stored = protection.protect("chromesync", read.keys)
        }
        assertSame(legacy, stored)
        val working = FolsomKeyProtection(::primaryEncrypt, ::primaryDecrypt, { data, _ -> data.copyOfRange(1, data.size) })
        val read = working.read("chromesync", stored)
        stored = working.protect("chromesync", read.keys)
        assertEquals(1.toByte(), stored.single().encryptedKeyMaterial!!.toByteArray()[0])
        assertArrayEquals(material, working.read("chromesync", stored).keys.single().keyMaterial!!.toByteArray())
    }
    @Test fun sensitiveEncryptionNeverCallsLegacyFallbackAndNewWritesFailClosed() {
        var legacyReads = 0
        val protection = FolsomKeyProtection({ _, _ -> throw IllegalStateException() }, ::primaryDecrypt,
            { _, _ -> legacyReads++; material.copyOf() })
        assertThrows(IllegalStateException::class.java) {
            protection.protect("hw_protected", listOf(Keys(keyVersion = 7, keyMaterial = material.toByteString())))
        }
        assertEquals(0, legacyReads)
    }
}
