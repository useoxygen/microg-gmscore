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
            var token: String? = null
            try {
                token = calendarToken(this@CalendarSyncService, account)
                val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(45, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
                val api = GoogleCalendarApi(HttpTransport { method, url, body ->
                    check(method == "GET" && body == null && prefs.enabled && !Thread.currentThread().isInterrupted)
                    val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
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
                            check(prefs.enabled && !Thread.currentThread().isInterrupted)
                        }
                        HttpReply(it.code, buffer.readUtf8(), it.header("Retry-After")?.toLongOrNull()?.coerceAtLeast(0) ?: 0)
                    }
                })
                val report = CalendarSync(api, AndroidCalendarStore(provider, account, prefs)) {
                    prefs.enabled && !Thread.currentThread().isInterrupted
                }.sync()
                result.stats.numUpdates += report.events
                result.stats.numConflictDetectedExceptions += report.conflicts
                result.stats.numSkippedEntries += report.skipped
                prefs.status = when { report.conflicts > 0 -> "conflict"; report.skipped > 0 -> "partial"; else -> "success" }
                if (report.skipped > 0) Log.w(TAG, "Calendar sync skipped ${report.skipped} unreadable events")
                if (report.conflicts == 0 && report.failure == null) prefs.lastSuccess = System.currentTimeMillis()
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
        fun calendarToken(context: Context, account: Account): String {
            val future = AccountManager.get(context).getAuthToken(account, "oauth2:$SCOPE", null, false, null, null)
            try {
                return future.getResult(45, TimeUnit.SECONDS).getString(AccountManager.KEY_AUTHTOKEN)?.takeIf { it.isNotBlank() }
                    ?: throw AuthenticatorException("Calendar authorization required")
            } finally { if (!future.isDone) future.cancel(true) }
        }
    }
}
