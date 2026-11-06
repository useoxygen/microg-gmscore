/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.recaptcha.base

import com.google.android.gms.droidguard.DroidGuardClient
import com.google.android.gms.droidguard.DroidGuardHandle
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * Handle ownership in [DroidGuardRecaptchaBaseGuard.open] at the points where a coroutine can lose a value: the return
 * from the worker that starts the init Task, and the resumption that delivers its handle. The caller's dispatcher and
 * the worker executor are queues this test drains by hand, so each race is taken at a chosen point rather than by timing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class RecaptchaBaseGuardTest {
    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(queue) { queue.addLast(block) }
        }

        fun runOne(): Boolean {
            val next = synchronized(queue) { queue.removeFirstOrNull() } ?: return false
            next.run()
            return true
        }
    }

    private class QueueExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            synchronized(queue) { queue.addLast(command) }
        }

        fun runOne(): Boolean {
            val next = synchronized(queue) { queue.removeFirstOrNull() } ?: return false
            next.run()
            return true
        }
    }

    private class FakeDroidGuardHandle(private val opened: Boolean = true) : DroidGuardHandle {
        val closeCount = AtomicInteger()
        override fun snapshot(data: Map<String, String>): String = syntheticGuardResult("snapshot")
        override fun isOpened(): Boolean = opened
        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private val main = QueueDispatcher()
    private val io = QueueExecutor()
    private val source = TaskCompletionSource<DroidGuardHandle>()
    private val inits = mutableListOf<DroidGuardResultsRequest?>()
    private var onInit: () -> Unit = {}

    private val client = object : DroidGuardClient {
        override fun init(flow: String, request: DroidGuardResultsRequest?): Task<DroidGuardHandle> {
            assertEquals(RECAPTCHA_BASE_FLOW, flow)
            inits += request
            onInit()
            return source.task
        }

        override fun getResults(flow: String, data: Map<String, String>?, request: DroidGuardResultsRequest?): Task<String> =
            throw AssertionError("not used")
    }

    private val guard = DroidGuardRecaptchaBaseGuard({ client }, { true }, io)

    private var outcome: Result<RecaptchaBaseGuardHandle>? = null

    private fun startOpen(): Job = CoroutineScope(main).launch {
        outcome = runCatching { guard.open("com.example.app") }
    }

    private fun drain() {
        while (main.runOne() || io.runOne()) Unit
    }

    @Test
    fun initTaskStartedAsTheOpenerIsCancelledStillClosesItsHandle() {
        lateinit var job: Job
        // Cancelled while the worker that started the Task is still running: its result is then dropped, so the Task
        // must already have an owner.
        onInit = { job.cancel() }
        job = startOpen()
        drain()
        assertEquals(1, inits.size)
        assertTrue(job.isCancelled)
        assertTrue(outcome!!.exceptionOrNull() is CancellationException)

        val handle = FakeDroidGuardHandle()
        source.setResult(handle)
        assertEquals(1, handle.closeCount.get())
    }

    @Test
    fun handleDeliveredAsTheOpenerIsCancelledIsClosedOnce() {
        val job = startOpen()
        drain()
        assertEquals(1, inits.size)
        // The handle arrives and the opener's resumption is queued; the opener is cancelled before it runs.
        val handle = FakeDroidGuardHandle()
        source.setResult(handle)
        job.cancel()
        drain()
        assertTrue(outcome!!.exceptionOrNull() is CancellationException)
        assertEquals(1, handle.closeCount.get())
    }

    @Test
    fun handleArrivingAfterTheOpenerIsGoneIsClosed() {
        val job = startOpen()
        drain()
        job.cancel()
        drain()
        val handle = FakeDroidGuardHandle()
        source.setResult(handle)
        drain()
        assertEquals(1, handle.closeCount.get())
    }

    @Test
    fun openedHandleIsReturnedWithTheCallerAndClosesOnce() {
        startOpen()
        drain()
        assertEquals("com.example.app", inits.single()!!.bundle.getString(KEY_THIRD_PARTY_CALLER))
        val handle = FakeDroidGuardHandle()
        source.setResult(handle)
        drain()
        val opened = outcome!!.getOrThrow()
        assertEquals(0, handle.closeCount.get())
        opened.close()
        opened.close()
        assertEquals(1, handle.closeCount.get())
    }

    @Test
    fun unopenedHandleIsClosedAndOpenFails() {
        startOpen()
        drain()
        val handle = FakeDroidGuardHandle(opened = false)
        source.setResult(handle)
        drain()
        assertTrue(outcome!!.exceptionOrNull() is GuardUnavailableException)
        assertEquals(1, handle.closeCount.get())
    }

    @Test
    fun failedInitTaskFailsOpen() {
        startOpen()
        drain()
        source.setException(IllegalStateException("synthetic"))
        drain()
        assertFalse(outcome!!.isSuccess)
        assertTrue(outcome!!.exceptionOrNull() is IllegalStateException)
    }
}
