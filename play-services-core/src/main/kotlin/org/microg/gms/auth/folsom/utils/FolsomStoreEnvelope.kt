/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom.utils

import com.google.crypto.tink.Aead
import java.security.GeneralSecurityException

/** Covers quarantined legacy keys, device private keypairs and account provenance as one authenticated record. */
internal object FolsomStoreEnvelope {
    private val magic = byteArrayOf(0x46, 0x4b, 0x53, 0x32)
    private val aad = "microg.folsom.store.v2".toByteArray(Charsets.UTF_8)
    const val MAX_BYTES = 16 * 1024 * 1024
    fun protected(raw: ByteArray): Boolean = raw.size >= magic.size && raw.copyOfRange(0, magic.size).contentEquals(magic)
    fun seal(plain: ByteArray, aead: Aead): ByteArray {
        if (plain.size > MAX_BYTES) throw GeneralSecurityException("Recovery store too large")
        return magic + aead.encrypt(plain, aad)
    }
    fun open(raw: ByteArray, aead: Aead): ByteArray {
        if (!protected(raw) || raw.size > MAX_BYTES + 1024) throw GeneralSecurityException("Invalid recovery envelope")
        return aead.decrypt(raw.copyOfRange(magic.size, raw.size), aad).also {
            if (it.size > MAX_BYTES) { it.fill(0); throw GeneralSecurityException("Recovery store too large") }
        }
    }
}
