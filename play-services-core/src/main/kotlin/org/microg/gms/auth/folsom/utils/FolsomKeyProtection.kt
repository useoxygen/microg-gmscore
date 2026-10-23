/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom.utils

import okio.ByteString.Companion.toByteString
import org.microg.gms.auth.folsom.Keys

/** Legacy plaintext/fallback reads are migration inputs; every replacement requires the Keystore AEAD. */
internal class FolsomKeyProtection(private val encrypt: (ByteArray, ByteArray) -> ByteArray,
    private val decrypt: (ByteArray, ByteArray) -> ByteArray,
    private val legacyDecrypt: ((ByteArray, ByteArray) -> ByteArray)? = null) {
    class Read(val keys: List<Keys>, val migrate: Boolean)
    fun read(domain: String, stored: List<Keys>): Read {
        val protected = sensitive(domain)
        var migrate = false
        val keys = stored.map { key ->
            val encrypted = key.encryptedKeyMaterial
            if (encrypted != null && encrypted.size > 0) {
                val aad = domain.toByteArray(Charsets.UTF_8)
                val plain = try { decrypt(encrypted.toByteArray(), aad) } catch (failure: Exception) {
                    val fallback = legacyDecrypt ?: throw failure
                    fallback(encrypted.toByteArray(), aad).also { if (protected) migrate = true }
                }
                try {
                    val duplicate = key.keyMetadata?.toByteArray()?.contentEquals(plain) == true
                    if (protected && (key.keyMaterial != null || duplicate)) migrate = true
                    key.copy(keyMaterial = plain.toByteString(), encryptedKeyMaterial = null,
                        keyMetadata = if (duplicate) null else key.keyMetadata)
                } finally { plain.fill(0) }
            } else {
                val material = key.keyMaterial ?: key.keyMetadata
                if (!protected || material == null || material.size == 0) key else {
                    migrate = true
                    key.copy(keyMaterial = material, keyMetadata = if (key.keyMaterial == null || key.keyMetadata == material) null else key.keyMetadata)
                }
            }
        }
        return Read(keys, migrate)
    }
    fun protect(domain: String, keys: List<Keys>): List<Keys> {
        if (!sensitive(domain)) return keys
        val aad = domain.toByteArray(Charsets.UTF_8)
        return keys.map { key ->
            val material = key.keyMaterial ?: if (key.encryptedKeyMaterial == null) key.keyMetadata else null
            if (material == null || material.size == 0) key else {
                val plain = material.toByteArray()
                try { key.copy(keyMaterial = null, keyMetadata = if (key.keyMetadata == material) null else key.keyMetadata,
                    encryptedKeyMaterial = encrypt(plain, aad).toByteString()) }
                finally { plain.fill(0) }
            }
        }
    }
    companion object {
        private val domains = setOf("on_device_location_history", "passwords", "gpm_passkeys", "hw_protected", "chrome_signin", "chromesync")
        fun sensitive(domain: String): Boolean = domain.substringAfterLast('/') in domains
    }
}
