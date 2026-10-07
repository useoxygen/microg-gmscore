/*
 * SPDX-FileCopyrightText: 2021 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import android.util.Log
import com.google.android.gms.droidguard.DroidGuardChimeraService
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import com.google.android.gms.droidguard.internal.IDroidGuardCallbacks
import com.google.android.gms.droidguard.internal.IDroidGuardHandle
import com.google.android.gms.droidguard.internal.IDroidGuardService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class DroidGuardServiceImpl(private val service: DroidGuardChimeraService, private val packageName: String) : IDroidGuardService.Stub() {
    override fun guard(callbacks: IDroidGuardCallbacks?, flow: String?, map: MutableMap<Any?, Any?>?) {
        Log.d(TAG, "guard()")
        guardWithRequest(callbacks, flow, map, null)
    }

    override fun guardWithRequest(callbacks: IDroidGuardCallbacks?, flow: String?, map: MutableMap<Any?, Any?>?, request: DroidGuardResultsRequest?) {
        Log.d(TAG, "guardWithRequest()")
        if (callbacks == null) return
        if (flow.isNullOrBlank()) {
            deliver(callbacks, guardFailure("invalid flow"))
            return
        }
        val data = HashMap(map.orEmpty())
        try {
            executor.execute {
                var handle: IDroidGuardHandle? = null
                executeGuard(
                    init = { handle = getHandle(); handle!!.initWithRequest(flow, request) },
                    snapshot = { handle!!.snapshot(data) },
                    close = { handle?.close() },
                    callback = { deliver(callbacks, it) }
                )
            }
        } catch (_: RejectedExecutionException) {
            deliver(callbacks, guardFailure("service busy"))
        }
    }

    private fun deliver(callbacks: IDroidGuardCallbacks, result: ByteArray) {
        try { callbacks.onResult(result) }
        catch (_: Exception) { Log.w(TAG, "DroidGuard callback unavailable") }
    }

    override fun getHandle(): IDroidGuardHandle {
        Log.d(TAG, "getHandle()")
        return when (DroidGuardPreferences.getMode(service)) {
            DroidGuardPreferences.Mode.Embedded -> DroidGuardHandleImpl(service, packageName, service.b, service.b(packageName))
            DroidGuardPreferences.Mode.Network -> RemoteHandleImpl(service, packageName)
        }
    }

    override fun getClientTimeoutMillis(): Int {
        Log.d(TAG, "getClientTimeoutMillis()")
        return 60000
    }

    companion object {
        const val TAG = "GmsGuardServiceImpl"
        // Shared and bounded: callers cannot create unbounded Binder-blocking work.
        private val executor = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            ArrayBlockingQueue<Runnable>(8), { runnable -> Thread(runnable, "DroidGuard-legacy").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    }
}
