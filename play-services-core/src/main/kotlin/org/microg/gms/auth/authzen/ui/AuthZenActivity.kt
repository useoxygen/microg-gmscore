/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.authzen.ui

import android.app.KeyguardManager
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.text.BidiFormatter
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.microg.gms.auth.authzen.AuthZenState
import org.microg.gms.auth.authzen.ConsentStep
import org.microg.gms.auth.authzen.EXTRA_AUTHZEN_HANDLE
import org.microg.gms.auth.authzen.OwnerAuthBinding
import org.microg.gms.auth.authzen.SendReport
import org.microg.gms.auth.authzen.authZenConsent
import org.microg.gms.auth.authzen.isAuthZenHandle

/**
 * The native confirmation screen. It renders only what the store holds for the opaque handle in its Intent, never
 * any other extra. The window is FLAG_SECURE before anything is drawn, sensitive content and decision controls stay
 * hidden while the phone is locked, and the screen closes when it goes to the background. Decision buttons ignore
 * touches through overlays and are disabled while an answer is processed.
 *
 * Skeleton: no transaction profile is verified yet (docs/google-prompt.md), so no request can reach this screen in
 * a shipped build, and any decision ends at the "not yet verified" message without contacting Google.
 */
class AuthZenActivity : ComponentActivity() {
    private val consent by lazy { authZenConsent() }
    private val ownerAuth by lazy { SystemOwnerAuthGate(this) }
    private lateinit var content: LinearLayout
    private var handle: String? = null
    private var busy = false
    /** The system authentication UI is up; it may cover this screen, which must not end the attempt. */
    private var authenticating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        super.onCreate(savedInstanceState)
        handle = intent.getStringExtra(EXTRA_AUTHZEN_HANDLE)?.takeIf { isAuthZenHandle(it) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply { addView(content) })
        if (handle == null) finish()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (authenticating) return
        intent.getStringExtra(EXTRA_AUTHZEN_HANDLE)?.takeIf { isAuthZenHandle(it) }?.let { handle = it }
    }

    override fun onResume() {
        super.onResume()
        val handle = handle ?: return
        if (authenticating) return
        val keyguard = ContextCompat.getSystemService(this, KeyguardManager::class.java)!!
        if (keyguard.isKeyguardLocked) {
            showMessage(getString(R.string.cyclon_authzen_locked))
            if (SDK_INT >= 26) keyguard.requestDismissKeyguard(this, null)
            return
        }
        perform { consent.open(handle) }
    }

    override fun onStop() {
        super.onStop()
        if (authenticating || isChangingConfigurations) return
        // Leaving the screen ends it; reopening starts from the stored request without any authorization.
        finish()
    }

    private fun perform(block: () -> ConsentStep) {
        if (busy) return
        busy = true
        setControlsEnabled(false)
        lifecycleScope.launch {
            // A store that cannot be read or written decides nothing; the screen just ends.
            val step = withContext(Dispatchers.IO) { runCatching(block).getOrElse { ConsentStep.Closed(null) } }
            busy = false
            render(step)
        }
    }

    private fun render(step: ConsentStep) {
        when (step) {
            is ConsentStep.Show -> showRequest(step, numbers = false)
            is ConsentStep.Authenticate -> authenticate(step.binding)
            is ConsentStep.Submitted -> showMessage(getString(when (val report = step.report) {
                is SendReport.NotReady -> R.string.cyclon_authzen_not_ready
                is SendReport.Stopped -> if (report.state == AuthZenState.SENDING) R.string.cyclon_authzen_submitted else R.string.cyclon_authzen_ended
                is SendReport.Sent -> when (report.state) {
                    AuthZenState.APPROVED_CONFIRMED -> R.string.cyclon_authzen_approved
                    AuthZenState.DENIED_CONFIRMED -> R.string.cyclon_authzen_submitted
                    AuthZenState.OUTCOME_UNKNOWN -> R.string.cyclon_authzen_unknown
                    else -> R.string.cyclon_authzen_ended
                }
            }))
            is ConsentStep.Closed -> showMessage(getString(R.string.cyclon_authzen_ended))
        }
    }

    private fun showRequest(step: ConsentStep.Show, numbers: Boolean) {
        val record = step.record
        val request = record.request ?: return showMessage(getString(R.string.cyclon_authzen_ended))
        content.removeAllViews()
        content.addView(text(getString(R.string.cyclon_authzen_title), 22f))
        val bidi = BidiFormatter.getInstance()
        content.addView(text(bidi.unicodeWrap(record.account), 16f))
        request.display.forEach { content.addView(text(bidi.unicodeWrap(it), 16f)) }
        val handle = record.handle
        if (numbers) {
            content.addView(text(getString(R.string.cyclon_authzen_choose_number), 16f))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            record.displayChoices.forEach { number ->
                row.addView(button(number.toString()) { perform { consent.approve(handle, number) } }.apply {
                    contentDescription = getString(R.string.cyclon_authzen_number, number)
                })
            }
            content.addView(row)
        } else {
            content.addView(button(getString(R.string.cyclon_authzen_yes)) {
                when {
                    ownerAuth.availability() != OwnerAuthAvailability.AVAILABLE -> showMessage(getString(R.string.cyclon_authzen_no_lock))
                    record.displayChoices.isNotEmpty() -> showRequest(step, numbers = true)
                    else -> perform { consent.approve(handle, null) }
                }
            })
        }
        content.addView(button(getString(R.string.cyclon_authzen_no)) { perform { consent.deny(handle) } })
    }

    private fun authenticate(binding: OwnerAuthBinding) {
        authenticating = true
        ownerAuth.authenticate(this) { result ->
            authenticating = false
            perform { consent.authenticated(binding, result) }
        }
    }

    override fun onDestroy() {
        ownerAuth.cancel()
        super.onDestroy()
    }

    private fun showMessage(message: String) {
        content.removeAllViews()
        content.addView(text(message, 16f))
    }

    private fun setControlsEnabled(enabled: Boolean) {
        for (i in 0 until content.childCount) content.getChildAt(i).isEnabled = enabled
    }

    private fun text(value: CharSequence, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        filterTouchesWhenObscured = true
        setOnClickListener { if (!busy) onClick() }
    }
}
