/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.Intent
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
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import org.microg.gms.auth.folsom.ui.GenericActivity
import org.microg.gms.common.Constants.GMS_PACKAGE_NAME
import org.microg.gms.cyclon.*
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
    private var question by mutableStateOf<Question?>(null)
    // Google's unlock covers this page, which then locks; the page stays and asks for the owner again on return.
    private var recovering = false
    private var recovered = false
    private val verifyLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        recovering = false
        recovered = result.resultCode == RESULT_OK
    }

    /** A confirmation the owner answers before Google's page opens or a change is closed. */
    private class Question(val title: Int, val message: Int, val confirm: Int, val accept: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        val supported = Build.VERSION.SDK_INT >= 28 && initializeProfile()
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        if (Build.VERSION.SDK_INT < 28 || !supported) { showUnavailable(); return }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        // The owner's FLAG_SECURE disables content capture on API29 too; this View setter starts at API30.
        if (Build.VERSION.SDK_INT >= 30) window.decorView.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        val accounts = AccountManager.get(this)
        val selected = accounts.getAccountsByType("com.google").singleOrNull { it.name == intent.getStringExtra(EXTRA_ACCOUNT) }
        val expected = intent.getStringExtra(EXTRA_ACCOUNT_ID)
        if (selected == null || expected.isNullOrBlank() || accounts.getUserData(selected, "GoogleUserId") != expected || !acquireLease(lease)) {
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
        refreshStatus(syncFirst = recovered)
        recovered = false
    }
    override fun onOwnerAuthenticationFailed() { finish() }
    override fun onOwnerLocked() {
        if (!ownerSessionStarted) return
        invalidate()
        // Back from the unlock, the owner is asked again as on first open: that prompt must not close the page.
        if (recovering) { ownerSessionStarted = false; showText(R.string.passwords_unlock_description) } else finish()
    }
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
        question = null
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

    /**
     * Like Google Password Manager: the page shows the account's actual setting. An attempt left open (its Google page
     * closed, or the owner left) is settled first, so it never blocks syncing or needs a manual check.
     */
    private fun refreshStatus(syncFirst: Boolean = false) = runWork { repository, token ->
        val status = withContext(Dispatchers.IO) {
            if (syncFirst) repository.syncSoon()
            settle(repository); repository.status()
        }
        verify(token); renderStatus(status)
    }

    /** Confirms a finished change from Google's own data, otherwise closes the local record and lets sync read Google. */
    private fun settle(repository: PasswordEncryptionRepository) {
        val bytes = repository.loadFlow() ?: return
        try { PasswordEncryptionState.decode(bytes)?.use { state ->
            when (state.phase) {
                PasswordEncryptionPhase.READY -> repository.release(state.id)
                PasswordEncryptionPhase.OPENED, PasswordEncryptionPhase.UNKNOWN -> {
                    val before = state.before ?: throw ConflictException()
                    // Only a definite "not as requested" closes the record; a network failure keeps it for Try again.
                    val finished = try {
                        if (state.operation == PasswordEncryptionOperation.ENROLL) {
                            val keys = state.deliveredKeys
                            // Staged keys Google no longer proves (a newer epoch, no member proof) can never finish it.
                            try { keys.isNotEmpty() && repository.completeEnrollment(before, keys, state.id) }
                            catch (_: ProtocolException) { false } catch (_: VaultLockedException) { false }
                            finally { keys.forEach(VersionedTrustedVaultKey::destroy) }
                        } else repository.reconcileDeletion(before, state.operation, state.id)
                    } catch (_: ConflictException) { false } catch (_: VaultResetException) { false }
                    if (!finished) repository.release(state.id)
                }
                else -> {}
            }
        } } finally { bytes.fill(0) }
    }

    private fun renderStatus(status: PasswordEncryptionRepository.Status) {
        verify()
        page {
            when (status) {
                PasswordEncryptionRepository.Status.OFF -> {
                    PasswordOwnerText(stringResource(R.string.passwords_encryption_intro))
                    PasswordOwnerText(stringResource(R.string.passwords_encryption_intro_unlock))
                    PasswordOwnerActions {
                        CyclonInkButton(stringResource(R.string.passwords_encryption_set_up)) {
                            unlessBusy { confirm(PasswordEncryptionOperation.ENROLL) }
                        }
                    }
                }
                PasswordEncryptionRepository.Status.ON -> {
                    CyclonRow(stringResource(R.string.passwords_encryption_on), stringResource(R.string.passwords_encryption_on_summary),
                        divider = false, trailing = { CyclonVoice("✓", color = Cyclon.palette.ink, size = 18) })
                }
                PasswordEncryptionRepository.Status.ON_LOCKED -> {
                    CyclonRow(stringResource(R.string.passwords_encryption_on), stringResource(R.string.passwords_encryption_verify_summary),
                        divider = false)
                    PasswordOwnerActions {
                        CyclonInkButton(stringResource(R.string.passwords_encryption_verify)) { unlessBusy { verifyIdentity() } }
                    }
                }
                PasswordEncryptionRepository.Status.OTHER ->
                    PasswordOwnerText(stringResource(R.string.passwords_encryption_passphrase))
            }
            CyclonSectionLabel(stringResource(R.string.passwords_encryption_reset_section), Modifier.padding(top = 16.dp))
            // Google's reset turns off on-device encryption; it doesn't apply to keystore or passphrase accounts.
            if (status == PasswordEncryptionRepository.Status.ON || status == PasswordEncryptionRepository.Status.ON_LOCKED) CyclonRow(stringResource(R.string.passwords_encryption_clear),
                onClick = { unlessBusy { confirm(PasswordEncryptionOperation.CLEAR_PASSWORDS) } }, trailing = { CyclonChevron() })
            CyclonRow(stringResource(R.string.passwords_encryption_passkeys),
                onClick = { unlessBusy { confirm(PasswordEncryptionOperation.RESET_PASSKEYS) } }, trailing = { CyclonChevron() })
        }
    }

    /** Google's account unlock for this phone, the same recovery the Passwords screen offers for a locked account. */
    private fun verifyIdentity() = runWork { repository, token ->
        withContext(Dispatchers.IO) { repository.prepareRecovery() }
        verify(token); recovering = true
        verifyLauncher.launch(Intent(this, GenericActivity::class.java)
            .putExtra(GenericActivity.EXTRA_ACCOUNT_NAME, account.name)
            .putExtra(GenericActivity.EXTRA_ACCOUNT_ID, accountId)
            .putExtra(GenericActivity.EXTRA_SECURITY_DOMAIN, "chromesync")
            .putExtra(GenericActivity.EXTRA_OPERATION, 1)
            .putExtra(GenericActivity.EXTRA_OFFER_RESET, false)
            .putExtra(GenericActivity.EXTRA_PASSWORD_OWNER_RECOVERY, true))
    }

    /** Every change asks first: setting up can't be undone without deleting all passwords, and the resets delete data. */
    private fun confirm(operation: PasswordEncryptionOperation, existingId: String? = null) {
        try { verify() } catch (_: Exception) { finish(); return }
        question = Question(title(operation), warning(operation), R.string.passwords_encryption_continue) { prepareAndOpen(operation, existingId) }
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
        if (Build.VERSION.SDK_INT < 28) throw VaultLockedException()
        val view = WebView(this)
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 30) view.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
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
            @RequiresApi(21)
            override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
            override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<android.net.Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                filePathCallback?.onReceiveValue(null); return true
            }
        }
        view.setDownloadListener { _, _, _, _, _ -> }
        view.webViewClient = object : WebViewClientCompat() {
            @RequiresApi(21)
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
        // The header is Compose; Google's page stays a plain WebView under it, owned and destroyed by this activity.
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(composed(scrolls = false) { PasswordOwnerText(stringResource(R.string.passwords_encryption_keep_open), Modifier.padding(bottom = 8.dp)) },
            LinearLayout.LayoutParams(-1, -2))
        layout.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
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
    /** Google's page finished or closed: settle the attempt and show the account's setting again. */
    private fun reconcile() {
        tearDownWeb()
        refreshStatus()
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
    private fun renderError() = page {
        PasswordOwnerText(stringResource(R.string.passwords_encryption_failed))
        PasswordOwnerActions { CyclonInkButton(stringResource(R.string.passwords_encryption_retry)) { unlessBusy { refreshStatus() } } }
    }
    private fun showUnavailable() { showText(R.string.passwords_encryption_not_supported) }
    private fun showText(message: Int) = page(onBack = ::finish) { PasswordOwnerText(stringResource(message)) }

    /** One state of this page: Back (closing as Close did), the title and the account, then [content]. */
    private fun page(onBack: () -> Unit = { unlessBusy { closeOwnerScreen() } }, content: @Composable ColumnScope.() -> Unit) =
        setContentView(composed(onBack = onBack, content = content))
    private fun composed(scrolls: Boolean = true, onBack: () -> Unit = { unlessBusy { closeOwnerScreen() } },
                         content: @Composable ColumnScope.() -> Unit) = ComposeView(this).apply {
        val accountName = if (::account.isInitialized) account.name else null
        setContent { CyclonMaterialTheme {
            if (scrolls) PasswordOwnerPage(stringResource(R.string.passwords_encryption_manage), accountName, onBack, content)
            // Above the WebView: only as tall as the header, so Google's page keeps the rest of the screen.
            else Surface(Modifier.fillMaxWidth()) { Column { PasswordOwnerHeader(stringResource(R.string.passwords_encryption_manage), accountName, onBack); content() } }
            question?.let { asked -> AlertDialog(onDismissRequest = { question = null },
                title = { Text(stringResource(asked.title)) },
                text = { PasswordDialogProtection(); PasswordDialogText(stringResource(asked.message), scrolls = true) },
                confirmButton = { CyclonTextAction(stringResource(asked.confirm)) { question = null; asked.accept() } },
                dismissButton = { CyclonTextAction(stringResource(android.R.string.cancel)) { question = null } }) }
        } }
    }
    private inline fun unlessBusy(click: () -> Unit) { if (!busy) click() }
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
            if (Build.VERSION.SDK_INT < 28) throw VaultLockedException()
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
            if (Build.VERSION.SDK_INT < 28) { sessions.cleanupTimedOut(value); return }
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
