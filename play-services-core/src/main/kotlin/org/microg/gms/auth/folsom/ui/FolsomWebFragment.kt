/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.folsom.ui

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.ScriptHandler
import com.google.android.gms.auth.folsom.SharedKey
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.microg.gms.auth.folsom.Keys
import org.microg.gms.auth.folsom.ui.GenericActivity.Companion.EXTRA_ACCOUNT_NAME
import org.microg.gms.auth.folsom.ui.GenericActivity.Companion.EXTRA_OFFER_RESET
import org.microg.gms.auth.folsom.ui.GenericActivity.Companion.EXTRA_OPERATION
import org.microg.gms.auth.folsom.ui.GenericActivity.Companion.EXTRA_SECURITY_DOMAIN
import org.microg.gms.auth.folsom.ui.GenericActivity.Companion.EXTRA_SESSION_ID
import org.microg.gms.auth.folsom.ui.GenericActivity.Companion.EXTRA_ACCOUNT_ID
import org.microg.gms.passwords.TrustedVaultRecoveryPolicy

data class KeyRetrievalMetadata(
    val keys: Map<String, List<SharedKey>>,
    val consent: Map<String, Boolean>
)

class FolsomWebFragment : Fragment() {

    companion object {
        fun newInstance(
            accountName: String,
            securityDomain: String,
            operation: Int = 0,
            sessionId: String = "",
            offerReset: Boolean = false,
            accountId: String = ""
        ): FolsomWebFragment = FolsomWebFragment().apply {
            arguments = Bundle().apply {
                putString(EXTRA_ACCOUNT_NAME, accountName)
                putString(EXTRA_SECURITY_DOMAIN, securityDomain)
                putInt(EXTRA_OPERATION, operation)
                putString(EXTRA_SESSION_ID, sessionId)
                putBoolean(EXTRA_OFFER_RESET, offerReset)
                putString(EXTRA_ACCOUNT_ID, accountId)
            }
        }
    }

    private var webView: WebView? = null
    private var progressBar: ProgressBar? = null
    private var jsBridge: FolsomJsBridge? = null
    private var webViewHelper: FolsomWebViewHelper? = null
    private var bridgeScript: ScriptHandler? = null

    private val accountName: String by lazy {
        requireArguments().getString(EXTRA_ACCOUNT_NAME, "")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return setupContainerView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBackPressedHandler()
        setupWebView()
    }

    private fun setupContainerView(): FrameLayout {
        val container = FrameLayout(requireContext())
        container.setBackgroundColor(if (isDarkMode()) Color.BLACK else Color.WHITE)

        progressBar = ProgressBar(requireContext()).also { pb ->
            val size = (48 * resources.displayMetrics.density).toInt()
            val params = FrameLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER }
            container.addView(pb, params)
        }

        return container
    }

    private fun setupBackPressedHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView?.canGoBack() == true) {
                    webView?.goBack()
                } else {
                    requireActivity().setResult(AppCompatActivity.RESULT_CANCELED)
                    requireActivity().finish()
                }
            }
        })
    }

    private fun setupWebView() {
        val container = view as? FrameLayout ?: return
        val wv = WebView(requireContext()).apply {
            isVisible = false
        }
        container.addView(
            wv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        webViewHelper = FolsomWebViewHelper(this, wv, accountName).also { helper ->
            helper.prepareWebViewSettings()
            helper.setupWebViewClient(
                onPageStarted = { onPageStarted() },
                onPageFinished = { onPageFinished() }
            )
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) { finishWithResult(null); return }
        val policy = TrustedVaultRecoveryPolicy(requireArguments().getString(EXTRA_ACCOUNT_ID).orEmpty(),
            requireArguments().getString(EXTRA_SECURITY_DOMAIN).orEmpty())
        jsBridge = FolsomJsBridge(wv, policy, { (requireActivity() as GenericActivity).requireRecoveryOwner() }, ::onKeyRetrievalResult)
        WebViewCompat.addWebMessageListener(wv, "_microgFolsom", setOf(TrustedVaultRecoveryPolicy.ORIGIN)) { _, message, sourceOrigin, isMainFrame, _ ->
            runCatching { jsBridge?.receive(sourceOrigin.toString(), isMainFrame, message.data.orEmpty()) }
        }
        bridgeScript = WebViewCompat.addDocumentStartJavaScript(wv, FolsomJsBridge.SHIM, setOf(TrustedVaultRecoveryPolicy.ORIGIN))
        webView = wv
        loadKeyRetrievalUrl()
    }

    private fun loadKeyRetrievalUrl() {
        if (Build.VERSION.SDK_INT < 21) {
            finishWithResult(null)
            return
        }

        val args = requireArguments()
        val targetUrl = webViewHelper?.buildKeyRetrievalUrl(
            sessionId = args.getString(EXTRA_SESSION_ID, ""),
            securityDomain = args.getString(EXTRA_SECURITY_DOMAIN, ""),
            offerReset = args.getBoolean(EXTRA_OFFER_RESET, false),
            darkMode = isDarkMode()
        ) ?: return

        webViewHelper?.loadUrlWithAuthentication(targetUrl)
    }

    private fun isDarkMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun onPageStarted() {
        progressBar?.isVisible = true
        webView?.isVisible = false
    }

    private fun onPageFinished() {
        progressBar?.isVisible = false
        webView?.isVisible = true
    }

    private fun onKeyRetrievalResult(status: Int, metadata: KeyRetrievalMetadata?) {
        try {
            val owner = requireActivity() as GenericActivity
            owner.requireRecoveryOwner()
            val domain = requireArguments().getString(EXTRA_SECURITY_DOMAIN).orEmpty()
            val shared = metadata?.keys?.takeIf { it.keys == setOf(domain) }?.get(domain)
            if (status != AppCompatActivity.RESULT_OK || shared.isNullOrEmpty()) { finishWithResult(null); return }
            owner.saveRecoveredKeys(shared.map { Keys(keyVersion = it.key, keyMaterial = it.keyMaterial?.toByteString()) })
            finishWithResult(AppCompatActivity.RESULT_OK)
        } catch (_: Exception) { finishWithResult(null) }
    }

    private fun finishWithResult(status: Int?) {
        requireActivity().setResult(status ?: AppCompatActivity.RESULT_CANCELED)
        requireActivity().finish()
    }

    internal fun cancelRecovery() {
        jsBridge?.clear()
        bridgeScript?.remove(); bridgeScript = null
        webView?.let { if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(it, "_microgFolsom") }
        webView?.stopLoading()
        webViewHelper?.destroy()
        webView?.destroy()
        webView = null
        webViewHelper = null
        jsBridge = null
        progressBar = null
    }
    override fun onDestroyView() { cancelRecovery(); super.onDestroyView() }
}

