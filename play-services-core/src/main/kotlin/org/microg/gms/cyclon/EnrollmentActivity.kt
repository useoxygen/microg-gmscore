/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    private var registrationPage by mutableStateOf(false)
    private var accounts by mutableStateOf<List<Account>>(emptyList())
    private var completed by mutableStateOf(false)
    private var job: Job? = null
    private var session: EnrollmentSession? = null
    private var id = ""
    private var accountName = ""
    private var canSubmit = true
    private var pollStarted = 0L
    private var script = ""
    private var challengeScript = ""
    private var helper by mutableStateOf(false)
    private var resumed = false
    private var challengeSince = 0L
    private var revision = ""
    private var revisionAt = 0L
    private var challengeFingerprint = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Production entry is first-run only. The exported alias is permission protected too.
        if (intent.component?.className == "org.microg.gms.cyclon.SetupEnrollment" &&
            Settings.Secure.getInt(contentResolver, "user_setup_complete", 0) != 0 &&
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) { finish(); return }
        script = assets.open("cyclon-enrollment.js").bufferedReader().use { it.readText() }
        challengeScript = assets.open("cyclon-challenge.js").bufferedReader().use { it.readText() }
        val fromCore = callingPackage == "ai.cyclon.core" && intent.component?.className == "org.microg.gms.cyclon.SetupEnrollment"
        helper = intent.getBooleanExtra("cyclon.assist_verification", false) && fromCore
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF202020), onPrimary = Color.White,
                surface = Color.White, background = Color.White)) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.cyclon_enrollment_title), style = MaterialTheme.typography.headlineMedium)
                        if (browser == null || registrationPage || !busy && status != R.string.cyclon_enrollment_signin) Text(stringResource(status))
                        if (busy && (browser == null || registrationPage)) LinearProgressIndicator(Modifier.fillMaxWidth())
                        browser?.let { view -> AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().weight(1f)) }
                        if (accounts.isNotEmpty() && browser == null) {
                            for (account in accounts) Button(onClick = { openRegistration(account) }, enabled = !busy) { Text(account.name) }
                        } else if (browser == null && !completed) {
                            Button(onClick = { prepare() }, enabled = !busy) { Text(stringResource(R.string.cyclon_enrollment_connect)) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            if (browser != null && status == R.string.cyclon_enrollment_web_error) TextButton(onClick = { retryRegistration() }) {
                                Text(stringResource(R.string.cyclon_enrollment_retry))
                            } else if (helper && browser != null && registrationPage && !completed) TextButton(onClick = { helper = false; revision = ""; status = R.string.cyclon_enrollment_challenge }) {
                                Text(stringResource(R.string.cyclon_enrollment_manual))
                            } else if (browser != null && registrationPage && !completed && !busy) TextButton(onClick = { resumeRegistration() }) {
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
        if (fromCore && intent.getBooleanExtra("cyclon.start_connection", false)) prepare()
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
                // Google can decline silent web sign-in even after native account addition.
                // Fall back to its normal page; the selected-account guard still precedes any write.
                val target = authUrl?.takeUnless { it.contains("WILL_NOT_SIGN_IN") } ?: REGISTRATION
                require(allowed(Uri.parse(target)))
                createBrowser(target)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w("CyclonEnrollment", "Registration preparation failed: ${e.javaClass.simpleName}")
                busy = false; status = R.string.cyclon_enrollment_web_error
            }
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
            private fun blocks(uri: Uri, mainFrame: Boolean): Boolean {
                val blocked = !allowed(uri)
                if (blocked && mainFrame) {
                    failBrowser()
                    if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                        val host = uri.host?.takeIf { it in setOf("www.google.com", "gds.google.com", "myaccount.google.com", "accounts.youtube.com") } ?: "other"
                        val path = uri.path?.takeIf { it in setOf("/accounts/SetSID", "/accounts/SetOSID", "/web/homeaddress", "/web/workaddress", "/web/recoveryoptions", "/web/landing", "/web/finish") } ?: "other"
                        Log.w("CyclonEnrollment", "Blocked sign-in navigation: $host $path")
                    }
                }
                return blocked
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                blocks(request.url, request.isForMainFrame)
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = blocks(Uri.parse(url), true)
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                busy = true
                registrationPage = isRegistration(url)
                status = if (registrationPage) R.string.cyclon_enrollment_verifying else R.string.cyclon_enrollment_signin
                secureWindow(!isRegistration(url))
            }
            override fun onPageFinished(view: WebView, url: String?) {
                busy = false
                registrationPage = isRegistration(url)
                if (isRegistration(url)) {
                    secureWindow(false)
                }
                if (status != R.string.cyclon_enrollment_web_error)
                    status = if (isRegistration(url)) R.string.cyclon_enrollment_verifying else R.string.cyclon_enrollment_signin
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel(); failBrowser()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) failBrowser()
            }
        }
        // Do not clear all microG cookies: other account flows can use the same cookie store.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        browser = view
        view.loadUrl(authUrl)
        resumeRegistration()
    }

    private fun failBrowser() {
        helper = false; revision = ""; busy = false; status = R.string.cyclon_enrollment_web_error
        job?.cancel()
    }

    private fun retryRegistration() {
        job?.cancel(); helper = false; revision = ""
        browser?.destroy(); browser = null
        val account = AccountManager.get(this).getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE)
            .singleOrNull { it.name == accountName }
        if (account != null) openRegistration(account) else prepare()
    }

    private fun resumeRegistration() {
        job?.cancel()
        pollStarted = System.currentTimeMillis()
        job = lifecycleScope.launch {
            while (isActive && !completed && System.currentTimeMillis() - pollStarted < 600_000) {
                val view = browser ?: return@launch
                if (isRegistration(view.url)) {
                    secureWindow(false)
                    evaluate(view, false)
                }
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
                "challenge" -> {
                    if (challengeSince == 0L) challengeSince = android.os.SystemClock.elapsedRealtime()
                    if (android.os.SystemClock.elapsedRealtime() - challengeSince > 120_000) helper = false
                    busy = helper
                    status = if (helper) R.string.cyclon_enrollment_assisting else R.string.cyclon_enrollment_challenge
                }
                "verification" -> { busy = false; status = R.string.cyclon_enrollment_verifying }
                "rejected" -> { busy = false; status = R.string.cyclon_enrollment_needs_help; job?.cancel() }
                "account_mismatch" -> { busy = false; status = R.string.cyclon_enrollment_choose_web; job?.cancel() }
            }
        }
    }

    override fun onResume() { super.onResume(); resumed = true; EnrollmentChallengeProvider.current = WeakReference(this) }
    override fun onPause() { resumed = false; revision = ""; super.onPause() }

    internal fun challengeRequest(method: String, extras: Bundle?, active: AtomicBoolean, reply: (Bundle) -> Unit) {
        fun result(phase: String) = reply(Bundle().apply { putString("state", JSONObject().put("phase", phase).toString()) })
        val view = browser
        fun eligible() = active.get() && helper && resumed && !isFinishing && !isDestroyed && !completed &&
            browser === view && view != null && isRegistration(view.url) && window.decorView.hasWindowFocus() &&
            window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0
        if (!eligible() || view == null) { result("inactive"); return }
        if (method == "stop") {
            helper = false; revision = ""; busy = false; status = R.string.cyclon_enrollment_challenge
            result("inactive"); return
        }
        val observation = challengeScript.replace("__ACTION__", "null").replace("__TILES__", "[]").replace("__EXPECTED__", "null").replace("__ACCOUNT__", JSONObject.quote(accountName))
        view.evaluateJavascript(observation) { raw ->
            if (!eligible()) { result("inactive"); return@evaluateJavascript }
            val state = runCatching { JSONObject(raw) }.getOrNull()
            if (state?.optString("phase") != "challenge") { result("waiting"); return@evaluateJavascript }
            try {
                val bounds = state.getJSONArray("bounds"); val viewport = state.getJSONArray("viewport")
                val scale = view.width.toDouble() / viewport.getDouble(0)
                val rawLeft = (bounds.getDouble(0) * scale).toInt(); val rawTop = (bounds.getDouble(1) * scale).toInt()
                val rawRight = (bounds.getDouble(2) * scale).toInt(); val rawBottom = (bounds.getDouble(3) * scale).toInt()
                // Google's iframe border can extend one CSS pixel beyond its viewport. Permit only
                // that rounding/border margin; a clipped puzzle still cannot leave this process.
                val border = kotlin.math.ceil(2 * scale).toInt()
                require(rawLeft >= -border && rawTop >= -border && rawRight <= view.width + border && rawBottom <= view.height + border)
                val left = rawLeft.coerceAtLeast(0); val top = rawTop.coerceAtLeast(0)
                val width = rawRight.coerceAtMost(view.width) - left
                val height = rawBottom.coerceAtMost(view.height) - top
                require(width in 100..1600 && height in 100..2000)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap); canvas.translate(-left.toFloat(), -top.toFloat()); view.draw(canvas)
                val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray(); bitmap.recycle()
                require(bytes.size in 1..512*1024)
                val fingerprint = state.getString("fingerprint")
                val fresh = MessageDigest.getInstance("SHA-256").digest(bytes + fingerprint.toByteArray()).joinToString("") { "%02x".format(it) }
                if (method == "observe") {
                    revision = fresh; revisionAt = android.os.SystemClock.elapsedRealtime(); challengeFingerprint = fingerprint
                    reply(Bundle().apply {
                        putString("state", JSONObject().put("phase", "challenge").put("revision", fresh).put("tileCount", state.getInt("tileCount")).put("selected", state.getJSONArray("selected")).toString())
                        putByteArray("image", bytes)
                    })
                } else {
                    val tiles = extras?.getIntArray("tiles") ?: intArrayOf()
                    val action = extras?.getString("action")
                    if (extras?.getString("revision") != revision || fresh != revision || fingerprint != challengeFingerprint ||
                        android.os.SystemClock.elapsedRealtime() - revisionAt > 30_000 || !eligible()) { result("stale"); return@evaluateJavascript }
                    require(action in setOf("tiles", "verify") && tiles.size <= 16)
                    // Consume before the effect. An ambiguous Binder response cannot replay this observation.
                    revision = ""
                    val command = challengeScript.replace("__ACTION__", JSONObject.quote(action)).replace("__TILES__", org.json.JSONArray(tiles.toList()).toString()).replace("__EXPECTED__", JSONObject.quote(fingerprint)).replace("__ACCOUNT__", JSONObject.quote(accountName))
                    view.evaluateJavascript(command) { actionResult ->
                        val phase = runCatching { JSONObject(actionResult).getString("phase") }.getOrDefault("unknown")
                        result(phase.takeIf { it in setOf("acted", "stale", "refused") } ?: "unknown")
                    }
                }
            } catch (_: Exception) { result("unavailable") }
        }
    }

    private fun finishSetup() {
        // Completing the optional step is distinct from Google accepting registration.
        setResult(RESULT_OK, Intent().putExtra("cyclon.google_enrollment", if (completed) "accepted" else "deferred"))
        finish()
    }
    private fun secureWindow(secure: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        // Repeated relayouts can disrupt WebView rendering; only change the actual flag.
        if ((window.attributes.flags and flag != 0) == secure) return
        if (secure) window.addFlags(flag) else window.clearFlags(flag)
    }
    override fun onDestroy() { helper = false; revision = ""; job?.cancel(); browser?.destroy(); browser = null; super.onDestroy() }

    companion object {
        private const val REGISTRATION = "https://www.google.com/android/uncertified/?hl=en"
        private fun allowed(uri: Uri): Boolean = uri.scheme == "https" && uri.userInfo == null &&
            uri.port in setOf(-1, 443) && (uri.host == "accounts.google.com" ||
            // Google's account-session redirect, observed during the live enrollment test.
            uri.host == "gds.google.com" && uri.path == "/web/landing" ||
            uri.host == "myaccount.google.com" && uri.path == "/accounts/SetOSID" ||
            uri.host == "www.google.com" && (uri.path == "/android/uncertified/" || uri.path?.startsWith("/recaptcha/") == true))
        private fun isRegistration(url: String?): Boolean = url?.let { Uri.parse(it) }?.let {
            allowed(it) && it.host == "www.google.com" && it.path == "/android/uncertified/"
        } == true
    }
}
