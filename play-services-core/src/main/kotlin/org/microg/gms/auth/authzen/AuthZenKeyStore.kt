/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.authzen

import android.content.Context
import android.os.Build.VERSION.SDK_INT
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.microg.gms.cryptauth.withCryptAuthLock
import org.microg.gms.cryptauth.withProcessFileLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Small named blobs, written atomically. Production files live in `noBackupFilesDir`.
 *
 * Callers hold the blob's [AuthZenLock] for every call, reads included, and write through [commit], which reads the
 * result back: a write that did not land throws [AuthZenStorageException] before any effect depends on it.
 */
internal interface AuthZenStorage {
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray)
    fun delete(name: String)
}

internal class AuthZenStorageException(message: String) : IOException(message)

/** Serializes every read and read-modify-write of one blob across threads and GmsCore processes. Not reentrant. */
internal interface AuthZenLock {
    fun <T> withLock(name: String, block: () -> T): T
}

/**
 * Writes a blob as a uniquely named temporary file, synced, then renamed over the old one; reads never touch
 * temporary files. Android's `AtomicFile` is not used: its `openRead()` deletes an unfinished `.new` file, so a read
 * racing a write in another process could silently undo that write, and its `finishWrite()` only logs a failed
 * rename (useoxygen/cyclon#842). Here a failed rename throws, and [commit] reads the result back.
 */
internal class AtomicFileStorage(private val directory: File) : AuthZenStorage {
    private fun file(name: String): File {
        require(name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "Bad blob name" }
        return File(directory, name)
    }

    override fun read(name: String): ByteArray? = file(name).takeIf { it.isFile }?.readBytes()

    override fun write(name: String, bytes: ByteArray) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw AuthZenStorageException("No storage directory")
        val target = file(name)
        val temporary = File.createTempFile("$name.", TEMPORARY_SUFFIX, directory)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.flush()
                stream.fd.sync()
            }
            if (!temporary.renameTo(target)) throw AuthZenStorageException("Rename failed")
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    override fun delete(name: String) {
        val target = file(name)
        if (target.exists() && !target.delete()) throw AuthZenStorageException("Delete failed")
        // Leftovers of a write that died before its rename; they never count as content.
        directory.listFiles { file -> file.name.startsWith("$name.") && file.name.endsWith(TEMPORARY_SUFFIX) }?.forEach { it.delete() }
    }

    private companion object {
        const val TEMPORARY_SUFFIX = ".tmp"
    }
}

/**
 * Writes [bytes] and checks that a read returns exactly them, so a storage that dropped or mangled the write fails
 * here, before any effect that depends on the new state. Call with the blob's lock held.
 */
internal fun AuthZenStorage.commit(name: String, bytes: ByteArray) {
    write(name, bytes)
    if (!(read(name) ?: throw AuthZenStorageException("Missing after write")).contentEquals(bytes)) throw AuthZenStorageException("Read-back mismatch")
}

/** The `cryptauth_locks` file locks shared with the Workspace screen-lock proof, under their own `authzen-` kinds. */
internal class CryptAuthFileLock(private val context: Context) : AuthZenLock {
    @Suppress("UNCHECKED_CAST")
    override fun <T> withLock(name: String, block: () -> T): T = context.withCryptAuthLock("authzen-$name", name, wait = true, block) as T
}

/** The same process and file locking as [CryptAuthFileLock], with lock files in [directory]; for host tests. */
internal class DirectoryFileLock(private val directory: File) : AuthZenLock {
    @Suppress("UNCHECKED_CAST")
    override fun <T> withLock(name: String, block: () -> T): T {
        directory.mkdirs()
        return withProcessFileLock(File(directory, "authzen-$name.lock"), wait = true, block) as T
    }
}

/** AuthZen state is never backed up: a restored master key or transaction must not act for another device. */
internal fun Context.authZenStorage(): AuthZenStorage =
    AtomicFileStorage(File(if (SDK_INT >= 21) noBackupFilesDir else filesDir, "authzen"))

/** Chromium `KeyType` values that a symmetric bundle may use. P256 and CURVE25519 are never accepted for `authzen`. */
internal enum class AuthZenKeyType(val wireValue: Int, val length: Int) { RAW128(1, 16), RAW256(2, 32) }

