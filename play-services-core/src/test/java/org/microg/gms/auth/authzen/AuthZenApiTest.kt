/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.authzen

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
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
}
