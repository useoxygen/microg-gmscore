/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

/** One owner launch only. An unknown receipt is durable and is never converted into a retry. */
class SharingSender(private val api: PasswordSharingApi, private val journal: SharingJournal) {
    fun launch(id: String, authorize: () -> Unit = {}) {
        val operation = journal.get(id)
        if (operation.kind != SharingOperationKind.SEND || operation.phase != SharingOperationPhase.PREPARED) throw PendingMutationException()
        authorize()
        if (PasswordApi.checkedParent(Proto.decode(api.metadata()).string(1)) != operation.parent) throw VaultResetException()
        authorize()
        journal.transition(id, setOf(SharingOperationPhase.PREPARED), SharingOperationPhase.LAUNCHED)
        try {
            authorize()
            val receipt = api.send(operation.request)
            val response = Proto.decode(receipt)
            // iaix.b is the presence mask. bfhz retires its outgoing operation only when
            // bit 1 is absent; the enum's numeric meanings are not assumed here.
            val phase = if (response.replace(1, null).encode().contentEquals(response.encode())) SharingOperationPhase.COMPLETE
                else SharingOperationPhase.INDETERMINATE
            journal.transition(id, setOf(SharingOperationPhase.LAUNCHED), phase, receipt)
        } catch (failure: Exception) {
            val phase = if (failure is PasswordRpcException && !failure.outcomeUnknown) SharingOperationPhase.REFUSED
                else SharingOperationPhase.INDETERMINATE
            journal.transition(id, setOf(SharingOperationPhase.LAUNCHED), phase)
            throw failure
        }
    }
}
