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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.microg.gms.droidguard.core.DroidGuardPreferences
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

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
 */
internal class DroidGuardRecaptchaBaseGuard(
    private val client: () -> DroidGuardClient,
    private val isAvailable: () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
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
        val available = withContext(io) { isAvailable() }
        if (!available) throw GuardUnavailableException("DroidGuard is disabled")
    }

    override suspend fun open(callerPackage: String): RecaptchaBaseGuardHandle {
        requireAvailable()
        val pending = PendingHandle()
        try {
            // The owner is attached in the same block that starts the Task: withContext drops its result when the
            // caller is cancelled during the dispatch back, and the Task must not be lost with it.
            withContext(io) {
                client().init(RECAPTCHA_BASE_FLOW, request(callerPackage)).addOnCompleteListener(DirectExecutor, pending)
            }
            val handle = pending.await()
            // The client reports a handle even when its initialization failed; such a handle only yields ERROR markers.
            val opened = try {
                withContext(io) { handle.isOpened }
            } catch (e: Throwable) {
                handle.close()
                throw e
            }
            if (!opened) {
                handle.close()
                throw GuardUnavailableException("DroidGuard handle did not open")
            }
            return DroidGuardHandleAdapter(handle)
        } finally {
            // Closes a handle that arrived but was not taken, and any handle that arrives after this point.
            pending.abandon()
        }
    }

    override suspend fun results(callerPackage: String, input: Map<String, String>): String {
        requireAvailable()
        return withContext(io) {
            client().getResults(RECAPTCHA_BASE_FLOW, input, request(callerPackage))
        }.awaitResult()
    }

    private class DroidGuardHandleAdapter(private val handle: DroidGuardHandle) : RecaptchaBaseGuardHandle {
        private val closed = AtomicBoolean()

        override fun snapshot(input: Map<String, String>): String = handle.snapshot(input)

        override fun close() {
            if (closed.compareAndSet(false, true)) handle.close()
        }
    }
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
