/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.app.Application
import android.app.KeyguardManager
import android.accounts.AccountManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import org.microg.gms.auth.folsom.utils.LocalKeyManager
import java.security.MessageDigest

/** Test-APK-only authority. It cannot enable a vault or authorize a credential acceptance driver. */
internal class FolsomFlagsReceipt(val name: String, val accountId: String, val issued: Long,
    val boot: Int, val run: String) {
    init {
        PasswordAcceptanceAccounts.checked(run, "primary") // UUID validation only; this is never a primary receipt.
        check(name.isNotBlank() && accountId.isNotBlank() && name.length <= 1024 && accountId.length <= 1024 &&
            listOf(name, accountId).none { value -> value.any(Char::isISOControl) } && issued >= 0 && boot >= 0)
    }
    fun encode(): ByteArray = Proto.field(1, name) + Proto.field(2, accountId) + Proto.integer(3, issued) +
        Proto.integer(4, boot.toLong()) + Proto.field(5, run) + Proto.field(6, "flags") + Proto.field(7, PURPOSE)
    fun verify(expectedRun: String, currentBoot: Int, now: Long, currentName: String?, currentId: String?) {
        check(run == expectedRun && boot == currentBoot && now >= issued && now - issued < 30 * 60 * 1000L &&
            name == currentName && accountId == currentId)
    }
    override fun toString() = "FolsomFlagsReceipt[redacted]"
    companion object {
        private const val PURPOSE = "folsom-device-flags-only-v1"
        fun decode(raw: ByteArray): FolsomFlagsReceipt = Proto.decode(raw).let { p ->
            check(p.string(6) == "flags" && p.string(7) == PURPOSE)
            val boot = p.integer(4)?.takeIf { it in 0..Int.MAX_VALUE } ?: throw ProtocolException()
            FolsomFlagsReceipt(p.string(1), p.string(2), p.integer(3) ?: throw ProtocolException(), boot.toInt(), p.string(5))
        }
    }
}