internal enum class AuthZenKeyStatus {
    /** Used to verify server messages and to encrypt replies. */
    ACTIVE,
    /** Retired by the server; still verifies messages already sent with it, never used for new replies. */
    INACTIVE,
}

internal class AuthZenSymmetricKey(val handle: ByteArray, val type: AuthZenKeyType, val material: ByteArray, val status: AuthZenKeyStatus) {
    val secretKey: SecretKey get() = SecretKeySpec(material, "AES")
}

/** A key generated for enrollment and not yet confirmed by the server. It never verifies or encrypts anything. */
internal class AuthZenPendingKey(val handle: ByteArray, val type: AuthZenKeyType, val material: ByteArray, val sent: Boolean)

/**
 * One account's AuthZen keys.
 *
 * The account's `PublicKey` (the Workspace screen-lock proof key of `CryptAuthKeys`) is not stored here and never
 * written by this registry. [publicKeyHandle] only records which `PublicKey` the `authzen` keys were enrolled with,
 * so that a changed `PublicKey` invalidates them instead of silently mixing identities.
 *
 * [generation] grows with every committed change; transactions remember it and fail once it changed.
 */
internal class AuthZenAccountKeys(
    val generation: Long = 0,
    val publicKeyHandle: ByteArray? = null,
    val keys: List<AuthZenSymmetricKey> = emptyList(),
    val pending: AuthZenPendingKey? = null,
) {
    val active: AuthZenSymmetricKey? get() = keys.firstOrNull { it.status == AuthZenKeyStatus.ACTIVE }
}

/**
 * Versioned registry for the symmetric `authzen` bundle, with a journal for enrollment: [stage] stores a new key
 * before anything is sent, [markSent] records that a request may have reached the server, [commit] activates it only
 * after the server confirmed. A process death between those steps leaves the journal to resume or abort, never a key
 * that counts as enrolled because it was generated.
 *
 * Enrollment itself is not implemented: the `authzen` bundle's current key type, derivation and handle convention are
 * UNKNOWN (docs/google-prompt.md). Nothing in GmsCore calls [stage] yet.
 */
internal class AuthZenKeyRegistry(private val storage: AuthZenStorage, private val lock: AuthZenLock) {

    fun keys(account: String): AuthZenAccountKeys = lock.withLock(fileName(account)) { read(account) }

    /** The key for a server message's (unverified) handle, active or decrypt-only, or null. */
    fun serverKey(account: String, handle: ByteArray): SecretKey? =
        keys(account).keys.firstOrNull { it.handle.contentEquals(handle) }?.secretKey

    fun stage(account: String, type: AuthZenKeyType, material: ByteArray, handle: ByteArray): AuthZenPendingKey = update(account) { keys ->
        require(material.size == type.length) { "Key length does not match its type" }
        check(keys.pending?.sent != true) { "An enrollment may have reached the server; resolve it first" }
        val pending = AuthZenPendingKey(handle.copyOf(), type, material.copyOf(), sent = false)
        AuthZenAccountKeys(keys.generation, keys.publicKeyHandle, keys.keys, pending) to pending
    }

    fun markSent(account: String, handle: ByteArray) = update(account) { keys ->
        val pending = keys.pending?.takeIf { it.handle.contentEquals(handle) } ?: error("No such staged key")
        AuthZenAccountKeys(keys.generation, keys.publicKeyHandle, keys.keys, AuthZenPendingKey(pending.handle, pending.type, pending.material, sent = true)) to Unit
    }

    /** Activates the staged key after the server confirmed it; the previous active key becomes decrypt-only. */
    fun commit(account: String, handle: ByteArray, publicKeyHandle: ByteArray) = update(account) { keys ->
        val pending = keys.pending?.takeIf { it.handle.contentEquals(handle) && it.sent } ?: error("No such sent key")
        val retained = keys.keys.filter { !it.handle.contentEquals(handle) }
            .map { AuthZenSymmetricKey(it.handle, it.type, it.material, AuthZenKeyStatus.INACTIVE) }
            .take(MAX_RETIRED_KEYS)
        val active = AuthZenSymmetricKey(pending.handle, pending.type, pending.material, AuthZenKeyStatus.ACTIVE)
        AuthZenAccountKeys(keys.generation + 1, publicKeyHandle.copyOf(), listOf(active) + retained, null) to Unit
    }

