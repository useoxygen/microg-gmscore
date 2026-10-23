/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class PasswordAuthenticationTest {
    private class Fixture : java.io.Closeable {
        val server = MockWebServer().apply { start() }
        val rejected = ArrayList<String>()
        var loads = 0
        val session = PasswordOAuthSession({ "synthetic-bearer-${++loads}" }, { rejected.add(it) })
        fun transport(method: PasswordMethod = PasswordMethod.CREATE, refresh: Boolean = true): GooglePasswordTransport {
            // Only synthetic tokens are sent to a loopback HTTP server. Product host validation still runs.
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            return GooglePasswordTransport(session::token, "https://${method.host}/".toHttpUrl(), client,
                if (refresh) session::reject else null)
        }
        fun request() = server.takeRequest(2, TimeUnit.SECONDS) ?: throw AssertionError("Missing request")
        override fun close() { server.shutdown() }
    }

    private fun success(raw: ByteArray = byteArrayOf()) = MockResponse().setBody(Buffer().write(frame(raw)))
        .setHeader("grpc-status", "0")
    private fun frame(raw: ByteArray) = ByteBuffer.allocate(raw.size + 5).put(0).putInt(raw.size).put(raw).array()

    @Test fun httpUnauthorizedRefreshesOnceAndResendsTheExactMutation() = Fixture().use { f ->
        val request = Proto.field(1, "synthetic-parent") + Proto.field(4, "stable-operation")
        val result = Proto.field(1, "synthetic-resource")
        f.server.enqueue(MockResponse().setResponseCode(401).setBody("excluded server details"))
        f.server.enqueue(success(result))
        assertArrayEquals(result, f.transport().invoke(PasswordMethod.CREATE, request))
        val first = f.request(); val second = f.request()
        assertEquals("Bearer synthetic-bearer-1", first.getHeader("Authorization"))
        assertEquals("Bearer synthetic-bearer-2", second.getHeader("Authorization"))
        assertEquals("/${PasswordMethod.CREATE.service}/CreatePassword", second.path)
        assertArrayEquals(first.body.readByteArray(), second.body.readByteArray())
        assertEquals(listOf("synthetic-bearer-1"), f.rejected); assertEquals(2, f.loads)
    }

    @Test fun grpcTrailerUnauthorizedAlsoRefreshesAfterConsumingTheResponse() = Fixture().use { f ->
        f.server.enqueue(MockResponse().setChunkedBody(Buffer().write(frame(byteArrayOf())), 3)
            .setTrailers(Headers.headersOf("grpc-status", "16")))
        f.server.enqueue(success())
        f.transport().invoke(PasswordMethod.BATCH_UPDATE, Proto.field(3, "registered-client"))
        assertEquals(2, f.server.requestCount); assertEquals(2, f.loads)
        assertEquals(listOf("synthetic-bearer-1"), f.rejected)
        assertArrayEquals(f.request().body.readByteArray(), f.request().body.readByteArray())
    }

    @Test fun persistentUnauthorizedIsBoundedAndInvalidatesTheSecondRejectedToken() = Fixture().use { f ->
        repeat(2) { f.server.enqueue(MockResponse().setResponseCode(401)) }
        val failure = assertThrows(PasswordRpcException::class.java) {
            f.transport().invoke(PasswordMethod.CREATE, byteArrayOf())
        }
        assertEquals(16, failure.code); assertFalse(failure.outcomeUnknown)
        assertEquals(2, f.server.requestCount); assertEquals(2, f.loads)
        assertEquals(listOf("synthetic-bearer-1", "synthetic-bearer-2"), f.rejected)
    }

    @Test fun deniedConsentDoesNotInvalidateOrRetry() = Fixture().use { f ->
        f.server.enqueue(MockResponse().setResponseCode(403))
        val failure = assertThrows(PasswordRpcException::class.java) {
            f.transport().invoke(PasswordMethod.CREATE, byteArrayOf())
        }
        assertEquals(7, failure.code); assertFalse(failure.outcomeUnknown)
        assertEquals(1, f.server.requestCount); assertTrue(f.rejected.isEmpty())
    }

    @Test fun disconnectedMutationHasAnUnknownOutcomeAndIsNotRetried() = Fixture().use { f ->
        f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val failure = assertThrows(PasswordRpcException::class.java) {
            f.transport().invoke(PasswordMethod.CREATE, byteArrayOf())
        }
        assertEquals(14, failure.code); assertTrue(failure.outcomeUnknown)
        assertEquals(1, f.server.requestCount); assertTrue(f.rejected.isEmpty())
    }

    @Test fun slowResponseCannotHoldTheAccountLockPastTheReferenceRpcDeadline() = Fixture().use { f ->
        // Each byte arrives within readTimeout. Only a whole-call deadline bounds this response.
        f.server.enqueue(success(ByteArray(24)).throttleBody(1, 1, TimeUnit.SECONDS))
        val started = System.nanoTime()
        val failure = assertThrows(PasswordRpcException::class.java) {
            f.transport().invoke(PasswordMethod.CREATE, byteArrayOf())
        }
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("Deadline did not bound the complete RPC", elapsed in 8_000..20_000)
        assertTrue(failure.outcomeUnknown)
        assertEquals(1, f.server.requestCount); assertTrue(f.rejected.isEmpty())
    }

    @Test fun redirectCannotMoveTheBearerOrTriggerAuthenticationRecovery() = Fixture().use { f ->
        MockWebServer().use { other ->
            other.start()
            f.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/unexpected")))
            val failure = assertThrows(PasswordRpcException::class.java) {
                f.transport().invoke(PasswordMethod.CREATE, byteArrayOf())
            }
            assertTrue(failure.outcomeUnknown); assertEquals(1, f.server.requestCount)
            assertEquals(0, other.requestCount); assertTrue(f.rejected.isEmpty())
        }
    }

    @Test fun registrationRecoveryDoesNotInvalidateTheIndependentPasswordSession() = Fixture().use { f ->
        val passwordInvalidations = ArrayList<String>()
        var passwordLoads = 0
        val passwords = PasswordOAuthSession({ passwordLoads++; "synthetic-password-scope" }, passwordInvalidations::add)
        assertEquals("synthetic-password-scope", passwords.token())
        f.server.enqueue(MockResponse().setResponseCode(401))
        f.server.enqueue(success())
        f.transport(PasswordMethod.CREATE_DEVICE).invoke(PasswordMethod.CREATE_DEVICE, Proto.field(4, "stable-nonce"))
        assertEquals("/${PasswordMethod.CREATE_DEVICE.service}/CreateDeviceInfo", f.request().path)
        assertEquals("/${PasswordMethod.CREATE_DEVICE.service}/CreateDeviceInfo", f.request().path)
        assertEquals(2, f.loads); assertEquals(1, passwordLoads)
        assertEquals("synthetic-password-scope", passwords.token()); assertTrue(passwordInvalidations.isEmpty())
    }

    @Test fun noRefreshCallbackPreservesTheExplicitFailure() = Fixture().use { f ->
        f.server.enqueue(MockResponse().setResponseCode(401))
        assertThrows(PasswordRpcException::class.java) { f.transport(refresh = false).invoke(PasswordMethod.CREATE, byteArrayOf()) }
        assertEquals(1, f.server.requestCount); assertEquals(1, f.loads); assertTrue(f.rejected.isEmpty())
    }

    @Test fun rejectedTokenCannotBeReusedWhenNativeInvalidationFails() {
        var loads = 0
        val session = PasswordOAuthSession({ "synthetic-${++loads}" }, { throw VaultLockedException() })
        assertThrows(VaultLockedException::class.java) { session.reject(session.token()) }
        assertEquals("synthetic-2", session.token())
        assertEquals("PasswordOAuthSession[redacted]", session.toString())
    }

    @Test fun missingNativeTokenFailsBeforeAnyRpc() = Fixture().use { f ->
        val session = PasswordOAuthSession({ null }, { throw AssertionError("No token to invalidate") })
        val transport = GooglePasswordTransport(session::token, "https://${PasswordMethod.CREATE.host}/".toHttpUrl(), rejectedToken = session::reject)
        assertThrows(VaultLockedException::class.java) { transport.invoke(PasswordMethod.CREATE, byteArrayOf()) }
        assertEquals(0, f.server.requestCount)
    }
}
