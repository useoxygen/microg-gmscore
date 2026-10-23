/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import org.microg.gms.auth.folsom.utils.LocalKeyManager
import java.security.MessageDigest

/** Account and birthday are pinned at selection; no plaintext private key is cached or returned by listing. */
class SyncedPasskeyVault(context: Context, val account: Account, val accountId: String, private val expectedParent: String? = null) {
    private val context = context.applicationContext
    private val accounts = AccountManager.get(this.context)
    private val repository = PasswordRepository(this.context, account)
    private val store = AndroidPasskeyStore(this.context, "google:$accountId", ::check)
    init { check() }
    fun check() {
        if (accountId.isBlank() || account.type != "com.google" || !accounts.getAccountsByType("com.google").contains(account) ||
            accounts.getUserData(account, "GoogleUserId") != accountId || !repository.enabled) throw VaultLockedException()
    }
    private fun <T> keys(action: (ByteArray, VaultKeys) -> T): T = repository.store.locked { session ->
        check()
        val state = session.load()
        val metadata = state.metadata ?: throw VaultLockedException()
        if (expectedParent != null && Proto.decode(metadata).string(1) != expectedParent) throw VaultResetException()
        val keys = repository.sharingKeys(metadata, state)
        try { action(metadata, keys).also { check() } } finally { keys.destroy() }
    }
    fun parent(): String = keys { metadata, _ -> PasswordApi.checkedParent(Proto.decode(metadata).string(1)) }
    fun list(refresh: Boolean = false): List<SyncedPasskey> {
        check()
        if (refresh) refresh()
        return keys { metadata, keys -> store.locked { session ->
            val state = session.state()
            if (state.parent == null) return@locked emptyList()
            if (state.parent != Proto.decode(metadata).string(1)) throw VaultResetException()
            SyncedPasskey.visible(state.records.map { PasskeyResourceCodec.read(it, keys) })
        } }
    }
    fun refresh() {
        check()
        // Password metadata/registration already has the account's owner opt-in. Do not initialize a new vault here.
        keys { metadata, keys -> store.locked { session ->
            val parent = PasswordApi.checkedParent(Proto.decode(metadata).string(1))
            val records = PasskeyApi(repository.sharingTransport()).snapshot(parent)
            val replacement = PasskeySnapshots.reconcile(session.state(), parent, records, keys, System.currentTimeMillis())
            check(); session.saveState(replacement)
        } }
    }
    fun hasDomainKeys(): Boolean = domainKeys().let { keys -> keys.isNotEmpty().also { keys.forEach(PasskeyDomainKey::close) } }
    private fun domainKeys(): List<PasskeyDomainKey> {
        check()
        val keys = LocalKeyManager.getInstance(context).getKeysForDomain(account.name, "hw_protected")
        val result = ArrayList<PasskeyDomainKey>()
        try {
            for (key in keys) {
                val raw = key.keyMaterial?.toByteArray() ?: continue
                try { if (raw.size == 32) result.add(PasskeyDomainKey(key.keyVersion ?: 0, raw)) } finally { raw.fill(0) }
            }
            check(); return result
        } catch (failure: Throwable) { result.forEach(PasskeyDomainKey::close); throw failure }
    }
    fun <T> withSecret(tag: String, rpId: String, allowed: List<ByteArray>, verifyOwner: () -> Unit,
        action: (SyncedPasskey, PasskeySecret) -> T): T {
        verifyOwner(); check()
        val selectedParent = parent()
        // Re-fetch before redemption: a remote deletion, reset or shadowing is authoritative.
        val selected = list(refresh = true).singleOrNull { it.tag == tag && it.rpId == rpId &&
            (allowed.isEmpty() || allowed.any { id -> MessageDigest.isEqual(id, it.credentialId) }) } ?: throw VaultLockedException()
        return keys { metadata, _ ->
            if (Proto.decode(metadata).string(1) != selectedParent) throw VaultResetException()
            val domains = domainKeys()
            var devices = emptyList<PasskeyDomainKey>()
            try {
                if (selected.encryptedField == 19) devices = PasskeyDeviceKeyClient(context, repository.sharingTransport()) {
                    verifyOwner(); check()
                }.get(setOf(selected.deviceKeyVersion), domains.isNotEmpty())
                PasskeyCrypto.decrypt(selected, domains, devices).use { secret ->
                    verifyOwner(); check()
                    action(selected, secret).also { verifyOwner(); check() }
                }
            } finally { domains.forEach(PasskeyDomainKey::close); devices.forEach(PasskeyDomainKey::close) }
        }
    }
    /** Persist launch before the RPC; an uncertain create is reconciled by full snapshots, never blindly retried. */
    fun create(specifics: SyncedPasskey, verifyOwner: () -> Unit, excluded: List<ByteArray> = emptyList()) {
        verifyOwner(); check()
        val token = repository.store.locked { it.load().deviceRegistration?.token?.copyOf() } ?: throw VaultLockedException()
        try { keys { metadata, keys -> store.locked { session ->
            val old = session.state()
            val parent = PasswordApi.checkedParent(Proto.decode(metadata).string(1))
            if (old.pending != null) throw PendingMutationException()
            val api = PasskeyApi(repository.sharingTransport())
            val snapshot = api.snapshot(parent)
            val state = PasskeySnapshots.reconcile(old, parent, snapshot, keys, System.currentTimeMillis())
            val previous = state.records.map { PasskeyResourceCodec.read(it, keys) }
            if (excluded.any { id -> previous.any { MessageDigest.isEqual(id, it.credentialId) } }) throw ConflictException()
            val actual = specifics.withShadows(previous.filter { it.rpId == specifics.rpId &&
                MessageDigest.isEqual(it.userId, specifics.userId) }.map { it.credentialId })
            val resource = PasskeyResourceCodec.write(actual, keys)
            session.saveState(PasskeyState(parent, state.records, state.fetchedMillis, resource, false, state.abandoned))
            var launched = false
            try {
                verifyOwner(); check()
                session.saveState(PasskeyState(parent, state.records, state.fetchedMillis, resource, true, state.abandoned))
                verifyOwner(); check()
                launched = true
                val result = api.create(parent, resource, token)
                val returned = PasskeyResourceCodec.read(result, keys)
                if (!returned.sameRegistration(actual)) throw ProtocolException()
                PasskeyApi.checkedName(Proto.decode(result).string(1), parent)
                verifyOwner(); check()
                session.saveState(PasskeyState(parent, state.records + listOf(result), System.currentTimeMillis(), abandoned = state.abandoned))
            } catch (failure: Exception) {
                if (!launched || failure is PasswordRpcException && !failure.outcomeUnknown)
                    session.saveState(PasskeyState(parent, state.records, state.fetchedMillis, abandoned = state.abandoned))
                throw failure
            }
        } } } finally { token.fill(0) }
    }
    fun hasPendingCreate(): Boolean = store.locked { it.state().pending != null }
    /** Explicit owner closure retains the old attempt and birthday; it does not delete a possible remote credential. */
    fun discardPendingCreate(verifyOwner: () -> Unit) = store.locked { session ->
        verifyOwner(); check()
        val state = session.state()
        val pending = state.pending ?: return@locked
        if (state.abandoned.size >= 1000) throw ProtocolException()
        val history = Proto.field(1, state.parent ?: throw ProtocolException()) + Proto.field(2, pending) +
            Proto.integer(3, if (state.pendingLaunched) 1 else 0) + Proto.integer(4, System.currentTimeMillis())
        session.saveState(PasskeyState(state.parent, state.records, state.fetchedMillis, abandoned = state.abandoned + listOf(history)))
    }
    fun createSpecifics(rp: String, user: ByteArray, username: String, display: String, pkcs8: ByteArray,
        verifyOwner: () -> Unit): SyncedPasskey {
        val domains = domainKeys()
        var devices = emptyList<PasskeyDomainKey>()
        try {
            val key = domains.maxByOrNull { it.version } ?: throw VaultLockedException()
            if (key.version <= 0) throw VaultLockedException()
            devices = PasskeyDeviceKeyClient(context, repository.sharingTransport()) { verifyOwner(); check() }
                .get(emptySet(), true)
            val device = devices.maxByOrNull { it.version } ?: throw VaultLockedException()
            val secret = Proto.field(1, pkcs8) + Proto.field(2, ByteArray(32).also(java.security.SecureRandom()::nextBytes))
            val random = java.security.SecureRandom()
            return try { SyncedPasskey.decode(Proto.field(1, ByteArray(16).also(random::nextBytes)) +
                Proto.field(2, ByteArray(16).also(random::nextBytes)) + Proto.field(3, SyncedPasskey.checkedRp(rp)) + Proto.field(4, user) +
                Proto.integer(6, System.currentTimeMillis()) + Proto.field(7, username) + Proto.field(8, display) +
                Proto.field(19, PasskeyCrypto.encryptWithDevice(secret, key, device)) + Proto.integer(14, key.version.toLong()) +
                Proto.integer(18, device.version.toLong())) }
            finally { secret.fill(0) }
        } finally { domains.forEach(PasskeyDomainKey::close); devices.forEach(PasskeyDomainKey::close) }
    }
    private fun AndroidPasskeyStore.Session.state(): PasskeyState { val raw = load(); return try { PasskeyState.decode(raw) } finally { raw.fill(0) } }
    private fun AndroidPasskeyStore.Session.saveState(state: PasskeyState) { val raw = state.encode(); try { save(raw) } finally { raw.fill(0) } }
    override fun toString(): String = "SyncedPasskeyVault[redacted]"
    companion object {
        fun accounts(context: Context): List<SyncedPasskeyVault> {
            val manager = AccountManager.get(context)
            return manager.getAccountsByType("com.google").take(20).mapNotNull { account -> runCatching {
                val id = manager.getUserData(account, "GoogleUserId")?.takeIf { it.isNotBlank() } ?: return@runCatching null
                SyncedPasskeyVault(context, account, id)
            }.getOrNull() }
        }
        fun purgeRemovedAccounts(context: Context) = AndroidPasskeyStore.purge(context) {
            val manager = AccountManager.get(context)
            manager.getAccountsByType("com.google").map { account ->
                val id = manager.getUserData(account, "GoogleUserId")?.takeIf { it.isNotBlank() } ?: throw VaultLockedException()
                "google:$id"
            }.toSet()
        }
    }
}
