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
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
@RequiresApi(Build.VERSION_CODES.M)
class EnrollmentActivity : ComponentActivity(), EnrollmentChallengeHost {
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
    private val puzzle by lazy { EnrollmentPuzzle(challengeScript, resources.displayMetrics.density, ::observeNote) }
    private var transientRetries = 0
    private var lastPhase: String? = null
    private var lastObserve: String? = null
    private fun observeNote(note: String) { if (note != lastObserve) { Log.i("CyclonEnrollment", "Observe $note"); lastObserve = note } }

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
            EnrollmentTheme {
                val colors = MaterialTheme.colorScheme
                val view = browser
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    if (view == null) {
                        // Before and after Google's page: a scrolling explanation above pinned actions.
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp).padding(top = 40.dp, bottom = 24.dp)) {
                            Text(stringResource(R.string.cyclon_enrollment_eyebrow), style = TextStyle(fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp, letterSpacing = (-0.2).sp, color = colors.onSurfaceVariant))
                            Spacer(Modifier.height(10.dp))
                            Text(stringResource(R.string.cyclon_enrollment_title), Modifier.semantics { heading() }, style = TextStyle(
                                fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface))
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(if (completed) R.string.cyclon_enrollment_done else R.string.cyclon_enrollment_intro),
                                Modifier.semantics { if (completed) liveRegion = LiveRegionMode.Polite },
                                style = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, color = if (completed) colors.onSurface else colors.onSurfaceVariant))
                            // Reserved so the page doesn't shift as work starts and ends.
                            Box(Modifier.padding(top = 20.dp).height(2.dp).fillMaxWidth()) {
                                if (busy) LinearProgressIndicator(Modifier.fillMaxSize(), color = colors.onSurface, trackColor = colors.outlineVariant)
                            }
                            Spacer(Modifier.height(18.dp))
                            if (!completed && status != R.string.cyclon_enrollment_intro) EnrollmentNotice(stringResource(status), status in PROBLEMS)
                            if (accounts.isNotEmpty()) {
                                Spacer(Modifier.height(20.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (account in accounts) OutlinedButton(onClick = { openRegistration(account) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                        enabled = !busy, shape = EnrollmentShape, border = BorderStroke(1.dp, colors.outlineVariant),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                                        Text(account.name, Modifier.fillMaxWidth(), style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp))
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = colors.outlineVariant)
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (completed) Button(onClick = { finishSetup() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = EnrollmentShape) {
                                Text(stringResource(R.string.cyclon_enrollment_continue))
                            } else {
                                if (accounts.isEmpty()) Button(onClick = { prepare() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !busy, shape = EnrollmentShape,
                                    colors = ButtonDefaults.buttonColors(disabledContainerColor = colors.surfaceVariant, disabledContentColor = colors.onSurfaceVariant)) {
                                    Text(stringResource(if (status in PROBLEMS) R.string.cyclon_enrollment_retry else R.string.cyclon_enrollment_connect))
                                }
                                EnrollmentQuietButton(stringResource(R.string.cyclon_enrollment_later), Modifier.fillMaxWidth()) { finishSetup() }
                            }
                        }
                    } else {
                        // Google's page gets the height: one compact line of state, the page, then one row of actions.
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(stringResource(R.string.cyclon_enrollment_title), Modifier.semantics { heading() }, style = TextStyle(
                                fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface))
                            if (registrationPage || !busy && status != R.string.cyclon_enrollment_signin)
                                Text(stringResource(status), Modifier.padding(top = 2.dp).semantics { liveRegion = LiveRegionMode.Polite },
                                    style = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant))
                        }
                        Box(Modifier.height(2.dp).fillMaxWidth()) {
                            if (busy && registrationPage) LinearProgressIndicator(Modifier.fillMaxSize(), color = colors.onSurface, trackColor = colors.outlineVariant)
                            else HorizontalDivider(Modifier.align(Alignment.BottomStart), color = colors.outlineVariant)
                        }
                        // One AndroidView per WebView instance; header changes above never recreate it.
                        key(view) { AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().weight(1f)) }
                        HorizontalDivider(color = colors.outlineVariant)
                        // Dismissive action first, the step's next action at the end; each may wrap at large text sizes.
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                            EnrollmentQuietButton(stringResource(R.string.cyclon_enrollment_later), Modifier.weight(1f, fill = false)) { finishSetup() }
                            if (status == R.string.cyclon_enrollment_web_error) Button(onClick = { retryRegistration() }, modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp), shape = EnrollmentShape) {
                                Text(stringResource(R.string.cyclon_enrollment_retry))
                            } else if (helper && registrationPage && !completed) OutlinedButton(onClick = { helper = false; puzzle.reset(); status = R.string.cyclon_enrollment_challenge },
                                modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp), shape = EnrollmentShape, border = BorderStroke(1.dp, colors.outline),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface)) {
                                Text(stringResource(R.string.cyclon_enrollment_manual))
                            } else if (registrationPage && !completed && !busy) OutlinedButton(onClick = { resumeRegistration() },
                                modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp), shape = EnrollmentShape, border = BorderStroke(1.dp, colors.outlineVariant),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface)) {
                                Text(stringResource(R.string.cyclon_enrollment_check))
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
                if (!request.isForMainFrame) return
                val uri = request.url
                val known = allowed(uri)
                Log.w("CyclonEnrollment", "Main-frame error ${error.errorCode} ${error.description} ${request.method} " +
                    "${uri.host?.takeIf { known } ?: "other"} ${uri.path?.takeIf { known } ?: "other"}")
                // Google's pages can drop a connection mid-load. Reloading a GET is safe; a failed POST may be the
                // registration write, which is never replayed automatically.
                if (error.errorCode in TRANSIENT_ERRORS && request.method == "GET" && known && transientRetries < 2) {
                    val attempt = ++transientRetries
                    lifecycleScope.launch {
                        delay(1_500L * attempt)
                        if (browser === view) view.loadUrl(uri.toString())
                    }
                    return
                }
                failBrowser()
            }
        }
        // Do not clear all microG cookies: other account flows can use the same cookie store.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        browser = view
        transientRetries = 0
        view.loadUrl(authUrl)
        resumeRegistration()
    }

    private fun failBrowser() {
        helper = false; puzzle.reset(); busy = false; status = R.string.cyclon_enrollment_web_error
        job?.cancel()
    }

    private fun retryRegistration() {
        job?.cancel(); helper = false; puzzle.reset()
        browser?.destroy(); browser = null
        val account = AccountManager.get(this).getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE)
            .singleOrNull { it.name == accountName }
        if (account != null) openRegistration(account) else prepare()
    }

    private fun resumeRegistration() {
        job?.cancel()
        pollStarted = System.currentTimeMillis()
        job = lifecycleScope.launch {
            while (isActive && !completed && System.currentTimeMillis() - pollStarted < POLL_WINDOW_MS) {
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
            if (phase != lastPhase) { Log.i("CyclonEnrollment", "Registration phase $phase"); lastPhase = phase }
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
                    if (android.os.SystemClock.elapsedRealtime() - challengeSince > ASSIST_WINDOW_MS) helper = false
                    busy = helper
                    status = if (helper) R.string.cyclon_enrollment_assisting else R.string.cyclon_enrollment_challenge
                }
                "verification" -> { busy = false; status = R.string.cyclon_enrollment_verifying }
                "rejected" -> { busy = false; status = R.string.cyclon_enrollment_needs_help; job?.cancel() }
                // Keep checking: the account label can be read mid-render. Nothing is filled or clicked until it matches.
                "account_mismatch" -> { busy = false; status = R.string.cyclon_enrollment_choose_web }
            }
        }
    }

    override fun onResume() { super.onResume(); resumed = true; EnrollmentChallengeProvider.current = WeakReference(this) }
    override fun onPause() { resumed = false; puzzle.reset(); super.onPause() }

    override fun challengeRequest(method: String, extras: Bundle?, active: AtomicBoolean, reply: (Bundle) -> Unit) {
        val view = browser
        fun eligible() = active.get() && helper && resumed && !isFinishing && !isDestroyed && !completed &&
            browser === view && view != null && isRegistration(view.url) && window.decorView.hasWindowFocus() &&
            window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0
        if (!eligible() || view == null) {
            observeNote("inactive helper=$helper resumed=$resumed focus=${window.decorView.hasWindowFocus()} secure=${window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0} registration=${isRegistration(view?.url)}")
            reply(Bundle().apply { putString("state", JSONObject().put("phase", "inactive").toString()) }); return
        }
        if (method == "stop") {
            helper = false; puzzle.reset(); busy = false; status = R.string.cyclon_enrollment_challenge
            reply(Bundle().apply { putString("state", JSONObject().put("phase", "inactive").toString()) }); return
        }
        puzzle.handle(method, extras, view, window, accountName, ::eligible, reply)
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
    override fun onDestroy() { helper = false; puzzle.reset(); job?.cancel(); browser?.destroy(); browser = null; super.onDestroy() }

    companion object {
        /** Statuses that need the owner's action; the notice's words say so, its red rule only reinforces them. */
        private val PROBLEMS = setOf(R.string.cyclon_enrollment_network_error, R.string.cyclon_enrollment_web_error,
            R.string.cyclon_enrollment_signin_cancelled, R.string.cyclon_enrollment_needs_help,
            R.string.cyclon_enrollment_uncertain, R.string.cyclon_enrollment_choose_web)
        internal const val REGISTRATION = "https://www.google.com/android/uncertified/?hl=en"
        // Outlast Cyclon's own assistant bounds (twelve minutes, 30-second model calls) so normal solving never hits these.
        internal const val POLL_WINDOW_MS = 20 * 60_000L
        internal const val ASSIST_WINDOW_MS = 14 * 60_000L
        internal val TRANSIENT_ERRORS = setOf(WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_IO,
            WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_UNKNOWN)
        internal fun allowed(uri: Uri): Boolean = uri.scheme == "https" && uri.userInfo == null &&
            uri.port in setOf(-1, 443) && (uri.host == "accounts.google.com" ||
            // Google's account-session redirect, observed during the live enrollment test.
            uri.host == "gds.google.com" && uri.path == "/web/landing" ||
            uri.host == "myaccount.google.com" && uri.path == "/accounts/SetOSID" ||
            uri.host == "www.google.com" && (uri.path == "/android/uncertified/" || uri.path?.startsWith("/recaptcha/") == true))
        internal fun isRegistration(url: String?): Boolean = url?.let { Uri.parse(it) }?.let {
            allowed(it) && it.host == "www.google.com" && it.path == "/android/uncertified/"
        } == true
    }
}

