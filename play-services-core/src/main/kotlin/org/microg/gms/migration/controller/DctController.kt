/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.controller

import android.content.Context
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.microg.gms.migration.ble.DctAdvertiser
import org.microg.gms.migration.ble.DctBluetoothNetwork
import org.microg.gms.migration.channel.DctPeerDiscovery
import org.microg.gms.migration.dct.*
import org.microg.gms.migration.payload.*
import org.microg.gms.migration.security.DctIntegrity
import org.microg.gms.migration.security.PlatformPakeTls
import org.microg.gms.migration.session.DctSession
import org.microg.gms.migration.upgrade.AwareDataPath
import java.io.Closeable
import java.net.Inet6Address
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One ephemeral import cluster, shared only with authenticated setup/Switch clients. */
internal class DctController(private val context: Context, val session: DctSession, private val serviceId: String = "quickStart", private val remote: Pair<String, Int>? = null, private val supportedServices: Int = 2) : Closeable {
    interface Listener {
        fun advertising(status: Int) {}
        fun connected(peer: DeviceInfoResponse) {}
        fun bandwidth(medium: Int, status: Int) {}
        fun oneWay(type: String, bytes: ByteArray): Boolean = false
        fun request(request: DctHttpRequest): DctHttpReply? = null
        fun closed(status: Int) {}
    }
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val incomingLock = Any()
    private val buffered = java.util.ArrayDeque<Pair<String, ByteArray>>()
    private var bufferedBytes = 0
    private val applicationOwner = java.util.concurrent.CompletableFuture<Listener>()
    private var applicationListener: Listener? = null
    private var draining = false
    internal fun oneWay(type: String, bytes: ByteArray) {
        if (isClosed) return
        if (org.microg.gms.esim.EsimDctRouter.oneWay(this, type, bytes)) return
        var overflow = false
        synchronized(incomingLock) {
            if (stopped.get()) return
            if (buffered.size >= 8 || bytes.size > 16 * 1024 * 1024 - bufferedBytes) overflow = true
            else { buffered.add(type to bytes.copyOf()); bufferedBytes += bytes.size }
        }
        if (overflow) fail(8013) else drainIncoming()
    }
    fun applicationReady(listener: Listener) {
        synchronized(incomingLock) { if (stopped.get()) return; applicationListener = listener }
        applicationOwner.complete(listener)
        drainIncoming()
    }
    private fun drainIncoming() {
        synchronized(incomingLock) { if (draining || applicationListener == null || stopped.get()) return; draining = true }
        while (true) {
            val next = synchronized(incomingLock) {
                val listener = applicationListener
                val message = buffered.poll()
                if (stopped.get() || listener == null || message == null) { draining = false; return }
                bufferedBytes -= message.second.size
                listener to message
            }
            try {
                if (!next.first.oneWay(next.second.first, next.second.second)) {
                    synchronized(incomingLock) { buffered.addFirst(next.second); bufferedBytes += next.second.second.size; applicationListener = null; draining = false }
                    return
                }
            } catch (e: Exception) { fail(8013); return }
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val channels = CopyOnWriteArrayList<DctControlChannel>()
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private var radio: DctBluetoothNetwork? = null
    private var advertiser: DctAdvertiser? = null
    private var discovery: DctPeerDiscovery? = null
    private var aware: AwareDataPath? = null
    private var server: DctHttpServer? = null
    @Volatile var publisher: DctServerControl? = null; private set
    private val upgradeStarted = AtomicBoolean(false)
    private val publisherProof = AtomicBoolean(false)
    @Volatile private var control: DctControlChannel? = null
    @Volatile var http: DctHttpChannel? = null; private set
    @Volatile var peer: DeviceInfoResponse? = null; private set
    @Volatile var advertised = false; private set
    @Volatile var upgraded = false; private set
    val isClosed get() = stopped.get()
    private val expires = Runnable { fail(8005) }
    fun add(listener: Listener) {
        listeners.add(listener)
        if (advertised) listener.advertising(0)
        peer?.let { listener.connected(it) }
        if (upgraded) listener.bandwidth(6, 1)
    }
    fun remove(listener: Listener) { listeners.remove(listener) }
    fun release(listener: Listener) { listeners.remove(listener); if (listeners.isEmpty()) close() }
    fun claimActive() { main.removeCallbacks(expires) }
    fun start() { main.post {
        if (stopped.get() || !started.compareAndSet(false, true)) return@post
        main.postDelayed(expires, 10 * 60_000L)
        try {
            radio = DctBluetoothNetwork(context, object : DctBluetoothNetwork.Listener {
                override fun onReserved(psm: Int) {
                    android.util.Log.i("GmsDct", "Bluetooth IPv6 reservation ready")
                    if (stopped.get()) return
                    try { advertiser = DctAdvertiser(context).also { it.start(session.sessionId, psm, supportedServices) { status ->
                        if (status != 0) fail(8000) else if (!stopped.get()) {
                            advertised = true; listeners.forEach { it.advertising(0) }
                        }
                    } } } catch (e: Exception) { fail(8000) }
                }
                override fun onAvailable(network: Network) {
                    android.util.Log.i("GmsDct", "Bluetooth IPv6 network available; starting scoped discovery")
                    if (stopped.get() || discovery != null) return
                    val link = context.getSystemService(android.net.ConnectivityManager::class.java).getLinkProperties(network)
                    android.util.Log.i("GmsDct", "Bluetooth link diagnostics: interface=${link?.interfaceName} linkLocal=${link?.linkAddresses?.any { it.address.isLinkLocalAddress }} ipv6Route=${link?.routes?.any { it.destination.address is Inet6Address }}")
                    try { discovery = DctPeerDiscovery(context, network, session.sessionId, object : DctPeerDiscovery.Listener {
                        override fun onResolved(network: Network, address: Inet6Address, port: Int) { android.util.Log.i("GmsDct", "Scoped DCT service resolved; linkLocal=${address.isLinkLocalAddress} scoped=${address.scopeId != 0}"); connect(network, address, port) }
                        override fun onFailure() { fail(8012) }
                    }).also { it.start() } } catch (e: Exception) { fail(8012) }
                }
                override fun onClosed() { if (!stopped.get() && !upgraded) fail(8000) }
            }).also { it.reserve(remote) }
        } catch (e: Exception) { fail(8000) }
    } }
    private fun connect(network: Network, address: Inet6Address, port: Int) { worker.execute {
        if (stopped.get()) return@execute
        try {
            val tls = PlatformPakeTls.create(session.sessionId, session.password)
            val channel = DctHttpChannel(network, address, port, tls)
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val controls = DctControlChannel(channel, Build.MODEL, Build.MANUFACTURER, salt,
                ::oneWay, { status -> if (!upgraded) fail(status) }, { request -> publish(request, tls) })
            synchronized(this) { if (stopped.get()) { controls.close(); return@execute }; http = channel; control = controls; channels.add(controls) }
            controls.start()
            if (org.microg.gms.checkin.LastCheckinInfo.read(context).androidId == 0L) {
                android.util.Log.i("GmsDct", "Google checkin prerequisite started")
                try { org.microg.gms.checkin.CheckinManager.checkin(context, false) } catch (e: Exception) { throw DctFailure(8018) }
                if (org.microg.gms.checkin.LastCheckinInfo.read(context).androidId == 0L) throw DctFailure(8018)
            }
            val info = DctHandshake(controls, Build.MANUFACTURER, Build.MODEL, stage = { android.util.Log.i("GmsDct", it) }, token = DctIntegrity(context)::token).run(salt)
            if (stopped.get()) return@execute
            android.util.Log.i("GmsDct", "PAKE/control/capability/integrity handshake complete")
            peer = info
            listeners.forEach { it.connected(info) }
            promotePublisher()
            upgrade(controls, tls, salt)
        } catch (e: Exception) { fail(if (e is DctFailure) e.status else if (e is javax.net.ssl.SSLHandshakeException) 8016 else 8012) }
    } }
    private fun upgrade(controls: DctControlChannel, tls: PlatformPakeTls, salt: String) {
        if (!upgradeStarted.compareAndSet(false, true)) return
        val response = controls.exchange(DctControlFrame(medium_negotiation_request = WirelessMediumNegotiationRequest(
            wifi_aware = WifiAwareRequest(field1 = okio.ByteString.of(0, 0, 0)))), 4).medium_negotiation_response?.wifi_aware
            ?: throw DctFailure(8019)
        // supports_r4 advertises peer capability, not a requirement to use pairing. We use normal PSK only.
        if (response.passphrase.length !in 8..63) throw DctFailure(8019)
        val info = "${session.sessionId}._dct._tcp.local"
        val name = android.util.Base64.encodeToString(serviceId.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING).replace('_', '.')
        main.post {
            if (stopped.get()) return@post
            try { aware = AwareDataPath(context, name, info.toByteArray(), response.passphrase, null, object : AwareDataPath.Listener {
                override fun onReady(network: Network, peer: Inet6Address, peerPort: Int?) { worker.execute {
                    if (stopped.get()) return@execute
                    try {
                        val next = DctHttpChannel(network, peer, requireNotNull(peerPort), tls)
                        try {
                            // The first successful PAKE-authenticated control response proves this network usable.
                            next.control(DctOfflineFrame(control = DctControlFrame(device_info_request = DeviceInfoRequest(
                                device_model = Build.MODEL, platform = 1, manufacturer = Build.MANUFACTURER))).encode()).execute().use {
                                val frame = DctOfflineFrame.ADAPTER.decode(DctControlChannel.bounded(it))
                                check(frame.control?.device_info_response != null)
                            }
                            val nextControl = DctControlChannel(next, Build.MODEL, Build.MANUFACTURER, salt, ::oneWay, ::fail)
                            synchronized(this@DctController) {
                                if (stopped.get()) { nextControl.close(); return@execute }
                                http = next; control = nextControl; channels.add(nextControl); upgraded = true
                            }
                            android.util.Log.i("GmsDct", "Authenticated Wi-Fi Aware channel ready")
                            nextControl.start()
                            listeners.forEach { it.bandwidth(6, 1) }
                            runCatching { controls.exchange(DctControlFrame(medium_closure_request = MediumClosureRequest()), 6) }
                            controls.close(); channels.remove(controls)
                            main.post { advertiser?.close(); advertiser = null; radio?.close(); radio = null }
                        } catch (t: Exception) { next.close(); throw t }
                    } catch (e: Exception) { fail(8019) }
                } }
                override fun onFailure() { fail(8019) }
            }).also { it.start() } } catch (e: Exception) { fail(8019) }
        }
    }
    private fun promotePublisher() {
        synchronized(this) {
            if (stopped.get() || upgraded || peer == null || !publisherProof.get()) return
            upgraded = true; http = null
        }
        android.util.Log.i("GmsDct", "Authenticated Wi-Fi Aware publisher channel ready")
        listeners.forEach { it.bandwidth(6, 1) }
        worker.execute {
            channels.forEach { channel -> runCatching { channel.exchange(DctControlFrame(medium_closure_request = MediumClosureRequest()), 6) }; channel.close() }; channels.clear()
            main.post { advertiser?.close(); radio?.close(); radio = null }
        }
    }
    private fun publish(request: WirelessMediumNegotiationRequest, tls: PlatformPakeTls): WirelessMediumNegotiationResponse {
        if (request.wifi_aware == null || !upgradeStarted.compareAndSet(false, true)) return WirelessMediumNegotiationResponse()
        val passphrase = AwareDataPath.freshPassphrase()
        val ready = java.util.concurrent.CompletableFuture<Int>()
        main.post {
            if (stopped.get()) { ready.completeExceptionally(DctFailure(8005)); return@post }
            try {
                val serverControl = DctServerControl(Build.MODEL, Build.MANUFACTURER,
                    { incoming -> org.microg.gms.esim.EsimDctRouter.incoming(this, incoming)
                        ?: applicationOwner.get(20, java.util.concurrent.TimeUnit.SECONDS).request(incoming) ?: throw DctFailure(8009) }, ::fail)
                val peerAddress = java.util.concurrent.atomic.AtomicReference<Inet6Address>()
                val serverNetwork = java.util.concurrent.atomic.AtomicReference<Network>()
                val next = DctHttpServer.pake(tls,
                    { socket ->
                        peerAddress.get()?.let { it.address.contentEquals(socket.inetAddress.address) } == true &&
                            serverNetwork.get()?.let { context.getSystemService(android.net.ConnectivityManager::class.java).getLinkProperties(it) }
                                ?.linkAddresses?.any { it.address.address.contentEquals(socket.localAddress.address) } == true
                    },
                    serverControl::handle, {
                        publisherProof.set(true); promotePublisher()
                    }, ::fail)
                server = next; publisher = serverControl
                val info = "${session.sessionId}._dct._tcp.local"
                val name = android.util.Base64.encodeToString(serviceId.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING).replace('_', '.')
                aware = AwareDataPath(context, name, info.toByteArray(), passphrase, next.port, object : AwareDataPath.Listener {
                    override fun onReady(network: Network, peer: Inet6Address, peerPort: Int?) { peerAddress.set(peer); serverNetwork.set(network); next.start() }
                    override fun onFailure() { fail(8019) }
                }).also { it.start() }
                ready.complete(next.port)
            } catch (e: Exception) { ready.completeExceptionally(e); fail(8019) }
        }
        try { ready.get(10, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { throw DctFailure(8019) }
        // This implementation declines R4 and offers the independent normal-PSK credential.
        return WirelessMediumNegotiationResponse(wifi_aware = WifiAwareResponse(passphrase = passphrase, supports_r4 = false))
    }
    fun stopAdvertising() { main.post { advertiser?.close(); advertiser = null } }
    fun fail(status: Int) {
        if (!stopped.compareAndSet(false, true)) return
        org.microg.gms.esim.EsimDctRouter.closed(this)
        android.util.Log.i("GmsDct", "Session closed status=$status")
        applicationOwner.completeExceptionally(DctFailure(status))
        synchronized(incomingLock) { buffered.clear(); bufferedBytes = 0; applicationListener = null; draining = false }
        listeners.forEach { runCatching { it.closed(status) } }; listeners.clear()
        synchronized(this) { channels.forEach { it.close() }; channels.clear(); control = null; http = null }
        server?.close(); publisher?.close(); publisher = null
        worker.shutdownNow()
        main.post { main.removeCallbacks(expires); aware?.close(); discovery?.close(); advertiser?.close(); radio?.close() }
    }
    override fun close() = fail(8005)
}
