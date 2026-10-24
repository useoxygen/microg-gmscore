/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.microg.gms.migration.controller.DctController
import org.microg.gms.migration.dct.DeviceInfoResponse
import org.microg.gms.migration.session.DctSessions

/** Debug-only, shell/setup-wizard permission protected. Uses the same retained session as Nearby. */
class MigrationWirelessActivity : Activity() {
    private var controller: DctController? = null
    private lateinit var status: TextView
    private val listener = object : DctController.Listener {
        override fun advertising(status: Int) { runOnUiThread { showQr() } }
        override fun connected(peer: DeviceInfoResponse) { runOnUiThread { status.text = "Authenticated iPhone connection. Negotiating Wi-Fi Aware…" } }
        override fun bandwidth(medium: Int, status: Int) { runOnUiThread {
            this@MigrationWirelessActivity.status.text = "Wi-Fi Aware connected. Session retained for Android Switch.\nSwitch onboarding launch still requires validation."
            setResult(200)
        } }
        override fun closed(status: Int) { runOnUiThread { this@MigrationWirelessActivity.status.text = "Connection ended (status $status)." } }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (Build.TYPE == "user") { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        status = TextView(this).apply { text = "Starting wireless iPhone import…" }
        setContentView(status)
        controller = DctSessions.retained() ?: DctSessions.create(this)
        requireNotNull(controller).also { it.add(listener); if (!it.advertised) it.start() }
    }
    private fun showQr() {
        val session = controller?.session ?: return
        val matrix = QRCodeWriter().encode(session.qrUri, BarcodeFormat.QR_CODE, 640, 640)
        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        for (y in 0 until 640) for (x in 0 until 640) bitmap.setPixel(x, y, if (matrix[x, y]) -0x1000000 else -1)
        (status.parent as? android.view.ViewGroup)?.removeView(status)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            status.text = "On iPhone (iOS 26.3+), open Transfer to Android and scan.\nPairing password: ${session.password}"
            addView(status); addView(ImageView(this@MigrationWirelessActivity).apply { setImageBitmap(bitmap) })
            addView(Button(this@MigrationWirelessActivity).apply { text = "Cancel"; setOnClickListener { controller?.close(); finish() } })
        })
    }
    override fun onBackPressed() { controller?.close(); super.onBackPressed() }
    override fun onDestroy() { controller?.remove(listener); if (isFinishing && controller?.upgraded != true) controller?.close(); super.onDestroy() }
}
