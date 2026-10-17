/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.authzen

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class AuthZenApiTest {
    private val responses = ArrayDeque<AuthZenHttpResult>()
    private val sent = mutableListOf<Triple<AuthZenEndpoint, String, String>>()
    private val invalidated = mutableListOf<String>()
    private var tokenCount = 0
    private var tokensAvailable = true

    private val api = AuthZenApi(object : AuthZenTransport {
        override fun post(endpoint: AuthZenEndpoint, bearerToken: String, body: String): AuthZenHttpResult {
            sent += Triple(endpoint, bearerToken, body)
            return responses.removeFirst()
        }
    }, object : AuthZenTokenProvider {
        override fun token(account: String) = if (tokensAvailable) "token-${++tokenCount}" else null
        override fun invalidate(account: String, token: String) {
            invalidated += token
        }
    })

    private fun post(vararg results: AuthZenHttpResult): AuthZenApiResult {
        responses.addAll(results)
        return api.post("account", AuthZenEndpoint.REPLY_TX, JSONObject().put("k", "v"))
    }

    private fun response(code: Int, body: String? = "{}", retryAfter: Long? = null) = AuthZenHttpResult.Response(code, body, retryAfter)

    @Test
    fun endpointsAndScopeAreFixed() {
        for (endpoint in AuthZenEndpoint.values()) {
            val url = URL(endpoint.url)
            assertEquals("https", url.protocol)
            assertEquals("www.googleapis.com", url.host)
            assertTrue(url.path.startsWith("/cryptauth/v1/authzen/"))
        }
        assertEquals("oauth2:https://www.googleapis.com/auth/cryptauth", AUTHZEN_SCOPE)
    }

    @Test
    fun successNeedsAParsableBody() {
        val ok = post(response(200, "{\"a\":1}"))
        assertEquals(1, (ok as AuthZenApiResult.Ok).body.getInt("a"))
        assertEquals("{\"k\":\"v\"}", sent.single().third)
        assertEquals(AuthZenApiResult.Unknown, post(response(200, "not json")))
        assertEquals(AuthZenApiResult.Unknown, post(response(204, null)))
    }

    @Test
    fun a401RefreshesTheTokenOnce() {
        assertTrue(post(response(401), response(200)) is AuthZenApiResult.Ok)
        assertEquals(listOf("token-1"), invalidated)
        assertEquals(listOf("token-1", "token-2"), sent.map { it.second })

        sent.clear()
        assertEquals(AuthZenApiResult.AuthFailed, post(response(401), response(401)))
        assertEquals(2, sent.size)
        assertEquals(3, invalidated.size)
    }

    @Test
    fun noTokenSendsNothing() {
        tokensAvailable = false
        assertEquals(AuthZenApiResult.AuthFailed, post())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun refusalsRateLimitsAndServerErrors() {
        assertEquals(403, (post(response(403)) as AuthZenApiResult.Rejected).code)
        assertEquals(302, (post(response(302)) as AuthZenApiResult.Rejected).code)
        assertEquals(120_000L, (post(response(429, retryAfter = 120)) as AuthZenApiResult.RateLimited).retryAfterMillis)
        assertEquals(60_000L, (post(response(429)) as AuthZenApiResult.RateLimited).retryAfterMillis)
        assertEquals(3_600_000L, (post(response(429, retryAfter = 999_999)) as AuthZenApiResult.RateLimited).retryAfterMillis)
        assertEquals(AuthZenApiResult.Unknown, post(response(503)))
        assertEquals(1, sent.count { it.second == "token-1" })
    }

    @Test
    fun timeoutsBeforeAndAfterSending() {
        assertEquals(AuthZenApiResult.NotDelivered, post(AuthZenHttpResult.NotSent))
        assertEquals(AuthZenApiResult.Unknown, post(AuthZenHttpResult.Interrupted))
    }

    @Test
    fun sendOutcomes() {
        val confirmed: (JSONObject) -> SendOutcome = { SendOutcome.CONFIRMED }
        assertEquals(SendOutcome.CONFIRMED, AuthZenApiResult.Ok(JSONObject()).toSendOutcome(confirmed))
        assertEquals(SendOutcome.NOT_DELIVERED, AuthZenApiResult.NotDelivered.toSendOutcome(confirmed))
        assertEquals(SendOutcome.NOT_DELIVERED, AuthZenApiResult.AuthFailed.toSendOutcome(confirmed))
        assertEquals(SendOutcome.RATE_LIMITED, AuthZenApiResult.RateLimited(1000).toSendOutcome(confirmed))
        assertEquals(SendOutcome.REJECTED, AuthZenApiResult.Rejected(403).toSendOutcome(confirmed))
        assertEquals(SendOutcome.UNKNOWN, AuthZenApiResult.Unknown.toSendOutcome(confirmed))
    }

    @Test
    fun retryAfterAcceptsDelaySecondsAndEveryHttpDateForm() {
        val now = 784_111_777_000L // Sun, 06 Nov 1994 08:49:37 GMT
        assertEquals(120L, parseRetryAfterSeconds("120", null, now))
        assertEquals(0L, parseRetryAfterSeconds(" 0 ", null, now))
        assertEquals(Long.MAX_VALUE, parseRetryAfterSeconds("99999999999999999999", null, now))
        // The same instant, 180 s after now, in IMF-fixdate, RFC 850 and asctime form (RFC 9110 §5.6.7).
        for (date in listOf("Sun, 06 Nov 1994 08:52:37 GMT", "Sunday, 06-Nov-94 08:52:37 GMT", "Sun Nov  6 08:52:37 1994")) {
            assertEquals(date, 180L, parseRetryAfterSeconds(date, null, now))
        }
        // Measured against the response's Date header, not the phone's (here 1 h fast) clock.
        assertEquals(180L, parseRetryAfterSeconds("Sun, 06 Nov 1994 08:52:37 GMT", "Sun, 06 Nov 1994 08:49:37 GMT", now + 3_600_000))
        // An unparsable Date header falls back to the local clock; a partial second rounds up; the past means now.
        assertEquals(180L, parseRetryAfterSeconds("Sun, 06 Nov 1994 08:52:37 GMT", "yesterday", now))
        assertEquals(1L, parseRetryAfterSeconds("Sun, 06 Nov 1994 08:49:38 GMT", null, now + 500))
        assertEquals(0L, parseRetryAfterSeconds("Sun, 06 Nov 1994 08:00:00 GMT", null, now))
        for (bad in listOf(null, "", "-5", "1.5", "soon", "Sun, 06 Nov 1994 08:52:37 PST", "Sun, 06 Nov 1994 08:52:37 GMT trailing")) {
            assertNull(bad, parseRetryAfterSeconds(bad, null, now))
        }
    }

    /** A canned 429 response; it never touches the network. */
    private class RateLimitedConnection(url: URL, private val headers: Map<String, String>) : HttpURLConnection(url) {
        val written = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream = written
        override fun getResponseCode() = 429
        override fun getErrorStream(): InputStream = ByteArrayInputStream("{}".toByteArray())
        override fun getHeaderField(name: String): String? = headers[name]
    }

    @Test
    fun anHttpDateRetryAfterKeepsTheRequestedWaitThroughTheTransport() {
        val now = 784_111_777_000L
        val connections = mutableListOf<RateLimitedConnection>()
        fun transport(headers: Map<String, String>) = HttpAuthZenTransport(
            openConnection = { url -> RateLimitedConnection(url, headers).also { connections += it } },
            wallClock = { now },
        )
        val dated = transport(mapOf("Retry-After" to "Sun, 06 Nov 1994 08:52:37 GMT", "Date" to "Sun, 06 Nov 1994 08:49:37 GMT"))
        val response = dated.post(AuthZenEndpoint.REPLY_TX, "token", "{}") as AuthZenHttpResult.Response
        assertEquals(180L, response.retryAfterSeconds)
        assertEquals(180_000L, (post(response) as AuthZenApiResult.RateLimited).retryAfterMillis)
        // The caps still apply to a date: at least 1 s, at most 1 h.
        val past = transport(mapOf("Retry-After" to "Sun, 06 Nov 1994 08:00:00 GMT")).post(AuthZenEndpoint.REPLY_TX, "token", "{}")
        assertEquals(1_000L, (post(past) as AuthZenApiResult.RateLimited).retryAfterMillis)
        val far = transport(mapOf("Retry-After" to "Mon, 07 Nov 1994 08:49:37 GMT")).post(AuthZenEndpoint.REPLY_TX, "token", "{}")
        assertEquals(3_600_000L, (post(far) as AuthZenApiResult.RateLimited).retryAfterMillis)
        val malformed = transport(mapOf("Retry-After" to "soon")).post(AuthZenEndpoint.REPLY_TX, "token", "{}")
        assertEquals(60_000L, (post(malformed) as AuthZenApiResult.RateLimited).retryAfterMillis)
        assertEquals("{}", connections.first().written.toString())
    }
}
