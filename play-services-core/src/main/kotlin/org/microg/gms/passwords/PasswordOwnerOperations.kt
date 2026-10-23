/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context

/** All transfer inputs are saved under a separate Android Keystore key before either store changes. */
class PasswordOwnerOperations(context: Context) {
    private val context = context.applicationContext
    private val accounts = AccountManager.get(this.context)
    private val journal = AndroidVaultStore(this.context, "owner-password-transfers-v1")
    private val engine = PasswordTransferEngine(journal, ::vault)
    fun identity(account: Account?): PasswordStoreIdentity = if (account == null) PasswordStoreIdentity() else {
        if (account.type != "com.google" || !accounts.getAccountsByType("com.google").contains(account)) throw VaultLockedException()
        PasswordStoreIdentity(account.name, accounts.getUserData(account, "GoogleUserId")?.takeIf { it.isNotBlank() } ?: throw VaultLockedException())
    }
    private fun vault(identity: PasswordStoreIdentity): ManagedPasswordVault {
        if (identity.accountName == null) return DevicePasswordVault(context)
        val account = accounts.getAccountsByType("com.google").singleOrNull { it.name == identity.accountName } ?: throw VaultLockedException()
        if (accounts.getUserData(account, "GoogleUserId") != identity.userId) throw VaultLockedException()
        return PasswordRepository(context, account, identity.vaultParent).also { if (!it.enabled) throw VaultLockedException() }
    }
    fun verifyIdentity(identity: PasswordStoreIdentity) { vault(identity) }
    fun pending(): List<PasswordTransferOperation> { purgeRemovedAccounts(context); return engine.operations() }
    private fun transferIdentity(account: Account?): PasswordStoreIdentity {
        val identity = identity(account)
        if (account == null) return identity
        val repository = vault(identity) as PasswordRepository
        repository.refresh()
        val parent = repository.store.locked { session -> Proto.decode(session.load().metadata ?: throw VaultLockedException()).string(1) }
        return PasswordStoreIdentity(identity.accountName, identity.userId, parent)
    }
    fun move(source: Account?, destination: Account?, original: ByteArray, desired: ByteArray = original): String {
        val from = transferIdentity(source)
        val to = if (source == destination) from else transferIdentity(destination)
        return engine.start(from, to, original, desired)
    }
    fun resume(id: String) { purgeRemovedAccounts(context); engine.resume(id) }
    fun drop(id: String) { purgeRemovedAccounts(context); engine.drop(id) }
    /** Queue every import before launching any RPC. Existing entries are never overwritten. */
    fun import(destination: Account?, passwords: List<PasswordData>): List<String> {
        val target = transferIdentity(destination)
        if (passwords.size > 1000 || passwords.map { it.tag() }.toSet().size != passwords.size) throw ProtocolException()
        return journal.locked { session ->
            val state = session.load()
            if (passwords.size + state.ownerTransfers.size > 1000) throw ProtocolException()
            val existing = state.ownerTransfers.filter { it.destination.same(target) }
                .map { PasswordData.decode(Proto.decode(it.desired).one(1)).tag() }.toSet()
            if (passwords.any { it.tag() in existing }) throw PendingMutationException()
            val operations = passwords.map { data -> PasswordTransferOperation(java.util.UUID.randomUUID().toString(), null, target, null, Proto.field(1, data.encode())) }
            state.ownerTransfers.addAll(operations); session.save(state)
            operations.map { it.id }
        }
    }
    companion object {
        /** Invoke from account-removal cleanup as well as before exposing any recovery journal. */
        fun purgeRemovedAccounts(context: Context) {
            val accounts = AccountManager.get(context)
            fun discover(): Map<String, String> = accounts.getAccountsByType("com.google").associate {
                it.name to (accounts.getUserData(it, "GoogleUserId")?.takeIf { id -> id.isNotBlank() } ?: throw VaultLockedException())
            }
            AndroidVaultStore(context, "owner-password-transfers-v1").locked { session ->
                val identities = discover()
                fun present(identity: PasswordStoreIdentity?): Boolean = identity?.accountName == null ||
                    identities[identity.accountName] == identity.userId
                val state = session.load()
                val changed = state.ownerTransfers.removeAll { !present(it.source) || !present(it.destination) }
                if (discover() != identities) throw VaultLockedException()
                if (changed) session.save(state)
            }
        }
    }
}
