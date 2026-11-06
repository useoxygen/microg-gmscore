/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.recaptcha.base

import android.content.Context
import com.google.android.gms.droidguard.DroidGuard
import com.google.android.gms.droidguard.DroidGuardClient
import com.google.android.gms.droidguard.DroidGuardHandle
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import org.microg.gms.droidguard.core.DroidGuardPreferences
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.microg.gms.droidguard.DroidGuardHandleImpl as DroidGuardClientHandle

/** DroidGuard flow Play services uses for reCAPTCHA Base. */
internal const val RECAPTCHA_BASE_FLOW = "recaptchabase-android"

/** DroidGuard request option naming the app the result is produced for. */
internal const val KEY_THIRD_PARTY_CALLER = "thirdPartyCallerAppPackageName"

/** DroidGuard input key that binds the caller's nonce into the result. */
internal const val KEY_TOKEN = "token"

internal const val GUARD_TIMEOUT_MILLIS = 60_000

/** Thrown when the DroidGuard runtime cannot serve the request at all. */
internal class GuardUnavailableException(message: String) : Exception(message)

/** An opened DroidGuard handle. [snapshot] blocks; [close] must be safe to call more than once. */
internal interface RecaptchaBaseGuardHandle {
    fun snapshot(input: Map<String, String>): String
    fun close()
}

/** The DroidGuard operations reCAPTCHA Base needs, on behalf of an already verified caller package. */
internal interface RecaptchaBaseGuard {
    /**
     * Opens and initializes a handle for [RECAPTCHA_BASE_FLOW]. Returns only an opened handle; throws otherwise. If the
     * caller is cancelled while opening, a handle that arrives later is closed.
     */
    suspend fun open(callerPackage: String): RecaptchaBaseGuardHandle

    /** Produces one result for [RECAPTCHA_BASE_FLOW] with a fresh handle. */
    suspend fun results(callerPackage: String, input: Map<String, String>): String
}

/**
 * [RecaptchaBaseGuard] backed by microG's existing DroidGuard runtime, under Play services' own package identity. The
 * caller is passed as the request's third-party caller option, as Play services does.
 *
 * Every call into the DroidGuard client runs on [workers]: when the client is connected, `init` and `getResults` do their
 * Binder work before they return a Task, and that work runs in DroidGuard's own process, where interrupting this thread
 * does not stop it. Callers wait for the worker cancellably instead, so a deadline or close answers at once; the worker
 * finishes on its own, and a handle it produces after its caller has gone is closed.
 */
