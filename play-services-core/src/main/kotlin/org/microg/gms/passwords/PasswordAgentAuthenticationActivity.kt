/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.WindowManager
import android.view.View
import androidx.activity.compose.setContent
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.microg.gms.cyclon.CyclonMaterialTheme
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Request-local native authority; no password, freshness flag, or decision is stored in an Intent or on disk. */
internal object PasswordAgentAuthenticationRequests {
    class Request(val fill: PasswordAgentFillRequest, val site: String, val target: String, val username: String, val use: (() -> Boolean) -> Unit) {
        val claimed = AtomicBoolean(false)
        @Volatile var close: (() -> Unit)? = null
        lateinit var expiry: Runnable
        override fun toString() = "PasswordAuthenticationRequest[redacted]"
    }
    private val requests = ConcurrentHashMap<String, Request>()
    private val handler = Handler(Looper.getMainLooper())

    fun prepare(context: Context, fill: PasswordAgentFillRequest, username: String = "", use: (() -> Boolean) -> Unit): PendingIntent {
        if (Build.VERSION.SDK_INT < 30) throw SecurityException()
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: throw SecurityException()
        if (!keyguard.isDeviceSecure || keyguard.isDeviceLocked || keyguard.isKeyguardLocked || !fill.valid(context)) throw SecurityException()
        val site = if (fill.realm.startsWith("https://")) java.net.URI(fill.realm).host else {
            val app = context.packageManager.getApplicationInfo(fill.packageName, 0)
            context.packageManager.getApplicationLabel(app).toString()
        }
        val identity = if (fill.realm.startsWith("https://")) fill.realm else fill.packageName
        val request = Request(fill, site.filterNot(Char::isISOControl).take(253), identity,
            username.filterNot(Char::isISOControl).take(320), use)
        if (requests.putIfAbsent(fill.handle, request) != null) throw SecurityException()
        request.expiry = Runnable { PasswordAgentFillRequests.cancel(fill.handle) }
        fill.close = { release(request); request.close?.invoke() }
        try {
            handler.postDelayed(request.expiry, (fill.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            return PendingIntent.getActivity(context, 0,
                Intent(context, PasswordAgentAuthenticationActivity::class.java)
                    .setData(Uri.parse("microg-password-agent:/authenticate/${fill.handle}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        } catch (failure: Exception) {
            release(request)
            throw failure
        }
    }

    fun find(uri: Uri?): Request {
        if (uri?.scheme != "microg-password-agent" || uri.pathSegments.size != 2 ||
            uri.pathSegments[0] != "authenticate") throw SecurityException()
        return requests[uri.pathSegments[1]] ?: throw SecurityException()
    }
    fun claim(request: Request): Boolean = requests[request.fill.handle] === request && request.claimed.compareAndSet(false, true)
    fun finish(request: Request) {
        release(request)
        PasswordAgentFillRequests.cancel(request.fill.handle)
    }
    private fun release(request: Request) {
        requests.remove(request.fill.handle, request)
        handler.removeCallbacks(request.expiry)
    }
}

/** A cancellable system challenge for exactly one strict sign-in; unlocked status is never proof. */
class PasswordAgentAuthenticationActivity : AppCompatActivity() {
    private var pending: PasswordAgentAuthenticationRequests.Request? = null
    private val verified = AtomicBoolean(false)
    private val resumed = AtomicBoolean(false)
    private val focused = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)
    private val proofReceived = AtomicBoolean(false)
    private var promptStarted = false
    private var dispatchStarted = false
    private var authenticationCancellation: CancellationSignal? = null
    private val handler = Handler(Looper.getMainLooper())
    private val focusDeadline = Runnable { if (!dispatchStarted) cancel() }
    private val keyguard get() = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
    private val hasSecureScreenLock get() = keyguard.isDeviceSecure
    private val ownerAuthenticationTitle: String get() =
        getString(R.string.passwords_assistant_verify_signin, pending?.site.orEmpty())
    private val ownerAuthenticationDescription: String get() =
        getString(R.string.passwords_assistant_verify_account,
            pending?.username?.takeIf(String::isNotEmpty) ?: getString(R.string.passwords_assistant_saved_login),
            pending?.target.orEmpty())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT < 30) { finishAndRemoveTask(); return }
        try {
            pending = PasswordAgentAuthenticationRequests.find(intent.data).also { request ->
                request.close = { cancel() }
                // Configuration/process restoration never restores fresh authentication.
                if (savedInstanceState != null || !deviceReady() || !request.fill.valid(this)) throw SecurityException()
            }
        } catch (_: Exception) { cancel(); return }
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        if (Build.VERSION.SDK_INT >= 34) window.decorView.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onBackPressedDispatcher.addCallback(this) { cancel() }
        setContent { CyclonMaterialTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(ownerAuthenticationTitle, style = MaterialTheme.typography.headlineSmall)
                Text(ownerAuthenticationDescription)
                LinearProgressIndicator()
                TextButton(onClick = { cancel() }) { Text(stringResource(android.R.string.cancel)) }
            }
        } } }
    }

    override fun onResume() {
        super.onResume()
        resumed.set(true)
        if (!deviceReady()) { cancel(); return }
        advance()
    }
    override fun onPause() {
        resumed.set(false)
        // Credential fallback may temporarily pause this Activity. Preserve only a
        // callback proof awaiting its first focus restoration; an active handover
        // loses authority immediately when the owner window leaves foreground.
        if (dispatchStarted) verified.set(false)
        super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focused.set(hasFocus)
        if (!hasFocus && dispatchStarted) verified.set(false)
        if (hasFocus) advance()
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        if (rejectsPasswordTouch(event.flags)) false else super.dispatchTouchEvent(event)

    private fun advance() {
        val request = pending ?: return
        if (ended.get() || !resumed.get() || !focused.get()) return
        if (!deviceReady() || !request.fill.valid(this)) { cancel(); return }
        if (!promptStarted) startAuthentication(request)
        else if (verified.get() && !dispatchStarted) dispatch(request)
    }

    private fun startAuthentication(request: PasswordAgentAuthenticationRequests.Request) {
        if (Build.VERSION.SDK_INT < 30) { cancel(); return }
        promptStarted = true
        val signal = CancellationSignal()
        authenticationCancellation = signal
        try {
            val prompt = BiometricPrompt.Builder(this)
                .setTitle(ownerAuthenticationTitle)
                .setDescription(ownerAuthenticationDescription)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .setConfirmationRequired(true)
                .build()
            prompt.authenticate(signal, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (!proofReceived.compareAndSet(false, true) || ended.get() || signal.isCanceled || authenticationCancellation !== signal ||
                        pending !== request || !hasSecureScreenLock || !onOwnerDisplay() ||
                        !request.fill.valid(this@PasswordAgentAuthenticationActivity) ||
                        result.authenticationType !in setOf(BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC,
                            BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL)) { cancel(); return }
                    // This callback is the only producer of authentication authority.
                    // The OS dialog may still own focus while its success callback runs.
                    verified.set(true)
                    handler.postDelayed(focusDeadline, 5_000)
                    advance()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { cancel() }
                // A mismatched biometric is retryable. It never grants authority.
                override fun onAuthenticationFailed() = Unit
            })
        } catch (_: Exception) { cancel() }
    }

    private fun dispatch(request: PasswordAgentAuthenticationRequests.Request) {
        if (!PasswordAgentAuthenticationRequests.claim(request)) { cancel(); return }
        dispatchStarted = true
        handler.removeCallbacks(focusDeadline)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    request.use { !ended.get() && verified.get() && resumed.get() && focused.get() && deviceReady() &&
                        request.fill.valid(this@PasswordAgentAuthenticationActivity) }
                }
            } catch (_: Exception) {
                // Only cancellation reaches the private receiver, never raw OS/vault errors.
            } finally { cancel() }
        }
    }
    @Suppress("DEPRECATION")
    private fun onOwnerDisplay() = windowManager.defaultDisplay.displayId == Display.DEFAULT_DISPLAY
    private fun deviceReady(): Boolean = hasSecureScreenLock && !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked &&
        getSystemService(PowerManager::class.java).isInteractive && onOwnerDisplay()

    private fun invalidateAndDismiss() {
        ended.set(true)
        verified.set(false)
        handler.removeCallbacks(focusDeadline)
        // Invalidate authority before the framework can deliver any cancellation/success callback.
        val signal = authenticationCancellation
        authenticationCancellation = null
        val request = pending
        pending = null
        runCatching { signal?.cancel() }
        request?.let(PasswordAgentAuthenticationRequests::finish)
    }
    private fun cancel() {
        invalidateAndDismiss()
        finishAndRemoveTask()
    }
    override fun onDestroy() {
        invalidateAndDismiss()
        super.onDestroy()
    }
}
