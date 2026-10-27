/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar

import android.accounts.*
import android.app.Service
import android.content.*
import android.os.Bundle
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.microg.gms.calendar.sync.*
import org.microg.gms.people.sync.*
import java.io.IOException
import java.util.concurrent.TimeUnit

class CalendarSyncService : Service() {
    private val adapter by lazy { object : AbstractThreadedSyncAdapter(this, true) {
        override fun onPerformSync(account: Account, extras: Bundle, authority: String,
            provider: ContentProviderClient, result: SyncResult) {
            val prefs = CalendarSyncPreferences(this@CalendarSyncService, account)
            if (!prefs.enabled) return
            val twoWay = prefs.twoWay
            fun active() = prefs.enabled && prefs.twoWay == twoWay && !Thread.currentThread().isInterrupted
            var token: String? = null
            try {
                token = calendarToken(this@CalendarSyncService, account, twoWay)
                val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(45, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
                val api = GoogleCalendarApi(CalendarTransport { method, url, body, headers ->
                    if (!active()) throw SyncStopped()
                    require(method in setOf("GET", "POST", "PATCH", "DELETE"))
                    if (method != "GET" && !(twoWay && prefs.uploadsAllowed)) throw SyncStopped()
                    val builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
                    headers.forEach { (key, value) ->
                        require(key.equals("If-Match", ignoreCase = true))
                        builder.header(key, value)
                    }
                    val request = builder.method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                    http.newCall(request).execute().use {
                        // Bound response memory before parsing. No raw response or token is logged.
                        val source = requireNotNull(it.body).source()
                        val buffer = okio.Buffer()
                        var total = 0L
                        while (true) {
                            val read = source.read(buffer, 8192)
                            if (read < 0) break
                            total += read
                            check(total <= 16 * 1024 * 1024)
                            if (!active()) throw SyncStopped()
                        }
                        HttpReply(it.code, buffer.readUtf8(), it.header("Retry-After")?.toLongOrNull()?.coerceAtLeast(0) ?: 0)
                    }
                })
                val report = CalendarSync(api, AndroidCalendarStore(provider, account, prefs), ::active,
                    { active() && twoWay && prefs.uploadsAllowed }).sync()
                result.stats.numUpdates += report.events + report.uploaded
                result.stats.numConflictDetectedExceptions += report.conflicts + report.uncertain
                result.stats.numSkippedEntries += report.skipped
                prefs.status = when { report.conflicts > 0 || report.uncertain > 0 -> "conflict"; report.skipped > 0 -> "partial"; else -> "success" }
                if (report.skipped > 0) Log.w(TAG, "Calendar sync skipped ${report.skipped} unreadable events")
                if (report.conflicts == 0 && report.uncertain == 0 && report.failure == null) prefs.lastSuccess = System.currentTimeMillis()
                // A calendar that failed: the others' counts above stand, and its error sets the status.
                report.failure?.let { throw it }
            } catch (e: CalendarRequestException) {
                if (e.status == 401) org.microg.gms.auth.invalidateAccountAuthToken(this@CalendarSyncService, account.type, token)
                val authorization = e.status == 401 || e.status == 403 && !e.rateLimited
                prefs.status = if (authorization) "authorization" else "network"
                if (authorization) result.stats.numAuthExceptions++ else result.stats.numIoExceptions++
                if (e.retryAfterSeconds > 0) result.delayUntil = System.currentTimeMillis()/1000 + e.retryAfterSeconds
            } catch (_: AuthenticatorException) {
                prefs.status = "authorization"; result.stats.numAuthExceptions++
            } catch (_: OperationCanceledException) {
                if (prefs.enabled) { prefs.status = "authorization"; result.stats.numAuthExceptions++ }
            } catch (_: IOException) {
                prefs.status = "network"; result.stats.numIoExceptions++
            } catch (_: SecurityException) {
                if (prefs.enabled) { prefs.status = "permission"; result.stats.numAuthExceptions++ }
            } catch (e: Exception) {
                // The kind only: no event content, account or token is logged.
                if (prefs.enabled && !Thread.currentThread().isInterrupted) {
                    Log.w(TAG, "Calendar sync could not apply a calendar: ${e.javaClass.simpleName}")
                    prefs.status = "local"; result.stats.numParseExceptions++
                }
            }
        }
    } }
    override fun onBind(intent: Intent) = adapter.syncAdapterBinder
    companion object {
        private const val TAG = "CyclonCalendarSync"
        const val SCOPE = "https://www.googleapis.com/auth/calendar.readonly"
        const val WRITE_SCOPE = "https://www.googleapis.com/auth/calendar"
        fun scope(twoWay: Boolean) = if (twoWay) WRITE_SCOPE else SCOPE
        fun calendarToken(context: Context, account: Account, twoWay: Boolean = false): String {
            val future = AccountManager.get(context).getAuthToken(account, "oauth2:${scope(twoWay)}", null, false, null, null)
            try {
                return future.getResult(45, TimeUnit.SECONDS).getString(AccountManager.KEY_AUTHTOKEN)?.takeIf { it.isNotBlank() }
                    ?: throw AuthenticatorException("Calendar authorization required")
            } finally { if (!future.isDone) future.cancel(true) }
        }
    }
}
