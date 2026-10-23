/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest

internal object PasswordAcceptanceCleanup {
    fun requireAbsent(vault: PasswordTransferEndpoint, tag: String) {
        check(!vault.pending(tag) && vault.committed(tag) == null)
    }
    fun dropQueuedClone(session: VaultSession, tag: String, parent: String) {
        val state = session.load()
        check(state.pendingMetadata == null && state.metadata?.let { Proto.decode(it).string(1) } == parent)
        state.mutations.singleOrNull { it.tag == tag }?.let { pending ->
            check(pending.kind == MutationKind.CREATE && pending.phase == MutationPhase.QUEUED)
            VaultRecovery.drop(session, pending.id)
        }
    }
    /** A stage4 crash may lose only the caller receipt after the real sender journal was committed. */
    fun acknowledgedSend(state: Proto, operations: List<SharingOperation>): Proto {
        check(state.integer(9) == 4L)
        val expected = SharingOperation.digest(state.one(13))
        val matches = operations.filter { it.kind == SharingOperationKind.SEND && it.parent == state.string(1) &&
            it.source == state.string(4) + ":" + state.string(11) && MessageDigest.isEqual(it.sourceDigest, expected) }
        val acknowledged = matches.singleOrNull()?.takeIf { it.phase == SharingOperationPhase.COMPLETE } ?: throw PendingMutationException()
        return state.replace(8, acknowledged.id.toByteArray(Charsets.UTF_8)).replaceInteger(9, 5)
    }
}
