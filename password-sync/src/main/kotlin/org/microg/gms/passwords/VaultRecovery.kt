/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest
import java.util.UUID

/** These actions are owner decisions, never background retry policy. */
object VaultRecovery {
    fun acceptTrustedKeys(session: VaultSession, keys: List<ByteArray>) {
        if (keys.isEmpty() || keys.size > 100 || keys.any { it.size !in 16..4096 }) throw ProtocolException()
        val state = session.load()
        val metadata = trustedMetadata(state)
        VaultKeys.unlock(metadata, trustedVaultKeys = keys).destroy()
        state.trustedVaultKeys.forEach { it.fill(0) }; state.trustedVaultKeys.clear()
        state.trustedVaultKeys.addAll(keys.map { it.copyOf() })
        // This legacy API establishes unlock material only, without asserting any epoch.
        state.versionedTrustedVaultKeys?.destroy(); state.versionedTrustedVaultKeys = null
        session.save(state)
    }
    fun trustedMetadata(state: VaultState): ByteArray {
        val metadata = state.pendingMetadata ?: state.metadata ?: throw VaultLockedException()
        if (state.pendingMetadata != null && state.metadata != null && Proto.decode(metadata).string(1) != Proto.decode(state.metadata!!).string(1)) throw VaultResetException()
        PasswordApi.checkedParent(Proto.decode(metadata).string(1))
        if (Proto.decode(Proto.decode(metadata).one(3)).integer(2) != 5L) throw VaultLockedException()
        return metadata
    }
    /** Explicit owner recovery; the epoch/material pairs are retained only after real keybag verification. */
    fun acceptVersionedTrustedKeys(session: VaultSession, keys: List<VersionedTrustedVaultKey>, expectedParent: String,
        authorize: () -> Unit = {}) {
        val recovered = VersionedTrustedVaultKeys(PasswordApi.checkedParent(expectedParent), keys)
        val raw = ArrayList<ByteArray>()
        var retained = false
        try {
            keys.forEach { raw.add(it.material) }
            authorize()
            val state = session.load()
            val metadata = trustedMetadata(state)
            if (Proto.decode(metadata).string(1) != expectedParent) throw VaultResetException()
            state.versionedTrustedVaultKeys?.let { previous ->
                if (previous.parent != expectedParent) throw VaultResetException()
                val old = previous.keys
                try {
                    for (key in old) {
                        val next = keys.singleOrNull { it.epoch == key.epoch } ?: continue
                        val first = key.material; val second = next.material
                        try { if (!MessageDigest.isEqual(first, second)) throw ProtocolException() }
                        finally { first.fill(0); second.fill(0) }
                    }
                } finally { old.forEach { it.destroy() } }
            }
            VaultKeys.unlock(metadata, trustedVaultKeys = raw).destroy()
            // A WebView success, key parsing, or a previously valid owner grant alone
            // cannot authorize a late write after owner/account revocation.
            authorize()
            state.trustedVaultKeys.forEach { it.fill(0) }; state.trustedVaultKeys.clear()
            state.trustedVaultKeys.addAll(raw.map { it.copyOf() })
            state.versionedTrustedVaultKeys?.destroy(); state.versionedTrustedVaultKeys = recovered
            session.save(state)
            retained = true
        } finally { raw.forEach { it.fill(0) }; if (!retained) recovered.destroy() }
    }
    fun drop(session: VaultSession, operationId: String) {
        val state = session.load()
        val operation = state.mutations.singleOrNull { it.id == operationId } ?: throw ConflictException()
        state.mutations.remove(operation)
        if (state.records.none { Proto.decode(it).string(2) == operation.tag }) state.chromeLocalData.remove(operation.tag)
        session.save(state)
    }

    /** Call only after a complete refresh and explicit acknowledgement of an uncertain effect. */
    fun retry(session: VaultSession, operationId: String, keys: VaultKeys, acknowledgeUncertain: Boolean): String? {
        val state = session.load()
        if (state.pendingMetadata != null || state.lastSyncMillis <= 0) throw VaultLockedException()
        val operation = state.mutations.singleOrNull { it.id == operationId } ?: throw ConflictException()
        if (operation.phase in setOf(MutationPhase.LAUNCHED, MutationPhase.INDETERMINATE) && !acknowledgeUncertain)
            throw PendingMutationException()
        val remote = state.records.singleOrNull { Proto.decode(it).string(2) == operation.tag }
        val base = remote?.let(Proto::decode)
        val kind = when {
            operation.kind == MutationKind.DELETE -> MutationKind.DELETE
            remote == null -> MutationKind.CREATE
            else -> MutationKind.UPDATE
        }
        val desired = if (kind == MutationKind.DELETE) null else PasswordResourceCodec.encrypt(
            PasswordResourceCodec.read(operation.desired ?: throw ProtocolException(), keys), keys, remote)
        val replacement = if (kind == MutationKind.DELETE && remote == null) null else Mutation(
            UUID.randomUUID().toString(), kind, operation.tag, base?.string(1), base?.optional(5), desired)
        state.mutations.remove(operation)
        replacement?.let(state.mutations::add)
        session.save(state)
        return replacement?.id
    }

    /** Keeps no stale outbox eligible for the replacement vault. Never resets Google's vault. */
    fun acceptRemoteReset(session: VaultSession) {
        val state = session.load()
        val pending = state.pendingMetadata ?: throw ConflictException()
        val previous = state.metadata ?: throw ConflictException()
        if (Proto.decode(previous).string(1) == Proto.decode(pending).string(1)) throw ConflictException()
        state.metadata = pending; state.pendingMetadata = null
        state.records.clear(); state.mutations.clear(); state.chromeLocalData.clear()
        state.preferences.clear(); state.preferenceMutations.clear(); state.preferenceParent = null
        state.deviceRegistration = null; state.affiliationCache = null; state.directAffiliationProofs = null; state.lastSyncMillis = 0
        state.trustedVaultKeys.forEach { it.fill(0) }; state.trustedVaultKeys.clear()
        state.versionedTrustedVaultKeys?.destroy(); state.versionedTrustedVaultKeys = null
        session.save(state)
    }
}
