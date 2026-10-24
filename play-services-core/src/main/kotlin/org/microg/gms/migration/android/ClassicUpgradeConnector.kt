/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import android.annotation.SuppressLint
import android.content.Context
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame.UpgradePathInfo
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Joins a peer-offered upgrade using actual OS callbacks. Requests are transient,
 * do not save Wi-Fi passwords, select a default network or remove an existing P2P
 * group. Credentials never enter logs. An unsupported medium fails explicitly. */
@SuppressLint("MissingPermission")
internal class ClassicUpgradeConnector(private val context: Context) {
    fun connect(path: UpgradePathInfo): ClassicRadio.Link = when(path.medium) {
        UpgradePathInfo.Medium.WIFI_LAN -> {
            val lan=path.wifiLanSocket
            val candidates=if(lan.addressCandidatesCount>0) lan.addressCandidatesList.map { it.ipAddress.toByteArray() to it.port } else listOf(lan.ipAddress.toByteArray() to lan.wifiPort)
            connectCandidates(candidates,5,null,{})
        }
        UpgradePathInfo.Medium.WIFI_HOTSPOT -> hotspot(path)
        UpgradePathInfo.Medium.WIFI_DIRECT -> direct(path)
        else -> throw UnsupportedOperationException("Unsupported migration upgrade medium")
    }
    private fun connectCandidates(addresses: List<Pair<ByteArray,Int>>, medium: Int, network: Network?, cleanup: () -> Unit): ClassicRadio.Link {
        require(addresses.size in 1..8)
        var error: Exception?=null
        for((bytes,port) in addresses) {
            try {
                require(bytes.size == 4 || bytes.size == 16); require(port in 1..65535)
                val address=InetAddress.getByAddress(bytes)
                require(!address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isMulticastAddress)
                if(medium==5)require(!address.isLinkLocalAddress)
                val socket=network?.socketFactory?.createSocket() ?: Socket()
                try { socket.connect(InetSocketAddress(address,port),5000); socket.soTimeout=30000; return ClassicRadio.Link(socket.getInputStream(),socket.getOutputStream(),medium) { try { socket.close() } finally { cleanup() } } }
                catch(e: Exception) { socket.close(); throw e }
            } catch(e: Exception) { error=e }
        }
        cleanup(); throw java.io.IOException("No reachable peer upgrade address",error)
    }
    private fun hotspot(path: UpgradePathInfo): ClassicRadio.Link {
        check(Build.VERSION.SDK_INT>=29)
        val credentials=path.wifiHotspotCredentials
        require(credentials.ssid.length in 1..32 && credentials.password.length in 8..63)
        val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val future=CompletableFuture<Network>(); val retired=AtomicBoolean(false)
        val callback=object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { if(!retired.get())future.complete(network) }
            override fun onUnavailable() { future.completeExceptionally(java.io.IOException("Peer hotspot unavailable")) }
            override fun onLost(network: Network) { future.completeExceptionally(java.io.IOException("Peer hotspot lost")) }
        }
        val cleanup={ if(retired.compareAndSet(false,true))runCatching { manager.unregisterNetworkCallback(callback) }; Unit }
        try {
            val specifier=WifiNetworkSpecifier.Builder().setSsid(credentials.ssid).setWpa2Passphrase(credentials.password).build()
            val request=NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build()
            manager.requestNetwork(request,callback,15000)
            val network=future.get(16,TimeUnit.SECONDS)
            val candidates=if(credentials.addressCandidatesCount>0) credentials.addressCandidatesList.map { it.ipAddress.toByteArray() to it.port }
                else listOf(InetAddress.getByName(credentials.gateway).address to credentials.port)
            return connectCandidates(candidates,3,network,cleanup)
        } catch(e: Exception) { cleanup(); throw e }
    }
    private fun direct(path: UpgradePathInfo): ClassicRadio.Link {
        check(Build.VERSION.SDK_INT>=29)
        val credentials=path.wifiDirectCredentials
        require(credentials.ssid.startsWith("DIRECT-") && credentials.ssid.length<=32 && credentials.password.length in 8..63 && credentials.port in 1..65535)
        val manager=context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: throw UnsupportedOperationException("No Wi-Fi Direct")
        val channel=manager.initialize(context,Looper.getMainLooper(),null)
        try {
            val group=CompletableFuture<android.net.wifi.p2p.WifiP2pGroup?>()
            manager.requestGroupInfo(channel) { group.complete(it) }
            check(group.get(3,TimeUnit.SECONDS)==null) { "Another Wi-Fi Direct group is active" }
        } catch(e: Exception) { channel.close(); throw e }
        val started=CompletableFuture<Unit>(); val owner=CompletableFuture<InetAddress>(); val retired=AtomicBoolean(false)
        var joinedGroupId: Int?=null
        val handler=Handler(Looper.getMainLooper())
        val cleanup={
            if(retired.compareAndSet(false,true)) {
                manager.requestGroupInfo(channel) { current ->
                    // Reconcile exact group identity; never remove an unrelated/new group.
                    if(current!=null && current.networkName==credentials.ssid && joinedGroupId==current.networkId) manager.removeGroup(channel,object: WifiP2pManager.ActionListener { override fun onSuccess() { channel.close() }; override fun onFailure(reason: Int) { channel.close() } })
                    else channel.close()
                }
            }; Unit
        }
        try {
            val config=WifiP2pConfig.Builder().setNetworkName(credentials.ssid).setPassphrase(credentials.password).enablePersistentMode(false).build()
            manager.connect(channel,config,object: WifiP2pManager.ActionListener { override fun onSuccess() { started.complete(Unit) }; override fun onFailure(reason: Int) { started.completeExceptionally(java.io.IOException("Wi-Fi Direct rejected ($reason)")) } })
            started.get(5,TimeUnit.SECONDS)
            val poll=object: Runnable { override fun run() { if(retired.get() || owner.isDone)return; manager.requestConnectionInfo(channel) { info -> if(info.groupFormed && info.groupOwnerAddress!=null) { manager.requestGroupInfo(channel) { current -> if(current?.networkName==credentials.ssid) { joinedGroupId=current.networkId; owner.complete(info.groupOwnerAddress) } else owner.completeExceptionally(java.io.IOException("Wrong Wi-Fi Direct group")) } } else handler.postDelayed(this,250) } } }
            handler.post(poll)
            val address=owner.get(15,TimeUnit.SECONDS)
            return connectCandidates(listOf(address.address to credentials.port),8,null,cleanup)
        } catch(e: Exception) { cleanup(); throw e }
    }
}
