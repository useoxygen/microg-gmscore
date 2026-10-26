/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class LeakCheckHttpTransportTest {
    @Test fun realHttpRequestUsesOnlyTheFixedTlsServiceAndAuthenticatedGrpcFrame() {
        val request = Proto.field(1, byteArrayOf(1, 2)) + Proto.integer(2, 2)
        val response = Proto.field(4, Proto.field(4, byteArrayOf(9)))
        val framed = ByteBuffer.allocate(5 + response.size).put(0).putInt(response.size).put(response).array()
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val sent = chain.request(); calls++
            assertEquals("https", sent.url.scheme); assertEquals("passwordsleakcheck-pa.googleapis.com", sent.url.host)
            assertEquals("/google.internal.identity.passwords.leak.check.v1.PasswordsLeakCheckService/LookupLeakMatches", sent.url.encodedPath)
            assertEquals("Bearer fixture-token", sent.header("Authorization")); assertNull(sent.header("Cookie"))
            val buffer = Buffer(); sent.body!!.writeTo(buffer); val body = ByteBuffer.wrap(buffer.readByteArray())
            assertEquals(0.toByte(), body.get()); assertEquals(request.size, body.int)
            val raw = ByteArray(body.remaining()); body.get(raw); assertArrayEquals(request, raw)
            Response.Builder().request(sent).protocol(Protocol.HTTP_2).code(200).message("OK").header("grpc-status", "0")
                .body(framed.toResponseBody()).build()
        }.build()
        val transport = LeakCheckHttpTransport({ "fixture-token" }, {}, {}, client)
        assertArrayEquals(response, transport.invoke(LeakCheckMethod.MATCHES, request)); assertEquals(1, calls)
    }

    @Test fun redirectsNeverCarryTheAccountTokenToAnotherHostAndMissingStatusIsNotSuccess() {
        for (code in listOf(302, 200)) {
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                assertEquals("passwordsleakcheck-pa.googleapis.com", chain.request().url.host)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_2).code(code).message("Fixture")
                    .header("Location", "https://untrusted.invalid/").body(ByteArray(5).toResponseBody()).build()
            }.build()
            val transport = LeakCheckHttpTransport({ "fixture-token" }, {}, {}, client)
            val failure = assertThrows(Exception::class.java) { transport.invoke(LeakCheckMethod.MATCHES, byteArrayOf()) }
            if (code == 200) assertTrue(failure is ProtocolException)
            assertEquals(1, calls)
        }
    }

    @Test fun aDefiniteAuthRefusalInvalidatesThatTokenAndRetriesOnlyOnce() {
        var calls = 0; var rejected = 0; var current = "first-token"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals("Bearer $current", chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_2).code(401).message("Denied")
                .body(byteArrayOf().toResponseBody()).build()
        }.build()
        val transport = LeakCheckHttpTransport({ current }, {}, { assertEquals(current, it); current = "second-token"; rejected++ }, client)
        val failure = assertThrows(PasswordRpcException::class.java) { transport.invoke(LeakCheckMethod.MATCHES, byteArrayOf()) }
        assertEquals(16, failure.code); assertEquals(2, calls); assertEquals(1, rejected)
    }

    @Test fun ownerCancellationCancelsTheActualInFlightCallAndDoesNotRetry() {
        val entered = CountDownLatch(1); val finished = CountDownLatch(1); val gate = CountDownLatch(1)
        val stopped = AtomicBoolean(false); val failure = AtomicReference<Throwable?>(); var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++; entered.countDown()
            var waits = 0
            while (!chain.call().isCanceled() && waits++ < 200) gate.await(10, TimeUnit.MILLISECONDS)
            throw IOException("Synthetic cancelled query")
        }.build()
        val transport = LeakCheckHttpTransport({ "fixture-token" }, { if (stopped.get()) throw LeakCheckCancelledException() }, {}, client)
        val thread = Thread {
            try { transport.invoke(LeakCheckMethod.MATCHES, byteArrayOf()) } catch (problem: Throwable) { failure.set(problem) }
            finally { finished.countDown() }
        }
        thread.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS)); stopped.set(true); transport.cancel()
            assertTrue(finished.await(3, TimeUnit.SECONDS)); assertTrue(failure.get() is LeakCheckCancelledException); assertEquals(1, calls)
        } finally { stopped.set(true); transport.cancel(); gate.countDown(); thread.join(3000) }
    }
}
