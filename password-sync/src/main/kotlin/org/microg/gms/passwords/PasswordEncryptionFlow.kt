/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.net.URI
import java.security.MessageDigest

enum class PasswordEncryptionOperation { ENROLL, CLEAR_PASSWORDS, RESET_PASSKEYS }
enum class PasswordEncryptionPhase { READY, OPENED, UNKNOWN, CONFIRMED, CLOSED }

/** Raw, complete server reads. The metadata may contain keys: never log or put this in an Intent. */
class PasswordEncryptionSnapshot(val accountId: String, val parent: String, metadata: ByteArray,
    passwords: List<ByteArray>, wifi: List<ByteArray>, passkeys: List<ByteArray>) : AutoCloseable {
    init {
        // Check the caller's bounded view before retaining a second copy of every resource.
        checkedAccount(accountId); PasswordApi.checkedParent(parent)
        val collections = listOf(passwords, wifi, passkeys)
        if (metadata.size > MAX_SNAPSHOT_BYTES || collections.any { it.size > MAX_RECORDS || it.any(ByteArray::isEmpty) } ||
            accountId.length.toLong() + parent.length + metadata.size + 32 + collections.sumOf { rows -> rows.sumOf { it.size.toLong() + 8 } } > MAX_SNAPSHOT_BYTES)
            throw ProtocolException()
        if (Proto.decode(metadata).string(1) != parent) throw ProtocolException()
    }
    private val rawMetadata = metadata.copyOf()
    private val rawPasswords = passwords.map(ByteArray::copyOf)
    private val rawWifi = wifi.map(ByteArray::copyOf)
    private val rawPasskeys = passkeys.map(ByteArray::copyOf)
    val metadata get() = rawMetadata.copyOf()
    val passwords get() = rawPasswords.map(ByteArray::copyOf)
    val wifi get() = rawWifi.map(ByteArray::copyOf)
    val passkeys get() = rawPasskeys.map(ByteArray::copyOf)
    fun encode(): ByteArray = Proto.field(1, accountId) + Proto.field(2, parent) + Proto.field(3, rawMetadata) +
        repeated(4, rawPasswords) + repeated(5, rawWifi) + repeated(6, rawPasskeys)
    /** Page order is not authority. Every raw resource and all metadata bytes remain bound. */
    fun digest(): ByteArray = MessageDigest.getInstance("SHA-256").digest(
        Proto.field(1, accountId) + Proto.field(2, parent) + Proto.field(3, rawMetadata) +
            repeated(4, sorted(rawPasswords)) + repeated(5, sorted(rawWifi)) + repeated(6, sorted(rawPasskeys)))
    fun copy(): PasswordEncryptionSnapshot = PasswordEncryptionSnapshot(accountId, parent, rawMetadata, rawPasswords, rawWifi, rawPasskeys)
    override fun close() { rawMetadata.fill(0); (rawPasswords + rawWifi + rawPasskeys).forEach { it.fill(0) } }
    override fun toString(): String = "PasswordEncryptionSnapshot[redacted]"
    companion object {
        const val MAX_RECORDS = 30_000
        const val MAX_SNAPSHOT_BYTES = Proto.MAX_BYTES - 512 * 1024
        fun decode(raw: ByteArray): PasswordEncryptionSnapshot {
            if (raw.size > MAX_SNAPSHOT_BYTES) throw ProtocolException()
            val p = Proto.decode(raw)
            val result = PasswordEncryptionSnapshot(p.string(1), p.string(2), p.one(3), p.bytes(4), p.bytes(5), p.bytes(6))
            if (!result.encode().contentEquals(raw)) { result.close(); throw ProtocolException() }
            return result
        }
        private fun sorted(values: List<ByteArray>) = values.sortedWith { a, b ->
            var result = 0
            for (i in 0 until minOf(a.size, b.size)) {
                result = (a[i].toInt() and 255) - (b[i].toInt() and 255)
                if (result != 0) break
            }
            if (result == 0) a.size.compareTo(b.size) else result
        }
    }
}

