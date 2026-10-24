/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
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

/** Setup-wizard permission protected; debug builds also permit shell. Uses the same retained session as Nearby. */
class MigrationWirelessActivity : Activity() {
    private var controller: DctController? = null
    private lateinit var status: TextView
    private var launched = false
    private val listener = object : DctController.Listener {
        override fun advertising(status: Int) { runOnUiThread { showQr() } }
        override fun connected(peer: DeviceInfoResponse) { runOnUiThread { status.text = "Authenticated iPhone connection. Negotiating Wi-Fi Aware…" } }
        override fun bandwidth(medium: Int, status: Int) { runOnUiThread {
            if (status == 1) launchImporter()
        } }
        override fun closed(status: Int) { runOnUiThread { this@MigrationWirelessActivity.status.text = "Connection ended (status $status)." } }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        launched = state?.getBoolean("importer_launched") ?: false
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        status = TextView(this).apply { text = "Starting wireless iPhone import…" }
        setContentView(status)
        val existing = DctSessions.retained()
        if (launched && existing == null) { setResult(RESULT_CANCELED); finish(); return }
        controller = existing ?: DctSessions.create(this)
        if (!launched) {
            // GmsCore also provides Wi-Fi network location, so its global Wi-Fi permission cannot assert neverForLocation.
            val required = mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) required.addAll(listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
            if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) startSession() else requestPermissions(missing.toTypedArray(), 700)
        }
    }
    private fun startSession() { controller?.also { it.add(listener); if (!it.advertised) it.start() } }
    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(request, permissions, grants)
        if (request == 700) {
            if (grants.isNotEmpty() && grants.all { it == PackageManager.PERMISSION_GRANTED }) startSession()
            else { status.text = "Allow Nearby devices and Location to connect your iPhone."; controller?.close() }
        }
    }
    private fun launchImporter() {
        if (launched || isFinishing) return
        try {
            startActivityForResult(MigrationSwitchImport.intent(intent), 701)
            launched = true
            // Switch becomes the terminal listener; its Binder death must close the retained cluster.
            controller?.remove(listener)
            android.util.Log.i("GmsDct", "Switch ResumeWifiD2dRestore importer launched")
        } catch (e: Exception) {
            status.text = "Android Switch could not open (${e.javaClass.simpleName})."
            controller?.close()
        }
    }
    override fun onSaveInstanceState(state: Bundle) { state.putBoolean("importer_launched", launched); super.onSaveInstanceState(state) }
    override fun onActivityResult(request: Int, result: Int, data: android.content.Intent?) {
        super.onActivityResult(request, result, data)
        if (request == 701) { controller?.close(); setResult(result, data); finish() }
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
            // status was the content view, so it still carries MATCH_PARENT height and would hide the QR.
            addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)); addView(ImageView(this@MigrationWirelessActivity).apply { setImageBitmap(bitmap) })
            addView(Button(this@MigrationWirelessActivity).apply { text = "Cancel"; setOnClickListener { controller?.close(); finish() } })
        })
    }
    override fun onBackPressed() { controller?.close(); super.onBackPressed() }
    override fun onDestroy() { controller?.remove(listener); if (isFinishing) controller?.close(); super.onDestroy() }
}
