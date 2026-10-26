/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.fido.core.hybrid

import org.microg.gms.fido.core.hybrid.utils.NoiseCrypter

/** The output admission, Noise nonce reservation and actual socket enqueue share one cancellation boundary. */
internal class HybridOutput(private val connection: HybridConnectionGuard,
    private val ready: (NoiseCrypter) -> Boolean, private val enqueue: (ByteArray) -> Boolean) {
    fun send(generation: Long, crypt: NoiseCrypter, response: ByteArray): Boolean = try {
        connection.install(generation) {
            check(ready(crypt))
            val encrypted = crypt.encrypt(byteArrayOf(1) + response) ?: error("Hybrid encryption failed")
            // enqueue never takes the TunnelWebsocket monitor: its close callback can acquire this guard.
            enqueue(encrypted) && connection.live(generation) && ready(crypt)
        }
    } catch (_: Exception) { false }
}