/** Local operation receipt. It is not a Google RPC schema or permission to repeat a web operation. */
class PasswordEncryptionState private constructor(val id: String, val operation: PasswordEncryptionOperation,
    val phase: PasswordEncryptionPhase, val accountId: String, val parent: String, val startedMillis: Long,
    before: PasswordEncryptionSnapshot?, baselineDigest: ByteArray, deliveredKeys: List<VersionedTrustedVaultKey>,
    val acknowledgedUnknown: Boolean = false) : AutoCloseable {
    val before = before?.copy()
    private val digest = baselineDigest.copyOf()
    private val keys = deliveredKeys.map { key -> val raw = key.material; try { VersionedTrustedVaultKey(key.epoch, raw) } finally { raw.fill(0) } }
    val baselineDigest get() = digest.copyOf()
    /** Caller owns and wipes these copies. Pending keys grant no fill/enrollment authority. */
    val deliveredKeys get() = keys.map { key -> val raw = key.material; try { VersionedTrustedVaultKey(key.epoch, raw) } finally { raw.fill(0) } }
    init {
        if (!Regex("[A-Za-z0-9_-]{1,128}").matches(id) || startedMillis <= 0 || digest.size != 32) throw ProtocolException()
        checkedAccount(accountId); PasswordApi.checkedParent(parent)
        val pending = phase in setOf(PasswordEncryptionPhase.READY, PasswordEncryptionPhase.OPENED, PasswordEncryptionPhase.UNKNOWN)
        if ((this.before != null) != pending || keys.size > 100 || keys.map { it.epoch }.distinct().size != keys.size ||
            keys.isNotEmpty() && (operation != PasswordEncryptionOperation.ENROLL || phase == PasswordEncryptionPhase.READY) ||
            acknowledgedUnknown && phase != PasswordEncryptionPhase.CLOSED) throw ProtocolException()
        this.before?.let {
            if (it.accountId != accountId || it.parent != parent || !MessageDigest.isEqual(digest, it.digest())) throw ProtocolException()
        }
    }
    fun encode(): ByteArray = Proto.integer(1, 1) + Proto.field(2, id) + Proto.integer(3, operation.ordinal.toLong()) +
        Proto.integer(4, phase.ordinal.toLong()) + Proto.field(5, accountId) + Proto.field(6, parent) + Proto.integer(7, startedMillis) +
        (before?.encode()?.let { Proto.field(8, it) } ?: byteArrayOf()) + Proto.field(9, digest) +
        keys.fold(byteArrayOf()) { out, key ->
            val raw = key.material
            try { out + Proto.field(10, Proto.integer(1, key.epoch.toLong()) + Proto.field(2, raw)) } finally { raw.fill(0) }
        } + if (acknowledgedUnknown) Proto.integer(11, 1) else byteArrayOf()
    /** Persist this transition BEFORE requesting authentication or loading any Google page. */
    fun open(): PasswordEncryptionState {
        if (phase != PasswordEncryptionPhase.READY) throw PendingMutationException()
        return changed(PasswordEncryptionPhase.OPENED)
    }
    fun stage(delivered: List<VersionedTrustedVaultKey>): PasswordEncryptionState {
        if (operation != PasswordEncryptionOperation.ENROLL || phase !in setOf(PasswordEncryptionPhase.OPENED, PasswordEncryptionPhase.UNKNOWN) || delivered.isEmpty())
            throw ConflictException()
        if (keys.isNotEmpty() && !sameKeys(keys, delivered)) throw ConflictException()
        return changed(phase, delivered)
    }
    /** Leaving an opened view never proves that Google did not process the operation. */
    fun closeView(): PasswordEncryptionState = when (phase) {
        PasswordEncryptionPhase.READY -> changed(PasswordEncryptionPhase.CLOSED)
        PasswordEncryptionPhase.OPENED -> changed(PasswordEncryptionPhase.UNKNOWN)
        else -> changed(phase)
    }
    /** Invoke only after authoritative native metadata/list/keybag verification, never from a page signal alone. */
    fun confirmed(): PasswordEncryptionState {
        if (phase !in setOf(PasswordEncryptionPhase.OPENED, PasswordEncryptionPhase.UNKNOWN)) throw ConflictException()
        return changed(PasswordEncryptionPhase.CONFIRMED)
    }
    /** Explicit local closure only after proving a usable, fresh current account snapshot. No remote success is asserted. */
    fun abandoned(): PasswordEncryptionState {
        if (phase !in setOf(PasswordEncryptionPhase.OPENED, PasswordEncryptionPhase.UNKNOWN)) throw ConflictException()
        return changed(PasswordEncryptionPhase.CLOSED, unknown = true)
    }
    private fun changed(next: PasswordEncryptionPhase, nextKeys: List<VersionedTrustedVaultKey> = keys, unknown: Boolean = acknowledgedUnknown): PasswordEncryptionState {
        val terminal = next in setOf(PasswordEncryptionPhase.CLOSED, PasswordEncryptionPhase.CONFIRMED)
        return PasswordEncryptionState(id, operation, next, accountId, parent, startedMillis,
            if (terminal) null else before, digest, if (terminal) emptyList() else nextKeys, unknown)
    }
    override fun close() { before?.close(); digest.fill(0); keys.forEach(VersionedTrustedVaultKey::destroy) }
    override fun toString(): String = "PasswordEncryptionState[redacted]"
    companion object {
        fun prepare(id: String, operation: PasswordEncryptionOperation, before: PasswordEncryptionSnapshot, nowMillis: Long): PasswordEncryptionState =
            PasswordEncryptionState(id, operation, PasswordEncryptionPhase.READY, before.accountId, before.parent, nowMillis, before, before.digest(), emptyList())
        fun decode(raw: ByteArray?): PasswordEncryptionState? {
            if (raw == null) return null
            val p = Proto.decode(raw)
            if (p.integer(1) != 1L) throw ProtocolException()
            val op = p.integer(3)?.toInt()?.let { PasswordEncryptionOperation.values().getOrNull(it) } ?: throw ProtocolException()
            val phase = p.integer(4)?.toInt()?.let { PasswordEncryptionPhase.values().getOrNull(it) } ?: throw ProtocolException()
            val before = p.optional(8)?.let(PasswordEncryptionSnapshot::decode)
            val keys = ArrayList<VersionedTrustedVaultKey>()
            try {
                for (rawKey in p.bytes(10)) {
                    val k = Proto.decode(rawKey); val epoch = k.integer(1) ?: throw ProtocolException()
                    if (epoch !in 1..Int.MAX_VALUE.toLong()) throw ProtocolException()
                    val material = k.one(2)
                    try { keys.add(VersionedTrustedVaultKey(epoch.toInt(), material)) } finally { material.fill(0) }
                }
                val result = PasswordEncryptionState(p.string(2), op, phase, p.string(5), p.string(6), p.integer(7) ?: throw ProtocolException(),
                    before, p.one(9), keys, when (p.integer(11)) { null -> false; 1L -> true; else -> throw ProtocolException() })
                if (!result.encode().contentEquals(raw)) { result.close(); throw ProtocolException() }
                return result
            } finally { before?.close(); keys.forEach(VersionedTrustedVaultKey::destroy) }
        }
        fun fences(raw: ByteArray?): Boolean = decode(raw)?.use {
            it.phase in setOf(PasswordEncryptionPhase.OPENED, PasswordEncryptionPhase.UNKNOWN)
        } ?: false
        private fun sameKeys(a: List<VersionedTrustedVaultKey>, b: List<VersionedTrustedVaultKey>): Boolean {
            if (a.size != b.size) return false
            return a.all { key ->
                val next = b.singleOrNull { it.epoch == key.epoch } ?: return@all false
                val first = key.material; val second = next.material
                try { MessageDigest.isEqual(first, second) } finally { first.fill(0); second.fill(0) }
            }
        }
    }
}

