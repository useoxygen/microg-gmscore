/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.credentials.provider

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.microg.gms.auth.AuthManager
import org.microg.gms.common.Constants.GMS_PACKAGE_NAME
import org.microg.gms.passwords.PasskeyReauthPolicy
import org.microg.gms.passwords.TrustedVaultRecoveryPolicy
import org.microg.gms.passwords.VaultLockedException
import java.net.URLEncoder

/** Private, owner-selected Google xreauth UI. No proof is stored, put in an Intent, or returned to the app/model. */
@RequiresApi(34)
internal object PasskeyReauthUi {
    suspend fun show(activity: PublicKeyProxyActivity, accountName: String, stableId: String, lookup: String,
        verify: () -> Unit): String = withTimeout(120_000) {
        verify()
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) throw VaultLockedException()
        val policy = PasskeyReauthPolicy(stableId)
        val result = CompletableDeferred<String>()
        val web = WebView(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            addView(TextView(activity).apply { text = "Confirm your Google account to use synced passkeys\n$accountName"; setPadding(24, 24, 24, 24) })
            addView(Button(activity).apply { setText(android.R.string.cancel); setOnClickListener { result.completeExceptionally(VaultLockedException()) } })
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        web.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            databaseEnabled = false; saveFormData = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, false) }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean = true
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { result.completeExceptionally(VaultLockedException()) }
        }
        activity.lifecycle.addObserver(observer)
        fun refuse() { result.completeExceptionally(VaultLockedException()) }
        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val allowed = TrustedVaultRecoveryPolicy.navigationAllowed(request.url.toString())
                if (!allowed && request.isForMainFrame) refuse()
                return !allowed
            }
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                val allowed = TrustedVaultRecoveryPolicy.navigationAllowed(url)
                if (!allowed) refuse()
                return !allowed
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (!TrustedVaultRecoveryPolicy.navigationAllowed(url.orEmpty())) refuse()
            }
        }
        WebViewCompat.addWebMessageListener(web, "_microgPasskeyReauth", setOf(TrustedVaultRecoveryPolicy.ORIGIN)) { _, message, origin, mainFrame, _ ->
            if (result.isCompleted) return@addWebMessageListener
            try {
                verify()
                if (!TrustedVaultRecoveryPolicy.navigationAllowed(web.url.orEmpty())) throw VaultLockedException()
                val raw = message.data ?: throw VaultLockedException()
                if (raw.length > 20_000) throw VaultLockedException()
                val parsed = JSONObject(raw)
                // Native account identity is rechecked by verify(); the proof is additionally
                // bound to the server's selected-account packaged lookup token.
                result.complete(policy.result(origin.toString(), mainFrame, stableId, parsed.getInt("status"), parsed.getString("rapt")))
            } catch (_: Exception) { refuse() }
        }
        val script = WebViewCompat.addDocumentStartJavaScript(web, """
            if (window === window.top) {
              Object.defineProperty(window, 'mm', {value:Object.freeze({
                setReauthResult:function(value) { _microgPasskeyReauth.postMessage(String(value)); }
              }),writable:false,configurable:false});
            }
        """.trimIndent(), setOf(TrustedVaultRecoveryPolicy.ORIGIN))
        try {
            activity.setContentView(root)
            // GMS aqhk requests an authenticated continuation for the selected account.
            val authenticated = withContext(Dispatchers.IO) {
                verify()
                val target = policy.target(accountName, lookup)
                AuthManager(activity, accountName, GMS_PACKAGE_NAME,
                    "weblogin:service=gaia&continue=" + URLEncoder.encode(target, "UTF-8"))
                    .requestAuth(false).auth
            }
            verify()
            if (result.isCompleted || !TrustedVaultRecoveryPolicy.navigationAllowed(authenticated.orEmpty())) throw VaultLockedException()
            web.loadUrl(authenticated!!)
            result.await().also { verify() }
        } finally {
            result.cancel()
            activity.lifecycle.removeObserver(observer)
            script.remove()
            WebViewCompat.removeWebMessageListener(web, "_microgPasskeyReauth")
            web.stopLoading(); web.loadUrl("about:blank"); web.clearHistory()
            root.removeView(web); web.destroy()
            activity.setContentView(android.widget.FrameLayout(activity))
        }
    }
}