internal class DroidGuardRecaptchaBaseGuard(
    private val client: () -> DroidGuardClient,
    private val isAvailable: () -> Boolean,
    private val workers: Executor = SHARED_WORKERS,
) : RecaptchaBaseGuard {
    constructor(context: Context) : this(
        context.applicationContext.let { app -> { DroidGuard.getClient(app) } },
        context.applicationContext.let { app -> { DroidGuardPreferences.isAvailable(app) } },
    )

    private fun request(callerPackage: String) = DroidGuardResultsRequest().apply {
        bundle.putString(KEY_THIRD_PARTY_CALLER, callerPackage)
        setTimeoutMillis(GUARD_TIMEOUT_MILLIS)
    }

    /** Reads microG's DroidGuard setting (a content provider query) off the main thread. */
    private suspend fun requireAvailable() {
        val available = workers.call { isAvailable() }
        if (!available) throw GuardUnavailableException("DroidGuard is disabled")
    }

    override suspend fun open(callerPackage: String): RecaptchaBaseGuardHandle {
        requireAvailable()
        val pending = PendingHandle()
        try {
            // The owner is attached on the worker that starts the Task, so a Task returned after the caller has gone
            // still has one.
            workers.call {
                client().init(RECAPTCHA_BASE_FLOW, request(callerPackage)).addOnCompleteListener(DirectExecutor, pending)
            }
            val handle = DroidGuardHandleAdapter(pending.await())
            // The client reports a handle even when its initialization failed; such a handle only yields ERROR markers.
            val initialized = try {
                workers.call { handle.isInitialized() }
            } catch (e: Throwable) {
                handle.close()
                throw e
            }
            if (!initialized) {
                handle.close()
                throw GuardUnavailableException("DroidGuard handle did not initialize")
            }
            return handle
        } finally {
            // Closes a handle that arrived but was not taken, and any handle that arrives after this point.
            pending.abandon()
        }
    }

    override suspend fun results(callerPackage: String, input: Map<String, String>): String {
        requireAvailable()
        return workers.call { client().getResults(RECAPTCHA_BASE_FLOW, input, request(callerPackage)) }.awaitResult()
    }

    private class DroidGuardHandleAdapter(private val handle: DroidGuardHandle) : RecaptchaBaseGuardHandle {
        private val closed = AtomicBoolean()

        /**
         * microG's client asks its service whether the runtime initialized; [DroidGuardHandle.isOpened] only shows that
         * the handle's Binder is alive.
         */
        fun isInitialized(): Boolean =
            if (handle is DroidGuardClientHandle) handle.isInitialized else handle.isOpened

        override fun snapshot(input: Map<String, String>): String = handle.snapshot(input)

        override fun close() {
            if (closed.compareAndSet(false, true)) handle.close()
        }
    }

    companion object {
        /**
         * Process-wide workers for blocking DroidGuard client calls. A worker held by a call that ignores interruption
         * stays held until the call returns, so the pool and its queue are both small and fixed; when they are full, a
         * request fails at once instead of waiting.
         */
        internal val SHARED_WORKERS: Executor = ThreadPoolExecutor(
            WORKER_THREADS, WORKER_THREADS, 30, TimeUnit.SECONDS, ArrayBlockingQueue(WORKER_QUEUE),
            { runnable -> Thread(runnable, "RecaptchaBase-DG").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
    }
}

internal const val WORKER_THREADS = 2
internal const val WORKER_QUEUE = 16

/**
 * Runs [block] on this executor and waits for it cancellably. If the caller is cancelled first, a call still queued is
 * withdrawn and never runs; a call already running is left to finish, and its result is dropped, so [block] must not
 * return anything that needs closing (an init Task's handle is owned by its listener instead). A full executor fails
 * with [GuardUnavailableException].
 */
internal suspend fun <T> Executor.call(block: () -> T): T = suspendCancellableCoroutine { cont ->
    val task = Runnable {
        if (cont.isActive) cont.resumeWith(runCatching(block))
    }
    try {
        execute(task)
    } catch (e: RejectedExecutionException) {
        throw GuardUnavailableException("DroidGuard workers busy")
    }
    cont.invokeOnCancellation { (this as? ThreadPoolExecutor)?.remove(task) }
}

private object DirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

/**
 * Owns the handle an init Task delivers from the moment the Task starts. The opener [await]s and takes it; once the opener
 * [abandon]s (taken or not), a handle still held or delivered later is closed. Exactly one of the two ends owns it.
 */
private class PendingHandle : OnCompleteListener<DroidGuardHandle> {
    private val lock = Any()
    private var outcome: Result<DroidGuardHandle>? = null
    private var abandoned = false
    private val done = CompletableDeferred<Unit>()

    override fun onComplete(task: Task<DroidGuardHandle>) {
        val exception = task.exception
        val result = when {
            exception != null -> Result.failure(exception)
            task.isCanceled -> Result.failure(GuardUnavailableException("DroidGuard init was cancelled"))
            else -> task.result?.let { Result.success(it) }
                ?: Result.failure(GuardUnavailableException("DroidGuard returned no handle"))
        }
        val orphan = synchronized(lock) {
            if (abandoned) result.getOrNull() else null.also { outcome = result }
        }
        orphan?.close()
        done.complete(Unit)
    }

    /** Waits for the Task and takes its handle; the caller owns it from the moment this returns. */
    suspend fun await(): DroidGuardHandle {
        done.await()
        val result = synchronized(lock) { outcome!!.also { outcome = null } }
        return result.getOrThrow()
    }

    fun abandon() {
        val orphan = synchronized(lock) {
            abandoned = true
            outcome?.getOrNull().also { outcome = null }
        }
        orphan?.close()
    }
}

private suspend fun Task<String>.awaitResult(): String =
    suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            val exception = task.exception
            when {
                exception != null -> cont.resumeWithException(exception)
                task.isCanceled -> cont.cancel()
                else -> cont.resume(task.result ?: "")
            }
        }
    }

/** Runs a blocking snapshot off the caller's thread; cancellation interrupts the wait. */
internal suspend fun RecaptchaBaseGuardHandle.snapshotInterruptible(input: Map<String, String>): String =
    runInterruptible(Dispatchers.IO) { snapshot(input) }
