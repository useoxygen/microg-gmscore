/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.gms.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.microg.gms.auth.AuthManager
import org.microg.gms.common.Constants.GMS_PACKAGE_NAME
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Google's owner setup UI, isolated from every other Services WebView and from credential callers. */
class PasswordEncryptionActivity : PasswordOwnerActivity() {
    private lateinit var account: Account
    private lateinit var accountId: String
    @Volatile private var generation = 0L
    private var work: Job? = null
    private var adapter: PasswordEncryptionRepository? = null
    private var web: WebView? = null
    private var script: ScriptHandler? = null
    private var busy = false
    private var initialized = false
    private var ownerSessionStarted = false
    private var reconcileRequested = false
    private var currentId: String? = null
    private var currentOperation: PasswordEncryptionOperation? = null
    private var currentPhase: PasswordEncryptionPhase? = null
    private val lease = UUID.randomUUID().toString()

    override fun onCreate(savedInstanceState: Bundle?) {
        val supported = Build.VERSION.SDK_INT >= 28 && initializeProfile()
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 29) window.decorView.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        val accounts = AccountManager.get(this)
        val selected = accounts.getAccountsByType("com.google").singleOrNull { it.name == intent.getStringExtra(EXTRA_ACCOUNT) }
        val expected = intent.getStringExtra(EXTRA_ACCOUNT_ID)
        if (!supported || selected == null || expected.isNullOrBlank() || accounts.getUserData(selected, "GoogleUserId") != expected || !acquireLease(lease)) {
            showUnavailable(); return
        }
        account = selected; accountId = expected
        showText(R.string.passwords_unlock_description)
        authenticateOwner()
    }

    private fun verify(expectedGeneration: Long = generation) {
        requireOwner()
        if (isFinishing || isDestroyed || expectedGeneration != generation || !ownsLease(lease)) throw VaultLockedException()
        val accounts = AccountManager.get(this)
        if (!accounts.getAccountsByType("com.google").contains(account) || accounts.getUserData(account, "GoogleUserId") != accountId) throw VaultLockedException()
    }

    override fun onOwnerAuthenticated() {
        if (!::account.isInitialized || initialized) return
        initialized = true
        ownerSessionStarted = true
        refreshStatus()
    }
    override fun onOwnerAuthenticationFailed() { finish() }
    override fun onOwnerLocked() { if (ownerSessionStarted) { invalidate(); finish() } }
    override fun onResume() {
        super.onResume()
        if (::account.isInitialized && !initialized && ownsLease(lease) && !isFinishing) authenticateOwner()
    }
    override fun onPause() {
        // No asynchronous authentication result can reopen a view after it loses foreground authority.
        if (web != null || busy) invalidate()
        super.onPause()
    }
    override fun onDestroy() { invalidate(); retireProfile(lease); super.onDestroy() }

    private fun invalidate() {
        generation++
        work?.cancel(); work = null
        adapter?.cancel(); adapter = null
        busy = false
        initialized = false
        reconcileRequested = false
        tearDownWeb()
    }

    private fun runWork(action: suspend (PasswordEncryptionRepository, Long) -> Unit) {
        if (busy) return
        val token = generation
        try { verify(token) } catch (_: Exception) { finish(); return }
        busy = true
        val constructing = AtomicReference<PasswordEncryptionRepository?>()
        work = lifecycleScope.launch {
            try {
                val repository = withContext(Dispatchers.IO) {
                    PasswordEncryptionRepository(this@PasswordEncryptionActivity, account, accountId) { verify(token) }.also { created ->
                        constructing.set(created)
                        try { verify(token) } catch (failure: Throwable) { created.cancel(); throw failure }
                    }
                }
                verify(token); adapter = repository
                action(repository, token)
            }
            catch (_: Exception) {
                if (token == generation && !isFinishing && !isDestroyed) {
                    try { verify(token); renderError() } catch (_: Exception) { finish() }
                }
            } finally {
                constructing.getAndSet(null)?.cancel()
                if (token == generation) {
                busy = false; work = null; adapter = null
                if (reconcileRequested) { reconcileRequested = false; requestReconciliation(token) }
                }
            }
        }
    }

    private fun refreshStatus() = runWork { repository, token ->
        val bytes = withContext(Dispatchers.IO) { repository.loadFlow() }
        try { verify(token); renderState(bytes) } finally { bytes?.fill(0) }
    }

    private fun renderState(raw: ByteArray?) {
        verify()
        val state = PasswordEncryptionState.decode(raw)
        try {
            currentId = state?.id; currentOperation = state?.operation; currentPhase = state?.phase
            val layout = layout()
            layout.addView(TextView(this).apply { text = account.name })
            when (state?.phase) {
                PasswordEncryptionPhase.OPENED, PasswordEncryptionPhase.UNKNOWN -> {
                    label(layout, title(state.operation)); label(layout, R.string.passwords_encryption_pending)
                    button(layout, R.string.passwords_encryption_check) { reconcile() }
                    button(layout, R.string.passwords_encryption_resolve) { confirmAbandon() }
                }
                PasswordEncryptionPhase.READY -> {
                    label(layout, title(state.operation)); label(layout, warning(state.operation))
                    button(layout, R.string.passwords_encryption_continue) { confirm(state.operation, existingId = state.id) }
                }
                else -> {
                    if (state?.phase == PasswordEncryptionPhase.CONFIRMED) label(layout, R.string.passwords_encryption_checked)
                    if (state?.acknowledgedUnknown == true) label(layout, R.string.passwords_encryption_unknown_closed)
                    label(layout, R.string.passwords_encryption_description)
                    for (operation in PasswordEncryptionOperation.values()) button(layout, title(operation)) { confirm(operation) }
                }
            }
            button(layout, R.string.passwords_encryption_close) { closeOwnerScreen() }
            setContentView(layout)
        } finally { state?.close() }
    }

    private fun confirm(operation: PasswordEncryptionOperation, existingId: String? = null) {
        try { verify() } catch (_: Exception) { finish(); return }
        AlertDialog.Builder(this).setTitle(title(operation)).setMessage(warning(operation))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.passwords_encryption_continue) { _, _ -> prepareAndOpen(operation, existingId) }.show()
    }

    private fun prepareAndOpen(operation: PasswordEncryptionOperation, existingId: String?) = runWork { repository, token ->
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) throw VaultLockedException()
        val readyBytes = withContext(Dispatchers.IO) {
            val previous = repository.loadFlow()
            try {
                val previousState = PasswordEncryptionState.decode(previous)
                try {
                    if (existingId != null) {
                        if (previousState?.id != existingId || previousState.phase != PasswordEncryptionPhase.READY || previousState.operation != operation) throw ConflictException()
                        previousState.encode()
                    } else {
                        if (previousState?.phase in setOf(PasswordEncryptionPhase.READY, PasswordEncryptionPhase.OPENED, PasswordEncryptionPhase.UNKNOWN)) throw PendingMutationException()
                        repository.capture(operation).use { before -> PasswordEncryptionState.prepare(UUID.randomUUID().toString(), operation, before,
                            System.currentTimeMillis()).use { ready -> ready.encode().also { repository.compareFlow(previous, it) } } }
                    }
                } finally { previousState?.close() }
            } finally { previous?.fill(0) }
        }
        try {
            val ready = PasswordEncryptionState.decode(readyBytes) ?: throw ConflictException()
            try {
                verify(token); currentId = ready.id; currentOperation = operation
                val authUrl = withContext(Dispatchers.IO) {
                    verify(token)
                    val url = PasswordEncryptionWebPolicy.startUrl(operation)
                    val auth = AuthManager(this@PasswordEncryptionActivity, account.name, GMS_PACKAGE_NAME,
                        "weblogin:service=gaia&continue=" + URLEncoder.encode(url, "UTF-8")).requestAuth(false).auth
                    verify(token)
                    auth?.takeIf { PasswordEncryptionWebPolicy.allowedNavigation(it, operation) } ?: throw VaultLockedException()
                }
                val openedBytes = ready.open().use(PasswordEncryptionState::encode)
                try { withContext(Dispatchers.IO) { repository.compareFlow(readyBytes, openedBytes) } } finally { openedBytes.fill(0) }
                verify(token); currentPhase = PasswordEncryptionPhase.OPENED; showGoogle(authUrl, operation, token)
            } finally { ready.close() }
        } finally { readyBytes.fill(0) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showGoogle(authUrl: String, operation: PasswordEncryptionOperation, token: Long) {
        verify(token)
        val view = WebView(this)
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 29) view.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false; databaseEnabled = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE; saveFormData = false
            javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
            override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
            override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<android.net.Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                filePathCallback?.onReceiveValue(null); return true
            }
        }
        view.setDownloadListener { _, _, _, _, _ -> }
        view.webViewClient = object : WebViewClientCompat() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !PasswordEncryptionWebPolicy.allowedNavigation(request.url.toString(), operation)
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !PasswordEncryptionWebPolicy.allowedNavigation(url, operation)
            override fun onPageFinished(view: WebView, url: String) {
                if (token == generation && PasswordEncryptionWebPolicy.terminal(url, operation)) requestReconciliation(token)
            }
        }
        web = view
        WebViewCompat.addWebMessageListener(view, BRIDGE, setOf(PasswordEncryptionWebPolicy.PASSWORDS_ORIGIN, PasswordEncryptionWebPolicy.ACCOUNTS_ORIGIN)) {
                _, message, origin, mainFrame, reply ->
            if (token != generation) return@addWebMessageListener
            try {
                verify(token)
                val raw = message.data.orEmpty()
                if (raw.length > 512 * 1024) throw ProtocolException()
                val json = JSONObject(raw)
                when (json.getString("method")) {
                    "closeView" -> if (PasswordEncryptionWebPolicy.acceptsTopMessage(origin.toString(), mainFrame, view.url.orEmpty(), operation)) requestReconciliation(token)
                    "setVaultSharedKeys" -> {
                        if (!PasswordEncryptionWebPolicy.acceptsRelayMessage(origin.toString(), mainFrame, view.url.orEmpty(), json.getString("frameUrl"), operation) ||
                            json.getString("accountId") != accountId) throw ProtocolException()
                        stageKeys(json.getString("keys"), reply, token)
                    }
                    else -> throw ProtocolException()
                }
            } catch (_: Exception) { /* No acknowledgement: the current Google relay ignores status and treats any reply as completion. */ }
        }
        script = WebViewCompat.addDocumentStartJavaScript(view, SHIM.replace("__EXPECTED_GAIA__", JSONObject.quote(accountId)),
            setOf(PasswordEncryptionWebPolicy.PASSWORDS_ORIGIN, PasswordEncryptionWebPolicy.ACCOUNTS_ORIGIN))
        val layout = layout()
        label(layout, R.string.passwords_encryption_keep_open)
        layout.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        button(layout, R.string.passwords_encryption_close) { closeOwnerScreen() }
        setContentView(layout)
        CookieManager.getInstance().apply {
            setAcceptCookie(true); setAcceptThirdPartyCookies(view, true)
            // This process uses its own data-directory suffix. No Services :ui cookies are cleared.
            removeAllCookies {
                try { verify(token); if (web === view) view.loadUrl(authUrl) } catch (_: Exception) { tearDownWeb() }
            }
        }
    }

    private fun stageKeys(raw: String, reply: JavaScriptReplyProxy, token: Long) {
        if (busy || token != generation) return
        runWork { repository, current ->
            val keys = PasswordEncryptionBridgeKeys.parse(raw)
            try {
                withContext(Dispatchers.IO) {
                    val old = repository.loadFlow() ?: throw ConflictException()
                    try { PasswordEncryptionState.decode(old)!!.use { state ->
                        if (state.id != currentId || state.operation != PasswordEncryptionOperation.ENROLL) throw ConflictException()
                        state.stage(keys).use { next -> val encoded = next.encode(); try { repository.compareFlow(old, encoded) } finally { encoded.fill(0) } }
                    } } finally { old.fill(0) }
                }
                verify(current)
                // The reply is routed to the actual source frame, rather than evaluated in the top document.
                if (web != null) reply.postMessage("{\"status\":0}")
            } finally { keys.forEach(VersionedTrustedVaultKey::destroy) }
        }
    }

    private fun requestReconciliation(token: Long) {
        if (token != generation) return
        if (busy) { reconcileRequested = true; return }
        tearDownWeb()
        reconcile()
    }
    private fun reconcile() = runWork { repository, token ->
        tearDownWeb()
        val bytes = withContext(Dispatchers.IO) { repository.loadFlow() } ?: throw ConflictException()
        try { PasswordEncryptionState.decode(bytes)!!.use { state ->
            if (state.id != currentId || state.phase !in setOf(PasswordEncryptionPhase.OPENED, PasswordEncryptionPhase.UNKNOWN)) throw ConflictException()
            val before = state.before ?: throw ConflictException()
            val complete = withContext(Dispatchers.IO) {
                if (state.operation == PasswordEncryptionOperation.ENROLL) {
                    val keys = state.deliveredKeys
                    try { keys.isNotEmpty() && repository.completeEnrollment(before, keys, state.id) } finally { keys.forEach(VersionedTrustedVaultKey::destroy) }
                } else repository.reconcileDeletion(before, state.operation, state.id)
            }
            verify(token)
            val latest = withContext(Dispatchers.IO) { repository.loadFlow() }
            try { renderState(latest); if (!complete) renderError() } finally { latest?.fill(0) }
        } } finally { bytes.fill(0) }
    }

    private fun confirmAbandon() {
        try { verify() } catch (_: Exception) { finish(); return }
        val expectedId = currentId ?: return
        AlertDialog.Builder(this).setTitle(R.string.passwords_encryption_resolve).setMessage(R.string.passwords_encryption_resolve_warning)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.passwords_encryption_resolve) { _, _ ->
                runWork { repository, token ->
                    val bytes = withContext(Dispatchers.IO) { repository.loadFlow() } ?: throw ConflictException()
                    try { PasswordEncryptionState.decode(bytes)!!.use { state ->
                        if (state.id != expectedId || state.id != currentId || state.phase !in setOf(PasswordEncryptionPhase.OPENED, PasswordEncryptionPhase.UNKNOWN)) throw ConflictException()
                        val complete = withContext(Dispatchers.IO) { repository.abandonVerified(state.before ?: throw ConflictException(), state.id) }
                        verify(token)
                        val latest = withContext(Dispatchers.IO) { repository.loadFlow() }
                        try { renderState(latest); if (!complete) renderError() } finally { latest?.fill(0) }
                    } } finally { bytes.fill(0) }
                }
            }.show()
    }

    private fun closeOwnerScreen() {
        val expectedId = currentId
        if (currentPhase != PasswordEncryptionPhase.READY || expectedId == null) { finish(); return }
        runWork { repository, token ->
            withContext(Dispatchers.IO) {
                val bytes = repository.loadFlow() ?: throw ConflictException()
                try { PasswordEncryptionState.decode(bytes)!!.use { state ->
                    if (state.id != expectedId || state.phase != PasswordEncryptionPhase.READY) throw ConflictException()
                    state.closeView().use { closed -> val replacement = closed.encode()
                        try { repository.compareFlow(bytes, replacement) } finally { replacement.fill(0) }
                    }
                } } finally { bytes.fill(0) }
            }
            verify(token); finish()
        }
    }

    private fun tearDownWeb() {
        val view = web; web = null
        script?.remove(); script = null
        if (view != null) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(view, BRIDGE)
            view.stopLoading(); view.clearHistory(); view.removeAllViews(); view.destroy()
        }
    }
    private fun renderError() {
        val layout = layout(); label(layout, R.string.passwords_encryption_failed)
        button(layout, R.string.passwords_encryption_check) { refreshStatus() }
        button(layout, R.string.passwords_encryption_close) { closeOwnerScreen() }; setContentView(layout)
    }
    private fun showUnavailable() { showText(R.string.passwords_encryption_not_supported) }
    private fun showText(message: Int) { val layout = layout(); label(layout, message); button(layout, R.string.passwords_encryption_close) { finish() }; setContentView(layout) }
    private fun layout() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
    private fun label(layout: LinearLayout, text: Int) { layout.addView(TextView(this).apply { setText(text); setPadding(0, 8, 0, 8) }) }
    private fun button(layout: LinearLayout, text: Int, click: () -> Unit) { layout.addView(Button(this).apply { setText(text); setOnClickListener { if (!busy) click() } }) }
    private fun title(operation: PasswordEncryptionOperation) = when (operation) {
        PasswordEncryptionOperation.ENROLL -> R.string.passwords_encryption_enable
        PasswordEncryptionOperation.CLEAR_PASSWORDS -> R.string.passwords_encryption_clear
        PasswordEncryptionOperation.RESET_PASSKEYS -> R.string.passwords_encryption_passkeys
    }
    private fun warning(operation: PasswordEncryptionOperation) = when (operation) {
        PasswordEncryptionOperation.ENROLL -> R.string.passwords_encryption_enable_warning
        PasswordEncryptionOperation.CLEAR_PASSWORDS -> R.string.passwords_encryption_clear_warning
        PasswordEncryptionOperation.RESET_PASSKEYS -> R.string.passwords_encryption_passkeys_warning
    }

    companion object {
        const val EXTRA_ACCOUNT = "account"
        const val EXTRA_ACCOUNT_ID = "google_user_id"
        private const val BRIDGE = "_microgPasswordEncryption"
        private var profileInitialized = false
        private val sessions = PasswordEncryptionSessionLease()
        @Synchronized private fun initializeProfile(): Boolean = try {
            if (!profileInitialized) {
                WebView.setDataDirectorySuffix("password-encryption-owner-v1")
                WebView.setWebContentsDebuggingEnabled(false)
                profileInitialized = true
            }
            true
        } catch (_: Exception) { false }
        private fun acquireLease(value: String): Boolean = sessions.acquire(value)
        private fun ownsLease(value: String): Boolean = sessions.owns(value)
        private fun retireProfile(value: String) {
            if (!sessions.retire(value)) return // A refused second activity cannot touch another account's cookies.
            try {
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies { sessions.cleared(value) }
                Handler(Looper.getMainLooper()).postDelayed({ sessions.cleanupTimedOut(value) }, 10_000)
            } catch (_: Exception) { sessions.cleanupTimedOut(value) }
        }
        internal val SHIM = """
            (function() {
              const topPage = window === window.top && location.origin === 'https://passwords.google.com';
              const relay = window !== window.top && location.origin === 'https://accounts.google.com' &&
                /^(?:\/u\/\d+)?\/encryption\/unlock\/datarelay${'$'}/.test(location.pathname) &&
                new URLSearchParams(location.search).getAll('origin').length === 1 &&
                new URLSearchParams(location.search).get('origin') === 'https://passwords.google.com';
              if (!topPage && !relay) return;
              const channel = window._microgPasswordEncryption;
              const methods = {};
              if (relay) {
                methods.setVaultSharedKeys = function(accountId, keys) {
                  channel.postMessage(JSON.stringify({method:'setVaultSharedKeys',accountId:String(accountId),keys:String(keys),frameUrl:location.href}));
                };
                channel.onmessage = function(event) {
                  const result = JSON.parse(event.data);
                  if (result.status === 0 && typeof window.onKeyDataSet === 'function') window.onKeyDataSet(result);
                };
              }
              if (topPage) {
                const expectedGaia = __EXPECTED_GAIA__;
                function requireSelectedAccount() {
                  const encoded = window.WIZ_global_data && window.WIZ_global_data.w2btAe;
                  let actual;
                  try { actual = JSON.parse('[' + String(encoded).substring(4))[0]; } catch (_) {}
                  if (typeof actual !== 'string' || actual !== expectedGaia) throw new Error('Selected Google account changed');
                }
                // The observed frontend sends its RPCs through XHR. Check the source's
                // selected Gaia before any send, including after a Google account switch.
                const send = XMLHttpRequest.prototype.send;
                Object.defineProperty(XMLHttpRequest.prototype, 'send', {value:function() {
                  requireSelectedAccount(); return send.apply(this, arguments);
                },writable:false,configurable:false});
                if (typeof window.fetch === 'function') {
                  const fetch = window.fetch;
                  Object.defineProperty(window, 'fetch', {value:function() {
                    requireSelectedAccount(); return fetch.apply(this, arguments);
                  },writable:false,configurable:false});
                }
                const submit = HTMLFormElement.prototype.submit;
                Object.defineProperty(HTMLFormElement.prototype, 'submit', {value:function() {
                  requireSelectedAccount(); return submit.apply(this, arguments);
                },writable:false,configurable:false});
                let sent = false;
                methods.closeView = function() { if (!sent) { sent=true; channel.postMessage(JSON.stringify({method:'closeView'})); } };
                setInterval(function() { if (/\/done${'$'}/.test(location.pathname)) methods.closeView(); }, 400);
              }
              Object.defineProperty(window, 'mm', {value:Object.freeze(methods),writable:false,configurable:false});
            })();
        """.trimIndent()
    }
}