private class FolsomJsBridge(private val webView: WebView, private val policy: TrustedVaultRecoveryPolicy,
    private val requireOwner: () -> Unit, private val onResult: (Int, KeyRetrievalMetadata?) -> Unit) {
    private var closed = false
    private val collectedKeys = mutableMapOf<String, List<SharedKey>>()
    private val collectedConsent = mutableMapOf<String, Boolean>()

    fun receive(origin: String, mainFrame: Boolean, raw: String) {
        if (closed || raw.length > 512 * 1024 || !policy.acceptsMessage(origin, mainFrame, policy.accountId, policy.domain)) return
        try {
            requireOwner()
            val message = JSONObject(raw)
            when (message.getString("method")) {
                "setVaultSharedKeys" -> {
                    if (message.getString("accountId") != policy.accountId) throw IllegalArgumentException()
                    val json = JSONObject(message.getString("keys"))
                    if (json.length() !in 1..32) throw IllegalArgumentException()
                    if (!policy.selectsKeys(json.keys().asSequence().toSet())) {
                        // A multi-domain flow may deliver another domain first. It grants no
                        // authority to persist that domain in this owner-selected recovery.
                        notifyWebView(0); return
                    }
                    val list = json.getJSONArray(policy.domain)
                    if (list.length() !in 1..100) throw IllegalArgumentException()
                    val keys = ArrayList<SharedKey>()
                    try {
                        for (i in 0 until list.length()) {
                            val key = list.getJSONObject(i); val epoch = key.getLong("epoch")
                            if (epoch !in 1..Int.MAX_VALUE.toLong()) throw IllegalArgumentException()
                            val material = parseKeyMaterial(key.getJSONObject("key")) ?: throw IllegalArgumentException()
                            if (material.size !in 16..4096) { material.fill(0); throw IllegalArgumentException() }
                            keys.add(SharedKey(epoch.toInt(), material))
                        }
                        collectedKeys.values.flatten().forEach { it.keyMaterial?.fill(0) }
                        collectedKeys.clear(); collectedKeys[policy.domain] = keys
                    } catch (failure: Exception) { keys.forEach { it.keyMaterial?.fill(0) }; throw failure }
                    notifyWebView(0)
                }
                "setConsent" -> {
                    if (!policy.acceptsMessage(origin, mainFrame, message.getString("accountId"), message.getString("domain"))) throw IllegalArgumentException()
                    collectedConsent[policy.domain] = message.getBoolean("consent"); notifyWebView(0)
                }
                "closeView" -> {
                    closed = true
                    try { onResult(if (collectedKeys.isEmpty()) AppCompatActivity.RESULT_CANCELED else AppCompatActivity.RESULT_OK,
                        KeyRetrievalMetadata(collectedKeys.toMap(), collectedConsent.toMap())) }
                    finally { clear() }
                }
                else -> throw IllegalArgumentException()
            }
        } catch (_: Exception) { notifyWebView(1) }
    }

    private fun parseKeyMaterial(keyData: JSONObject): ByteArray? = runCatching {
        keyData.optString("keyMaterial").takeIf { !it.isNullOrEmpty() }?.let {
            Base64.decode(it, Base64.DEFAULT)
        } ?: if (keyData.length() > 0) {
            if (keyData.length() !in 16..4096) return@runCatching null
            ByteArray(keyData.length()) { i -> keyData.getInt(i.toString()).also { if (it !in 0..255) throw IllegalArgumentException() }.toByte() }
        } else null
    }.getOrNull()

    private fun notifyWebView(status: Int) {
        if (!closed && TrustedVaultRecoveryPolicy.navigationAllowed(webView.url.orEmpty()))
            webView.evaluateJavascript("window.onKeyDataSet(${JSONObject().put("status", status)})", null)
    }
    fun clear() { closed = true; collectedKeys.values.flatten().forEach { it.keyMaterial?.fill(0) }; collectedKeys.clear(); collectedConsent.clear() }
    companion object {
        val SHIM = """
            if (window === window.top) {
              Object.defineProperty(window, 'mm', {value: Object.freeze({
                setVaultSharedKeys: function(accountId, keys) { _microgFolsom.postMessage(JSON.stringify({method:'setVaultSharedKeys',accountId:String(accountId),keys:String(keys)})); },
                setConsent: function(accountId, domain, consent) { _microgFolsom.postMessage(JSON.stringify({method:'setConsent',accountId:String(accountId),domain:String(domain),consent:!!consent})); },
                closeView: function() { _microgFolsom.postMessage(JSON.stringify({method:'closeView'})); }
              }), writable:false, configurable:false});
            }
        """.trimIndent()
    }
}
