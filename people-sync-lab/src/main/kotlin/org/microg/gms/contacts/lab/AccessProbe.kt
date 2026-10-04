/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.contacts.lab

import android.accounts.Account
import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.microg.gms.people.sync.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Tokens stay in the authorized Android process. Receipts contain no contact content. */
object AccessProbe {
    fun errorCode(error: Exception): String? {
        val messages = generateSequence<Throwable>(error) { it.cause }.take(8)
            .map { it.message.orEmpty() }.joinToString(" ")
        return listOf("UNREGISTERED_ON_API_CONSOLE", "InvalidApp", "BadAuthentication", "NeedsBrowser", "InvalidScope")
            .firstOrNull { messages.contains(it, ignoreCase = true) }
    }
    fun receipt(account: Account) = JSONObject().put("operation", "read-only-google-contacts-probe")
        .put("googleWrites", 0).put("providerWrites", 0)
        .put("accountSha256", MessageDigest.getInstance("SHA-256")
            .digest(account.name.toByteArray()).joinToString("") { "%02x".format(it) })

    fun save(context: Context, receipt: JSONObject) {
        File(context.filesDir, "access-probe.json").writeText(receipt.toString(2))
    }

    fun verify(context: Context, receipt: JSONObject, token: String): String {
        try {
            val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS).followRedirects(false)
                .followSslRedirects(false).retryOnConnectionFailure(false).build()
            val api = PeopleApi(HttpTransport { method, url, body ->
                check(method == "GET" && body == null)
                val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    receipt.put("httpStatus", response.code)
                    if (!response.isSuccessful) {
                        val reason = runCatching { JSONObject(text).getJSONObject("error")
                            .optJSONArray("details")?.let { details ->
                                (0 until details.length()).map { details.getJSONObject(it).optString("reason") }
                                    .firstOrNull { it in setOf("SERVICE_DISABLED", "ACCESS_TOKEN_SCOPE_INSUFFICIENT", "CONSUMER_INVALID") }
                            } }.getOrNull()
                        reason?.let { receipt.put("apiReason", it) }
                    }
                    HttpReply(response.code, text)
                }
            }, { false })
            var pageToken: String? = null
            val seenPages = mutableSetOf<String>()
            val resources = mutableSetOf<String>()
            var pages = 0
            do {
                val page = api.list(null, pageToken)
                pages++
                page.people.forEach { check(resources.add(it.resource)) }
                pageToken = page.nextPage
                check(pages <= 1000 && resources.size <= 100_000)
                if (pageToken != null) check(seenPages.add(pageToken))
                else check(!page.nextSync.isNullOrBlank())
            } while (pageToken != null)
            receipt.put("contacts", resources.size).put("pages", pages)
                .put("status", "Google access verified: ${resources.size} contacts across $pages page(s). No contacts changed.")
        } catch (e: Exception) {
            receipt.put("exception", e.javaClass.simpleName)
                .put("status", if (e is ApiException) "Google API refused the check (HTTP ${e.status})." else
                    "Google access check stopped (${e.javaClass.simpleName}).")
        }
        save(context, receipt)
        return receipt.getString("status")
    }
}
