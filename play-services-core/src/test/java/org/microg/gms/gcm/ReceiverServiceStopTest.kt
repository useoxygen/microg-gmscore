/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.gcm

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * When the push receiver service stops (useoxygen/cyclon#842). Android's start-id rule is modelled: stopSelfResult(id)
 * stops only if [id] is the latest start Android has handed out, even if that start's onStartCommand has not run yet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ReceiverServiceStopTest {
    class TestService : ReceiverService("ReceiverServiceStopTest") {
        @Volatile var latestIssued = 0
        @Volatile var stopped = false
        val received = mutableListOf<String?>()
        val stopRequests = mutableListOf<Int>()
        val gate = Semaphore(0)
        val done = Semaphore(0)

        override fun allowed() = true
        override fun receiver(intent: Intent) {
            gate.acquire()
            synchronized(received) { received += intent.action }
        }

        override fun stopAfter(startId: Int): Boolean {
            val stops = !stopped && startId == latestIssued
            synchronized(stopRequests) { stopRequests += startId }
            if (stops) stopped = true
            done.release()
            return stops
        }

        /** Android hands out start id [id]; its onStartCommand follows later on the main thread. */
        fun issue(id: Int) { latestIssued = id }
        fun deliver(id: Int) = onStartCommand(Intent("push-$id"), 0, id)
        fun finishOne() { gate.release(); assertTrue(done.tryAcquire(5, TimeUnit.SECONDS)) }
    }

    private fun service() = Robolectric.buildService(TestService::class.java).create().get()

    @Test
    fun aStartIssuedWhileTheLastMessageFinishesKeepsTheService() {
        val service = service()
        service.issue(1); service.deliver(1)
        // The reviewer's race: push 2 is already issued by Android when push 1 finishes, but not yet delivered.
        service.issue(2)
        service.finishOne()
        assertFalse(service.stopped)
        service.deliver(2)
        service.finishOne()
        assertTrue(service.stopped)
        assertEquals(listOf("push-1", "push-2"), service.received)
        assertEquals(listOf(1, 2), service.stopRequests)
    }

    @Test
    fun aStartDeliveredBeforeTheFirstFinishesIsHandledBeforeStopping() {
        val service = service()
        service.issue(1); service.deliver(1)
        service.issue(2); service.deliver(2)
        service.finishOne()
        assertFalse(service.stopped)
        service.finishOne()
        assertTrue(service.stopped)
        assertEquals(listOf("push-1", "push-2"), service.received)
        // Each message asks to stop with its own start id (a shared counter or "latest id" field could race).
        assertEquals(listOf(1, 2), service.stopRequests)
    }

    @Test
    fun aSingleMessageStopsTheServiceAfterwards() {
        val service = service()
        service.issue(7); service.deliver(7)
        service.finishOne()
        assertTrue(service.stopped)
        assertEquals(listOf(7), service.stopRequests)
    }
}
