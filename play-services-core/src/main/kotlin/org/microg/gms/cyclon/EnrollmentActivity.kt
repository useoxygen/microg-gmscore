/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.R
import kotlinx.coroutines.*
import org.json.JSONTokener
import org.json.JSONObject
import org.microg.gms.auth.AuthConstants
import org.microg.gms.auth.AuthManager
import org.microg.gms.checkin.CheckinManager
import org.microg.gms.checkin.CheckinPreferences
import org.microg.gms.checkin.LastCheckinInfo
import org.microg.gms.common.Constants.GMS_PACKAGE_NAME
import org.microg.gms.gcm.GcmPrefs
import java.net.URLEncoder

/** Lineage's DeviceSpecificActivity launches this and owns the wizard's NEXT/result contract. */
class EnrollmentActivity : ComponentActivity() {
    private var status by mutableStateOf(R.string.cyclon_enrollment_intro)
    private var busy by mutableStateOf(false)
    private var browser by mutableStateOf<WebView?>(null)
    private var accounts by mutableStateOf<List<Account>>(emptyList())
    private var completed by mutableStateOf(false)
    private var job: Job? = null
    private var session: EnrollmentSession? = null
    private var id = ""
    private var accountName = ""
    private var canSubmit = true
    private var pollStarted = 0L
    private var script = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Production entry is first-run only. The exported alias is permission protected too.
        if (intent.component?.className == "org.microg.gms.cyclon.SetupEnrollment" &&
            Settings.Secure.getInt(contentResolver, "user_setup_complete", 0) != 0 &&
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) { finish(); return }
        script = assets.open("cyclon-enrollment.js").bufferedReader().use { it.readText() }
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.cyclon_enrollment_title), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(status))
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        browser?.let { view -> AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().weight(1f)) }
                        if (accounts.isNotEmpty() && browser == null) {
                            for (account in accounts) Button(onClick = { openRegistration(account) }, enabled = !busy) { Text(account.name) }
                        } else if (browser == null && !completed) {
                            Button(onClick = { prepare() }, enabled = !busy) { Text(stringResource(R.string.cyclon_enrollment_connect)) }
                        }
                        if (browser != null && !completed && !busy) TextButton(onClick = { resumeRegistration() }) {
                            Text(stringResource(R.string.cyclon_enrollment_check))
                        }
                        TextButton(onClick = { finishSetup() }) {
                            Text(stringResource(if (completed) R.string.cyclon_enrollment_continue else R.string.cyclon_enrollment_later))
                        }
                    }
                }
            }
        }
    }

    private fun prepare() {
        busy = true
        status = R.string.cyclon_enrollment_preparing
        job = lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    CheckinPreferences.setEnabled(this@EnrollmentActivity, true)
                    GcmPrefs.setEnabled(this@EnrollmentActivity, true)
                    CheckinManager.checkin(this@EnrollmentActivity,
                        LastCheckinInfo.read(this@EnrollmentActivity).androidId <= 0)
                    LastCheckinInfo.read(this@EnrollmentActivity)
                }
                require(info.androidId > 0) // never Settings.Secure.ANDROID_ID or a guessed ID
                // Google's current UI requires exactly 19 decimal digits. Keep all 64 bits.
                id = info.androidId.toString().padStart(19, '0')
                val existing = AccountManager.get(this@EnrollmentActivity).getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE).toList()
                busy = false
                when (existing.size) {
                    0 -> addAccount()
                    1 -> openRegistration(existing.single())
                    else -> { accounts = existing; status = R.string.cyclon_enrollment_choose }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { busy = false; status = R.string.cyclon_enrollment_network_error }
        }
    }

    private fun addAccount() {
        busy = true
        status = R.string.cyclon_enrollment_signin
        AccountManager.get(this).addAccount(AuthConstants.DEFAULT_ACCOUNT_TYPE, null, null, null, this, { future ->
            if (isFinishing || isDestroyed) return@addAccount
            val name = runCatching { future.result.getString(AccountManager.KEY_ACCOUNT_NAME) }.getOrNull()
            busy = false
            val account = AccountManager.get(this).getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE).singleOrNull { it.name == name }
            if (account == null) status = R.string.cyclon_enrollment_signin_cancelled
            else openRegistration(account)
        }, null)
    }

    private fun openRegistration(account: Account) {
        accounts = emptyList()
        val currentId = LastCheckinInfo.read(this).androidId
        if (currentId <= 0) { status = R.string.cyclon_enrollment_network_error; return }
        id = currentId.toString().padStart(19, '0')
        accountName = account.name
        session = EnrollmentSession(this, account.name, id.toLong())
        if (session!!.accepted) { completed = true; status = R.string.cyclon_enrollment_done; return }
        canSubmit = !session!!.attempted
        busy = true
        status = R.string.cyclon_enrollment_registering
        job = lifecycleScope.launch {
            try {
                val authUrl = withContext(Dispatchers.IO) {
                    // Reuse microG's account-settings weblogin flow; credentials remain native.
                    AuthManager(this@EnrollmentActivity, account.name, GMS_PACKAGE_NAME,
                        "weblogin:continue=" + URLEncoder.encode(REGISTRATION, "utf-8"))
                        .requestAuthWithForegroundResolution(false)?.auth
                }
                require(authUrl != null && allowed(Uri.parse(authUrl)))
                createBrowser(authUrl)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { busy = false; status = R.string.cyclon_enrollment_web_error }
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun createBrowser(authUrl: String) {
        val view = WebView(this)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !allowed(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !allowed(Uri.parse(url))
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                busy = true
                if (isRegistration(url)) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            override fun onPageFinished(view: WebView, url: String?) {
                busy = false
                status = if (isRegistration(url)) R.string.cyclon_enrollment_verifying else R.string.cyclon_enrollment_signin
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel(); busy = false; status = R.string.cyclon_enrollment_web_error
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { busy = false; status = R.string.cyclon_enrollment_web_error }
            }
        }
        // Do not clear all microG cookies: other account flows can use the same cookie store.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        browser = view
        view.loadUrl(authUrl)
        resumeRegistration()
    }

    private fun resumeRegistration() {
        job?.cancel()
        pollStarted = System.currentTimeMillis()
        job = lifecycleScope.launch {
            while (isActive && !completed && System.currentTimeMillis() - pollStarted < 120_000) {
                val view = browser ?: return@launch
                if (isRegistration(view.url)) evaluate(view, false)
                delay(750)
            }
            if (!completed) { busy = false; status = R.string.cyclon_enrollment_needs_help }
        }
    }

    private fun evaluate(view: WebView, submit: Boolean) {
        if (!isRegistration(view.url)) return
        view.evaluateJavascript(script.replace("__CYCLON_ID__", "\"$id\"")
            .replace("__CYCLON_ACCOUNT__", JSONObject.quote(accountName))
            .replace("__CYCLON_SUBMIT__", submit.toString())) { result ->
            if (isFinishing || isDestroyed || browser !== view || !isRegistration(view.url)) return@evaluateJavascript
            val phase = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
            when (phase) {
                "ready" -> if (canSubmit && session?.admit() == true) {
                    canSubmit = false
                    evaluate(view, true)
                } else { busy = false; status = R.string.cyclon_enrollment_uncertain }
                "submitting" -> { busy = true; status = R.string.cyclon_enrollment_registering }
                "accepted" -> if (session?.accept() == true) {
                    completed = true; busy = false; status = R.string.cyclon_enrollment_done
                    job?.cancel(); view.stopLoading(); browser = null; view.destroy()
                }
                "challenge" -> { busy = false; status = R.string.cyclon_enrollment_challenge }
                "verification" -> { busy = false; status = R.string.cyclon_enrollment_verifying }
                "rejected" -> { busy = false; status = R.string.cyclon_enrollment_needs_help; job?.cancel() }
                "account_mismatch" -> { busy = false; status = R.string.cyclon_enrollment_choose_web; job?.cancel() }
            }
        }
    }

    private fun finishSetup() {
        // Completing the optional step is distinct from Google accepting registration.
        setResult(RESULT_OK, Intent().putExtra("cyclon.google_enrollment", if (completed) "accepted" else "deferred"))
        finish()
    }
    override fun onDestroy() { job?.cancel(); browser?.destroy(); browser = null; super.onDestroy() }

    companion object {
        private const val REGISTRATION = "https://www.google.com/android/uncertified/?hl=en"
        private fun allowed(uri: Uri): Boolean = uri.scheme == "https" && uri.userInfo == null &&
            uri.port in setOf(-1, 443) && (uri.host == "accounts.google.com" ||
            uri.host == "www.google.com" && (uri.path == "/android/uncertified/" || uri.path?.startsWith("/recaptcha/") == true))
        private fun isRegistration(url: String?): Boolean = url?.let { Uri.parse(it) }?.let {
            allowed(it) && it.host == "www.google.com" && it.path == "/android/uncertified/"
        } == true
    }
}