// Cyclon's setup design (ink on paper, black in the dark, hairlines, one filled action), as its own setup screens
// draw it. microG has no Cyclon fonts, so these are the token values in the system typeface.
private val EnrollmentShape = RoundedCornerShape(8.dp)

@Composable
private fun EnrollmentTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val paper = Color(if (dark) 0xFF000000 else 0xFFFAFAF9)
    val ink = Color(if (dark) 0xFFF2F2F5 else 0xFF171719)
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(primary = ink, onPrimary = paper,
        background = paper, onBackground = ink, surface = paper, onSurface = ink,
        onSurfaceVariant = Color(if (dark) 0xFF8E8E96 else 0xFF5B5B63), surfaceVariant = Color(if (dark) 0xFF1C1C20 else 0xFFE6E6EA),
        outline = Color(if (dark) 0xFF3B3B42 else 0xFFB8B8BD), outlineVariant = Color(if (dark) 0xFF242427 else 0xFFDEDEE3),
        error = Color(0xFFD9453D))
    MaterialTheme(colorScheme = scheme, typography = Typography().let {
        it.copy(labelLarge = it.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp))
    }) {
        Surface(Modifier.fillMaxSize(), color = paper, contentColor = ink, content = content)
    }
}

@Composable
private fun EnrollmentNotice(text: String, problem: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).semantics { liveRegion = LiveRegionMode.Polite }) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(if (problem) colors.error else colors.outline))
        Text(text, Modifier.padding(start = 12.dp), style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = colors.onSurface))
    }
}

@Composable
private fun EnrollmentQuietButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), shape = EnrollmentShape,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
        Text(text)
    }
}
