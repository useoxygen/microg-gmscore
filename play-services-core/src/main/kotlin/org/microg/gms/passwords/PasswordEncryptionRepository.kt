/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.toByteString
import org.microg.gms.auth.AuthManager
import org.microg.gms.auth.folsom.Keys
import org.microg.gms.auth.folsom.getSecurityDomain
import org.microg.gms.auth.folsom.loadSecurityDomainMembers
import org.microg.gms.auth.folsom.utils.LocalKeyManager
import org.microg.gms.common.Constants
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Private Google owner-page reconciliation. Every remote operation here is a read. */
internal class PasswordEncryptionRepository(context: Context, private val account: Account,
    private val expectedUserId: String, private val authorize: () -> Unit) {
    private val context = context.applicationContext
    private val local = LocalKeyManager.getInstance(this.context)
    private val accounts = AccountManager.get(this.context)
    private val stopped = AtomicBoolean(false)
    private val http = OkHttpClient()
    private val transfers = PasswordOwnerOperations(this.context)
    private val transport by lazy {
        verify()
        val auth = AuthManager(this.context, account.name, Constants.GMS_PACKAGE_NAME,
            "oauth2:https://www.googleapis.com/auth/chromesync").apply {
            isGmsApp = true; setPackageSignature(Constants.GMS_PACKAGE_SIGNATURE_SHA1)
        }
        val oauth = PasswordOAuthSession({
            verify(); auth.requestAuthWithBackgroundResolution(false).auth.also { verify() }
        }, { rejected -> verify(); auth.invalidateAuthToken(rejected); verify() })
        val native = GooglePasswordTransport({ verify(); oauth.token().also { verify() } },
            "https://chromesyncpasswords-pa.googleapis.com/".toHttpUrl(), http, oauth::reject)
        PasswordTransport { method, request ->
            if (method.mutates) throw ProtocolException()
            verify(); native.invoke(method, request).also { verify() }
        }
    }
    private lateinit var repository: PasswordRepository
    private val passkeys: AndroidPasskeyStore
    init {
        verify()
        repository = PasswordRepository.encryptionOwner(this.context, account)
        verify()
        if (!repository.enabled) throw VaultLockedException()
        passkeys = AndroidPasskeyStore(this.context, "google:$expectedUserId", ::verify)
    }
    private fun verify() {
        if (stopped.get()) throw VaultLockedException()
        authorize()
        if (account.type != "com.google" || expectedUserId.isBlank() || !accounts.getAccountsByType("com.google").contains(account) ||
            accounts.getUserData(account, "GoogleUserId") != expectedUserId ||
            local.hasPendingDomainReset(account.name, "chromesync") || local.hasPendingDomainReset(account.name, "hw_protected")) throw VaultLockedException()
        if (::repository.isInitialized && !repository.enabled) throw VaultLockedException()
    }
    fun cancel() { stopped.set(true); http.dispatcher.cancelAll() }
    private fun <T> locked(action: (VaultSession, AndroidPasskeyStore.Session) -> T): T =
        transfers.withAccountTransfersQuiesced(expectedUserId) { local.withRecoveryAuthority(account.name, expectedUserId) {
            verify(); repository.store.locked { session -> passkeys.locked { passkey ->
                verify(); action(session, passkey).also { verify() }
            } }
        } }
    private fun noWrites(state: VaultState, passkey: AndroidPasskeyStore.Session) {
        val wifi = PasswordWifiState.decode(state.wifiSync)
        val raw = passkey.load()
        val saved = try { PasskeyState.decode(raw) } finally { raw.fill(0) }
        if (state.pendingMetadata != null || state.mutations.isNotEmpty() || state.preferenceMutations.isNotEmpty() ||
            state.ownerTransfers.isNotEmpty() || wifi.pending != 0 || sharingPending(state.sharingJournal) ||
            saved.pending != null || saved.ownerMutation != null) throw PendingMutationException()
    }
    fun loadFlow(): ByteArray? = locked { session, _ -> session.load().passwordEncryptionFlow?.copyOf() }
    /** CAS only prepares/opens/stages/closes. Only native readback methods below can confirm. */
    fun compareFlow(expected: ByteArray?, replacement: ByteArray?) = locked { session, passkey ->
        val state = session.load()
        if (!same(state.passwordEncryptionFlow, expected)) throw ConflictException()
        val previous = PasswordEncryptionState.decode(expected)
        val next = PasswordEncryptionState.decode(replacement) ?: throw ProtocolException()
        try {
            if (next.accountId != expectedUserId) throw VaultLockedException()
            if (previous == null || previous.phase in setOf(PasswordEncryptionPhase.CLOSED, PasswordEncryptionPhase.CONFIRMED)) {
                if (next.phase != PasswordEncryptionPhase.READY) throw ConflictException()
                noWrites(state, passkey)
            } else {
                if (previous.id != next.id || previous.operation != next.operation || previous.parent != next.parent ||
                    !MessageDigest.isEqual(previous.baselineDigest, next.baselineDigest)) throw ConflictException()
                val allowed = when {
                    previous.phase == PasswordEncryptionPhase.READY && next.phase == PasswordEncryptionPhase.OPENED -> previous.open()
                    previous.phase == next.phase && previous.operation == PasswordEncryptionOperation.ENROLL -> {
                        val keys = next.deliveredKeys
                        try { if (keys.isEmpty()) previous.closeView() else previous.stage(keys) } finally { keys.forEach(VersionedTrustedVaultKey::destroy) }
                    }
                    next.phase == PasswordEncryptionPhase.UNKNOWN || next.phase == PasswordEncryptionPhase.CLOSED -> previous.closeView()
                    else -> throw ConflictException()
                }
                allowed.use { if (!MessageDigest.isEqual(it.encode(), replacement)) throw ConflictException() }
                if (next.phase == PasswordEncryptionPhase.OPENED && previous.phase == PasswordEncryptionPhase.READY) {
                    noWrites(state, passkey)
                    readSnapshot().use { current ->
                        if (!PasswordEncryptionReadback.canStart(current, next.operation) ||
                            !MessageDigest.isEqual(current.digest(), previous.baselineDigest)) throw ConflictException()
                    }
                }
            }
            verify(); state.passwordEncryptionFlow = replacement!!.copyOf(); session.save(state)
        } finally { previous?.close(); next.close() }
    }
    fun capture(operation: PasswordEncryptionOperation): PasswordEncryptionSnapshot = locked { session, passkey ->
        val state = session.load()
        if (PasswordEncryptionState.fences(state.passwordEncryptionFlow)) throw PendingMutationException()
        noWrites(state, passkey)
        readSnapshot().also { snapshot ->
            if (!PasswordEncryptionReadback.canStart(snapshot, operation)) {
                snapshot.close(); throw ConflictException()
            }
        }
    }
    private fun readSnapshot(): PasswordEncryptionSnapshot {
        verify()
        val api = PasswordApi(transport)
        val metadata = api.metadata()
        val parent = PasswordApi.checkedParent(Proto.decode(metadata).string(1))
        // The baseline is retained beside the existing cache. Reject before accumulating
        // or copying a combined snapshot that cannot fit the bounded encrypted journal.
        val budget = SnapshotBudget(Proto.MAX_BYTES / 2L - 128 * 1024 - metadata.size)
        val passwords = resourceSnapshot(parent, PasswordMethod.LIST, budget)
        val wifi = resourceSnapshot(parent, PasswordMethod.LIST_WIFI, budget)
        val credentials = resourceSnapshot(parent, PasswordMethod.LIST_WEBAUTHN, budget)
        val after = api.metadata()
        verify()
        if (!MessageDigest.isEqual(metadata, after)) throw ConflictException()
        return PasswordEncryptionSnapshot(expectedUserId, parent, metadata, passwords, wifi, credentials)
    }
    private fun checkedOperation(session: VaultSession, before: PasswordEncryptionSnapshot, kind: PasswordEncryptionOperation,
        expectedId: String): PasswordEncryptionState {
        if (before.accountId != expectedUserId) throw VaultLockedException()
        val operation = PasswordEncryptionState.decode(session.load().passwordEncryptionFlow) ?: throw ConflictException()
        if (operation.id != expectedId || operation.accountId != expectedUserId || operation.parent != before.parent || operation.operation != kind ||
            operation.phase !in setOf(PasswordEncryptionPhase.OPENED, PasswordEncryptionPhase.UNKNOWN) ||
            !MessageDigest.isEqual(operation.baselineDigest, before.digest())) { operation.close(); throw ConflictException() }
        return operation
    }
    fun completeEnrollment(before: PasswordEncryptionSnapshot, keys: List<VersionedTrustedVaultKey>, expectedId: String): Boolean = locked { session, passkey ->
        checkedOperation(session, before, PasswordEncryptionOperation.ENROLL, expectedId).use { operation ->
            noWrites(session.load(), passkey)
            val staged = operation.deliveredKeys
            try { if (!sameKeys(staged, keys)) throw ConflictException() } finally { staged.forEach(VersionedTrustedVaultKey::destroy) }
            readSnapshot().use { fresh ->
                if (fresh.parent != before.parent || encryptionType(fresh.metadata) != 5L) return@locked false
                val raw = keys.map { it.material }
                val known = local.getKeysForDomain(account.name, "chromesync").mapNotNull { key ->
                    val material = key.keyMaterial?.toByteArray() ?: throw VaultLockedException()
                    try {
                        val epoch = key.keyVersion ?: throw VaultLockedException()
                        if (epoch == 0) {
                            // Source anqw's all-zero bootstrap is unlock-only, never an SDS epoch anchor.
                            if (material.size != 16 || material.any { it != 0.toByte() }) throw VaultLockedException()
                            null
                        } else VersionedTrustedVaultKey(epoch, material)
                    } finally { material.fill(0) }
                }
                try {
                    val id = UUID.randomUUID().toString()
                    verify()
                    val domain = runBlocking { getSecurityDomain(context, account.name, id, "chromesync") }.encode()
                    verify()
                    val members = runBlocking { loadSecurityDomainMembers(context, account.name, id, "chromesync") }.members.map { it.encode() }
                    verify()
                    val after = runBlocking { getSecurityDomain(context, account.name, id, "chromesync") }.encode()
                    verify()
                    if (!MessageDigest.isEqual(domain, after)) throw ConflictException()
                    PasswordEncryptionKeyProof.verify(domain, members, keys, known)
                    val latest = keys.maxByOrNull { it.epoch } ?: throw VaultLockedException()
                    val latestRaw = latest.material
                    try { VaultKeys.unlock(fresh.metadata, trustedVaultKeys = listOf(latestRaw)).destroy() } finally { latestRaw.fill(0) }
                    VaultKeys.unlock(fresh.metadata, trustedVaultKeys = raw).useKeys { wrapping -> validate(fresh, wrapping) }
                    val currentMetadata = PasswordApi(transport).metadata()
                    if (!MessageDigest.isEqual(currentMetadata, fresh.metadata)) throw ConflictException()
                    verify()
                    val state = session.load()
                    state.metadata = fresh.metadata; state.pendingMetadata = null
                    // The actual fresh metadata is installed while still fenced, before the
                    // existing trusted-key validator examines its Nigori keybag.
                    session.save(state)
                    VaultRecovery.acceptVersionedTrustedKeys(session, keys, before.parent, ::verify)
                    val retained = (known + keys).associateBy { it.epoch }.values.map { key ->
                        val material = key.material
                        try { Keys(keyVersion = key.epoch, keyMaterial = material.toByteString()) } finally { material.fill(0) }
                    }
                    local.saveKeysForDomain(account.name, "chromesync", retained, expectedUserId = expectedUserId)
                    // SDS proof and key persistence may take time. Never republish rows
                    // captured before that I/O as the final credential cache.
                    readSnapshot().use { latestSnapshot ->
                        if (latestSnapshot.parent != before.parent || encryptionType(latestSnapshot.metadata) != 5L) throw VaultResetException()
                        VaultKeys.unlock(latestSnapshot.metadata, trustedVaultKeys = raw).useKeys { validate(latestSnapshot, it) }
                        verify()
                        val finalDomain = runBlocking { getSecurityDomain(context, account.name, id, "chromesync") }.encode()
                        verify()
                        if (!MessageDigest.isEqual(domain, finalDomain)) throw ConflictException()
                        commit(session, passkey, latestSnapshot, operation)
                    }
                    true
                } finally { raw.forEach { it.fill(0) }; known.forEach(VersionedTrustedVaultKey::destroy) }
            }
        }
    }
    fun reconcileDeletion(before: PasswordEncryptionSnapshot, operation: PasswordEncryptionOperation, expectedId: String): Boolean = locked { session, passkey ->
        if (operation == PasswordEncryptionOperation.ENROLL) throw ProtocolException()
        checkedOperation(session, before, operation, expectedId).use { receipt ->
            noWrites(session.load(), passkey)
            readSnapshot().use { fresh ->
                val complete = PasswordEncryptionReadback.deletion(fresh, operation)
                if (!complete) return@locked false
                val state = session.load()
                // No old key is asserted to unlock a replacement birthday. Empty snapshots
                // can be committed without decrypting data that no longer exists.
                if (operation == PasswordEncryptionOperation.CLEAR_PASSWORDS ||
                    fresh.passwords.isNotEmpty() || fresh.wifi.isNotEmpty() || fresh.passkeys.isNotEmpty()) {
                    repository.sharingKeys(fresh.metadata, state).useKeys { validate(fresh, it) }
                }
                verify(); commit(session, passkey, fresh, receipt)
                true
            }
        }
    }
    /**
     * Ends an interrupted change (an attempt whose Google page closed before it could be confirmed) without asserting
     * what Google did. Only the local receipt closes: no snapshot is adopted, so nothing needs to be decrypted here.
     * Normal sync then reads Google's authoritative state, like any change made on another device: unchanged data just
     * syncs again, and an account Google moved to on-device encryption asks for its keys through account recovery.
     */
    fun release(expectedId: String) {
        locked { session, passkey ->
            val state = session.load()
            val current = PasswordEncryptionState.decode(state.passwordEncryptionFlow) ?: throw ConflictException()
            current.use { receipt ->
                if (receipt.id != expectedId || receipt.accountId != expectedUserId) throw ConflictException()
                val terminal = when (receipt.phase) {
                    PasswordEncryptionPhase.READY -> receipt.closeView()
                    PasswordEncryptionPhase.OPENED, PasswordEncryptionPhase.UNKNOWN -> {
                        noWrites(state, passkey)
                        receipt.abandoned()
                    }
                    else -> return@locked
                }
                terminal.use { closed -> state.passwordEncryptionFlow = closed.encode(); verify(); session.save(state) }
            }
        }
        // Outside the vault lock: the sync worker takes the same lock when it runs.
        repository.schedule()
    }

    /** Unlocked keys from Google's account recovery reach the vault on its next sync. */
    fun syncSoon() { verify(); repository.schedule() }

    /**
     * Google's unlock reads the encryption this phone last synced. A sync first records encryption another device turned
     * on (it stops at the lock, keeping the new metadata for recovery), so the unlock opens for the vault Google has now.
     */
    fun prepareRecovery() {
        verify()
        try { repository.refresh() } catch (_: VaultLockedException) {}
        verify()
        repository.trustedVaultRecoveryParent(expectedUserId)
        verify()
    }

    enum class Status { OFF, ON, ON_LOCKED, OTHER }

    /** Google's current encryption setting, from one read of the account's metadata, and whether this phone can use it. */
    fun status(): Status {
        verify()
        val metadata = PasswordApi(transport).metadata()
        verify()
        return when (encryptionType(metadata)) {
            2L -> Status.OFF
            5L -> locked { session, _ ->
                try { repository.sharingKeys(metadata, session.load()).destroy(); Status.ON }
                catch (_: VaultLockedException) { Status.ON_LOCKED }
            }
            else -> Status.OTHER
        }
    }

    /** The owner may adopt a verified current vault without asserting that Google cancelled or completed the old request. */
    fun abandonVerified(before: PasswordEncryptionSnapshot, expectedId: String): Boolean = locked { session, passkey ->
        val current = PasswordEncryptionState.decode(session.load().passwordEncryptionFlow) ?: throw ConflictException()
        current.use { receipt ->
            if (receipt.id != expectedId || before.accountId != expectedUserId || receipt.accountId != expectedUserId || receipt.parent != before.parent ||
                receipt.phase !in setOf(PasswordEncryptionPhase.OPENED, PasswordEncryptionPhase.UNKNOWN) ||
                !MessageDigest.isEqual(before.digest(), receipt.baselineDigest)) throw ConflictException()
            noWrites(session.load(), passkey)
            readSnapshot().use { fresh ->
                val wrapping = try { repository.sharingKeys(fresh.metadata, session.load()) }
                catch (_: VaultLockedException) { return@locked false }
                wrapping.useKeys { validate(fresh, it) }
                verify()
                commit(session, passkey, fresh, receipt, abandoned = true)
                true
            }
        }
    }
    private fun validate(snapshot: PasswordEncryptionSnapshot, keys: VaultKeys) {
        snapshot.passwords.forEach { PasswordResourceCodec.plaintext(it, keys).encode().fill(0) }
        snapshot.wifi.forEach { PasswordWifiResource.read(it, keys) }
        SyncedPasskey.visible(snapshot.passkeys.map { PasskeyResourceCodec.read(it, keys) })
    }
    private fun commit(session: VaultSession, passkey: AndroidPasskeyStore.Session,
        fresh: PasswordEncryptionSnapshot, receipt: PasswordEncryptionState, abandoned: Boolean = false) {
        verify()
        val state = session.load()
        val changedParent = state.metadata?.let { Proto.decode(it).string(1) != fresh.parent } == true
        if (changedParent) {
            state.customKey?.fill(0); state.customKey = null
            state.trustedVaultKeys.forEach { it.fill(0) }; state.trustedVaultKeys.clear()
            state.versionedTrustedVaultKeys?.destroy(); state.versionedTrustedVaultKeys = null
            state.preferences.clear(); state.preferenceParent = null; state.deviceRegistration = null
            state.affiliationCache = null; state.directAffiliationProofs = null; state.sharingJournal = null
        }
        state.metadata = fresh.metadata; state.pendingMetadata = null
        state.records.clear(); state.records.addAll(fresh.passwords)
        state.chromeLocalData.keys.retainAll(fresh.passwords.map { Proto.decode(it).string(2) }.toSet())
        if (changedParent || !abandoned && receipt.operation == PasswordEncryptionOperation.CLEAR_PASSWORDS) state.wifiSync = null
        state.lastSyncMillis = System.currentTimeMillis()
        val encoded = PasskeyState(fresh.parent, fresh.passkeys, System.currentTimeMillis()).encode()
        try { passkey.save(encoded) } finally { encoded.fill(0) }
        (if (abandoned) receipt.abandoned() else receipt.confirmed()).use { terminal ->
            state.passwordEncryptionFlow = terminal.encode(); verify(); session.save(state)
        }
    }
    private class SnapshotBudget(var remaining: Long) {
        init { if (remaining < 0) throw ProtocolException() }
        fun consume(bytes: Int) {
            if (bytes + 16L > remaining) throw ProtocolException()
            remaining -= bytes + 16L
        }
    }
    private fun resourceSnapshot(parent: String, method: PasswordMethod, budget: SnapshotBudget): List<ByteArray> {
        val entries = ArrayList<ByteArray>(); val tokens = HashSet<String>(); val names = HashSet<String>(); val tags = HashSet<String>()
        var token: ByteArray? = null
        val limit = if (method == PasswordMethod.LIST) PasswordEncryptionSnapshot.MAX_RECORDS else 10_000
        repeat(if (method == PasswordMethod.LIST) 256 else 100) {
            val response = Proto.decode(transport.invoke(method, Proto.field(1, parent) +
                (token?.let { Proto.field(3, it) } ?: byteArrayOf())))
            for (entry in response.bytes(1)) {
                budget.consume(entry.size)
                val p = Proto.decode(entry)
                val name = when (method) {
                    PasswordMethod.LIST -> PasswordApi.checkedName(p.string(1)).also { if (!it.startsWith("$parent/passwords/")) throw ProtocolException() }
                    PasswordMethod.LIST_WIFI -> PasswordWifiApi.checkedId(p.string(1), parent)
                    PasswordMethod.LIST_WEBAUTHN -> PasskeyApi.checkedName(p.string(1), parent)
                    else -> throw ProtocolException()
                }
                if (!names.add(name) || !tags.add(p.string(2)) || entries.size >= limit) throw ProtocolException()
                entries.add(entry)
            }
            token = response.optional(2)?.takeIf { it.isNotEmpty() }
            if (token == null) return entries
            val tokenDigest = MessageDigest.getInstance("SHA-256").digest(token!!)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            if (!tokens.add(tokenDigest)) throw ProtocolException()
        }
        throw ProtocolException()
    }
    private inline fun <T> VaultKeys.useKeys(action: (VaultKeys) -> T): T = try { action(this) } finally { destroy() }
    private fun sharingPending(raw: ByteArray?): Boolean {
        if (raw == null) return false
        val p = Proto.decode(raw)
        if (p.integer(1) != 1L || p.bytes(2).size > 128) throw ProtocolException()
        return p.bytes(2).map(SharingOperation::decode).any { !it.phase.terminal }
    }
    private fun encryptionType(metadata: ByteArray) = Proto.decode(Proto.decode(metadata).one(3)).integer(2)
    private fun same(a: ByteArray?, b: ByteArray?) = if (a == null || b == null) a == null && b == null else MessageDigest.isEqual(a, b)
    private fun sameKeys(a: List<VersionedTrustedVaultKey>, b: List<VersionedTrustedVaultKey>) = a.size == b.size && a.all { key ->
        val other = b.singleOrNull { it.epoch == key.epoch } ?: return@all false
        val first = key.material; val second = other.material
        try { MessageDigest.isEqual(first, second) } finally { first.fill(0); second.fill(0) }
    }
    override fun toString() = "PasswordEncryptionRepository[redacted]"
}
