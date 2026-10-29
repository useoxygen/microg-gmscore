/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.microg.gms.cyclon.DeviceAccount
import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import okio.ByteString.Companion.toByteString
import org.microg.gms.auth.folsom.utils.LocalKeyManager
import java.security.MessageDigest

class SyncedPasskeyOwnerEntry(val resource: ByteArray, val data: SyncedPasskey, val shadowed: Boolean, val deletionCount: Int) {
    override fun toString() = "SyncedPasskeyOwnerEntry[redacted]"
}
class SyncedPasskeyOwnerScreen(val parent: String, val entries: List<SyncedPasskeyOwnerEntry>,
    val operation: PasskeyOwnerMutation?, val reset: Boolean, val pendingCreate: Boolean,
    val operationEntries: List<SyncedPasskey> = emptyList()) {
    override fun toString() = "SyncedPasskeyOwnerScreen[redacted]"
}

/** Account and birthday are pinned at selection; no plaintext private key is cached or returned by listing. */
class SyncedPasskeyVault(context: Context, val account: Account, val accountId: String, private val expectedParent: String? = null) {
    private val context = context.applicationContext
    private val accounts = AccountManager.get(this.context)
    init { checkIdentity() }
    private val repository = PasswordRepository(this.context, account, expectedParent)
    private val store = AndroidPasskeyStore(this.context, "google:$accountId", ::check)
    init { check() }
    private fun checkIdentity() {
        if (accountId.isBlank() || account.type != "com.google" || !accounts.getAccountsByType("com.google").contains(account) ||
            accounts.getUserData(account, "GoogleUserId") != accountId) throw VaultLockedException()
    }
    fun check() { checkIdentity(); if (!repository.enabled) throw VaultLockedException() }
    private fun checkRecoveryAuthority() {
        check()
        val local = LocalKeyManager.getInstance(context)
        if (local.hasPendingDomainReset(account.name, "chromesync") || local.hasPendingDomainReset(account.name, "hw_protected"))
            throw VaultLockedException()
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
            // A pending owner delete cannot be used for signing while its remote outcome is unresolved.
            val denied = PasskeyOwnerMutations.signingDenied(state)
            SyncedPasskey.visible(state.records.filter { Proto.decode(it).string(1) !in denied }.map { PasskeyResourceCodec.read(it, keys) })
        } }
    }
    fun refresh() {
        check()
        // Password metadata/registration already has the account's owner opt-in. Do not initialize a new vault here.
        keys { metadata, keys -> store.locked { session ->
            val parent = PasswordApi.checkedParent(Proto.decode(metadata).string(1))
            session.state().ownerMutation?.let { if (it.accountId != accountId) throw VaultLockedException() }
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
    fun <T> withSecret(tag: String, rpId: String, allowed: List<ByteArray>, verifyOwner: () -> Unit, rapt: String? = null,
        secretUpdate: ((SyncedPasskey, PasskeySecret) -> ByteArray?)? = null, requestBinding: ByteArray? = null,
        action: (SyncedPasskey, PasskeySecret) -> T): T =
        LocalKeyManager.getInstance(context).withRecoveryAuthority(account.name, accountId) {
        // The recovery lock precedes the password/passkey locks, matching lifecycle operations.
        // Reset cannot finish between opening a secret and returning a signed result.
        verifyOwner(); checkRecoveryAuthority()
        val token = if (secretUpdate != null) repository.store.locked { it.load().deviceRegistration?.token?.copyOf() } ?: byteArrayOf() else byteArrayOf()
        try {
        val selectedParent = parent()
        // Re-fetch before redemption: a remote deletion, reset or shadowing is authoritative.
        val selected = list(refresh = true).singleOrNull { it.tag == tag && it.rpId == rpId &&
            (allowed.isEmpty() || allowed.any { id -> MessageDigest.isEqual(id, it.credentialId) }) } ?: throw VaultLockedException()
        return@withRecoveryAuthority keys { metadata, wrapping -> store.locked { session ->
            if (Proto.decode(metadata).string(1) != selectedParent) throw VaultResetException()
            // Serialize local management through the actual signing callback, not just the earlier picker snapshot.
            val state = session.state()
            if (state.parent != selectedParent) throw VaultResetException()
            val denied = PasskeyOwnerMutations.signingDenied(state)
            val current = SyncedPasskey.visible(state.records.filter { Proto.decode(it).string(1) !in denied }
                .map { PasskeyResourceCodec.read(it, wrapping) }).singleOrNull { it.tag == selected.tag && it.sameRegistration(selected) }
                ?: throw VaultLockedException()
            val domains = domainKeys()
            var devices = emptyList<PasskeyDomainKey>()
            try {
                if (current.encryptedField == 19) devices = PasskeyDeviceKeyClient(context, repository.sharingTransport()) {
                    verifyOwner(); check()
                }.get(setOf(current.deviceKeyVersion), domains.isNotEmpty(), rapt)
                PasskeyCrypto.decrypt(current, domains, devices).use opened@ { secret ->
                    verifyOwner(); check()
                    val after = secretUpdate?.invoke(current, secret)
                    try {
                        if (after != null) {
                            val before = secret.encode()
                            try {
                                if (!MessageDigest.isEqual(before, after)) {
                                    val binding = requestBinding?.takeIf { it.size == 32 } ?: throw ProtocolException()
                                    val replacement = PasskeyCrypto.updateSecret(current, before, after, domains, devices)
                                    val baseline = state.records.singleOrNull { Proto.decode(it).string(2) == current.tag } ?: throw ConflictException()
                                    val manager = PasskeyOwnerManagement(accountId, selectedParent, wrapping,
                                        PasskeyApi(repository.sharingTransport()), token, { session.state() },
                                        { session.saveState(it) }, { verifyOwner(); check() })
                                    manager.secretUpdate(baseline, replacement, binding)
                                    val committed = session.state().records.singleOrNull { Proto.decode(it).string(2) == current.tag }
                                        ?.let { PasskeyResourceCodec.read(it, wrapping) } ?: throw ConflictException()
                                    return@opened PasskeyCrypto.decrypt(committed, domains, devices).use { persisted ->
                                        verifyOwner(); check(); action(committed, persisted).also { verifyOwner(); check() }
                                    }
                                }
                            } finally { before.fill(0) }
                        }
                        action(current, secret).also { verifyOwner(); check() }
                    } finally { after?.fill(0) }
                }
            } finally { domains.forEach(PasskeyDomainKey::close); devices.forEach(PasskeyDomainKey::close) }
        } }
        } finally { token.fill(0) }
    }
    /** Persist launch before the RPC; an uncertain create is reconciled by full snapshots, never blindly retried. */
    fun create(specifics: SyncedPasskey, verifyOwner: () -> Unit, excluded: List<ByteArray> = emptyList()) =
        LocalKeyManager.getInstance(context).withRecoveryAuthority(account.name, accountId) {
        verifyOwner(); checkRecoveryAuthority()
        // A reset between preparing the encrypted registration and its commit invalidates it,
        // including a recreated domain that reuses the same numeric epoch with a different key.
        val liveDomains = domainKeys()
        try { PasskeyCrypto.requireDomainAuthority(specifics, liveDomains) }
        finally { liveDomains.forEach(PasskeyDomainKey::close) }
        val token = repository.store.locked { it.load().deviceRegistration?.token?.copyOf() } ?: throw VaultLockedException()
        try { keys { metadata, keys -> store.locked { session ->
            val old = session.state()
            val parent = PasswordApi.checkedParent(Proto.decode(metadata).string(1))
            if (old.pending != null || old.ownerMutation != null) throw PendingMutationException()
            val api = PasskeyApi(repository.sharingTransport())
            val snapshot = api.snapshot(parent)
            val state = PasskeySnapshots.reconcile(old, parent, snapshot, keys, System.currentTimeMillis())
            val previous = state.records.map { PasskeyResourceCodec.read(it, keys) }
            if (PasskeyExclusions.contains(specifics.rpId, excluded, previous)) throw ConflictException()
            val actual = specifics.withShadows(previous.filter { it.rpId == specifics.rpId &&
                MessageDigest.isEqual(it.userId, specifics.userId) }.map { it.credentialId })
            val resource = PasskeyResourceCodec.write(actual, keys)
            session.saveState(PasskeyState(parent, state.records, state.fetchedMillis, resource, false, state.abandoned,
                ownerHistory = state.ownerHistory))
            var launched = false
            try {
                verifyOwner(); check()
                session.saveState(PasskeyState(parent, state.records, state.fetchedMillis, resource, true, state.abandoned,
                    ownerHistory = state.ownerHistory))
                verifyOwner(); check()
                launched = true
                val result = api.create(parent, resource, token)
                val returned = PasskeyResourceCodec.read(result, keys)
                if (!returned.sameRegistration(actual)) throw ProtocolException()
                PasskeyApi.checkedName(Proto.decode(result).string(1), parent)
                verifyOwner(); check()
                session.saveState(PasskeyState(parent, state.records + listOf(result), System.currentTimeMillis(), abandoned = state.abandoned,
                    ownerHistory = state.ownerHistory))
            } catch (failure: Exception) {
                if (!launched || failure is PasswordRpcException && !failure.outcomeUnknown)
                    session.saveState(PasskeyState(parent, state.records, state.fetchedMillis, abandoned = state.abandoned,
                        ownerHistory = state.ownerHistory))
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
        session.saveState(PasskeyState(state.parent, state.records, state.fetchedMillis, abandoned = state.abandoned + listOf(history),
            ownerMutation = state.ownerMutation, ownerHistory = state.ownerHistory))
    }
    private fun <T> ownerAction(verifyOwner: () -> Unit,
        action: (PasskeyOwnerManagement, AndroidPasskeyStore.Session, String, VaultKeys) -> T): T {
        verifyOwner(); check()
        val token = repository.store.locked { it.load().deviceRegistration?.token?.copyOf() } ?: byteArrayOf()
        return try { keys { metadata, keys -> store.locked { session ->
            verifyOwner(); check()
            val parent = PasswordApi.checkedParent(Proto.decode(metadata).string(1))
            session.state().ownerMutation?.let { if (it.accountId != accountId) throw VaultLockedException() }
            val manager = PasskeyOwnerManagement(accountId, parent, keys, PasskeyApi(repository.sharingTransport()), token,
                { session.state() }, { session.saveState(it) }, { verifyOwner(); check() })
            action(manager, session, parent, keys).also { verifyOwner(); check() }
        } } } finally { token.fill(0) }
    }
    fun ownerScreen(verifyOwner: () -> Unit, refresh: Boolean = true): SyncedPasskeyOwnerScreen =
        ownerAction(verifyOwner) { manager, session, parent, keys ->
            var state = session.state()
            if (state.parent != null && state.parent != parent && (state.ownerMutation != null || state.pending != null))
                return@ownerAction SyncedPasskeyOwnerScreen(parent, emptyList(), state.ownerMutation, true, state.pending != null)
            if (refresh) state = manager.refresh()
            if (state.parent != null && state.parent != parent) return@ownerAction SyncedPasskeyOwnerScreen(parent, emptyList(), null, false, false)
            val all = state.records.map { PasskeyResourceCodec.read(it, keys) }
            fun id(raw: ByteArray) = raw.toByteString().base64()
            val shadowed = all.flatMap { item -> item.shadowedCredentialIds.map { item.rpId to id(it) } }.toSet()
            val groups = all.groupingBy { it.rpId to id(it.userId) }.eachCount()
            val entries = state.records.zip(all).map { (raw, data) -> SyncedPasskeyOwnerEntry(raw.copyOf(), data,
                (data.rpId to id(data.credentialId)) in shadowed,
                if ((data.rpId to id(data.credentialId)) in shadowed) 1
                else groups[data.rpId to id(data.userId)] ?: throw ProtocolException()) }
                .sortedWith(compareBy({ it.data.rpId }, { it.data.username }, { it.data.tag }))
            SyncedPasskeyOwnerScreen(parent, entries, state.ownerMutation, false, state.pending != null,
                state.ownerMutation?.steps?.map { PasskeyResourceCodec.read(it.before, keys) }.orEmpty())
        }
    fun ownerRename(baseline: ByteArray, username: String, displayName: String, verifyOwner: () -> Unit) =
        ownerAction(verifyOwner) { manager, _, _, _ -> manager.rename(baseline, username, displayName) }
    fun ownerDelete(baseline: ByteArray, reviewedGroup: List<ByteArray>, verifyOwner: () -> Unit) =
        ownerAction(verifyOwner) { manager, _, _, _ -> manager.delete(baseline, reviewedGroup) }
    fun ownerRetry(id: String, verifyOwner: () -> Unit) =
        ownerAction(verifyOwner) { manager, _, _, _ -> manager.retry(id) }
    fun ownerClose(id: String, verifyOwner: () -> Unit) =
        ownerAction(verifyOwner) { manager, _, _, _ -> manager.close(id) }
    fun createSpecifics(rp: String, user: ByteArray, username: String, display: String, pkcs8: ByteArray,
        verifyOwner: () -> Unit, rapt: String? = null, secretDecorator: ((ByteArray) -> ByteArray)? = null): SyncedPasskey {
        val domains = domainKeys()
        var devices = emptyList<PasskeyDomainKey>()
        try {
            val key = domains.maxByOrNull { it.version } ?: throw VaultLockedException()
            if (key.version <= 0) throw VaultLockedException()
            devices = PasskeyDeviceKeyClient(context, repository.sharingTransport()) { verifyOwner(); check() }
                .get(emptySet(), true, rapt)
            val device = devices.maxByOrNull { it.version } ?: throw VaultLockedException()
            val generated = Proto.field(1, pkcs8) + Proto.field(2, ByteArray(32).also(java.security.SecureRandom()::nextBytes))
            val secret = try { secretDecorator?.invoke(generated) ?: generated.copyOf() } finally { generated.fill(0) }
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
            DeviceAccount.visible(context, manager.getAccountsByType("com.google")).map { account ->
                val id = manager.getUserData(account, "GoogleUserId")?.takeIf { it.isNotBlank() } ?: throw VaultLockedException()
                "google:$id"
            }.toSet()
        }
    }
}
