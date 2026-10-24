/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.channel

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.net.Inet6Address
import java.util.concurrent.Executor

/** Resolve only the session's DCT service on the Bluetooth network, preserving IPv6 scope. */
internal class DctPeerDiscovery(context: Context, private val network: Network, private val sessionId: String,
                                private val listener: Listener) : Closeable {
    interface Listener {
        fun onResolved(network: Network, address: Inet6Address, port: Int)
        fun onFailure()
    }
    private val contextConnectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
    private val manager = context.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    private var discovering = false
    private var resolving = false
    private var closed = false
    private val timeout = Runnable { fail() }
    private val resolution = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            synchronized(this@DctPeerDiscovery) { resolving = false; fail() }
        }
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            synchronized(this@DctPeerDiscovery) {
                resolving = false
                if (closed || serviceInfo.serviceName != sessionId || serviceInfo.network != network) return
                var address = serviceInfo.hostAddresses.filterIsInstance<Inet6Address>()
                    .firstOrNull { !it.isAnyLocalAddress && !it.isMulticastAddress } ?: run { fail(); return }
                if (address.isLinkLocalAddress && address.scopeId == 0) {
                    val name = contextConnectivity.getLinkProperties(network)?.interfaceName ?: run { fail(); return }
                    val iface = java.net.NetworkInterface.getByName(name) ?: run { fail(); return }
                    address = Inet6Address.getByAddress(null, address.address, iface)
                }
                if (serviceInfo.port !in 1..65535) { fail(); return }
                close()
                listener.onResolved(network, address, serviceInfo.port)
            }
        }
    }
    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = Unit
        override fun onDiscoveryStopped(type: String) = Unit
        override fun onServiceLost(info: NsdServiceInfo) = Unit
        override fun onStartDiscoveryFailed(type: String, error: Int) { fail() }
        override fun onStopDiscoveryFailed(type: String, error: Int) { fail() }
        override fun onServiceFound(info: NsdServiceInfo) {
            synchronized(this@DctPeerDiscovery) {
                if (closed || resolving || info.serviceName != sessionId) return
                // Never let resolution fall back to the default network.
                info.network = network
                resolving = true
                try { manager.resolveService(info, executor, resolution) }
                catch (e: Exception) { resolving = false; fail() }
            }
        }
    }
    @Synchronized fun start() {
        require(Build.VERSION.SDK_INT >= 34 && sessionId.length == 6)
        check(!discovering && !closed)
        discovering = true
        handler.postDelayed(timeout, 30_000)
        try { manager.discoverServices("_dct._tcp", NsdManager.PROTOCOL_DNS_SD, network, executor, discovery) }
        catch (e: Exception) { close(); throw e }
    }
    private fun fail() { synchronized(this) { if (!closed) { close(); listener.onFailure() } } }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(timeout)
        if (resolving) runCatching { manager.stopServiceResolution(resolution) }
        if (discovering) runCatching { manager.stopServiceDiscovery(discovery) }
        resolving = false
        discovering = false
    }
}
