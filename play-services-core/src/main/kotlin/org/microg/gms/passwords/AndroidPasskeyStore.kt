/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** No-backup, account-isolated AEAD state. A missing key for an existing ciphertext is never recreated. */
internal class AndroidPasskeyStore(context: Context, identity: String, private val verify: () -> Unit) {
    private val root = if (Build.VERSION.SDK_INT < 23) throw VaultLockedException() else File(context.noBackupFilesDir, "synced-passkeys-v1").apply {
        if (!isDirectory && !mkdirs()) throw VaultLockedException()
    }
    private val id = marker(identity)
    private val file = AtomicFile(File(root, "$id.bin"))
    private val alias = "microg.passkeys.v1.$id"
    private val aad = "microg.passkeys.v1:$id".toByteArray(Charsets.UTF_8)
    fun <T> locked(action: (Session) -> T): T = synchronized(LOCKS.getOrPut(id) { Any() }) {
        RandomAccessFile(File(root, "$id.lock"), "rw").use { lock -> lock.channel.lock().use {
            verify()
            action(Session()).also { verify() }
        } }
    }
    inner class Session {
        fun load(): ByteArray {
            verify()
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return byteArrayOf()
            val data = file.openRead().use { input ->
                val bytes = input.readBytesBounded(Proto.MAX_BYTES + 29)
                if (bytes.size < 29 || bytes[0] != 1.toByte()) throw ProtocolException()
                bytes
            }
            return try { Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, data.copyOfRange(1, 13)))
                updateAAD(aad); doFinal(data, 13, data.size - 13)
            }.also { verify() } } catch (_: Exception) { throw VaultLockedException() } finally { data.fill(0) }
        }
        fun save(plain: ByteArray) {
            verify()
            if (plain.size > Proto.MAX_BYTES) throw ProtocolException()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val exists = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
            cipher.init(Cipher.ENCRYPT_MODE, key(!exists)); cipher.updateAAD(aad)
            if (cipher.iv.size != 12) throw ProtocolException()
            val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
            val stream = file.startWrite()
            try { verify(); stream.write(encrypted); verify(); file.finishWrite(stream) }
            catch (failure: Throwable) { file.failWrite(stream); throw failure }
            finally { encrypted.fill(0) }
        }
    }
    private fun key(create: Boolean): SecretKey {
        if (Build.VERSION.SDK_INT < 23) throw VaultLockedException()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw VaultLockedException()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build())
            generateKey()
        }
    }
    override fun toString(): String = "AndroidPasskeyStore[redacted]"
    companion object {
        private val LOCKS = ConcurrentHashMap<String, Any>()
        private fun marker(identity: String) = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        /** Caller must re-read stable account IDs while holding each file lock before erasing its account key. */
        fun purge(context: Context, active: () -> Set<String>) {
            // This store cannot have been created without the Android Keystore AES API.
            if (Build.VERSION.SDK_INT < 23) return
            val root = File(context.noBackupFilesDir, "synced-passkeys-v1")
            for (file in root.listFiles().orEmpty().filter { Regex("[0-9a-f]{64}\\.bin(?:\\.bak)?").matches(it.name) }) {
                val id = file.name.substringBefore('.')
                synchronized(LOCKS.getOrPut(id) { Any() }) {
                    RandomAccessFile(File(root, "$id.lock"), "rw").use { lock -> lock.channel.lock().use {
                        if (id !in active().map(::marker)) {
                            AtomicFile(File(root, "$id.bin")).delete()
                            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("microg.passkeys.v1.$id") }
                        }
                    } }
                }
            }
        }
        private fun java.io.InputStream.readBytesBounded(max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = read(buffer)
                if (count < 0) return out.toByteArray()
                if (count > max - out.size()) throw ProtocolException()
                out.write(buffer, 0, count)
            }
        }
    }
}
