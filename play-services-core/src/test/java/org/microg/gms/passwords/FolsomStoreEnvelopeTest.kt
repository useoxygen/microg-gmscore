/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.folsom.AccountData
import org.microg.gms.auth.folsom.FolsomKeyStore
import org.microg.gms.auth.folsom.KeyPair
import org.microg.gms.auth.folsom.utils.FolsomStoreEnvelope
import okio.ByteString.Companion.toByteString
import java.security.GeneralSecurityException

class FolsomStoreEnvelopeTest {
    private fun aead(): Aead {
        AeadConfig.register()
        return KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(Aead::class.java)
    }
    @Test fun completeLegacyDeviceKeysAndUnboundProvenanceRoundTripOnlyUnderTheCorrectKey() {
        val secret = "synthetic legacy private key material".toByteArray()
        val legacy = FolsomKeyStore(accounts = listOf(FolsomKeyStore.AccountsEntry(key = "fixture", value_ =
            AccountData(accountKeyPairs = listOf(KeyPair(privateKey = secret.toByteString())))))).encode()
        assertFalse(FolsomStoreEnvelope.protected(legacy))
        val key = aead()
        val sealed = FolsomStoreEnvelope.seal(legacy, key)
        assertTrue(FolsomStoreEnvelope.protected(sealed))
        assertFalse(sealed.toString(Charsets.ISO_8859_1).contains(secret.toString(Charsets.ISO_8859_1)))
        assertArrayEquals(legacy, FolsomStoreEnvelope.open(sealed, key))
        assertNull(FolsomKeyStore.ADAPTER.decode(FolsomStoreEnvelope.open(sealed, key)).accounts.single().value_!!.googleUserId)
        assertThrows(GeneralSecurityException::class.java) { FolsomStoreEnvelope.open(sealed, aead()) }
        val corrupted = sealed.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { FolsomStoreEnvelope.open(corrupted, key) }
        assertThrows(GeneralSecurityException::class.java) { FolsomStoreEnvelope.open(legacy, key) }
    }
}