/** Exact origins and finite routes for a dedicated owner-only Google web flow. */
object PasswordEncryptionWebPolicy {
    const val PASSWORDS_ORIGIN = "https://passwords.google.com"
    const val ACCOUNTS_ORIGIN = "https://accounts.google.com"
    fun startUrl(operation: PasswordEncryptionOperation): String = PASSWORDS_ORIGIN + when (operation) {
        PasswordEncryptionOperation.ENROLL -> "/embedded/encryption/enroll/intro?ilcp=false"
        PasswordEncryptionOperation.CLEAR_PASSWORDS -> "/embedded/encryption/reset/intro"
        PasswordEncryptionOperation.RESET_PASSKEYS -> "/embedded/passkeys/reset/intro"
    }
    fun allowedNavigation(url: String, operation: PasswordEncryptionOperation): Boolean {
        val uri = uri(url) ?: return false
        if (origin(uri) == ACCOUNTS_ORIGIN) return true // The selected-account webLogin and Google's reauthentication flow.
        if (origin(uri) != PASSWORDS_ORIGIN) return false
        val path = withoutAccountPrefix(uri.rawPath)
        val prefix = when (operation) {
            PasswordEncryptionOperation.ENROLL -> "encryption/enroll"
            PasswordEncryptionOperation.CLEAR_PASSWORDS -> "encryption/reset"
            PasswordEncryptionOperation.RESET_PASSKEYS -> "passkeys/reset"
        }
        return path == prefix || path in listOf("intro", "setup", "run", "done", "error").map { "$prefix/$it" } ||
            path.startsWith("error/") && path.removePrefix("error/").matches(Regex("[a-z-]{1,80}"))
    }
    fun terminal(url: String, operation: PasswordEncryptionOperation): Boolean {
        val uri = uri(url) ?: return false
        return origin(uri) == PASSWORDS_ORIGIN && allowedNavigation(url, operation) && withoutAccountPrefix(uri.rawPath).endsWith("/done")
    }
    fun acceptsTopMessage(origin: String, mainFrame: Boolean, topUrl: String, operation: PasswordEncryptionOperation): Boolean =
        mainFrame && origin == PASSWORDS_ORIGIN && allowedNavigation(topUrl, operation) && uri(topUrl)?.let(::origin) == PASSWORDS_ORIGIN
    fun acceptsRelayMessage(sourceOrigin: String, mainFrame: Boolean, topUrl: String, frameUrl: String,
        operation: PasswordEncryptionOperation): Boolean {
        if (operation != PasswordEncryptionOperation.ENROLL || sourceOrigin != ACCOUNTS_ORIGIN || mainFrame ||
            !allowedNavigation(topUrl, operation) || uri(topUrl)?.let(::origin) != PASSWORDS_ORIGIN) return false
        val frame = uri(frameUrl) ?: return false
        if (origin(frame) != ACCOUNTS_ORIGIN || withoutAccountPrefix(frame.rawPath) != "encryption/unlock/datarelay") return false
        val query = frame.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
        val parents = query.filter { it.first() == "origin" }
        if (parents.size != 1 || parents[0].size != 2) return false
        return try { java.net.URLDecoder.decode(parents[0][1], "UTF-8") == PASSWORDS_ORIGIN } catch (_: Exception) { false }
    }
    private fun withoutAccountPrefix(path: String): String = path.removePrefix("/").replaceFirst(Regex("^(?:u/[0-9]{1,5}/)?(?:embedded/)?"), "")
    private fun uri(value: String): URI? = try {
        URI(value).takeIf { it.scheme == "https" && it.host in setOf("accounts.google.com", "passwords.google.com") &&
            it.userInfo == null && it.port in setOf(-1, 443) && it.rawFragment == null && !it.rawPath.contains('%') }
    } catch (_: Exception) { null }
    private fun origin(uri: URI): String = "https://${uri.host}"
}

