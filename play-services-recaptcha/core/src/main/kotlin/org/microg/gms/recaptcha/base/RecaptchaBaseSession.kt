/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.recaptcha.base

import android.util.Log
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.recaptchabase.ExecuteResult
import com.google.android.gms.recaptchabase.InitResult
import com.google.android.gms.recaptchabase.internal.IRecaptchaBaseCallbacks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "RecaptchaBase"

/** Requests one caller connection may have running at once; more are refused rather than queued. */
internal const val MAX_IN_FLIGHT_PER_CONNECTION = 8

internal val STATUS_TIMEOUT = Status(CommonStatusCodes.TIMEOUT)
internal val STATUS_INTERNAL_ERROR = Status(CommonStatusCodes.INTERNAL_ERROR)
internal val STATUS_DEVELOPER_ERROR = Status(CommonStatusCodes.DEVELOPER_ERROR)

/**
 * reCAPTCHA Base state for one caller connection. [callerPackage] was verified against the binder caller before this
 * session was created; nothing here re-reads caller identity from requests.
 *
 * init opens one DroidGuard handle and keeps it; the next execute takes that handle, snapshots with the nonce, and
 * closes it. Later executes use one-shot DroidGuard results. The execute ID is never passed to DroidGuard; it is only
 * attached to the returned envelope. Each operation is bounded by [timeoutMillis] and answered exactly once.
 *
 * Work runs in this session's own child job of [scope]. [close] cancels that job only, answers every request still
 * open with a failure, closes the kept handle and refuses later requests; other sessions on [scope] are unaffected.
 */
