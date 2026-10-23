/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Network
import android.graphics.Bitmap
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.util.Log
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.microg.gms.migration.ble.DctAdvertiser
import org.microg.gms.migration.ble.DctBluetoothNetwork
import org.microg.gms.migration.security.PlatformPakeTls
import org.microg.gms.migration.session.DctSession
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Bounded local TLS test. No radio, accounts, settings, Google server or migration data touched. */
class MigrationLabActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var radio: DctBluetoothNetwork? = null
    private var advertising: DctAdvertiser? = null
    private var rawServer: BluetoothServerSocket? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.TYPE == "user") { finish(); return }
        if (intent.getStringExtra("mode") == "ble") { startRadioProbe(); return }
        if (intent.getStringExtra("mode") == "raw-ble") { startRawCocProbe(); return }
        if (intent.getStringExtra("mode") == "session") { showSession(DctSession.create()); return }
        setContentView(TextView(this).apply { text = "Checking local DCT PAKE TLS…" })
        Thread {
            val result = runCatching {
                val session = DctSession.create()
                val tls = PlatformPakeTls.create(session.sessionId, session.password)
                val executor = Executors.newSingleThreadExecutor()
                try {
                    (tls.server.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket).use { server ->
                        server.soTimeout = 5000
                        val accepted = executor.submit<Boolean> {
                            (server.accept() as SSLSocket).use { socket ->
                                socket.soTimeout = 5000
                                socket.startHandshake()
                                val value = socket.inputStream.read()
                                socket.outputStream.write(value + 1)
                                socket.outputStream.flush()
                                value == 41 && socket.session.protocol == "TLSv1.3"
                            }
                        }
                        (tls.client.socketFactory.createSocket(InetAddress.getLoopbackAddress(), server.localPort) as SSLSocket).use { socket ->
                            socket.soTimeout = 5000
                            socket.startHandshake()
                            socket.outputStream.write(41)
                            socket.outputStream.flush()
                            check(socket.inputStream.read() == 42 && accepted.get(6, TimeUnit.SECONDS))
                            check(socket.session.protocol == "TLSv1.3")
                        }
                    }
                } finally { executor.shutdownNow() }
                "PASS platform PAKE TLS1.3 loopback"
            }.getOrElse { "BLOCKED platform PAKE: ${it.javaClass.simpleName}; cause=${it.cause?.javaClass?.simpleName}" }
            Log.i("GmsSwitchLab", result)
            runOnUiThread { finish() }
        }.start()
    }

    private fun showSession(session: DctSession) {
        val matrix = QRCodeWriter().encode(session.qrUri, BarcodeFormat.QR_CODE, 640, 640)
        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        for (y in 0 until 640) for (x in 0 until 640) bitmap.setPixel(x, y, if (matrix[x, y]) -0x1000000 else -1)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MigrationLabActivity).apply {
                text = "DCT test session\nRadio and data transfer are not started.\nPairing password: ${session.password}"
            })
            addView(ImageView(this@MigrationLabActivity).apply { setImageBitmap(bitmap) })
        })
    }

    private fun startRadioProbe() {
        setContentView(TextView(this).apply { text = "Testing DCT advertisement for 15 seconds…" })
        val session = DctSession.create()
        val advertisedSession = intent.getStringExtra("test_session") ?: session.sessionId
        handler.postDelayed({ finish() }, 15_000)
        val listener = object : DctBluetoothNetwork.Listener {
            override fun onReserved(psm: Int) {
                Log.i("GmsSwitchLab", "PASS Bluetooth IPv6 PSM reserved: $psm")
                runCatching {
                    advertising = DctAdvertiser(this@MigrationLabActivity).also {
                        it.start(advertisedSession, psm) { result -> Log.i("GmsSwitchLab", "DCT advertise result=$result") }
                    }
                }.onFailure { Log.i("GmsSwitchLab", "BLOCKED advertising: ${it.javaClass.simpleName}"); finish() }
            }
            override fun onAvailable(network: Network) { Log.i("GmsSwitchLab", "Bluetooth IPv6 network available") }
            override fun onClosed() { Log.i("GmsSwitchLab", "Bluetooth IPv6 reservation closed") }
        }
        runCatching {
            radio = DctBluetoothNetwork(this, listener).also { it.reserve() }
        }.onFailure {
            Log.i("GmsSwitchLab", "BLOCKED Bluetooth IPv6: ${it.javaClass.simpleName}; cause=${it.cause?.javaClass?.simpleName}")
            finish()
        }
    }

    /** Mechanics probe only: a raw CoC is NOT the migration IPv6 network. */
    private fun startRawCocProbe() {
        setContentView(TextView(this).apply { text = "Testing raw BLE CoC mechanics only (15 seconds)…\nThis is not a migration channel." })
        handler.postDelayed({ finish() }, 15_000)
        runCatching {
            val adapter = getSystemService(BluetoothManager::class.java).adapter
            val server = adapter.listenUsingInsecureL2capChannel()
            rawServer = server
            advertising = DctAdvertiser(this).also { advertiser ->
                advertiser.start(intent.getStringExtra("test_session") ?: "abc234", server.psm) { result ->
                    Log.i("GmsSwitchLab", "Raw-CoC FC73 advertise result=$result; PSM=${server.psm}; IPv6 not exercised")
                }
            }
            Thread {
                val result = runCatching {
                    server.accept(10_000).use { /* open/close only; no data or authentication claimed */ }
                    "PASS raw BLE CoC accepted and closed; IPv6 not exercised"
                }.getOrElse { "BLOCKED raw BLE CoC peer: ${it.javaClass.simpleName}" }
                Log.i("GmsSwitchLab", result)
                runOnUiThread { finish() }
            }.start()
        }.onFailure { Log.i("GmsSwitchLab", "BLOCKED raw BLE CoC: ${it.javaClass.simpleName}"); finish() }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        advertising?.close()
        runCatching { rawServer?.close() }
        radio?.close()
        super.onDestroy()
    }
}
