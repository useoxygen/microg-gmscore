/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.ble

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.NetworkSpecifier
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import java.io.Closeable

/** Owns one Android 16+ Bluetooth IPv6/6LoWPAN reservation. Never binds the process default network. */
internal class DctBluetoothNetwork(context: Context, private val listener: Listener) : Closeable {
    interface Listener {
        fun onReserved(psm: Int)
        fun onAvailable(network: Network)
        fun onClosed()
    }
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var registered = false
    private var closed = false
    private val handler = Handler(Looper.getMainLooper())
    private val callback = object : ConnectivityManager.NetworkCallback() {
        // Compiles against SDK 35; the runtime virtual override was added in API 36.
        @Suppress("unused")
        @Keep
        fun onReserved(capabilities: NetworkCapabilities) {
            val specifier = capabilities.networkSpecifier ?: return
            val psm = specifier.javaClass.getMethod("getPsm").invoke(specifier) as Int
            synchronized(this@DctBluetoothNetwork) { if (!closed && psm in 1..65535) listener.onReserved(psm) }
        }
        override fun onAvailable(network: Network) {
            synchronized(this@DctBluetoothNetwork) { if (!closed) listener.onAvailable(network) }
        }
        override fun onLost(network: Network) { close() }
        override fun onUnavailable() { close() }
    }

    @Synchronized fun reserve() {
        check(!registered && !closed)
        val builder = Class.forName("android.net.L2capNetworkSpecifier\$Builder")
        val specifier = builder.getConstructor().newInstance()
        builder.getMethod("setRole", Int::class.javaPrimitiveType).invoke(specifier, 2)
        builder.getMethod("setHeaderCompression", Int::class.javaPrimitiveType).invoke(specifier, 2)
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH)
            .setNetworkSpecifier(builder.getMethod("build").invoke(specifier) as NetworkSpecifier)
            .removeCapability(14).removeCapability(13).removeCapability(37).build()
        // Set before registration so immediate/reentrant callbacks and close cannot leak the reservation.
        registered = true
        try {
            ConnectivityManager::class.java.getMethod("reserveNetwork", NetworkRequest::class.java,
                Handler::class.java, ConnectivityManager.NetworkCallback::class.java)
                .invoke(connectivity, request, handler, callback)
        } catch (t: Exception) { close(); throw t }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) }
        registered = false
        listener.onClosed()
    }
}
