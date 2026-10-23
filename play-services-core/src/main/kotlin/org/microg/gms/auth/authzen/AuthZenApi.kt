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
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

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
    /** 429: wait at least [retryAfterMillis]; the sender stores it with the decision. */
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
    AuthZenApiResult.NotDelivered, AuthZenApiResult.AuthFailed -> SendOutcome.NOT_DELIVERED
    is AuthZenApiResult.RateLimited -> SendOutcome.RATE_LIMITED
    is AuthZenApiResult.Rejected -> SendOutcome.REJECTED
    AuthZenApiResult.Unknown -> SendOutcome.UNKNOWN
}

/**
 * HTTPS POST without redirects; the token only ever goes to an [AuthZenEndpoint] URL. [openConnection] and
 * [wallClock] exist for host tests, which never open a real connection.
 */
internal class HttpAuthZenTransport(
    private val timeoutMillis: Int = 20_000,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val wallClock: () -> Long = System::currentTimeMillis,
) : AuthZenTransport {
    override fun post(endpoint: AuthZenEndpoint, bearerToken: String, body: String): AuthZenHttpResult {
        val url = URL(endpoint.url)
        check(url.protocol == "https" && url.host == "www.googleapis.com")
        val connection = openConnection(url)
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
            val retryAfter = parseRetryAfterSeconds(connection.getHeaderField("Retry-After"), connection.getHeaderField("Date"), wallClock())
            AuthZenHttpResult.Response(code, text, retryAfter)
        } catch (e: SocketTimeoutException) {
            AuthZenHttpResult.Interrupted
        } catch (e: IOException) {
            AuthZenHttpResult.Interrupted
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * The wait a `Retry-After` value asks for, in whole seconds, or null when it is missing or malformed (RFC 9110
 * §10.2.3). It is either delay-seconds or an HTTP-date; recipients must accept all three HTTP-date forms (§5.6.7).
 * A date is measured against the response's own `Date` header when that parses, so the phone's clock skew does not
 * change the wait, and otherwise against [nowWallMillis]; a date already past means no wait. Rounded up, never
 * negative; [AuthZenApi] applies the caps, and the store turns the wait into a monotonic deadline.
 */
internal fun parseRetryAfterSeconds(retryAfter: String?, responseDate: String?, nowWallMillis: Long): Long? {
    val value = retryAfter?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (value.all { it in '0'..'9' }) return value.toLongOrNull() ?: Long.MAX_VALUE
    val until = parseHttpDate(value) ?: return null
    val from = responseDate?.let(::parseHttpDate) ?: nowWallMillis
    val millis = until - from
    return if (millis <= 0) 0 else (millis + 999) / 1000
}

/**
 * IMF-fixdate, then the obsolete RFC 850 and asctime forms; always GMT, English names, strict. The grammar allows a
 * seconds value of 60 for a leap second, which strict [SimpleDateFormat] rejects: such a time is read as second 59
 * plus one second, the instant just after the leap second on a clock without one.
 */
private fun parseHttpDate(value: String): Long? {
    val leap = LEAP_SECOND_TIME.findAll(value).toList()
    if (leap.size == 1) {
        val group = leap.single().groups[1]!!
        return parseStrictHttpDate(value.replaceRange(group.range, "59"))?.plus(1000)
    }
    return parseStrictHttpDate(value)
}

private fun parseStrictHttpDate(value: String): Long? {
    for (pattern in HTTP_DATE_PATTERNS) {
        val format = SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
            isLenient = false
        }
        val position = ParsePosition(0)
        val date = format.parse(value, position)
        if (date != null && position.index == value.length) return date.time
    }
    return null
}

/** The `time-of-day` of every HTTP-date form, preceded and followed by a space, with second 60. */
private val LEAP_SECOND_TIME = Regex(" [0-9]{2}:[0-9]{2}:(60) ")

private val HTTP_DATE_PATTERNS = listOf("EEE, dd MMM yyyy HH:mm:ss 'GMT'", "EEEE, dd-MMM-yy HH:mm:ss 'GMT'", "EEE MMM d HH:mm:ss yyyy")

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
