/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.fido.core.hybrid

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.fido.core.hybrid.utils.NoiseCrypter
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class HybridControlFramesTest {
    /** Independent initiator record fixture: AES-GCM, zero sequence, caBLE 32-byte padding. */
    private fun record(type: Byte, key: ByteArray): ByteArray {
        val padded = ByteArray(32); padded[0] = type; padded[31] = 30
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ByteArray(12)))
            doFinal(padded)
        }
    }
    @Test fun disconnectDuringServerHelloSendCannotInstallReadyOrIssueAnOwnerLease() {
        val connection = HybridConnectionGuard(); connection.connected()
        val captured = connection.capture()
        var installed = false
        // Real send failure / onClosing may invalidate on another thread before final handshake installation.
        connection.invalidate()
        assertThrows(IllegalStateException::class.java) { connection.install(captured) { installed = true } }
        assertFalse(installed); assertFalse(connection.live(captured))
        assertThrows(IllegalStateException::class.java) { connection.connected() }
        assertThrows(IllegalStateException::class.java) { HybridAccountSessions.open(ByteArray(32)) { connection.live(captured) } }
    }
    @Test fun authenticatedShutdownRevokesAndCancelsOwnerOperationBeforeItCanSign() = runBlocking<Unit> {
        val connection = HybridConnectionGuard(); connection.connected(); val generation = connection.capture()
        val session = HybridAccountSessions.open(ByteArray(32) { 7 }) { connection.live(generation) }
        val raw = byteArrayOf(4, 5); val hash = ByteArray(32)
        val token = HybridAccountSessions.issue(session, raw, "example.invalid", hash, false, ByteArray(32) { 8 })
        HybridAccountSessions.requireLive(token, raw, "example.invalid", hash, false, claim = true)
        val ownerReady = CompletableDeferred<Unit>(); var signatures = 0
        val operation = launch(start = CoroutineStart.UNDISPATCHED) {
            ownerReady.await()
            HybridAccountSessions.requireLive(token, raw, "example.invalid", hash, false, claimed = true)
            signatures++
        }
        val key = ByteArray(32) { 3 }
        val reader = HybridAuthenticatedFrames(NoiseCrypter(key, ByteArray(32))) {
            connection.invalidate(); session.revoke(); operation.cancel()
        }
        assertNull(reader.read(record(0, key)))
        ownerReady.complete(Unit); operation.join()
        assertEquals(0, signatures); assertTrue(operation.isCancelled); assertFalse(connection.live(generation))
        assertThrows(SecurityException::class.java) { HybridAccountSessions.requireLive(token, raw, "example.invalid", hash, false) }
    }
    @Test fun unauthenticatedShutdownCannotCancelAnOwnerOperation() {
        var shutdowns = 0
        val key = ByteArray(32) { 4 }
        val reader = HybridAuthenticatedFrames(NoiseCrypter(key, ByteArray(32))) { shutdowns++ }
        val modified = record(0, key).apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(SecurityException::class.java) { reader.read(modified) }
        assertEquals(0, shutdowns)
        val independent = HybridAuthenticatedFrames(NoiseCrypter(key, ByteArray(32))) { shutdowns++ }
        assertEquals(1, independent.read(record(1, key))!![0].toInt()); assertEquals(0, shutdowns)
    }
}
