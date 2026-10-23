/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.upgrade

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.net.Inet6Address
import java.security.SecureRandom

/** Normal PSK data path only. Service name/role/passphrase must come from authenticated DCT negotiation. */
internal class AwareDataPath(context: Context, private val serviceName: String, serviceInfo: ByteArray,
                             private val passphrase: String, private val serverPort: Int?,
                             private val listener: Listener) : Closeable {
    interface Listener {
        fun onReady(network: Network, peer: Inet6Address, peerPort: Int?)
        fun onFailure()
    }
    private val info = serviceInfo.copyOf()
    private val aware = context.getSystemService(WifiAwareManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var session: WifiAwareSession? = null
    private var discovery: DiscoverySession? = null
    private var requested = false
    private var closed = false
    private var ready = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            synchronized(this@AwareDataPath) {
                if (closed || ready) return
                val details = capabilities.transportInfo as? WifiAwareNetworkInfo ?: return
                val address = details.peerIpv6Addr ?: return
                val port = details.port.takeIf { it in 1..65535 }
                if (serverPort == null && port == null) return
                ready = true
                handler.removeCallbacksAndMessages(null)
                listener.onReady(network, address, port)
            }
        }
        override fun onUnavailable() { fail() }
        override fun onLost(network: Network) { fail() }
    }
    private val discoveryCallback = object : DiscoverySessionCallback() {
        override fun onPublishStarted(publish: PublishDiscoverySession) {
            synchronized(this@AwareDataPath) {
                if (closed) { publish.close(); return }
                discovery = publish
                request(WifiAwareNetworkSpecifier.Builder(publish))
            }
        }
        override fun onSubscribeStarted(subscribe: SubscribeDiscoverySession) {
            synchronized(this@AwareDataPath) {
                if (closed) { subscribe.close(); return }
                discovery = subscribe
            }
        }
        override fun onServiceDiscovered(peerHandle: PeerHandle, specificInfo: ByteArray, matchFilter: MutableList<ByteArray>) {
            synchronized(this@AwareDataPath) {
                val active = discovery ?: return
                if (!closed && !requested && specificInfo.contentEquals(info)) {
                    request(WifiAwareNetworkSpecifier.Builder(active, peerHandle))
                }
            }
        }
        override fun onSessionConfigFailed() { fail() }
        override fun onSessionTerminated() { fail() }
    }

    @Synchronized fun start() {
        check(!closed && session == null)
        require(serviceName.isNotBlank() && serviceName.length <= 255)
        require(passphrase.length in 8..63 && passphrase.all { it.code in 32..126 })
        require(serverPort == null || serverPort in 1..65535)
        check(aware != null && aware.isAvailable) { "Wi-Fi Aware is unavailable" }
        handler.postDelayed({ fail() }, 30_000)
        try {
            aware.attach(object : AttachCallback() {
                override fun onAttached(attached: WifiAwareSession) {
                    synchronized(this@AwareDataPath) {
                        if (closed) { attached.close(); return }
                        session = attached
                        try {
                            if (serverPort != null) attached.publish(PublishConfig.Builder().setServiceName(serviceName)
                                .setServiceSpecificInfo(info).setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED).build(),
                                discoveryCallback, handler)
                            else attached.subscribe(SubscribeConfig.Builder().setServiceName(serviceName)
                                .setServiceSpecificInfo(info).setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE).build(),
                                discoveryCallback, handler)
                        } catch (e: Exception) { fail() }
                    }
                }
                override fun onAttachFailed() { fail() }
            }, handler)
        } catch (e: Exception) { close(); throw e }
    }

    private fun request(builder: WifiAwareNetworkSpecifier.Builder) {
        check(!requested)
        try {
            builder.setPskPassphrase(passphrase)
            serverPort?.let { builder.setPort(it).setTransportProtocol(6) }
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(builder.build()).build()
            requested = true
            connectivity.requestNetwork(request, networkCallback, handler, 20_000)
        } catch (e: Exception) { fail() }
    }
    private fun fail() { synchronized(this) { if (!closed) { listener.onFailure(); close() } } }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        if (requested) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        requested = false
        discovery?.close()
        session?.close()
        discovery = null
        session = null
    }
    companion object {
        fun freshPassphrase(random: SecureRandom = SecureRandom()): String =
            ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
