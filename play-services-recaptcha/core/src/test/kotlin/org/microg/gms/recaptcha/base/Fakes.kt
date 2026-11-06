/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.recaptcha.base

import com.google.android.gms.common.api.Status
import com.google.android.gms.recaptchabase.ExecuteResult
import com.google.android.gms.recaptchabase.InitResult
import com.google.android.gms.recaptchabase.internal.IRecaptchaBaseCallbacks
import kotlinx.coroutines.awaitCancellation
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** base64 of synthetic bytes; stands in for a DroidGuard result. */
internal fun syntheticGuardResult(tag: String): String =
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("synthetic-dg-$tag".toByteArray())

internal val ERROR_RESULT: String =
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("ERROR : synthetic failure".toByteArray())

internal class FakeHandle(private val result: (Map<String, String>) -> String) : RecaptchaBaseGuardHandle {
    val inputs: MutableList<Map<String, String>> = Collections.synchronizedList(ArrayList())
    val closeCount = AtomicInteger()

    override fun snapshot(input: Map<String, String>): String {
        inputs += input
        return result(input)
    }

    override fun close() {
        closeCount.incrementAndGet()
    }
}

/** Records every guard call with the caller package it was made for. */
internal class FakeGuard : RecaptchaBaseGuard {
    val opens: MutableList<String> = Collections.synchronizedList(ArrayList())
    val results: MutableList<Pair<String, Map<String, String>>> = Collections.synchronizedList(ArrayList())
    val handles: MutableList<FakeHandle> = Collections.synchronizedList(ArrayList())

    @Volatile
    var openBehavior: suspend (String) -> RecaptchaBaseGuardHandle = { caller ->
        FakeHandle { syntheticGuardResult("handle-$caller-${it[KEY_TOKEN]}") }.also { handles += it }
    }

    @Volatile
    var resultsBehavior: suspend (String, Map<String, String>) -> String = { caller, input ->
        syntheticGuardResult("oneshot-$caller-${input[KEY_TOKEN]}")
    }

    override suspend fun open(callerPackage: String): RecaptchaBaseGuardHandle {
        opens += callerPackage
        return openBehavior(callerPackage)
    }

    override suspend fun results(callerPackage: String, input: Map<String, String>): String {
        results += callerPackage to input
        return resultsBehavior(callerPackage, input)
    }

    companion object {
        val NEVER: suspend (String) -> RecaptchaBaseGuardHandle = { awaitCancellation() }
    }
}

internal sealed class Reply {
    data class Init(val status: Status, val result: InitResult?) : Reply()
    data class Execute(val status: Status, val result: ExecuteResult?) : Reply()
}

/** Callback stub recording every reply, so tests can check each request is answered exactly once. */
internal class RecordingCallbacks : IRecaptchaBaseCallbacks.Stub() {
    val replies = LinkedBlockingQueue<Reply>()
    val count = AtomicInteger()

    override fun onInitResponse(status: Status, result: InitResult?) {
        count.incrementAndGet()
        replies += Reply.Init(status, result)
    }

    override fun onExecuteResponse(status: Status, result: ExecuteResult?) {
        count.incrementAndGet()
        replies += Reply.Execute(status, result)
    }

    fun next(timeoutMillis: Long = 5_000): Reply =
        replies.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: throw AssertionError("no callback within $timeoutMillis ms")

    fun nextInit(): Reply.Init = next() as Reply.Init
    fun nextExecute(): Reply.Execute = next() as Reply.Execute
}

internal fun CountDownLatch.awaitOrFail(millis: Long = 5_000) {
    if (!await(millis, TimeUnit.MILLISECONDS)) throw AssertionError("timed out")
}
