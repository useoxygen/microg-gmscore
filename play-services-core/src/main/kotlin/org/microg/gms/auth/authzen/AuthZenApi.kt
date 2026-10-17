/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.authzen

import android.content.Context
import org.json.JSONObject
import org.microg.gms.auth.AuthManager
import org.microg.gms.common.Constants
import org.microg.gms.common.Utils
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * The only endpoints the native route may call. Push data never supplies a URL, host or scope.
 *
 * HISTORICAL: these paths come from a static reading of Play services 20.15.15 (docs/google-prompt.md); whether
 * Google still serves them, and with which body, is UNKNOWN. They are not called until a profile is verified.
 */
internal enum class AuthZenEndpoint(val url: String) {
    REPLY_TX("https://www.googleapis.com/cryptauth/v1/authzen/replytx"),
    SYNC_TX("https://www.googleapis.com/cryptauth/v1/authzen/synctx"),
}

/** The fixed token scope for [AuthZenEndpoint]; HISTORICAL like the endpoints. */
internal const val AUTHZEN_SCOPE = "oauth2:https://www.googleapis.com/auth/cryptauth"

internal sealed class AuthZenHttpResult {
    class Response(val code: Int, val body: String?, val retryAfterSeconds: Long?) : AuthZenHttpResult()
    /** Failed before any request byte was written (DNS, connect). */
    data object NotSent : AuthZenHttpResult()
    /** Failed after the request may have been written (timeout, reset). */
    data object Interrupted : AuthZenHttpResult()
}

internal interface AuthZenTransport {
    fun post(endpoint: AuthZenEndpoint, bearerToken: String, body: String): AuthZenHttpResult
}

internal interface AuthZenTokenProvider {
    fun token(account: String): String?
    fun invalidate(account: String, token: String)
}

internal sealed class AuthZenApiResult {
    class Ok(val body: JSONObject) : AuthZenApiResult()
    /** Provably not delivered; the caller may try again later. */
    data object NotDelivered : AuthZenApiResult()
    /** No token, or the token was refused twice. Shown as a setup problem; never retried in a loop. */
    data object AuthFailed : AuthZenApiResult()
    /** 429: wait at least [retryAfterMillis]. */
    class RateLimited(val retryAfterMillis: Long) : AuthZenApiResult()
    /** 403 and other refusals, including redirects, which are never followed. */
    class Rejected(val code: Int) : AuthZenApiResult()
    /** It may have been processed: 5xx, a timeout after sending, or a 200 whose body is not JSON. */
    data object Unknown : AuthZenApiResult()
}

/**
 * Calls [AuthZenEndpoint]s for one account with the fixed [AUTHZEN_SCOPE] token: one token refresh after a 401, then
 * failure; no automatic retry of anything that may have reached the server.
 */
internal class AuthZenApi(private val transport: AuthZenTransport, private val tokens: AuthZenTokenProvider) {

    fun post(account: String, endpoint: AuthZenEndpoint, body: JSONObject): AuthZenApiResult {
        val payload = body.toString()
        var refreshed = false
        while (true) {
            val token = tokens.token(account) ?: return AuthZenApiResult.AuthFailed
            val result = transport.post(endpoint, token, payload)
            if (result !is AuthZenHttpResult.Response) {
                return if (result is AuthZenHttpResult.NotSent) AuthZenApiResult.NotDelivered else AuthZenApiResult.Unknown
            }
            when (result.code) {
                in 200..299 -> return try {
                    AuthZenApiResult.Ok(JSONObject(result.body ?: ""))
                } catch (e: Exception) {
                    AuthZenApiResult.Unknown
                }
                401 -> {
                    tokens.invalidate(account, token)
                    if (refreshed) return AuthZenApiResult.AuthFailed
                    refreshed = true
                }
                429 -> return AuthZenApiResult.RateLimited(((result.retryAfterSeconds ?: 60L).coerceIn(1L, 3600L)) * 1000)
                in 500..599 -> return AuthZenApiResult.Unknown
                else -> return AuthZenApiResult.Rejected(result.code)
            }
        }
    }
}

internal fun AuthZenApiResult.toSendOutcome(interpret: (JSONObject) -> SendOutcome): SendOutcome = when (this) {
    is AuthZenApiResult.Ok -> interpret(body)
    AuthZenApiResult.NotDelivered, AuthZenApiResult.AuthFailed, is AuthZenApiResult.RateLimited -> SendOutcome.NOT_DELIVERED
    is AuthZenApiResult.Rejected -> SendOutcome.REJECTED
    AuthZenApiResult.Unknown -> SendOutcome.UNKNOWN
}

/** HTTPS POST without redirects; the token only ever goes to an [AuthZenEndpoint] URL. */
internal class HttpAuthZenTransport(private val timeoutMillis: Int = 20_000) : AuthZenTransport {
    override fun post(endpoint: AuthZenEndpoint, bearerToken: String, body: String): AuthZenHttpResult {
        val url = URL(endpoint.url)
        check(url.protocol == "https" && url.host == "www.googleapis.com")
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.setRequestProperty("Authorization", "Bearer $bearerToken")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("x-android-package", Constants.GMS_PACKAGE_NAME)
        try {
            connection.connect()
        } catch (e: IOException) {
            return AuthZenHttpResult.NotSent
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { String(Utils.readStreamToEnd(it)) }
            AuthZenHttpResult.Response(code, text, connection.getHeaderField("Retry-After")?.toLongOrNull())
        } catch (e: SocketTimeoutException) {
            AuthZenHttpResult.Interrupted
        } catch (e: IOException) {
            AuthZenHttpResult.Interrupted
        } finally {
            connection.disconnect()
        }
    }
}

internal class AccountManagerTokenProvider(private val context: Context) : AuthZenTokenProvider {
    override fun token(account: String): String? = try {
        AuthManager(context, account, Constants.GMS_PACKAGE_NAME, AUTHZEN_SCOPE).requestAuth(false).auth
    } catch (e: Exception) {
        null
    }

    override fun invalidate(account: String, token: String) {
        AuthManager(context, account, Constants.GMS_PACKAGE_NAME, AUTHZEN_SCOPE).invalidateAuthToken(token)
    }
}
