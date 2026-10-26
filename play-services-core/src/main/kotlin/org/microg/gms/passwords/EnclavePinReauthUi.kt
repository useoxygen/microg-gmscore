/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.RequiresApi
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.webkit.WebViewClientCompat
import com.google.android.gms.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.microg.gms.auth.AuthManager
import org.microg.gms.common.Constants.GMS_PACKAGE_NAME
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Actual Google PIN-reset reauthentication, authenticated for the selected native account. No JavaScript token bridge. */
internal object EnclavePinReauthUi {
    suspend fun show(activity: AppCompatActivity, accountName: String, stableId: String, verify: () -> Unit): String = withTimeout(120_000) {
        if (android.os.Build.VERSION.SDK_INT < 26) throw VaultLockedException()
        verify()
        val policy = EnclavePinReauthPolicy(stableId)
        val result = CompletableDeferred<String>()
        val http = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .callTimeout(20, TimeUnit.SECONDS).build()
        val web = WebView(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            addView(TextView(activity).apply { text = activity.getString(R.string.passwords_enclave_pin_reauth, accountName); setPadding(24, 24, 24, 24) })
            addView(Button(activity).apply { setText(android.R.string.cancel); setOnClickListener { result.completeExceptionally(VaultLockedException()) } })
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        web.settings.apply {
            javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            databaseEnabled = false; saveFormData = false; javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
        }
        val cookies = CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, false) }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage) = true
            @RequiresApi(21)
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { result.completeExceptionally(VaultLockedException()) }
        }
        activity.lifecycle.addObserver(observer)
        fun refuse() { result.completeExceptionally(VaultLockedException()) }
        @RequiresApi(21)
        fun empty(status: Int, reason: String) = WebResourceResponse("text/html", "UTF-8", status, reason,
            mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(byteArrayOf()))
        web.webViewClient = object : WebViewClientCompat() {
            @RequiresApi(21)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val permitted = EnclavePinReauthPolicy.navigationAllowed(request.url.toString())
                if (!permitted && request.isForMainFrame) refuse()
                return !permitted
            }
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                val permitted = EnclavePinReauthPolicy.navigationAllowed(url)
                if (!permitted) refuse()
                return !permitted
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (!EnclavePinReauthPolicy.navigationAllowed(url)) refuse()
            }
            @RequiresApi(21)
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (!request.isForMainFrame || !policy.proofCandidate(url)) return null
                // WebView's finish callback has no HTTP status. Perform this one terminal GET
                // through its real Google session so only the source's200/204 can acknowledge a token.
                return try {
                    verify()
                    if (result.isCompleted || request.method != "GET") throw VaultLockedException()
                    val builder = Request.Builder().url(url).get()
                    request.requestHeaders.filterKeys { it.lowercase() in setOf("accept", "accept-language", "user-agent", "referer") }
                        .forEach { (name, value) -> builder.header(name, value) }
                    cookies.getCookie(url)?.let { builder.header("Cookie", it) }
                    http.newCall(builder.build()).execute().use { response ->
                        verify()
                        val proof = policy.result(url, true, stableId, response.code)
                        response.headers.values("Set-Cookie").forEach { cookies.setCookie(url, it) }
                        if (!result.complete(proof)) throw VaultLockedException()
                        empty(response.code, if (response.code == 204) "No Content" else "OK")
                    }
                } catch (_: Exception) { refuse(); empty(403, "Forbidden") }
            }
        }
        try {
            activity.setContentView(root)
            val authenticated = withContext(Dispatchers.IO) {
                verify()
                AuthManager(activity, accountName, GMS_PACKAGE_NAME, "weblogin:service=gaia&continue=" +
                    URLEncoder.encode(EnclavePinReauthPolicy.TARGET, "UTF-8")).requestAuth(false).auth
            }
            verify()
            if (result.isCompleted || !TrustedVaultRecoveryPolicy.navigationAllowed(authenticated.orEmpty())) throw VaultLockedException()
            web.loadUrl(authenticated!!)
            result.await().also { verify() }
        } finally {
            result.cancel(); http.dispatcher.cancelAll(); activity.lifecycle.removeObserver(observer)
            web.stopLoading(); web.loadUrl("about:blank"); web.clearHistory(); root.removeView(web); web.destroy()
            activity.setContentView(android.widget.FrameLayout(activity))
        }
    }
}
