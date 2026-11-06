/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.recaptcha.base

import android.content.Context
import com.google.android.gms.droidguard.DroidGuard
import com.google.android.gms.droidguard.DroidGuardHandle
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.microg.gms.droidguard.core.DroidGuardPreferences
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
internal class DroidGuardRecaptchaBaseGuard(context: Context) : RecaptchaBaseGuard {
    private val context = context.applicationContext

    private fun request(callerPackage: String) = DroidGuardResultsRequest().apply {
        bundle.putString(KEY_THIRD_PARTY_CALLER, callerPackage)
        setTimeoutMillis(GUARD_TIMEOUT_MILLIS)
    }

    /** Reads microG's DroidGuard setting (a content provider query) off the main thread. */
    private suspend fun requireAvailable() {
        val available = withContext(Dispatchers.IO) { DroidGuardPreferences.isAvailable(context) }
        if (!available) throw GuardUnavailableException("DroidGuard is disabled")
    }

    override suspend fun open(callerPackage: String): RecaptchaBaseGuardHandle {
        requireAvailable()
        val handle = withContext(Dispatchers.IO) {
            DroidGuard.getClient(context).init(RECAPTCHA_BASE_FLOW, request(callerPackage))
        }.awaitClosingLate()
        // The client reports a handle even when its initialization failed; such a handle only yields ERROR markers.
        val opened = try {
            withContext(Dispatchers.IO) { handle.isOpened }
        } catch (e: Throwable) {
            handle.close()
            throw e
        }
        if (!opened) {
            handle.close()
            throw GuardUnavailableException("DroidGuard handle did not open")
        }
        return DroidGuardHandleAdapter(handle)
    }

    override suspend fun results(callerPackage: String, input: Map<String, String>): String {
        requireAvailable()
        return withContext(Dispatchers.IO) {
            DroidGuard.getClient(context).getResults(RECAPTCHA_BASE_FLOW, input, request(callerPackage))
        }.awaitResult()
    }

    private class DroidGuardHandleAdapter(private val handle: DroidGuardHandle) : RecaptchaBaseGuardHandle {
        @Volatile
        private var closed = false

        override fun snapshot(input: Map<String, String>): String = handle.snapshot(input)

        override fun close() {
            if (closed) return
            closed = true
            handle.close()
        }
    }
}

/** Awaits a handle; one that arrives after the waiter was cancelled is closed instead of leaking. */
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun Task<DroidGuardHandle>.awaitClosingLate(): DroidGuardHandle =
    suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            val exception = task.exception
            when {
                exception != null -> cont.resumeWithException(exception)
                task.isCanceled -> cont.cancel()
                else -> {
                    val handle = task.result
                    if (handle == null) {
                        cont.resumeWithException(GuardUnavailableException("DroidGuard returned no handle"))
                    } else {
                        cont.resume(handle) { _ -> handle.close() }
                    }
                }
            }
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
