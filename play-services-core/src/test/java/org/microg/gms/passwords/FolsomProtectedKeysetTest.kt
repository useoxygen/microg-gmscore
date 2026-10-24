/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import com.google.crypto.tink.Aead
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.JsonKeysetReader
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.aead.AeadConfig
import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.folsom.utils.FolsomProtectedKeyset
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException

class FolsomProtectedKeysetTest {
    private fun handle(): KeysetHandle {
        AeadConfig.register()
        return KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
    }
    @Test fun legacyPlaintextIsRewrittenAndOnlyTheMasterKeyCanReadItWithoutChangingRecoveryKeys() {
        val original = handle()
        val aad = "domain-fixture".toByteArray()
        val oldCiphertext = original.getPrimitive(Aead::class.java).encrypt("synthetic key".toByteArray(), aad)
        val oldBytes = ByteArrayOutputStream().also { CleartextKeysetHandle.write(original, BinaryKeysetWriter.withOutputStream(it)) }.toByteArray()
        val loadedLegacy = CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(oldBytes))
        val master = handle().getPrimitive(Aead::class.java)
        val output = ByteArrayOutputStream()
        val recovered = FolsomProtectedKeyset.persist(loadedLegacy, master, BinaryKeysetWriter.withOutputStream(output)) {
            BinaryKeysetReader.withBytes(output.toByteArray())
        }
        assertArrayEquals("synthetic key".toByteArray(), recovered.decrypt(oldCiphertext, aad))
        assertTrue(original.equalsKeyset(KeysetHandle.read(BinaryKeysetReader.withBytes(output.toByteArray()), master)))
        assertThrows(Exception::class.java) { CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(output.toByteArray())) }
        val wrongMaster = handle().getPrimitive(Aead::class.java)
        assertThrows(GeneralSecurityException::class.java) { KeysetHandle.read(BinaryKeysetReader.withBytes(output.toByteArray()), wrongMaster) }
    }
    @Test fun failedMasterOrUnverifiedReadbackNeverProducesAUsableRecoveryAead() {
        val original = handle()
        val output = ByteArrayOutputStream()
        val unavailable = object : Aead {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = throw GeneralSecurityException()
            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray = throw GeneralSecurityException()
        }
        assertThrows(GeneralSecurityException::class.java) {
            FolsomProtectedKeyset.persist(original, unavailable, BinaryKeysetWriter.withOutputStream(output)) {
                BinaryKeysetReader.withBytes(output.toByteArray())
            }
        }
        assertEquals(0, output.size())
        val master = handle().getPrimitive(Aead::class.java)
        val other = ByteArrayOutputStream().also { handle().write(BinaryKeysetWriter.withOutputStream(it), master) }.toByteArray()
        assertThrows(GeneralSecurityException::class.java) {
            FolsomProtectedKeyset.persist(original, master, BinaryKeysetWriter.withOutputStream(output)) { BinaryKeysetReader.withBytes(other) }
        }
    }
    @Test fun standaloneLegacyJsonIsEncryptedAndExistingEncryptedJsonNeverFallsBackToPlaintext() {
        val legacy = handle()
        val master = handle().getPrimitive(Aead::class.java)
        var persisted = ByteArrayOutputStream().also { CleartextKeysetHandle.write(legacy, JsonKeysetWriter.withOutputStream(it)) }.toByteArray()
        var writes = 0
        val migrated = FolsomProtectedKeyset.readOrProtectLegacyJson(persisted, master) { encrypted ->
            writes++; persisted = encrypted; persisted
        }
        assertEquals(1, writes)
        val reopened = KeysetHandle.read(JsonKeysetReader.withBytes(persisted), master)
        assertTrue(legacy.equalsKeyset(reopened))
        val ciphertext = migrated.encrypt(byteArrayOf(1, 2, 3), byteArrayOf())
        val again = FolsomProtectedKeyset.readOrProtectLegacyJson(persisted, master) { throw AssertionError("Already encrypted") }
        assertArrayEquals(byteArrayOf(1, 2, 3), again.decrypt(ciphertext, byteArrayOf()))
        val original = persisted.copyOf()
        assertThrows(Exception::class.java) { FolsomProtectedKeyset.readOrProtectLegacyJson(persisted, handle().getPrimitive(Aead::class.java)) {
            throw AssertionError("Key loss must not replace encrypted JSON")
        } }
        assertArrayEquals(original, persisted)
    }
}
