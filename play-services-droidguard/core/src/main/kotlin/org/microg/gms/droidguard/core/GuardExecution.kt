/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.droidguard.core

// A failure response is explicitly an error, never an invented attestation result.
internal fun guardFailure(reason: String): ByteArray = "ERROR : $reason".encodeToByteArray()

internal fun executeGuard(init: () -> Unit, snapshot: () -> ByteArray, close: () -> Unit,
                          callback: (ByteArray) -> Unit) {
    val response = try {
        init()
        snapshot()
    } catch (_: Exception) {
        guardFailure("execution failed")
    } finally {
        // Cleanup failure must not discard a result or trigger a second callback.
        try { close() } catch (_: Exception) { }
    }
    callback(response)
}
