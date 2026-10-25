/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.fido.core.hybrid

import org.microg.gms.fido.core.hybrid.utils.NoiseCrypter

/** The ordered reader handles authenticated control messages independently of a pending owner operation. */
internal class HybridAuthenticatedFrames(private val crypt: NoiseCrypter, private val shutdown: () -> Unit) {
    fun read(encrypted: ByteArray): ByteArray? {
        val plain = crypt.decrypt(encrypted) ?: throw SecurityException("Invalid hybrid frame")
        require(plain.isNotEmpty())
        // Chromium caBLE v2 MessageType::kShutdown. It is not a post-handshake acknowledgement.
        if (plain[0] == 0.toByte()) {
            require(plain.size == 1)
            shutdown()
            plain.fill(0)
            return null
        }
        return plain
    }
}
