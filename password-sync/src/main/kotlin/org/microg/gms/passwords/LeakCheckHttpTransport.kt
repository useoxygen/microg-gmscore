/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Fixed-origin gRPC transport. Cancelling the owner operation cancels the actual in-flight query. */
class LeakCheckHttpTransport(private val token: () -> String, private val check: () -> Unit,
    private val rejectedToken: (String) -> Unit, client: OkHttpClient = OkHttpClient()) : LeakCheckTransport {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build()
    private val active = AtomicReference<Call?>()
    fun cancel() { active.get()?.cancel() }
    override fun invoke(method: LeakCheckMethod, request: ByteArray): ByteArray {
        if (request.size > 128 * 1024) throw ProtocolException()
        repeat(2) { attempt ->
            check()
            val authorization = token()
            check()
            try { return execute(method, request, authorization) }
            catch (failure: PasswordRpcException) {
                if (failure.code != 16 || attempt != 0) throw failure
                rejectedToken(authorization)
            }
        }
        throw AssertionError("Unreachable authentication retry")
    }
    private fun execute(method: LeakCheckMethod, request: ByteArray, authorization: String): ByteArray {
        val frame = ByteBuffer.allocate(5 + request.size).put(0).putInt(request.size).put(request).array()
        val url = "https://passwordsleakcheck-pa.googleapis.com/".toHttpUrl().newBuilder()
            .encodedPath("/google.internal.identity.passwords.leak.check.v1.PasswordsLeakCheckService/${method.rpc}").build()
        val call = client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $authorization")
            .header("TE", "trailers").header("grpc-accept-encoding", "gzip")
            .post(frame.toRequestBody("application/grpc".toMediaType())).build())
        if (!active.compareAndSet(null, call)) throw PendingMutationException()
        try {
            check()
            call.execute().use { response ->
                if (response.code == 401) throw PasswordRpcException(16, false)
                if (response.code == 403) throw PasswordRpcException(7, false)
                if (response.code != 200) throw PasswordRpcException(14, false)
                val body = response.body ?: throw ProtocolException()
                val raw = body.byteStream().use { input ->
                    val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) {
                        check()
                        val length = input.read(buffer)
                        if (length < 0) break
                        if (out.size() + length > Proto.MAX_BYTES + 5) throw ProtocolException()
                        out.write(buffer, 0, length)
                    }
                    out.toByteArray()
                }
                check()
                val status = (response.trailers()["grpc-status"] ?: response.header("grpc-status"))?.toIntOrNull() ?: throw ProtocolException()
                if (status != 0) throw PasswordRpcException(status, false)
                return GooglePasswordTransport.decodeFrame(raw, response.header("grpc-encoding"))
            }
        } catch (_: IOException) { check(); throw PasswordRpcException(14, false) }
        finally { active.compareAndSet(call, null) }
    }
    override fun toString() = "LeakCheckHttpTransport[redacted]"
}
