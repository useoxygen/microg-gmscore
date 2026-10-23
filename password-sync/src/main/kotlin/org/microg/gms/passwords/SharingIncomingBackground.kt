/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.util.UUID

/** GMS bmhh -> bmhb automatic account receiver, using the same durable owner-flow journal. */
class SharingIncomingBackground(private val api: PasswordSharingApi, private val journal: SharingJournal,
    private val destination: PasswordTransferEndpoint, private val authorize: () -> Unit = {},
    private val imported: (SharingOperation) -> Unit = {}) {
    fun synchronize(parent: String, clientToken: ByteArray, decode: (SharingInvitation) -> SharingPasswordBatch): Int {
        authorize()
        if (PasswordApi.checkedParent(Proto.decode(api.metadata()).string(1)) != parent) throw VaultResetException()
        val consumer = SharingIncomingConsumer(api, journal, destination, authorize)
        var received = 0
        fun process(operation: SharingOperation) {
            val count = consumer.resume(operation.id)
            received += count
            // Notification failure cannot turn a completed receive into a replayable operation.
            if (count > 0) runCatching { authorize(); imported(journal.get(operation.id)) }
        }
        // Reconcile even when an invitation disappeared after an uncertain acknowledgement.
        // SEND and explicit owner operations are never resumed by the background receiver.
        val listed = api.incoming(parent)
        for (operation in journal.operations().filter { it.parent == parent && it.kind == SharingOperationKind.AUTOMATIC && !it.phase.terminal }) {
            // A stored plaintext checkpoint is not a substitute for currently available, authenticated keys.
            if (operation.phase in setOf(SharingOperationPhase.PREPARED, SharingOperationPhase.IMPORTED)) {
                listed.singleOrNull { it.name == operation.source }?.let { decode(it) }
            }
            process(operation)
        }
        authorize()
        for (invitation in api.incoming(parent)) {
            // A concurrent picker/worker owns this invitation until its durable operation closes.
            if (journal.operations().any { it.source == invitation.name && !it.phase.terminal }) continue
            val batch = decode(invitation) // Missing keys or any authentication/parse failure retain it.
            val operation = SharingOperation(UUID.randomUUID().toString(), SharingOperationKind.AUTOMATIC, SharingOperationPhase.PREPARED,
                parent, invitation.name, SharingOperation.digest(invitation.encode()), Proto.field(3, clientToken),
                batch.passwords.map { Proto.field(1, it.encode()) }, invalidPasswordCount = batch.invalidCount, invitationGuid = invitation.guid)
            authorize()
            journal.prepare(operation)
            process(operation)
        }
        return received
    }
}
