/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Per-account durable transactions; no vault plaintext or keys enter SQLite/WAL/journal files. */
class AndroidVaultStore private constructor(context: Context, identity: String, private val verifyAccess: () -> Unit,
    alreadyHashed: Boolean, private val verifyState: (VaultState) -> Unit) : VaultStore {
    constructor(context: Context, identity: String, verifyAccess: () -> Unit = {}) : this(context, identity, verifyAccess, false, {})
    constructor(context: Context, identity: String, verifyAccess: () -> Unit,
        verifyState: (VaultState) -> Unit) : this(context, identity, verifyAccess, false, verifyState)
    private val context = context.applicationContext
    private val id = if (alreadyHashed) identity else MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val alias = "microg.passwords.v1.$id"
    private val aad = ("microg.passwords.state.v1:" + id).toByteArray(Charsets.UTF_8)
    private val database = database(this.context)

    val enabled: Boolean get() = database.readableDatabase.query("accounts", arrayOf("enabled"), "account_id=?",
        arrayOf(id), null, null, null).use { it.moveToFirst() && it.getInt(0) == 1 }
    fun registerAccount() = locked {
        verifyAccess()
        it.load()
        val values = ContentValues().apply { put("account_id", id); put("enabled", 0) }
        database.writableDatabase.insertWithOnConflict("accounts", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        Unit
    }
    fun setEnabled(value: Boolean) = locked {
        verifyAccess()
        it.load()
        val values = ContentValues().apply { put("account_id", id); put("enabled", if (value) 1 else 0) }
        if (database.writableDatabase.insertWithOnConflict("accounts", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0) throw VaultLockedException()
        Unit
    }

    override fun <T> locked(action: (VaultSession) -> T): T = synchronized(LOCKS.getOrPut(id) { Any() }) {
        if (Build.VERSION.SDK_INT < 23) throw VaultLockedException()
        val directory = File(this.context.noBackupFilesDir, "password-vault-locks")
        if (!directory.isDirectory && !directory.mkdirs()) throw IllegalStateException("Vault lock unavailable")
        RandomAccessFile(File(directory, "$id.lock"), "rw").use { file ->
            file.channel.lock().use {
                action(object : VaultSession {
                    override fun load(): VaultState {
                        verifyAccess()
                        val ciphertext = database.readableDatabase.query("vaults", arrayOf("ciphertext"), "account_id=?",
                            arrayOf(id), null, null, null).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null }
                        if (ciphertext == null) return VaultState().also(verifyState)
                        val plain = decrypt(ciphertext)
                        return try { VaultState.decode(plain).also(verifyState) } finally { plain.fill(0) }
                    }

                    override fun save(state: VaultState) {
                        verifyAccess()
                        // A stale object cannot strip the durable launch fence.
                        load()
                        verifyState(state)
                        val plain = state.encode()
                        val encrypted = try { encrypt(plain) } finally { plain.fill(0) }
                        val db = database.writableDatabase
                        db.beginTransaction()
                        try {
                            val values = ContentValues().apply { put("account_id", id); put("ciphertext", encrypted) }
                            db.insertWithOnConflict("vaults", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                                .also { if (it < 0) throw IllegalStateException("Vault commit failed") }
                            db.setTransactionSuccessful()
                        } finally { db.endTransaction() }
                    }
                })
            }
        }
    }

    /** Account removal is explicit. Recreating a missing key for an existing blob is forbidden. */
    fun clear() = locked {
        it.load()
        eraseUnlocked()
    }
    private fun eraseRemovedAccount() = locked { eraseUnlocked() }
    private fun eraseUnlocked() {
        database.writableDatabase.delete("vaults", "account_id=?", arrayOf(id))
        database.writableDatabase.delete("accounts", "account_id=?", arrayOf(id))
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keys.containsAlias(alias)) keys.deleteEntry(alias)
    }

    private fun key(create: Boolean): SecretKey {
        if (Build.VERSION.SDK_INT < 23) throw VaultLockedException()
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keys.getKey(alias, null)
        if (existing is SecretKey) return existing
        if (!create) throw VaultLockedException()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build())
            generateKey()
        }
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(aad)
        if (cipher.iv.size != 12) throw ProtocolException()
        val encrypted = cipher.doFinal(plain)
        return ByteBuffer.allocate(1 + 12 + encrypted.size).put(1).put(cipher.iv).put(encrypted).array()
    }

    private fun decrypt(data: ByteArray): ByteArray {
        if (data.size < 29 || data[0] != 1.toByte()) throw ProtocolException()
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, data.copyOfRange(1, 13)))
            cipher.updateAAD(aad)
            cipher.doFinal(data, 13, data.size - 13)
        } catch (_: Exception) { throw VaultLockedException() }
    }

    override fun toString(): String = "AndroidVaultStore[redacted]"

    private class Database @RequiresApi(23) constructor(context: Context) : SQLiteOpenHelper(context,
        File(context.noBackupFilesDir, "password-vaults.db").absolutePath, null, 2) {
        override fun onConfigure(db: SQLiteDatabase) {
            // secure_delete returns the resulting value, even when assigning it. Newer Android
            // SQLite bindings reject row-returning statements passed through execSQL.
            db.rawQuery("PRAGMA secure_delete=ON", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getInt(0) != 1) throw IllegalStateException("Vault secure deletion unavailable")
            }
            db.execSQL("PRAGMA synchronous=FULL")
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE vaults(account_id TEXT PRIMARY KEY NOT NULL, ciphertext BLOB NOT NULL)")
            db.execSQL("CREATE TABLE accounts(account_id TEXT PRIMARY KEY NOT NULL, enabled INTEGER NOT NULL DEFAULT 0)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion == 1 && newVersion == 2) db.execSQL("CREATE TABLE accounts(account_id TEXT PRIMARY KEY NOT NULL, enabled INTEGER NOT NULL DEFAULT 0)")
            else throw IllegalStateException("Vault migration required")
        }
    }
    companion object {
        fun accountVaults(context: Context): Set<String> = database(context).readableDatabase.query("accounts", arrayOf("account_id"),
            null, null, null, null, null).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        fun eraseAccountVault(context: Context, hash: String) {
            require(Regex("[0-9a-f]{64}").matches(hash))
            AndroidVaultStore(context, hash, {}, true, {}).eraseRemovedAccount()
        }
        private val LOCKS = ConcurrentHashMap<String, Any>()
        @Volatile private var DB: Database? = null
        private fun database(context: Context): Database {
            // Refuse before opening storage; this vault requires Keystore-backed AES keys.
            if (Build.VERSION.SDK_INT < 23) throw VaultLockedException()
            return DB ?: synchronized(this) {
                DB ?: Database(context.applicationContext).also { DB = it }
            }
        }
    }
}
