/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AuthenticatorException
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.microg.gms.people.sync.*
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The existing microG account/provider adapter, now with opt-in synchronization. */
class ContactSyncService : Service() {
    private val adapter by lazy {
        object : AbstractThreadedSyncAdapter(this, true) {
            override fun onPerformSync(account: Account, extras: Bundle, authority: String,
                provider: ContentProviderClient, result: SyncResult) {
                val prefs = ContactSyncPreferences(this@ContactSyncService, account)
                if (prefs.mode == SyncMode.OFF) return
                var usedToken: String? = null
                try {
                    val mode = prefs.mode
                    val token = contactsToken(this@ContactSyncService, account, mode)
                    usedToken = token
                    val api = contactsApi(token) { prefs.mode == SyncMode.TWO_WAY && mode == SyncMode.TWO_WAY }
                    val store = AndroidContactStore(provider, account, prefs)
                    store.configureMode()
                    val report = ContactsSync(api, store) { prefs.mode }.sync()
                    result.stats.numEntries += report.downloaded
                    result.stats.numUpdates += report.uploaded
                    result.stats.numConflictDetectedExceptions += report.conflicts + report.uncertain
                    prefs.status = when {
                        report.uncertain > 0 -> "uncertain"
                        report.conflicts > 0 -> "conflict"
                        else -> "success"
                    }
                    if (report.conflicts == 0 && report.uncertain == 0) prefs.lastSuccess = System.currentTimeMillis()
                } catch (e: ApiException) {
                    if (e.status == 401) {
                        AccountManager.get(this@ContactSyncService).invalidateAuthToken(account.type,
                            usedToken)
                    }
                    prefs.status = if (e.status == 401 || e.status == 403) "authorization" else "network"
                    if (e.status == 401 || e.status == 403) result.stats.numAuthExceptions++ else result.stats.numIoExceptions++
                    if (e.retryAfterSeconds > 0) result.delayUntil = System.currentTimeMillis() / 1000 + e.retryAfterSeconds
                    Log.w(TAG, "Contacts API status=${e.status}")
                } catch (e: AuthenticatorException) {
                    prefs.status = "authorization"
                    result.stats.numAuthExceptions++
                } catch (e: IOException) {
                    prefs.status = "network"
                    result.stats.numIoExceptions++
                    Log.w(TAG, "Contacts transport interrupted")
                } catch (e: Exception) {
                    prefs.status = "local"
                    result.stats.numParseExceptions++
                    Log.w(TAG, "Contacts sync stopped (${e.javaClass.simpleName})")
                }
            }
        }
    }
    override fun onBind(intent: Intent) = adapter.syncAdapterBinder

    companion object {
        private const val TAG = "GmsContactSync"
        fun scope(mode: SyncMode) = if (mode == SyncMode.TWO_WAY)
            "https://www.googleapis.com/auth/contacts" else "https://www.googleapis.com/auth/contacts.readonly"
        fun contactsToken(context: Context, account: Account, mode: SyncMode): String {
            check(mode != SyncMode.OFF)
            val result = AccountManager.get(context).getAuthToken(account, "oauth2:" + scope(mode), null, false, null, null).result
            return result.getString(AccountManager.KEY_AUTHTOKEN)?.takeIf { it.isNotBlank() }
                ?: throw AuthenticatorException("Contacts authorization required")
        }
        fun contactsApi(token: String, canUpload: () -> Boolean): PeopleApi {
            val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
            return PeopleApi(HttpTransport { method, url, body ->
                // Redirects and transparent POST retries must not forward tokens
                // or duplicate contacts after a lost mutation response.
                val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
                    .method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                http.newCall(request).execute().use { response ->
                    HttpReply(response.code, response.body?.string().orEmpty(),
                        response.header("Retry-After")?.toLongOrNull()?.coerceAtLeast(0) ?: 0)
                }
            }, canUpload)
        }
    }
}
