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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
 */
internal class RecaptchaBaseSession(
    val callerPackage: String,
    private val guard: RecaptchaBaseGuard,
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = GUARD_TIMEOUT_MILLIS.toLong(),
    private val maxInFlight: Int = MAX_IN_FLIGHT_PER_CONNECTION,
) {
    private val lock = Any()
    private var initializedHandle: RecaptchaBaseGuardHandle? = null
    private var closed = false
    private val inFlight = AtomicInteger()

    fun init(callbacks: IRecaptchaBaseCallbacks) {
        val reply = once<Status> { replyInit(callbacks, it) }
        launchBounded(onUnanswered = { reply(STATUS_INTERNAL_ERROR) }) {
            val status = try {
                val handle = withTimeout(timeoutMillis) { guard.open(callerPackage) }
                keepInitialized(handle)
                Status.SUCCESS
            } catch (e: TimeoutCancellationException) {
                STATUS_TIMEOUT
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "init failed for $callerPackage: ${e.javaClass.simpleName}")
                STATUS_INTERNAL_ERROR
            }
            reply(status)
        }
    }

    fun execute(callbacks: IRecaptchaBaseCallbacks, nonce: String?, executeId: String?) {
        val reply = once<Pair<Status, ExecuteResult?>> { (status, result) -> replyExecute(callbacks, status, result) }
        if (nonce.isNullOrEmpty()) {
            // An empty nonce would produce a result bound to nothing; refuse it rather than attest it.
            reply(STATUS_DEVELOPER_ERROR to null)
            return
        }
        val id = executeId ?: ""
        launchBounded(onUnanswered = { reply(STATUS_INTERNAL_ERROR to null) }) {
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
            reply(outcome)
        }
    }

    /** Closes the kept handle; a handle that finishes opening later is closed immediately. */
    fun close() {
        val handle = synchronized(lock) {
            closed = true
            initializedHandle.also { initializedHandle = null }
        }
        handle?.close()
    }

    internal val hasInitializedHandle: Boolean get() = synchronized(lock) { initializedHandle != null }

    internal val inFlightCount: Int get() = inFlight.get()

    private fun keepInitialized(handle: RecaptchaBaseGuardHandle) {
        val replaced = synchronized(lock) {
            if (closed) {
                handle
            } else {
                initializedHandle.also { initializedHandle = handle }
            }
        }
        replaced?.close()
    }

    private fun takeInitialized(): RecaptchaBaseGuardHandle? = synchronized(lock) {
        initializedHandle.also { initializedHandle = null }
    }

    /**
     * Runs [block] unless this connection already has [maxInFlight] requests running. [onUnanswered] runs when the
     * request is refused and again when the job completes; replies are [once], so it only answers a request that the
     * block did not (refusal, cancellation, a scope that is already gone).
     */
    private fun launchBounded(onUnanswered: () -> Unit, block: suspend CoroutineScope.() -> Unit) {
        if (inFlight.incrementAndGet() > maxInFlight) {
            inFlight.decrementAndGet()
            Log.w(TAG, "too many concurrent requests from $callerPackage")
            onUnanswered()
            return
        }
        val job = try {
            scope.launch(block = block)
        } catch (e: Throwable) {
            inFlight.decrementAndGet()
            onUnanswered()
            throw e
        }
        job.invokeOnCompletion {
            inFlight.decrementAndGet()
            onUnanswered()
        }
    }

    private fun <T> once(send: (T) -> Unit): (T) -> Unit {
        val sent = AtomicBoolean()
        return { value -> if (sent.compareAndSet(false, true)) send(value) }
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
