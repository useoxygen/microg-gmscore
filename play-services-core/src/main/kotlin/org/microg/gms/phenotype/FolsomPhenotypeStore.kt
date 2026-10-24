/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.phenotype

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.microg.gms.passwords.ProtocolException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Independent device scope, not a password account store. No-backup and AEAD protect opaque server tokens. */
internal class FolsomPhenotypeStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "folsom-phenotype-v1").apply {
        check(isDirectory || mkdirs()) { "Device configuration storage unavailable" }
    }
    private val file = AtomicFile(File(root, "configuration.bin"))
    fun <T> locked(authorize: () -> Unit = {}, action: (Session) -> T): T = synchronized(LOCK) {
        RandomAccessFile(File(root, "configuration.lock"), "rw").use { handle -> handle.channel.lock().use {
            authorize(); action(Session(authorize)).also { authorize() }
        } }
    }
    inner class Session(private val authorize: () -> Unit) {
        fun load(): ByteArray? {
            authorize()
            if (!exists()) return null
            val encrypted = file.openRead().use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    if (count > FolsomPhenotypeProtocol.MAX_BYTES + 29 - out.size()) throw ProtocolException()
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            try {
                if (encrypted.size < 29 || encrypted[0] != 1.toByte()) throw ProtocolException()
                return Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
                    updateAAD(AAD); doFinal(encrypted, 13, encrypted.size - 13)
                }.also { authorize() }
            } finally { encrypted.fill(0) }
        }
        fun save(raw: ByteArray) {
            authorize()
            FolsomPhenotypeProtocol.decode(raw)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(!exists())); cipher.updateAAD(AAD)
            check(cipher.iv.size == 12)
            val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(raw)
            val stream = file.startWrite()
            try { authorize(); stream.write(encrypted); authorize(); file.finishWrite(stream) }
            catch (failure: Throwable) { file.failWrite(stream); throw failure }
            finally { encrypted.fill(0) }
        }
    }
    private fun exists() = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Device configuration key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build())
            generateKey()
        }
    }
    override fun toString() = "FolsomPhenotypeStore[redacted]"
    companion object {
        private val LOCK = Any()
        private const val ALIAS = "microg.folsom.phenotype.v1"
        private val AAD = "microg.folsom.phenotype.v1".toByteArray()
    }
}
