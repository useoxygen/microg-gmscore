/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.cyclon

import android.accounts.AccountManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Presentation
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.webkit.*
import androidx.core.app.NotificationCompat
import com.google.android.gms.R
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener
import org.microg.gms.auth.AuthConstants
import org.microg.gms.auth.AuthManager
import org.microg.gms.checkin.CheckinManager
import org.microg.gms.checkin.CheckinPreferences
import org.microg.gms.checkin.LastCheckinInfo
import org.microg.gms.common.Constants.GMS_PACKAGE_NAME
import org.microg.gms.cyclon.EnrollmentActivity.Companion.ASSIST_WINDOW_MS
import org.microg.gms.cyclon.EnrollmentActivity.Companion.POLL_WINDOW_MS
import org.microg.gms.cyclon.EnrollmentActivity.Companion.REGISTRATION
import org.microg.gms.cyclon.EnrollmentActivity.Companion.TRANSIENT_ERRORS
import org.microg.gms.cyclon.EnrollmentActivity.Companion.allowed
import org.microg.gms.cyclon.EnrollmentActivity.Companion.isRegistration
import org.microg.gms.gcm.GcmPrefs
import java.lang.ref.WeakReference
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Registers this phone with Google while the owner continues setup. The registration page runs in a WebView on a
 * private virtual display that only microG can draw to and nobody sees; Core's assistant reaches the puzzle through
 * the same bounded provider as on the visible screen. Sign-in and account choice are never shown here: Core adds the
 * account on the owner's screen first. Without registration the owner gets a notification to finish it themselves.
 */
@androidx.annotation.RequiresApi(26)
class BackgroundEnrollmentService : Service(), EnrollmentChallengeHost {
    private val scope = MainScope()
    private var running = false
    private var helper = false
    /** Core's last scheduled attempt: only then does an unfinished registration ask the owner to finish it. */
    private var finalAttempt = true
    private var completed = false
    private var ended = false
    private var id = ""
    private var accountName = ""
    private var canSubmit = true
    private var session: EnrollmentSession? = null
    private var script = ""
    private var challengeScript = ""
    private var browser: WebView? = null
    private var presentation: Presentation? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var frames: HandlerThread? = null
    private var job: Job? = null
    private var challengeSince = 0L
    private var transientRetries = 0
    private var lastPhase: String? = null
    private var lastObserve: String? = null
    private val puzzle by lazy { EnrollmentPuzzle(challengeScript, resources.displayMetrics.density, ::observeNote) }

