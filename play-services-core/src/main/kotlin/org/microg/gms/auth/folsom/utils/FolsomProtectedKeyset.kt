/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom.utils

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.KeysetReader
import com.google.crypto.tink.KeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.JsonKeysetReader
import com.google.crypto.tink.JsonKeysetWriter
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException

/** AndroidKeysetManager may accept a legacy cleartext keyset even with a working master key. */
internal object FolsomProtectedKeyset {
    fun persist(handle: KeysetHandle, master: Aead, writer: KeysetWriter, reader: () -> KeysetReader): Aead {
        handle.write(writer, master)
        val persisted = KeysetHandle.read(reader(), master)
        if (!handle.equalsKeyset(persisted)) throw GeneralSecurityException("Recovery keyset changed during protection")
        return persisted.getPrimitive(Aead::class.java)
    }
    fun readOrProtectLegacyJson(raw: ByteArray, master: Aead, persist: (ByteArray) -> ByteArray): Aead {
        val encrypted = runCatching { KeysetHandle.read(JsonKeysetReader.withBytes(raw), master) }.getOrNull()
        if (encrypted != null) return encrypted.getPrimitive(Aead::class.java)
        val legacy = CleartextKeysetHandle.read(JsonKeysetReader.withBytes(raw))
        val output = ByteArrayOutputStream()
        legacy.write(JsonKeysetWriter.withOutputStream(output), master)
        val actual = persist(output.toByteArray())
        val reopened = KeysetHandle.read(JsonKeysetReader.withBytes(actual), master)
        if (!legacy.equalsKeyset(reopened)) throw GeneralSecurityException("Legacy recovery keyset changed during protection")
        return reopened.getPrimitive(Aead::class.java)
    }
}