    /** Drops a staged key. A key that may have been sent stays until the server's answer is known. */
    fun abort(account: String, force: Boolean = false) = update(account) { keys ->
        if (keys.pending?.sent == true && !force) keys to false
        else AuthZenAccountKeys(keys.generation, keys.publicKeyHandle, keys.keys, null) to true
    }

    /** Server key directives: deactivate keeps a key decrypt-only, delete removes it. */
    fun deactivate(account: String, handle: ByteArray) = update(account) { keys ->
        AuthZenAccountKeys(keys.generation + 1, keys.publicKeyHandle, keys.keys.map {
            if (it.handle.contentEquals(handle)) AuthZenSymmetricKey(it.handle, it.type, it.material, AuthZenKeyStatus.INACTIVE) else it
        }, keys.pending) to Unit
    }

    fun delete(account: String, handle: ByteArray) = update(account) { keys ->
        AuthZenAccountKeys(keys.generation + 1, keys.publicKeyHandle, keys.keys.filter { !it.handle.contentEquals(handle) }, keys.pending) to Unit
    }

    /** Account removal or opt-out: forget every AuthZen key of the account. The `PublicKey` file is not touched. */
    fun clear(account: String) = lock.withLock(fileName(account)) { storage.delete(fileName(account)) }

    private fun <T> update(account: String, block: (AuthZenAccountKeys) -> Pair<AuthZenAccountKeys, T>): T = lock.withLock(fileName(account)) {
        val (next, result) = block(read(account))
        storage.commit(fileName(account), encode(next))
        result
    }

    /** Call with the account's lock held. An unreadable file counts as no keys; prompts then fail closed. */
    private fun read(account: String): AuthZenAccountKeys = storage.read(fileName(account))?.let {
        try {
            decode(it)
        } catch (e: Exception) {
            null
        }
    } ?: AuthZenAccountKeys()

    companion object {
        private const val FORMAT = 1
        private const val MAX_RETIRED_KEYS = 2

        fun fileName(account: String) = "keys-" + account.encodeUtf8().sha256().hex()

        private fun ByteArray.b64() = toByteString().base64()
        private fun String.unb64() = decodeBase64()!!.toByteArray()

        fun encode(keys: AuthZenAccountKeys): ByteArray = JSONObject().apply {
            put("format", FORMAT)
            put("generation", keys.generation)
            keys.publicKeyHandle?.let { put("publicKeyHandle", it.b64()) }
            put("keys", JSONArray().apply {
                keys.keys.forEach { put(JSONObject().put("handle", it.handle.b64()).put("type", it.type.name).put("material", it.material.b64()).put("status", it.status.name)) }
            })
            keys.pending?.let { put("pending", JSONObject().put("handle", it.handle.b64()).put("type", it.type.name).put("material", it.material.b64()).put("sent", it.sent)) }
        }.toString().toByteArray()

        fun decode(bytes: ByteArray): AuthZenAccountKeys {
            val json = JSONObject(String(bytes))
            require(json.getInt("format") == FORMAT)
            val keys = json.getJSONArray("keys").let { array ->
                (0 until array.length()).map { array.getJSONObject(it) }.map {
                    AuthZenSymmetricKey(it.getString("handle").unb64(), AuthZenKeyType.valueOf(it.getString("type")), it.getString("material").unb64(), AuthZenKeyStatus.valueOf(it.getString("status")))
                }
            }
            val pending = json.optJSONObject("pending")?.let {
                AuthZenPendingKey(it.getString("handle").unb64(), AuthZenKeyType.valueOf(it.getString("type")), it.getString("material").unb64(), it.getBoolean("sent"))
            }
            return AuthZenAccountKeys(json.getLong("generation"), json.optString("publicKeyHandle", "").takeIf { it.isNotEmpty() }?.unb64(), keys, pending)
        }
    }
}
