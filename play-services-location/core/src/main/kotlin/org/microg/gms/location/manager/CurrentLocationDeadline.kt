/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.location.manager

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeoutOrNull

// Balanced requests do not run the high-accuracy manager's periodic expiry check.
// Own the one-shot deadline even when no provider ever produces a position.
internal suspend fun awaitCurrentLocationDeadline(durationMillis: Long, expired: () -> Unit,
                                                 register: suspend () -> Unit) {
    withTimeoutOrNull(durationMillis.coerceAtLeast(1)) {
        register()
        awaitCancellation()
    }
    expired()
}
