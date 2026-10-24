/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest

/** Import is independently durable; invitation deletion follows verified committed destination records. */
class SharingIncomingConsumer(private val api: PasswordSharingApi, private val journal: SharingJournal,
    private val destination: PasswordTransferEndpoint, private val authorize: () -> Unit = {}) {
    fun resume(id: String): Int {
        var operation = journal.get(id)
        if (operation.kind !in setOf(SharingOperationKind.ACCEPT, SharingOperationKind.DISMISS, SharingOperationKind.AUTOMATIC) ||
            operation.phase.terminal) throw ConflictException()
        authorize()
        if (PasswordApi.checkedParent(Proto.decode(api.metadata()).string(1)) != operation.parent) throw VaultResetException()
        val current = api.findIncoming(operation.parent, operation.source)
        if (current == null) {
            journal.transition(id, setOf(operation.phase), SharingOperationPhase.COMPLETE)
            return importedCount(operation)
        }
        if (!MessageDigest.isEqual(SharingOperation.digest(current.encode()), operation.sourceDigest)) throw ConflictException()
        // An uncertain deletion may be observed complete, but must never be launched again.
        if (operation.phase in setOf(SharingOperationPhase.LAUNCHED, SharingOperationPhase.INDETERMINATE)) throw PendingMutationException()
        var imported = importedCount(operation)
        val importedRealms = operation.receipt?.let { Proto.decode(it).bytes(2).map(Proto::utf8) }?.toMutableList() ?: ArrayList()
        if (operation.kind == SharingOperationKind.AUTOMATIC) {
            refresh()
            if (operation.phase == SharingOperationPhase.PREPARED) for (chrome in operation.passwords) {
                authorize()
                val incoming = PasswordData.decode(Proto.decode(chrome).one(1))
                val tag = incoming.tag()
                if (destination.pending(tag)) throw PendingMutationException()
                val existing = destination.committed(tag)?.let { PasswordData.decode(Proto.decode(it).one(1)) }
                if (SharingIncomingPolicy.classify(incoming, existing) == SharingIncomingDisposition.NEW) {
                    destination.insertIfAbsent(chrome)
                    refresh()
                    if (destination.pending(tag)) throw PendingMutationException()
                    val committed = destination.committed(tag)?.let { PasswordData.decode(Proto.decode(it).one(1)) }
                    if (committed == null || !SharingIncomingPolicy.sameImportedPassword(committed, incoming)) throw ConflictException()
                    imported++
                    importedRealms.add(incoming.realm)
                }
                // All other partitions are consumed without modifying the existing credential.
                // In particular, same-sender updates stay off under the pinned default.
            }
        } else if (operation.kind == SharingOperationKind.ACCEPT) {
            refresh()
            for (chrome in operation.passwords) {
                authorize()
                val tag = PasswordData.decode(Proto.decode(chrome).one(1)).tag()
                if (destination.pending(tag)) throw PendingMutationException()
                var committed = destination.committed(tag)
                if (committed == null) {
                    if (operation.phase != SharingOperationPhase.PREPARED) throw ConflictException()
                    destination.insertIfAbsent(chrome)
                    refresh()
                    if (destination.pending(tag)) throw PendingMutationException()
                    committed = destination.committed(tag)
                }
                if (committed == null || !PasswordTransferEngine.samePassword(committed, chrome)) throw ConflictException()
            }
        }
        val receipt = importedRealms.fold(Proto.integer(1, imported.toLong())) { out, realm -> out + Proto.field(2, realm) }
        operation = journal.transition(id, setOf(operation.phase), SharingOperationPhase.IMPORTED, receipt)
        authorize()
        if (PasswordApi.checkedParent(Proto.decode(api.metadata()).string(1)) != operation.parent) throw VaultResetException()
        val latest = api.findIncoming(operation.parent, operation.source)
        if (latest == null) {
            journal.transition(id, setOf(SharingOperationPhase.IMPORTED), SharingOperationPhase.COMPLETE)
            return imported
        }
        if (!MessageDigest.isEqual(SharingOperation.digest(latest.encode()), operation.sourceDigest)) throw ConflictException()
        // Check everything that is known to happen before the RPC before recording a launch.
        // Losing owner/account authority here leaves a safely resumable IMPORTED checkpoint.
        authorize()
        val clientToken = Proto.decode(operation.request).one(3)
        journal.transition(id, setOf(SharingOperationPhase.IMPORTED), SharingOperationPhase.LAUNCHED)
        var deleteReturned = false
        try {
            api.delete(operation.parent, operation.source, clientToken)
            deleteReturned = true
            if (api.findIncoming(operation.parent, operation.source) != null) throw PendingMutationException()
            journal.transition(id, setOf(SharingOperationPhase.LAUNCHED), SharingOperationPhase.COMPLETE)
        } catch (failure: Exception) {
            val phase = if (!deleteReturned && failure is PasswordRpcException && !failure.outcomeUnknown)
                SharingOperationPhase.ACK_REFUSED else SharingOperationPhase.INDETERMINATE
            journal.transition(id, setOf(SharingOperationPhase.LAUNCHED), phase)
            throw failure
        }
        return imported
    }

    private fun refresh() {
        val receipt = destination.refresh()
        if (receipt.conflicts != 0 || receipt.indeterminate != 0) throw PendingMutationException()
    }
    private fun importedCount(operation: SharingOperation): Int = operation.receipt?.let { Proto.decode(it).integer(1) ?: 0 }?.toInt() ?: 0
}
