/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.cyclon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Window
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** A host page (visible screen or background display) that Core's puzzle assistant may observe and act on. */
internal interface EnrollmentChallengeHost {
    fun challengeRequest(method: String, extras: Bundle?, active: java.util.concurrent.atomic.AtomicBoolean, reply: (Bundle) -> Unit)
}

/**
 * Observes Google's verification puzzle for Core's assistant and performs its bounded actions. The host decides
 * eligibility; this class owns the observation token, the semantic fingerprint check and the capture.
 */
@androidx.annotation.RequiresApi(23)
internal class EnrollmentPuzzle(private val script: String, private val density: Float, private val note: (String) -> Unit) {
    private var revision = ""
    private var revisionAt = 0L
    private var fingerprint = ""

    fun reset() { revision = "" }

    fun handle(method: String, extras: Bundle?, view: WebView, window: Window, account: String, eligible: () -> Boolean, reply: (Bundle) -> Unit) {
        fun result(phase: String) = reply(Bundle().apply { putString("state", JSONObject().put("phase", phase).toString()) })
        val observation = script.replace("__ACTION__", "null").replace("__TILES__", "[]").replace("__EXPECTED__", "null").replace("__ACCOUNT__", JSONObject.quote(account))
        view.evaluateJavascript(observation) { raw ->
            if (!eligible()) { result("inactive"); return@evaluateJavascript }
            val state = runCatching { JSONObject(raw) }.getOrNull()
            if (state?.optString("phase") != "challenge") {
                note("waiting ${state?.optString("phase") ?: "none"}")
                // A pending action on a settling or vanished puzzle clicked nothing; the assistant re-observes.
                result(if (method == "observe") "waiting" else "stale"); return@evaluateJavascript
            }
            try {
                val current = state.getString("fingerprint")
                if (method != "observe") {
                    // Pixels may differ (fading error text, animations); the puzzle itself must not.
                    val tiles = extras?.getIntArray("tiles") ?: intArrayOf()
                    val action = extras?.getString("action")
                    if (extras?.getString("revision") != revision || current != fingerprint ||
                        SystemClock.elapsedRealtime() - revisionAt > REVISION_TTL_MS || !eligible()) { result("stale"); return@evaluateJavascript }
                    require(action in setOf("tiles", "verify", "reload") && tiles.size <= 16 && (action == "tiles" || tiles.isEmpty()))
                    // Consume before the effect. An ambiguous Binder response cannot replay this observation.
                    revision = ""
                    val command = script.replace("__ACTION__", JSONObject.quote(action)).replace("__TILES__", JSONArray(tiles.toList()).toString()).replace("__EXPECTED__", JSONObject.quote(current)).replace("__ACCOUNT__", JSONObject.quote(account))
                    view.evaluateJavascript(command) { actionResult ->
                        val phase = runCatching { JSONObject(actionResult).getString("phase") }.getOrDefault("unknown")
                        result(phase.takeIf { it in setOf("acted", "stale", "refused") } ?: "unknown")
                    }
                    return@evaluateJavascript
                }
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
                val tileRects = state.optJSONArray("tileRects")
                capture(view, window, left, top, width, height) { bitmap ->
                    if (bitmap == null || !eligible()) { note("unavailable capture"); result("unavailable"); return@capture }
                    try {
                        val canvas = Canvas(bitmap); canvas.translate(-left.toFloat(), -top.toFloat())
                        label(canvas, tileRects, scale)
                        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray(); bitmap.recycle()
                        require(bytes.size in 1..512 * 1024)
                        val fresh = MessageDigest.getInstance("SHA-256").digest(bytes + current.toByteArray()).joinToString("") { "%02x".format(it) }
                        note("challenge")
                        revision = fresh; revisionAt = SystemClock.elapsedRealtime(); fingerprint = current
                        reply(Bundle().apply {
                            putString("state", JSONObject().put("phase", "challenge").put("revision", fresh).put("tileCount", state.getInt("tileCount")).put("selected", state.getJSONArray("selected")).toString())
                            putByteArray("image", bytes)
                        })
                    } catch (e: Exception) { note("unavailable ${e.javaClass.simpleName}"); result("unavailable") }
                }
            } catch (e: Exception) { note("unavailable ${e.javaClass.simpleName}: ${e.message?.take(120)}"); result("unavailable") }
        }
    }

    /**
     * Copies the composited pixels of the puzzle. Drawing the WebView into an offscreen canvas skips GPU tiles it has
     * not rasterized, which left whole tile columns blank in what the assistant saw.
     */
    private fun capture(view: WebView, window: Window, left: Int, top: Int, width: Int, height: Int, done: (Bitmap?) -> Unit) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (Build.VERSION.SDK_INT < 26) {
            val canvas = Canvas(bitmap); canvas.translate(-left.toFloat(), -top.toFloat()); view.draw(canvas); done(bitmap); return
        }
        val origin = IntArray(2).also { view.getLocationInWindow(it) }
        val source = android.graphics.Rect(origin[0] + left, origin[1] + top, origin[0] + left + width, origin[1] + top + height)
        try {
            android.view.PixelCopy.request(window, source, bitmap, { code ->
                if (code == android.view.PixelCopy.SUCCESS) done(bitmap) else { bitmap.recycle(); done(null) }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (_: Exception) { bitmap.recycle(); done(null) }
    }

    /** Prints each tile's zero-based index in its top-left corner so the model reads positions instead of counting. */
    private fun label(canvas: Canvas, rects: JSONArray?, scale: Double) {
        if (rects == null) return
        val size = (14 * density).coerceAtLeast(18f)
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE; textSize = size; typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val badge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6D00000.toInt() }
        for (i in 0 until rects.length()) {
            val r = rects.optJSONArray(i) ?: continue
            val x = (r.getDouble(0) * scale).toFloat() + size * 0.15f; val y = (r.getDouble(1) * scale).toFloat() + size * 0.15f
            val w = size * (if (i >= 10) 1.5f else 1.1f); val h = size * 1.2f
            canvas.drawRoundRect(x, y, x + w, y + h, size * 0.25f, size * 0.25f, badge)
            canvas.drawText(i.toString(), x + w / 2, y + h * 0.8f, text)
        }
    }

    private companion object {
        const val REVISION_TTL_MS = 60_000L
    }
}
