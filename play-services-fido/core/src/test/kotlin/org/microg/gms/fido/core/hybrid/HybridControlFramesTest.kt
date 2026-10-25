/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.fido.core.hybrid

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.fido.core.hybrid.utils.NoiseCrypter
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

    private fun decryptRecord(bytes: ByteArray, key: ByteArray, sequence: Int): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        val nonce = ByteArray(12)
        nonce[8] = (sequence ushr 24).toByte(); nonce[9] = (sequence ushr 16).toByte()
        nonce[10] = (sequence ushr 8).toByte(); nonce[11] = sequence.toByte()
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val padded = doFinal(bytes)
        padded.copyOfRange(0, padded.size - (padded.last().toInt() and 255) - 1)
    }
    @Test fun shutdownAfterPreliminaryCheckCannotReserveANonceOrEnqueueSignedOutput() {
        val connection = HybridConnectionGuard(); connection.connected(); val generation = connection.capture()
        val inputKey = ByteArray(32) { 5 }; val outputKey = ByteArray(32) { 6 }
        val crypt = NoiseCrypter(inputKey, outputKey)
        var sent = 0
        val output = HybridOutput(connection, { it === crypt }) { sent++; true }
        assertTrue(connection.live(generation)) // The owner's earlier check has already succeeded.
        val reader = HybridAuthenticatedFrames(crypt) { connection.invalidate() }
        assertNull(reader.read(record(0, inputKey)))
        assertFalse(output.send(generation, crypt, byteArrayOf(0, 11, 12)))
        assertEquals(0, sent)
        // Rejected output did not consume a nonce before discovering cancellation.
        assertArrayEquals(byteArrayOf(9), decryptRecord(crypt.encrypt(byteArrayOf(9))!!, outputKey, 0))
    }
    @Test fun concurrentBusyAndOwnerOutputsKeepCiphertextNonceOrderEqualToActualEnqueueOrder() {
        val connection = HybridConnectionGuard(); connection.connected(); val generation = connection.capture()
        val key = ByteArray(32) { 7 }; val crypt = NoiseCrypter(ByteArray(32), key)
        val firstEntered = CountDownLatch(1); val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1); val secondEntered = CountDownLatch(1)
        val calls = AtomicInteger(); val queued = Collections.synchronizedList(ArrayList<ByteArray>())
        val output = HybridOutput(connection, { it === crypt }) { encrypted ->
            if (calls.getAndIncrement() == 0) {
                firstEntered.countDown(); check(releaseFirst.await(3, TimeUnit.SECONDS))
            } else secondEntered.countDown()
            queued.add(encrypted.copyOf()); true
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val owner = executor.submit(Callable { output.send(generation, crypt, byteArrayOf(0, 21)) })
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            val busy = executor.submit(Callable { secondStarted.countDown(); output.send(generation, crypt, byteArrayOf(6)) })
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            assertTrue(owner.get(2, TimeUnit.SECONDS)); assertTrue(busy.get(2, TimeUnit.SECONDS))
            assertEquals(2, queued.size)
            assertArrayEquals(byteArrayOf(1, 0, 21), decryptRecord(queued[0], key, 0))
            assertArrayEquals(byteArrayOf(1, 6), decryptRecord(queued[1], key, 1))
        } finally { releaseFirst.countDown(); executor.shutdownNow() }
    }
}
