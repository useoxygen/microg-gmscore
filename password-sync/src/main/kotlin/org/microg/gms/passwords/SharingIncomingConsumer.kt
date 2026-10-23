/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest

/** Import is independently durable; invitation deletion follows verified committed destination records. */
class SharingIncomingConsumer(private val api: PasswordSharingApi, private val journal: SharingJournal,
    private val destination: PasswordTransferEndpoint, private val authorize: () -> Unit = {}) {
    fun resume(id: String) {
        var operation = journal.get(id)
        if (operation.kind !in setOf(SharingOperationKind.ACCEPT, SharingOperationKind.DISMISS) ||
            operation.phase in setOf(SharingOperationPhase.COMPLETE, SharingOperationPhase.REFUSED)) throw ConflictException()
        authorize()
        if (PasswordApi.checkedParent(Proto.decode(api.metadata()).string(1)) != operation.parent) throw VaultResetException()
        if (operation.kind == SharingOperationKind.ACCEPT) {
            destination.refresh()
            for (chrome in operation.passwords) {
                authorize()
                val tag = PasswordData.decode(Proto.decode(chrome).one(1)).tag()
                if (destination.pending(tag)) throw PendingMutationException()
                var committed = destination.committed(tag)
                if (committed == null) {
                    if (operation.phase != SharingOperationPhase.PREPARED) throw ConflictException()
                    destination.insertIfAbsent(chrome)
                    destination.refresh()
                    if (destination.pending(tag)) throw PendingMutationException()
                    committed = destination.committed(tag)
                }
                if (committed == null || !PasswordTransferEngine.samePassword(committed, chrome)) throw ConflictException()
            }
        }
        operation = journal.transition(id, setOf(operation.phase), SharingOperationPhase.IMPORTED)
        authorize()
        val current = api.incoming(operation.parent).singleOrNull { it.name == operation.source }
        if (current == null) {
            journal.transition(id, setOf(SharingOperationPhase.IMPORTED), SharingOperationPhase.COMPLETE)
            return
        }
        if (!MessageDigest.isEqual(SharingOperation.digest(current.encode()), operation.sourceDigest)) throw ConflictException()
        journal.transition(id, setOf(SharingOperationPhase.IMPORTED), SharingOperationPhase.LAUNCHED)
        try {
            authorize()
            val request = Proto.decode(operation.request)
            api.delete(operation.parent, operation.source, request.one(3))
            if (api.incoming(operation.parent).any { it.name == operation.source }) throw PendingMutationException()
            journal.transition(id, setOf(SharingOperationPhase.LAUNCHED), SharingOperationPhase.COMPLETE)
        } catch (failure: Exception) {
            journal.transition(id, setOf(SharingOperationPhase.LAUNCHED), SharingOperationPhase.INDETERMINATE)
            throw failure
        }
    }
}