private fun checkedAccount(value: String) {
    if (!Regex("[0-9]{1,64}").matches(value)) throw ProtocolException()
}

/** One isolated WebView cookie profile cannot carry two accounts at once, even during asynchronous clearing. */
class PasswordEncryptionSessionLease {
    private var active: String? = null
    private var retiring = false
    private var poisoned = false
    @Synchronized fun acquire(id: String): Boolean {
        if (id.isBlank() || active != null || poisoned) return false
        active = id; return true
    }
    @Synchronized fun owns(id: String): Boolean = !retiring && !poisoned && active == id
    @Synchronized fun retire(id: String): Boolean {
        if (active != id || retiring) return false
        retiring = true; return true
    }
    @Synchronized fun cleared(id: String) {
        if (active == id && retiring && !poisoned) { active = null; retiring = false }
    }
    /** A late or missing cookie callback cannot make a second account race the old profile cleanup. */
    @Synchronized fun cleanupTimedOut(id: String) { if (active == id && retiring) poisoned = true }
}
private fun repeated(field: Int, values: List<ByteArray>): ByteArray = java.io.ByteArrayOutputStream().run {
    for (value in values) { val raw = Proto.field(field, value); if (raw.size > Proto.MAX_BYTES - size()) throw ProtocolException(); write(raw) }
    toByteArray()
}