internal class RecaptchaBaseSession(
    val callerPackage: String,
    private val guard: RecaptchaBaseGuard,
    scope: CoroutineScope,
    private val timeoutMillis: Long = GUARD_TIMEOUT_MILLIS.toLong(),
    private val maxInFlight: Int = MAX_IN_FLIGHT_PER_CONNECTION,
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val sessionScope = CoroutineScope(scope.coroutineContext + job)

    private val lock = Any()
    private var initializedHandle: RecaptchaBaseGuardHandle? = null
    private var closed = false
    private val pending = LinkedHashSet<Answer>()

    /** The single answer to one request: the first [claim] wins; [fail] sends the request's failure if nothing was sent. */
    private class Answer(private val sendFailure: () -> Unit) {
        private val sent = AtomicBoolean()
        fun claim(): Boolean = sent.compareAndSet(false, true)
        fun fail() {
            if (claim()) sendFailure()
        }
    }

    fun init(callbacks: IRecaptchaBaseCallbacks) {
        val answer = Answer { replyInit(callbacks, STATUS_INTERNAL_ERROR) }
        launchBounded(answer) {
            var kept = false
            val status = try {
                withTimeout(timeoutMillis) {
                    // Accept the handle inside withTimeout: a resource returned across it is dropped when the timeout or
                    // a cancellation lands just as it is returned.
                    kept = keepInitialized(guard.open(callerPackage))
                }
                if (kept) Status.SUCCESS else STATUS_INTERNAL_ERROR
            } catch (e: TimeoutCancellationException) {
                // The handle may have been kept just as the timeout fired; the session then holds it, so init worked.
                if (kept) Status.SUCCESS else STATUS_TIMEOUT
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "init failed for $callerPackage: ${e.javaClass.simpleName}")
                STATUS_INTERNAL_ERROR
            }
            answerIfOpen(answer) { replyInit(callbacks, status) }
        }
    }

    fun execute(callbacks: IRecaptchaBaseCallbacks, nonce: String?, executeId: String?) {
        if (nonce.isNullOrEmpty()) {
            // An empty nonce would produce a result bound to nothing; refuse it rather than attest it.
            replyExecute(callbacks, STATUS_DEVELOPER_ERROR, null)
            return
        }
        val id = executeId ?: ""
        val answer = Answer { replyExecute(callbacks, STATUS_INTERNAL_ERROR, null) }
        launchBounded(answer) {
            val input = mapOf(KEY_TOKEN to nonce)
            val outcome = try {
                val guardResult = withTimeout(timeoutMillis) {
                    val handle = takeInitialized()
                    if (handle != null) {
                        try {
                            handle.snapshotInterruptible(input)
                        } finally {
                            handle.close()
                        }
                    } else {
                        guard.results(callerPackage, input)
                    }
                }
                val payload = RecaptchaBasePayloads.build(guardResult, id)
                Status.SUCCESS to ExecuteResult(payload.payload, payload.payloadBytes)
            } catch (e: TimeoutCancellationException) {
                STATUS_TIMEOUT to null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "execute failed for $callerPackage: ${e.javaClass.simpleName}")
                STATUS_INTERNAL_ERROR to null
            }
            answerIfOpen(answer) { replyExecute(callbacks, outcome.first, outcome.second) }
        }
    }

    /**
     * Closes this session once: refuses new requests, cancels its running ones, answers each of them with a failure,
     * and closes the kept handle. A handle that finishes opening later is closed when it arrives.
     */
    fun close() {
        val (handle, unanswered) = synchronized(lock) {
            if (closed) return
            closed = true
            initializedHandle.also { initializedHandle = null } to pending.toList()
        }
        job.cancel()
        handle?.close()
        unanswered.forEach { it.fail() }
    }

    internal val hasInitializedHandle: Boolean get() = synchronized(lock) { initializedHandle != null }

    internal val inFlightCount: Int get() = synchronized(lock) { pending.size }

    /** Keeps [handle] as the initialized handle and returns true, or closes it and returns false if this session is closed. */
    private fun keepInitialized(handle: RecaptchaBaseGuardHandle): Boolean {
        var kept = false
        val toClose = synchronized(lock) {
            if (closed) {
                handle
            } else {
                kept = true
                initializedHandle.also { initializedHandle = handle }
            }
        }
        toClose?.close()
        return kept
    }

    private fun takeInitialized(): RecaptchaBaseGuardHandle? = synchronized(lock) {
        initializedHandle.also { initializedHandle = null }
    }

    /** Sends an operation's own answer unless [close] already took over answering it. */
    private fun answerIfOpen(answer: Answer, send: () -> Unit) {
        val claimed = synchronized(lock) { !closed && answer.claim() }
        if (claimed) send()
    }

    /**
     * Runs [block] for [answer] unless this session is closed or already has [maxInFlight] requests running; a refused
     * request is answered with its failure at once. When the job ends (normally, cancelled, or never started because the
     * scope is gone) the request is answered with its failure unless it was answered already.
     */
    private fun launchBounded(answer: Answer, block: suspend CoroutineScope.() -> Unit) {
        val refusal = synchronized(lock) {
            when {
                closed -> "closed"
                pending.size >= maxInFlight -> "busy"
                else -> {
                    pending += answer
                    null
                }
            }
        }
        if (refusal != null) {
            if (refusal == "busy") Log.w(TAG, "too many concurrent requests from $callerPackage")
            answer.fail()
            return
        }
        val launched = try {
            sessionScope.launch(block = block)
        } catch (e: Throwable) {
            finish(answer)
            throw e
        }
        launched.invokeOnCompletion { finish(answer) }
    }

    private fun finish(answer: Answer) {
        synchronized(lock) { pending -= answer }
        answer.fail()
    }

    private fun replyInit(callbacks: IRecaptchaBaseCallbacks, status: Status) {
        try {
            callbacks.onInitResponse(status, if (status.isSuccess) InitResult() else null)
        } catch (e: Exception) {
            Log.w(TAG, "init callback for $callerPackage failed: ${e.javaClass.simpleName}")
        }
    }

    private fun replyExecute(callbacks: IRecaptchaBaseCallbacks, status: Status, result: ExecuteResult?) {
        try {
            callbacks.onExecuteResponse(status, result)
        } catch (e: Exception) {
            Log.w(TAG, "execute callback for $callerPackage failed: ${e.javaClass.simpleName}")
        }
    }
}