internal object FolsomFlagsAcceptanceAccount {
    private fun verifyNative(context: Context) {
        check(com.google.android.gms.BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 28 &&
            Application.getProcessName() == "com.google.android.gms:ui")
        LegacyPasskeyTicketStore.system(context)
        val lock = context.getSystemService(KeyguardManager::class.java)
        check(!lock.isDeviceLocked && !lock.isKeyguardLocked)
    }
    private fun boot(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        .also { check(it >= 0) }
    private fun store(context: Context, run: String, verify: () -> Unit): AndroidPasskeyStore {
        PasswordAcceptanceAccounts.checked(run, "primary")
        // Ordinary primary/family resolvers never read this namespace, even for the same run UUID.
        return PasswordAcceptanceStorage.store(context, "folsom-flags-only/$run", verify)
    }
    private fun sameCandidates(first: List<PasswordAcceptanceAccounts.Selected>, second: List<PasswordAcceptanceAccounts.Selected>) =
        first.size == second.size && first.all { wanted -> second.count { it.account == wanted.account && it.accountId == wanted.accountId } == 1 }
    private fun stableCandidates(context: Context): List<PasswordAcceptanceAccounts.Selected> {
        val installed = AccountManager.get(context).getAccountsByType("com.google").toList()
        val candidates = PasswordAcceptanceAccounts.candidates(context)
        // Missing stable IDs or the bounded helper's truncation must never hide a possible second fixture.
        check(installed.size == candidates.size && candidates.size < 20 &&
            installed.all { account -> candidates.count { it.account == account } == 1 })
        candidates.forEach { PasswordAcceptanceAccounts.verify(context, it) }
        return candidates
    }

    /** Explicit current-task admission, never receipt renewal or implicit password-sync opt-in. */
    fun admit(context: Context, run: String) {
        verifyNative(context); PasswordAcceptanceAccounts.checked(run, "primary")
        val candidates = stableCandidates(context)
        // An unreadable or blocked account aborts the scan; it must not silently turn ambiguity into a singleton.
        val selected = uniqueFixture(candidates) { selected -> withCachedMarker(context, selected) { it } }
        withCachedMarker(context, selected) { matches ->
            check(matches && sameCandidates(candidates, stableCandidates(context)))
            fun verify() { verifyNative(context); PasswordAcceptanceAccounts.verify(context, selected) }
            verify()
            val receipt = FolsomFlagsReceipt(selected.account.name, selected.accountId, SystemClock.elapsedRealtime(), boot(context), run)
            val raw = receipt.encode()
            try { store(context, run, ::verify).locked { session ->
                verify()
                val previous = session.load()
                try { check(previous.isEmpty()) } finally { previous.fill(0) }
                session.save(raw); verify()
            } }
            finally { raw.fill(0) }
        }
        resolve(context, run)
    }

    internal fun uniqueFixture(candidates: List<PasswordAcceptanceAccounts.Selected>,
        hasMarker: (PasswordAcceptanceAccounts.Selected) -> Boolean): PasswordAcceptanceAccounts.Selected {
        check(candidates.isNotEmpty() && candidates.size < 20 && candidates.map { it.accountId }.toSet().size == candidates.size)
        return candidates.filter(hasMarker).singleOrNull() ?: throw VaultLockedException()
    }

    fun resolve(context: Context, run: String): PasswordAcceptanceAccounts.Selected {
        verifyNative(context)
        return store(context, run) { verifyNative(context) }.locked { session ->
            val raw = session.load()
            try {
                val receipt = FolsomFlagsReceipt.decode(raw)
                val selected = stableCandidates(context).singleOrNull {
                    it.account.name == receipt.name && it.accountId == receipt.accountId
                } ?: throw VaultLockedException()
                receipt.verify(run, boot(context), SystemClock.elapsedRealtime(), selected.account.name, selected.accountId)
                PasswordAcceptanceAccounts.verify(context, selected)
                val local = LocalKeyManager.getInstance(context)
                if (local.hasPendingDomainReset(selected.account.name, "chromesync") ||
                    local.hasPendingDomainReset(selected.account.name, "hw_protected")) throw PendingMutationException()
                verifyNative(context); selected
            } finally { raw.fill(0) }
        }
    }

    private fun <T> withCachedMarker(context: Context, selected: PasswordAcceptanceAccounts.Selected, action: (Boolean) -> T): T {
        val local = LocalKeyManager.getInstance(context)
        fun verify() {
            verifyNative(context); PasswordAcceptanceAccounts.verify(context, selected)
            if (local.hasPendingDomainReset(selected.account.name, "chromesync") ||
                local.hasPendingDomainReset(selected.account.name, "hw_protected")) throw PendingMutationException()
        }
        return PasswordOwnerOperations(context).withAccountTransfersQuiesced(selected.accountId) {
            local.withRecoveryAuthority(selected.account.name, selected.accountId) {
                verify()
                val vault = AndroidVaultStore(context, "google:" + selected.accountId, ::verify)
                val passkeys = AndroidPasskeyStore(context, "google:" + selected.accountId, ::verify)
                vault.locked { session -> passkeys.locked { passkey ->
                    val enabled = vault.enabled
                    val state = session.load()
                    val passkeyRaw = passkey.load()
                    val before = state.encode()
                    try {
                        val saved = PasskeyState.decode(passkeyRaw)
                        if (saved.pending != null || saved.ownerMutation != null) throw PendingMutationException()
                        val matches = cachedMarker(state)
                        verify()
                        action(matches).also {
                            verify(); check(vault.enabled == enabled)
                            val after = session.load().encode()
                            try { check(MessageDigest.isEqual(before, after)) } finally { after.fill(0) }
                        }
                    } finally {
                        before.fill(0); passkeyRaw.fill(0)
                        state.customKey?.fill(0); state.trustedVaultKeys.forEach { it.fill(0) }
                        state.versionedTrustedVaultKeys?.destroy(); state.metadata?.fill(0); state.pendingMetadata?.fill(0)
                    }
                } }
            }
        }
    }

    /** No repository, transport, key retrieval, save, or unrelated credential decryption is reachable here. */
    internal fun cachedMarker(state: VaultState): Boolean {
        if (state.pendingMetadata != null || state.mutations.isNotEmpty() || state.preferenceMutations.isNotEmpty() ||
            state.ownerTransfers.isNotEmpty() || PasswordWifiState.decode(state.wifiSync).pending != 0 ||
            PasswordEncryptionState.fences(state.passwordEncryptionFlow)) throw PendingMutationException()
        state.sharingJournal?.let { raw ->
            val p = Proto.decode(raw)
            if (p.integer(1) != 1L || p.bytes(2).size > 128) throw ProtocolException()
            if (p.bytes(2).map(SharingOperation::decode).any { !it.phase.terminal }) throw PendingMutationException()
        }
        val tags = HashSet<String>()
        val candidates = state.records.filter { raw ->
            val record = Proto.decode(raw)
            check(tags.add(record.string(2)))
            record.optional(8)?.let { Proto.decode(it).optionalString(3) } == PasswordAcceptanceAccounts.REALM
        }
        if (candidates.isEmpty()) return false
        check(candidates.size == 1)
        val metadata = state.metadata ?: throw VaultLockedException()
        val keys = VaultKeys.unlock(metadata, trustedVaultKeys = state.trustedVaultKeys, customKey = state.customKey)
        return try {
            val raw = candidates.single()
            val data = PasswordResourceCodec.read(raw, keys)
            check(Proto.decode(raw).string(2) == data.tag())
            data.realm == PasswordAcceptanceAccounts.REALM && data.username == PasswordAcceptanceAccounts.USERNAME &&
                data.canFillRealm(PasswordAcceptanceAccounts.REALM)
        } finally { keys.destroy() }
    }
}
