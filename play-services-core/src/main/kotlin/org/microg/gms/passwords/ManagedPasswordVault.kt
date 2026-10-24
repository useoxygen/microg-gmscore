/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

class PasswordPendingChange(val id: String, val kind: MutationKind, val phase: MutationPhase, val realm: String) {
    override fun toString() = "PasswordPendingChange[redacted]"
}

/** Privileged owner operations. They must never be exposed through an automatic fill consumer. */
interface ManagedPasswordVault : PasswordVault, PasswordTransferEndpoint {
    fun editIfUnchanged(original: ByteArray, replacement: ByteArray)
    fun pendingChanges(): List<PasswordPendingChange> = emptyList()
    fun dropChange(id: String) { throw UnsupportedOperationException() }
    fun retryChange(id: String, acknowledgeUncertain: Boolean) { throw UnsupportedOperationException() }
    fun hasRemoteReset(): Boolean = false
    fun acceptRemoteReset() { throw UnsupportedOperationException() }
    override fun refresh(): SyncReceipt
}
