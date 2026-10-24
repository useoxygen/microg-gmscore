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
import okhttp3.HttpUrl.Companion.toHttpUrl
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
                if (prefs.mode == SyncMode.TWO_WAY) { prefs.status = "two_way_unavailable"; return }
                if (prefs.blockedClientContext == privatePeopleContext.binding) { prefs.status = "needs_update"; return }
                var usedToken: String? = null
                try {
                    val mode = prefs.mode
                    val token = contactsToken(this@ContactSyncService, account, mode)
                    usedToken = token
                    val api = privateContactsApi(token)
                    val currentMode = { if (prefs.mode == SyncMode.DOWNLOAD) SyncMode.DOWNLOAD else SyncMode.OFF }
                    val groups = AndroidGroupStore(provider, account, prefs, PrivatePeopleApi.OWNER, privatePeopleContext.binding)
                    groups.initialize()
                    val groupReport = GroupsSync(api, groups, currentMode).sync()
                    val store = AndroidContactStore(provider, account, prefs, groups, api, PrivatePeopleApi.OWNER, privatePeopleContext.binding)
                    store.configureMode()
                    val contactReport = ContactsSync(api, store, currentMode).sync()
                    val photoReport = PhotosSync(api, api, store, currentMode).sync()
                    val report = groupReport + contactReport + photoReport
                    result.stats.numEntries += report.downloaded
                    result.stats.numUpdates += report.uploaded
                    result.stats.numParseExceptions += report.skipped
                    result.stats.numConflictDetectedExceptions += report.conflicts + report.uncertain
                    prefs.status = when {
                        report.skipped > 0 -> "partial"
                        report.uncertain > 0 -> "uncertain"
                        report.conflicts > 0 -> "conflict"
                        else -> "success"
                    }
                    if (report.conflicts == 0 && report.uncertain == 0 && report.skipped == 0) prefs.lastSuccess = System.currentTimeMillis()
                } catch (e: PeopleClientUpdateRequired) {
                    prefs.status = "needs_update"
                    prefs.blockedClientContext = privatePeopleContext.binding
                    Log.w(TAG, "stage=api failure=NEEDS_UPDATE")
                    // No I/O failure count: OS retries cannot hammer a rejected
                    // context. A new advertised version or user refresh unlocks it.
                } catch (e: ApiException) {
                    if (e.status == 401) {
                        AccountManager.get(this@ContactSyncService).invalidateAuthToken(account.type,
                            usedToken)
                    }
                    prefs.status = if (e.status == 401 || e.status == 403) "authorization" else "network"
                    if (e.status == 401 || e.status == 403) result.stats.numAuthExceptions++ else result.stats.numIoExceptions++
                    if (e.retryAfterSeconds > 0) result.delayUntil = System.currentTimeMillis() / 1000 + e.retryAfterSeconds
                    prefs.diagnostic = ContactsDiagnostics.activation(ActivationStage.API, e)
                    Log.w(TAG, "sync " + prefs.diagnostic)
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
        val privatePeopleContext: PrivatePeopleContext get() = PrivatePeopleContext.fromGmsVersionCode(org.microg.gms.common.Constants.GMS_VERSION_CODE)
        fun scope(mode: SyncMode): String {
            if (mode == SyncMode.TWO_WAY) throw ContactsTwoWayUnavailable()
            require(mode == SyncMode.DOWNLOAD)
            return PrivatePeopleScope.READONLY.scope
        }
        fun contactsToken(context: Context, account: Account, mode: SyncMode): String {
            check(mode != SyncMode.OFF)
            Log.i(TAG, "stage=authorization oauth2:" + scope(mode))
            val result = AccountManager.get(context).getAuthToken(account, "oauth2:" + scope(mode), null, false, null, null).result
            return result.getString(AccountManager.KEY_AUTHTOKEN)?.takeIf { it.isNotBlank() }
                ?: throw AuthenticatorException("Contacts authorization required")
        }
        /** Fixed diagnostic GET. No provider/store, retries, redirects or borrowed API key. */
        fun privatePeopleProbeGet(token: String, url: String): HttpReply {
            require(url == PrivatePeopleProbe.url(privatePeopleContext))
            val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
            http.newCall(request).execute().use { response ->
                val body = response.body ?: return HttpReply(response.code, "")
                if (body.contentLength() > PrivatePeopleProbe.MAX_RESPONSE_BYTES) throw ProbeResponseTooLarge(response.code)
                val out = java.io.ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        if (out.size() + count > PrivatePeopleProbe.MAX_RESPONSE_BYTES) throw ProbeResponseTooLarge(response.code)
                        out.write(buffer, 0, count)
                    }
                }
                return HttpReply(response.code, out.toString("UTF-8"))
            }
        }
        private fun http() = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        private fun transport(http: OkHttpClient, token: String, origin: String) = HttpTransport { method, url, body ->
            require(url.startsWith(origin))
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
                .method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            http.newCall(request).execute().use { response ->
                val result = java.io.ByteArrayOutputStream()
                response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        if (result.size() + count > 16 * 1024 * 1024) throw IOException("Contacts response too large")
                        result.write(buffer, 0, count)
                    }
                }
                HttpReply(response.code, result.toString("UTF-8"), response.header("Retry-After")?.toLongOrNull()?.coerceAtLeast(0) ?: 0)
            }
        }
        private fun photos(http: OkHttpClient) = PhotoTransport { url ->
            require(ContactPhotos.safeUrl(url))
            // Separate request, with no account bearer credentials on image hosts.
            val photoUrl = url.toHttpUrl().newBuilder().setQueryParameter("sz", "1024").build()
            http.newCall(Request.Builder().url(photoUrl).get().build()).execute().use { response ->
                if (response.code in listOf(403, 404)) throw UnreadableContact()
                if (!response.isSuccessful) throw ApiException(response.code)
                val body = response.body ?: throw UnreadableContact()
                if (body.contentLength() > ContactPhotos.MAX_BYTES) throw UnreadableContact()
                body.byteStream().use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        if (out.size() + count > ContactPhotos.MAX_BYTES) throw UnreadableContact()
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
            }
        }
        fun privateContactsApi(token: String): PrivatePeopleApi = http().let {
            PrivatePeopleApi(transport(it, token, PrivatePeopleApi.ORIGIN), privatePeopleContext)
        }
        // Retained for an eventual separately registered public OAuth client.
        // Production account sync and activation never call this factory.
        fun contactsApi(token: String, canUpload: () -> Boolean): PeopleApi = http().let {
            PeopleApi(transport(it, token, PeopleApi.ORIGIN), canUpload, photos(it))
        }
    }
}