    private fun observeNote(note: String) { if (note != lastObserve) { Log.i(TAG, "Observe $note"); lastObserve = note } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Core only starts this on Android 8.0+; the component is enabled from API 23 with the visible flow.
        if (Build.VERSION.SDK_INT < 26) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION_RUNNING, notification(getString(R.string.cyclon_enrollment_background_running), null, ongoing = true))
        if (running) return START_NOT_STICKY
        running = true
        helper = intent?.getBooleanExtra("cyclon.assist_verification", false) == true
        finalAttempt = intent?.getBooleanExtra("cyclon.final_attempt", true) != false
        script = assets.open("cyclon-enrollment.js").bufferedReader().use { it.readText() }
        challengeScript = assets.open("cyclon-challenge.js").bufferedReader().use { it.readText() }
        Log.i(TAG, "Background enrollment started (assist=$helper final=$finalAttempt)")
        // Without a usable network nothing is tried; Core waits for one and costs no attempt.
        if (!online()) { end("network"); return START_NOT_STICKY }
        prepare()
        return START_NOT_STICKY
    }

    private fun prepare() {
        job = scope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    CheckinPreferences.setEnabled(this@BackgroundEnrollmentService, true)
                    GcmPrefs.setEnabled(this@BackgroundEnrollmentService, true)
                    CheckinManager.checkin(this@BackgroundEnrollmentService, LastCheckinInfo.read(this@BackgroundEnrollmentService).androidId <= 0)
                    LastCheckinInfo.read(this@BackgroundEnrollmentService)
                }
                require(info.androidId > 0) // never Settings.Secure.ANDROID_ID or a guessed ID
                id = info.androidId.toString().padStart(19, '0')
                val account = AccountManager.get(this@BackgroundEnrollmentService).getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE)
                    .singleOrNull() ?: return@launch end("account")
                accountName = account.name
                session = EnrollmentSession(this@BackgroundEnrollmentService, account.name, id.toLong())
                if (session!!.accepted) { completed = true; return@launch end("accepted") }
                canSubmit = !session!!.attempted
                val authUrl = withContext(Dispatchers.IO) {
                    // Reuse microG's account-settings weblogin flow; credentials remain native.
                    AuthManager(this@BackgroundEnrollmentService, account.name, GMS_PACKAGE_NAME,
                        "weblogin:continue=" + URLEncoder.encode(REGISTRATION, "utf-8"))
                        .requestAuthWithForegroundResolution(false)?.auth
                }
                val target = authUrl?.takeUnless { it.contains("WILL_NOT_SIGN_IN") } ?: REGISTRATION
                require(allowed(Uri.parse(target)))
                createBrowser(target)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w(TAG, "Background preparation failed: ${e.javaClass.simpleName}"); end("network") }
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun createBrowser(authUrl: String) {
        val metrics = resources.displayMetrics
        val worker = HandlerThread("cyclon-enrollment-frames").also { it.start() }
        val surface = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        // Without a consumer the producer stalls and the page stops rendering.
        surface.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(worker.looper))
        // Private (no PUBLIC flag): only microG can show content here, and nothing is mirrored anywhere.
        val created = getSystemService(DisplayManager::class.java).createVirtualDisplay("Cyclon-Enrollment",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, surface.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        frames = worker; reader = surface; display = created
        val host = Presentation(this, created.display)
        val view = WebView(host.context)
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
                if (blocked && mainFrame) end("navigation")
                return blocked
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = blocks(request.url, request.isForMainFrame)
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = blocks(Uri.parse(url), true)
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) { handler.cancel(); end("ssl") }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                Log.w(TAG, "Main-frame error ${error.errorCode} ${error.description} ${request.method}")
                // Reloading a GET is safe; a failed POST may be the registration write, which is never replayed.
                if (error.errorCode in TRANSIENT_ERRORS && request.method == "GET" && allowed(request.url) && transientRetries < 2) {
                    val attempt = ++transientRetries
                    scope.launch { delay(1_500L * attempt); if (browser === view) view.loadUrl(request.url.toString()) }
                    return
                }
                end("web_error")
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        host.setContentView(view)
        host.show()
        presentation = host; browser = view
        EnrollmentChallengeProvider.current = WeakReference(this)
        view.loadUrl(authUrl)
        poll()
    }

    private fun poll() {
        job?.cancel()
        val started = System.currentTimeMillis()
        job = scope.launch {
            while (isActive && !completed && !ended && System.currentTimeMillis() - started < POLL_WINDOW_MS) {
                val view = browser ?: return@launch
                if (isRegistration(view.url)) evaluate(view, false)
                delay(750)
            }
            if (!completed) end("poll_timeout")
        }
    }

    private fun evaluate(view: WebView, submit: Boolean) {
        view.evaluateJavascript(script.replace("__CYCLON_ID__", "\"$id\"")
            .replace("__CYCLON_ACCOUNT__", JSONObject.quote(accountName))
            .replace("__CYCLON_SUBMIT__", submit.toString())) { result ->
            if (ended || browser !== view || !isRegistration(view.url)) return@evaluateJavascript
            val phase = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
            if (phase != lastPhase) { Log.i(TAG, "Registration phase $phase"); lastPhase = phase }
            when (phase) {
                "ready" -> if (canSubmit && session?.admit() == true) { canSubmit = false; evaluate(view, true) } else end("uncertain")
                "accepted" -> if (session?.accept() == true) { completed = true; end("accepted") }
                "challenge" -> {
                    if (challengeSince == 0L) challengeSince = SystemClock.elapsedRealtime()
                    if (SystemClock.elapsedRealtime() - challengeSince > ASSIST_WINDOW_MS) { helper = false; end("timeout") }
                }
                "rejected" -> end("rejected")
                // account_mismatch keeps polling: nothing is filled or clicked until the intended account is shown.
            }
        }
    }

    override fun challengeRequest(method: String, extras: Bundle?, active: AtomicBoolean, reply: (Bundle) -> Unit) {
        val view = browser
        val window = presentation?.window
        fun eligible() = active.get() && helper && !ended && !completed && browser === view && view != null && isRegistration(view.url)
        if (!eligible() || view == null || window == null) {
            observeNote("inactive helper=$helper ended=$ended registration=${isRegistration(view?.url)}")
            reply(Bundle().apply { putString("state", JSONObject().put("phase", "inactive").toString()) }); return
        }
        if (method == "stop") {
            helper = false; puzzle.reset()
            reply(Bundle().apply { putString("state", JSONObject().put("phase", "inactive").toString()) })
            end("assistant_stopped"); return
        }
        puzzle.handle(method, extras, view, window, accountName, ::eligible, reply)
    }

    /** Releases the hidden display. Without registration the owner gets a notification to finish it on their screen. */
    private fun end(reason: String) {
        if (ended) return
        ended = true
        Log.i(TAG, "Background enrollment ended: ${if (completed) "accepted" else reason}${if (!completed && !online()) " (offline)" else ""}")
        job?.cancel(); puzzle.reset()
        if (EnrollmentChallengeProvider.current.get() === this) EnrollmentChallengeProvider.current = WeakReference(null)
        browser?.let { it.stopLoading(); it.destroy() }; browser = null
        runCatching { presentation?.dismiss() }; presentation = null
        display?.release(); display = null
        reader?.close(); reader = null
        frames?.quitSafely(); frames = null
        // Losing the network mid-attempt (model calls and page loads fail) is not a failed puzzle: Core waits for one.
        val outcome = when { completed -> "accepted"; reason !in PERMANENT && !online() -> "network"; else -> reason }
        reportToCore(outcome)
        if (!completed && outcome != "network" && (finalAttempt || reason in PERMANENT)) {
            val open = PendingIntent.getActivity(this, 0, Intent(this, EnrollmentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_HELP, notification(getString(R.string.cyclon_enrollment_background_help), open, ongoing = false))
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Core schedules the next attempt (or stops) from this; without Core nothing else retries. */
    private fun reportToCore(outcome: String) {
        runCatching { contentResolver.call(Uri.parse("content://ai.cyclon.core.google_enrollment"), "result", outcome, null) }
            .onFailure { Log.w(TAG, "Core did not take the background result: ${it.javaClass.simpleName}") }
    }

    private fun online(): Boolean {
        val connectivity = getSystemService(android.net.ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun notification(text: String, open: PendingIntent?, ongoing: Boolean): Notification {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.cyclon_enrollment_title), NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cloud_bell)
            .setContentTitle(getString(R.string.cyclon_enrollment_title))
            .setContentText(text)
            .setOngoing(ongoing).setAutoCancel(!ongoing)
            .apply { if (open != null) setContentIntent(open) }
            .build()
    }

    override fun onDestroy() { if (!ended) end("destroyed"); scope.cancel(); super.onDestroy() }

    private companion object {
        const val TAG = "CyclonEnrollment"
        const val CHANNEL = "cyclon_enrollment"
        const val NOTIFICATION_RUNNING = 0x7e56
        const val NOTIFICATION_HELP = 0x7e57
        /** Outcomes another attempt cannot change. */
        val PERMANENT = setOf("account")
    }
}
