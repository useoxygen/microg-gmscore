/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

internal class SyncProgress { var uploaded = 0 }

/** A definite stale-metadata refusal permits one fresh read, never replay of an unknown effect. */
internal fun retryStaleMetadata(action: (refresh: Boolean, progress: SyncProgress) -> SyncReceipt): SyncReceipt {
    val progress = SyncProgress()
    try { return action(false, progress) }
    catch (failure: PasswordRpcException) {
        if (failure.code != 9 || failure.outcomeUnknown) throw failure
    }
    return action(true, progress)
}
