/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.ParcelUuid
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Owns only its listeners/advertisements. Never enables radios, renames the adapter,
 * changes a saved network or tears down somebody else's P2P group. */
@SuppressLint("MissingPermission")
internal class ClassicRadio(private val context: Context, private val service: String,
    private val localId: String, private val info: ByteArray, private val pcp: Int,
    private val incoming: (Link) -> Unit, private val found: (Peer) -> Unit,
    private val lost: (String) -> Unit, private val failure: (Int) -> Unit) : Closeable {
    class Link(val input: InputStream, val output: OutputStream, val medium: Int, private val shutdown: () -> Unit) : Closeable { override fun close() = shutdown() }
    data class Peer(val endpoint: ClassicDiscoveryCodec.Endpoint, val address: InetAddress? = null, val port: Int = 0, val bluetooth: String? = null)
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val workers = Executors.newFixedThreadPool(3)
    private val closed = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var bluetoothServer: android.bluetooth.BluetoothServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var advertiser: AdvertiseCallback? = null
    private var scanner: ScanCallback? = null
    private val peers = ConcurrentHashMap<String,String>()
    private val sockets = ConcurrentHashMap.newKeySet<Closeable>()
    val port get() = server?.localPort ?: 0
    fun advertiseLan(ready: (Int) -> Unit) {
        check(!closed.get() && server == null)
        val socket = ServerSocket(0).also { server = it }
        workers.execute { try { while (!closed.get()) { val accepted = socket.accept().apply { soTimeout = 30000 }; sockets.add(accepted); incoming(link(accepted)) } } catch (_: Exception) { if (!closed.get() && server === socket) failure(8012) } }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) { if (!closed.get()) ready(0) }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { ready(8000); stopAdvertising() }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registration = listener
        try {
            nsd.registerService(NsdServiceInfo().apply { serviceName = ClassicDiscoveryCodec.lanName(service,localId,info,pcp); serviceType = ClassicDiscoveryCodec.serviceType(service); port = socket.localPort; setAttribute("n",ClassicDiscoveryCodec.lanInfo(info)) }, NsdManager.PROTOCOL_DNS_SD,listener)
        } catch (e: Exception) { stopAdvertising(); ready(8000) }
    }
    fun discoverLan(ready: (Int) -> Unit) {
        check(!closed.get() && discovery == null)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { ready(0) }
            override fun onStartDiscoveryFailed(type: String, code: Int) { ready(8000) }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) { peers.remove(serviceInfo.serviceName)?.let(lost) }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (closed.get() || serviceInfo.serviceType.trimEnd('.') != ClassicDiscoveryCodec.serviceType(service).trimEnd('.')) return
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, code: Int) {}
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        if (closed.get()) return
                        runCatching { val ep = ClassicDiscoveryCodec.parseLan(service,resolved.serviceName,resolved.attributes["n"])
                            if (peers.size<256 && ep.id != localId && ep.pcp == pcp && resolved.port in 1..65535) { peers[resolved.serviceName] = ep.id; found(Peer(ep,resolved.host,resolved.port)) } }
                    }
                })
            }
        }
        discovery = listener
        try { nsd.discoverServices(ClassicDiscoveryCodec.serviceType(service),NsdManager.PROTOCOL_DNS_SD,listener) } catch (_: Exception) { discovery = null; ready(8000) }
    }
    fun listenBluetooth(): Boolean = try {
        val bt = requireNotNull(adapter).also { check(it.isEnabled) }
        val socket = bt.listenUsingInsecureRfcommWithServiceRecord(service,ClassicDiscoveryCodec.bluetoothUuid(service)); bluetoothServer = socket
        workers.execute { try { while (!closed.get()) { val accepted = socket.accept(); sockets.add(accepted); incoming(Link(accepted.inputStream,accepted.outputStream,2) { sockets.remove(accepted); accepted.close() }) } } catch (_: Exception) { if (!closed.get() && bluetoothServer === socket) failure(8012) } }
        true
    } catch (_: Exception) { false }
    private val ble by lazy { ClassicBleRadio(context,service,localId,info,pcp,found) }
    fun advertiseBle(uuid: ParcelUuid?, ready: (Int) -> Unit) = ble.advertise(uuid,ready)
    fun scanBle(uuid: ParcelUuid?, ready: (Int) -> Unit) = ble.scan(uuid,ready)
    fun connect(peer: Peer): Link {
        check(!closed.get())
        if (peer.address != null) { val socket = Socket(); sockets.add(socket); try { socket.connect(InetSocketAddress(peer.address,peer.port),10000); socket.soTimeout = 30000; return link(socket) } catch (e: Exception) { sockets.remove(socket); socket.close(); throw e } }
        val socket = requireNotNull(adapter).getRemoteDevice(requireNotNull(peer.bluetooth)).createInsecureRfcommSocketToServiceRecord(ClassicDiscoveryCodec.bluetoothUuid(service)); sockets.add(socket)
        try { socket.connect(); return Link(socket.inputStream,socket.outputStream,2) { sockets.remove(socket); socket.close() } } catch (e: Exception) { sockets.remove(socket); socket.close(); throw e }
    }
    private fun link(socket: Socket) = Link(socket.getInputStream(),socket.getOutputStream(),5) { sockets.remove(socket); socket.close() }
    fun stopAdvertising() { ble.stopAdvertising(); registration?.let { runCatching { nsd.unregisterService(it) } }; registration = null; val old = server; server = null; runCatching { old?.close() }; val bt = bluetoothServer; bluetoothServer = null; runCatching { bt?.close() }; advertiser?.let { runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }; advertiser = null }
    fun stopDiscovery() { ble.stopDiscovery(); discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discovery = null; scanner?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }; scanner = null; peers.clear() }
    override fun close() { if (!closed.compareAndSet(false,true)) return; stopAdvertising(); stopDiscovery(); sockets.toList().forEach { runCatching { it.close() } }; sockets.clear(); workers.shutdownNow() }
}
