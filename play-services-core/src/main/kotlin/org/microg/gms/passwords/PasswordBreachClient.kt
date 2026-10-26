/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.AccountManager
import android.content.Context
import org.microg.gms.auth.AuthManager
import org.microg.gms.common.Constants
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** An owner operation, tied to one stable account and one vault birthday throughout assessment and commit. */
class PasswordBreachClient(context: Context, private val repository: PasswordRepository, private val expectedUserId: String,
    private val authorize: () -> Unit) : LeakCheckVault {
    private val context = context.applicationContext
    private val cancelled = AtomicBoolean(false)
    private val auth = AuthManager(this.context, repository.account.name, Constants.GMS_PACKAGE_NAME,
        "oauth2:https://www.googleapis.com/auth/identity.passwords.leak.check").apply {
        isGmsApp = true
        setPackageSignature(Constants.GMS_PACKAGE_SIGNATURE_SHA1)
    }
    private val oauth = PasswordOAuthSession({
        verify(); auth.setPermitted(true)
        auth.requestAuthWithBackgroundResolution(false).auth
    }, auth::invalidateAuthToken)
    private val transport = LeakCheckHttpTransport({ verify(); oauth.token() }, ::verify, oauth::reject)
    private val passwordTransport = repository.sharingTransport().let { source -> PasswordTransport { method, request ->
        verify(); source.invoke(method, request).also { verify() }
    } }
    fun cancel() { cancelled.set(true); transport.cancel() }
    private fun verify() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw LeakCheckCancelledException()
        authorize()
        val accounts = AccountManager.get(context)
        if (!accounts.getAccountsByType("com.google").contains(repository.account) ||
            accounts.getUserData(repository.account, "GoogleUserId") != expectedUserId || !repository.enabled) throw VaultLockedException()
    }
    fun assess(progress: (LeakCheckProgress) -> Unit): LeakCheckAssessment =
        LeakCheckEngine(LeakCheckProtocol(transport, ::verify), this, ::verify, progress).assess()

    private fun stateParent(state: VaultState): String {
        if (state.pendingMetadata != null) throw VaultResetException()
        if (state.mutations.isNotEmpty()) throw PendingMutationException()
        return PasswordApi.checkedParent(Proto.decode(state.metadata ?: throw VaultLockedException()).string(1))
    }
    private fun synchronize(parent: String) {
        verify()
        val saved = repository.store.locked { it.load() }
        val device = DeviceTokenProvider { _, state, expected ->
            verify()
            state.deviceRegistration?.takeIf { it.parent == expected }?.token?.copyOf() ?: throw VaultLockedException()
        }
        try {
            val receipt = VaultSync(PasswordApi(passwordTransport), repository.store, device = device).synchronize { metadata ->
                verify()
                if (Proto.decode(metadata).string(1) != parent) throw VaultResetException()
                repository.sharingKeys(metadata, saved)
            }
            if (receipt.conflicts != 0 || receipt.indeterminate != 0) throw PendingMutationException()
        } finally { saved.customKey?.fill(0); saved.trustedVaultKeys.forEach { it.fill(0) } }
        verify()
    }
    private fun passwords(state: VaultState, parent: String): List<PasswordData> {
        if (stateParent(state) != parent) throw VaultResetException()
        val keys = repository.sharingKeys(state.metadata ?: throw VaultLockedException(), state)
        return try { state.records.map { PasswordResourceCodec.read(it, keys) } } finally { keys.destroy() }
    }
    override fun snapshot(): LeakCheckSnapshot {
        verify()
        val parent = repository.store.locked { session -> stateParent(session.load()) }
        synchronize(parent)
        return repository.store.locked { session -> verify(); LeakCheckSnapshot(parent, passwords(session.load(), parent)) }
    }
    override fun commit(snapshot: LeakCheckSnapshot, changes: List<LeakCheckChange>) {
        verify()
        synchronize(snapshot.parent)
        repository.store.locked { session ->
            verify()
            val state = session.load()
            val current = passwords(state, snapshot.parent).associateBy { it.tag() }
            snapshot.verify(stateParent(state), current.values.toList())
            val keys = repository.sharingKeys(state.metadata ?: throw VaultLockedException(), state)
            try {
                val buffer = object : VaultSession {
                    override fun load() = state
                    override fun save(updated: VaultState) { if (updated !== state) throw ConflictException() }
                }
                val queue = VaultSync(PasswordApi(passwordTransport), repository.store)
                for (change in changes) {
                    verify()
                    val tag = change.original.tag()
                    if (change.replacement.tag() != tag || current[tag]?.let { MessageDigest.isEqual(it.encode(), change.original.encode()) } != true) throw ConflictException()
                    val existing = state.records.singleOrNull { Proto.decode(it).string(2) == tag } ?: throw ConflictException()
                    queue.enqueue(buffer, MutationKind.UPDATE, tag, PasswordResourceCodec.encrypt(change.replacement, keys, existing))
                }
                verify()
                if (changes.isNotEmpty()) session.save(state)
            } finally { keys.destroy() }
        }
        // The normal encrypted outbox owns any request already launched when cancellation arrives.
        // Cancellation never reports a fresh result and never replays an uncertain mutation.
        if (changes.isNotEmpty()) synchronize(snapshot.parent)
        repository.store.locked { session ->
            verify()
            val expected = snapshot.passwords.associateBy { it.tag() }.toMutableMap()
            changes.forEach { expected[it.original.tag()] = it.replacement }
            val current = passwords(session.load(), snapshot.parent).associateBy { it.tag() }
            LeakCheckSnapshot(snapshot.parent, expected.values.toList()).verify(snapshot.parent, current.values.toList())
        }
        verify()
    }
    override fun toString() = "PasswordBreachClient[redacted]"
}
