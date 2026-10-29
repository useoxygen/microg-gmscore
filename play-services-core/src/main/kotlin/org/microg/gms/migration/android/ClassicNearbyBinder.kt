/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.*
import org.microg.gms.migration.*
import org.microg.gms.migration.nearby.DctRecord
import org.microg.gms.migration.nearby.NEARBY_DESCRIPTOR
import org.microg.gms.migration.protobuf.ByteString
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

internal object ClassicSchemas {
    private val I = DctRecord.Kind.IntValue; private val L = DctRecord.Kind.LongValue
    private val S = DctRecord.Kind.Text; private val B = DctRecord.Kind.Bytes; private val C = DctRecord.Kind.Binder; private val Z = DctRecord.Kind.Bool
    private fun obj(s: () -> Map<Int,DctRecord.Kind>) = DctRecord.Kind.Object(s)
    val strategy = mapOf(3 to I, 4 to I)
    val options = mapOf(1 to obj { strategy }, 2 to Z, 3 to Z, 4 to Z, 5 to Z, 8 to DctRecord.Kind.UuidValue, 9 to Z, 12 to Z, 24 to DctRecord.Kind.Ints)
    val discoveryOptions = mapOf(1 to obj { strategy }, 2 to Z, 3 to Z, 4 to Z, 5 to Z, 6 to DctRecord.Kind.UuidValue, 8 to Z, 16 to DctRecord.Kind.Ints)
    val start = mapOf(1 to C, 2 to C, 3 to S, 4 to S, 6 to obj { options }, 7 to C, 8 to B)
    val discovery = mapOf(1 to C, 3 to S, 5 to obj { discoveryOptions }, 6 to C)
    val request = mapOf(1 to C, 4 to S, 5 to S, 7 to C, 8 to B)
    val accept = mapOf(1 to C, 3 to S, 5 to C)
    val result = mapOf(1 to C)
    val endpoint = mapOf(1 to S)
    val reject = mapOf(1 to C, 2 to S)
    val cancel = mapOf(1 to C, 2 to L)
    val payload = mapOf(1 to L, 2 to I, 3 to B, 4 to DctRecord.Kind.Fd, 5 to S, 6 to L, 7 to DctRecord.Kind.Fd, 8 to DctRecord.Kind.UriValue, 11 to obj { org.microg.gms.migration.nearby.DctSchemas.parcelBytes })
    val send = mapOf(1 to C, 2 to DctRecord.Kind.Strings, 3 to obj { payload })
    val started = mapOf(1 to I, 2 to S)
    val found = mapOf(1 to S, 2 to S, 3 to S, 5 to B, 6 to I)
    val initiated = mapOf(1 to S, 2 to S, 3 to S, 4 to Z, 5 to B, 6 to B, 7 to B, 9 to I)
    val connected = mapOf(1 to S, 2 to I, 4 to I)
    val bandwidth = mapOf(1 to S, 2 to I, 3 to I, 4 to I, 12 to I)
    val disconnected = mapOf(1 to S, 2 to I, 6 to I)
    val received = mapOf(1 to S, 2 to obj { payload }, 4 to I)
    val progress = mapOf(1 to L, 2 to I, 3 to L, 4 to L)
    val update = mapOf(1 to S, 2 to obj { progress }, 3 to I)
}

/** Classic Nearby per-UID client. Advertising/discovery completion comes from the
 * actual medium; connection success follows both UKEY2 acceptances. */
/**
 * Runs an outgoing connection on a worker and reports its status. Switch may die meanwhile; a dead callback must not
 * escape the worker thread (that would end GmsCore), so a failed report closes the session instead.
 */
internal fun reportConnection(connect: () -> Unit, report: (Int) -> Unit, failed: () -> Unit) {
    val status = try { connect(); 0 } catch (_: Exception) { 8012 }
    try { report(status) } catch (_: Exception) { failed() }
}

// GMS's classic Nearby options for SmartDevice: keep-alive every 5 s, peer dropped after 30 s of silence. Our own
// app accepts (Switch) within moments; the old phone's owner may take minutes, bounded only by the keep-alives.
internal const val SETUP_SECONDS = 30L
internal const val KEEP_ALIVE_MS = 5_000L
internal const val KEEP_ALIVE_TIMEOUT_MS = 30_000L
internal const val DECISION_SECONDS = 120L
// How long an offered Wi-Fi LAN upgrade waits for the peer's connection. Switch's own
// bandwidth-upgrade timeout is 25 s, and the peer answers an unreachable path with UPGRADE_FAILURE.
internal const val UPGRADE_ACCEPT_MS = 15_000L
internal const val MAX_UPGRADE_OFFERS = 3
private const val TAG = "GmsClassicNearby"

internal enum class ClassicFrameAction { PAYLOAD, KEEP_ALIVE, DISCONNECT, UPGRADE_PATH_AVAILABLE, UPGRADE_RETRY, IGNORE }

/** What an established classic connection does with [frame]. Frames this build does not act on (including types
 * newer than its protos, and bandwidth-upgrade events outside an upgrade) are skipped, never fatal: GMS sends
 * BANDWIDTH_UPGRADE_RETRY right after connecting, and treating it as an error dropped the link. */
internal fun classicFrameAction(frame: V1Frame): ClassicFrameAction = when(frame.type) {
    V1Frame.FrameType.PAYLOAD_TRANSFER -> ClassicFrameAction.PAYLOAD
    V1Frame.FrameType.KEEP_ALIVE -> ClassicFrameAction.KEEP_ALIVE
    V1Frame.FrameType.DISCONNECTION -> ClassicFrameAction.DISCONNECT
    V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION ->
        if (frame.bandwidthUpgradeNegotiation.eventType == BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE) ClassicFrameAction.UPGRADE_PATH_AVAILABLE
        else ClassicFrameAction.IGNORE
    V1Frame.FrameType.BANDWIDTH_UPGRADE_RETRY -> ClassicFrameAction.UPGRADE_RETRY
    else -> ClassicFrameAction.IGNORE
}

/**
 * Sends the offer in [listener] on [channel] and waits up to [timeoutMs] for the peer to connect to it.
 * Only a connection whose CLIENT_INTRODUCTION names this channel's endpoint is returned; others are closed and the
 * wait goes on. Meanwhile frames already waiting on the prior medium are read and passed to [handle], so traffic keeps
 * flowing; the peer's UPGRADE_FAILURE ends the wait. Must run on the channel's sole reader.
 */
internal fun awaitUpgradeConnection(channel: ClassicNearbyChannel, priorInput: InputStream, listener: ClassicUpgradeListener,
    timeoutMs: Long, handle: (V1Frame) -> Unit): ClassicRadio.Link {
    channel.offerUpgrade(listener.path)
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (true) {
        listener.poll(250) { channel.checkIntroduction(it) }?.let { return it }
        while (priorInput.available() > 0) {
            val frame = channel.receive()
            if (frame.type == V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION &&
                frame.bandwidthUpgradeNegotiation.eventType == BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_FAILURE)
                throw IOException("Peer could not use the offered upgrade path")
            handle(frame)
        }
        if (System.nanoTime() - deadline > 0) throw IOException("Peer did not connect to the offered upgrade path")
    }
}

/**
 * Both acceptances, after setup: [decide] is our app's choice, then [channel] waits for the peer's, which comes when
 * the old phone's owner taps Next. Meanwhile cleartext keep-alives go out every [keepAliveMs], and the link is
 * closed only after [timeoutMs] without a frame from the peer, as Nearby's keep-alive manager does. False if our
 * app declined.
 */
internal fun awaitAcceptance(channel: ClassicNearbyChannel, close: () -> Unit,
    timer: ScheduledExecutorService, token: ByteArray,
    keepAliveMs: Long = KEEP_ALIVE_MS, timeoutMs: Long = KEEP_ALIVE_TIMEOUT_MS, decide: () -> Boolean): Boolean {
    // Writes can block on a stalled link, so they get their own thread; the shared timer only watches the clock and
    // closes the link, which also ends a blocked write.
    val writer = Executors.newSingleThreadExecutor()
    val writing = java.util.concurrent.atomic.AtomicLong(0)
    val alive = timer.scheduleAtFixedRate({
        val now = System.nanoTime()
        val silent = channel.peerHeardNanos?.let { TimeUnit.NANOSECONDS.toMillis(now-it) } ?: 0
        val stuck = writing.get().let { if (it == 0L) 0 else TimeUnit.NANOSECONDS.toMillis(now-it) }
        if (silent > timeoutMs || stuck > timeoutMs) runCatching(close)
        else if (writing.compareAndSet(0, now)) runCatching { writer.execute {
            try { channel.sendPendingKeepAlive() } catch (_: Exception) { runCatching(close) } finally { writing.set(0) }
        } }.onFailure { writing.set(0) }
    },keepAliveMs,keepAliveMs,TimeUnit.MILLISECONDS)
    try {
        if (!decide()) { channel.reject(); return false }
        channel.accept(token); return true
    } finally { alive.cancel(false); writer.shutdownNow() }
}

/** [switchUid]: the caller, when its broker authenticated it as Switch; its live copy transports keep its retained
 * pairing in use ([RetainedSessionUse]). Null for microG's own pairing transport. */
/** [upgradeListener] and [upgradeAcceptMs] are replaced only by tests. */
internal class ClassicNearbyBinder(private val context: Context, uid: Int, private val switchUid: Int? = null,
    private val upgradeListener: () -> ClassicUpgradeListener? = { ClassicUpgradeListener.wifiLan(context) },
    private val upgradeAcceptMs: Long = UPGRADE_ACCEPT_MS) :
    MigrationBinder(NEARBY_DESCRIPTOR,uid), Closeable {
    private val closed = AtomicBoolean(false)
    private val ownerUid=uid
    @Volatile var diagnosticFailure: String?=null; private set
    private val workers = ThreadPoolExecutor(10,10,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(64),ThreadPoolExecutor.AbortPolicy())
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private var radio: ClassicRadio? = null
    private var lifecycle: IBinder? = null
    private var service: String? = null
    private var localInfo = byteArrayOf()
    private val localId = ClassicDiscoveryCodec.endpointId()
    private val peers = ConcurrentHashMap<String,ClassicRadio.Peer>()
    private val connectionSlots = Semaphore(4)
    private val sessions = ConcurrentHashMap<String,Session>()
    private val deaths = ConcurrentHashMap<IBinder,IBinder.DeathRecipient>()
    private class Outgoing(val input: InputStream, val status: InputStream?) : Closeable {
        val cancelled=AtomicBoolean(false)
        fun cancel() { cancelled.set(true); close() }
        override fun close() { runCatching { input.close() }; runCatching { status?.close() } }
    }
    private val transfers = ConcurrentHashMap<Long,Outgoing>()
    private val prefix = "com.google.android.gms.nearby.internal.connection."
    private inner class Session(val link: ClassicRadio.Link, val channel: ClassicNearbyChannel) : Closeable {
        val decision = CountDownLatch(1); var accepted = false; var payload: IBinder? = null; var token = byteArrayOf(); var connected = false
        var receiver: ClassicNearbyPayloads? = null
        val outputs = ConcurrentHashMap<Long,OutputStream>()
        val cancelled = ConcurrentHashMap.newKeySet<Long>()
        val cacheFiles = ConcurrentHashMap.newKeySet<File>()
        val upgrades = ConcurrentHashMap.newKeySet<Closeable>()
        /** We advertised and the peer connected to us: Nearby makes this side the BWU initiator. */
        var incoming = false
        /** Upgrade mediums from the peer's CONNECTION_REQUEST (incoming) or its retry frames. */
        var peerMediums: Set<Int> = emptySet()
        /** Upgrade mediums this side can use for this connection. */
        var localMediums: List<Int> = emptyList()
        var upgraded = false; var upgradeOffers = 0
        override fun close() { decision.countDown(); outputs.values.toList().forEach { runCatching { it.close() } }; outputs.clear(); upgrades.toList().forEach { runCatching { it.close() } }; upgrades.clear(); runCatching { link.close() }; receiver?.close(); runCatching { channel.close() }; cacheFiles.toList().forEach { it.delete() }; cacheFiles.clear(); token.fill(0) }
    }
    private fun watch(binder: IBinder) { if (deaths.containsKey(binder)) return; require(deaths.size<256); val death = IBinder.DeathRecipient { close() }; binder.linkToDeath(death,0); if (deaths.putIfAbsent(binder,death) != null) binder.unlinkToDeath(death,0) }
    private fun event(target: IBinder?, name: String, code: Int, value: DctRecord) { if (target == null || closed.get()) return; try { sendCallback(target,prefix+name,code) { writeNullable(value) } } catch (_: Exception) { close() } }
    private fun result(target: IBinder?, code: Int) { if (target != null) sendCallback(target,prefix+"IResultListener",2) { writeInt(code) } }
    private fun update(session: Session, endpoint: String, id: Long, status: Int, total: Long, bytes: Long) = event(session.payload,"IPayloadListener",3,DctRecord(ClassicSchemas.update,mapOf(1 to endpoint,2 to DctRecord(ClassicSchemas.progress,mapOf(1 to id,2 to status,3 to total,4 to bytes)),3 to 0)))
    private fun getRadio(id: String, pcp: Int = 1): ClassicRadio {
        require(id.isNotBlank() && id.length <= 256)
        if (radio != null) { require(service == id); return requireNotNull(radio) }
        service = id
        return ClassicRadio(context,id,localId,localInfo,pcp,{ link -> open(link,null) },{ peer -> if(peers.size >= 256 && !peers.containsKey(peer.endpoint.id))return@ClassicRadio
            peers.compute(peer.endpoint.id) { _, prior -> if(peer.address!=null || peer.bluetooth!=null || prior==null)peer else prior }; event(discoveryListener,"IDiscoveryListener",2,DctRecord(ClassicSchemas.found,mapOf(1 to peer.endpoint.id,2 to peer.endpoint.info.toString(Charsets.UTF_8),3 to id,5 to peer.endpoint.info,6 to 0))) },{ endpoint -> peers.remove(endpoint); event(discoveryListener,"IDiscoveryListener",3,DctRecord(ClassicSchemas.endpoint,mapOf(1 to endpoint))) },{ status -> if (!closed.get()) sessions.keys.toList().forEach { disconnect(it,status) } }).also { radio = it }
    }
    private fun mediumResults(count: Int, complete: (Int) -> Unit): (Int) -> Unit {
        var remaining=count; var done=false
        if(count==0) { complete(ClassicStatus.CAPABILITY); done=true }
        return { status -> synchronized(complete) { if(!done) { remaining--; if(status==0 || remaining==0) { done=true; complete(status) } } } }
    }
    private fun pcp(options: DctRecord?): Int = when(options?.obj(1)?.int(4,3) ?: 3) { 3 -> 2; 2 -> 1; 1 -> 3; else -> throw IllegalArgumentException("Unsupported strategy") }
    private var discoveryListener: IBinder? = null
    private fun open(link: ClassicRadio.Link, peer: ClassicRadio.Peer?) {
        if (closed.get() || !connectionSlots.tryAcquire()) { link.close(); return }
        try { workers.execute {
            val channel = ClassicNearbyChannel(link.input,link.output); val session = Session(link,channel); var endpoint: String? = null
            // The deadline covers connection setup (request and UKEY2) only. Waiting for both sides to accept can take
            // as long as the owner needs to tap Next on the old phone; then, as in Nearby, keep-alives keep the link.
            val deadline = timer.schedule({ runCatching { link.close() } },SETUP_SECONDS,TimeUnit.SECONDS)
            var inUse: Closeable? = null
            try {
                val info: ByteArray
                val incoming = peer == null
                session.incoming = incoming
                if (incoming) { val (request,token) = channel.receiveConnection(); endpoint = request.endpointId; info = request.endpointInfo.toByteArray().takeIf { it.isNotEmpty() } ?: request.endpointName.toByteArray(); session.token = token
                    session.peerMediums = request.mediumsList.map { it.number }.toSet(); session.localMediums = if (switchUid != null) listOf(ClassicUpgradeListener.WIFI_LAN) else emptyList() }
                else { endpoint = requireNotNull(peer).endpoint.id; info = peer.endpoint.info; val mediums = buildList {
                    add(ConnectionRequestFrame.Medium.WIFI_LAN)
                    if(android.os.Build.VERSION.SDK_INT>=29 && context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI))add(ConnectionRequestFrame.Medium.WIFI_HOTSPOT)
                    if(android.os.Build.VERSION.SDK_INT>=29 && context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_DIRECT))add(ConnectionRequestFrame.Medium.WIFI_DIRECT)
                }
                    session.localMediums = mediums.map { it.number }; session.token = channel.requestConnection(localId,localInfo,endpoint,mediums) }
                val ep = requireNotNull(endpoint)
                check(sessions.putIfAbsent(ep,session) == null)
                event(lifecycle,"IConnectionLifecycleListener",2,DctRecord(ClassicSchemas.initiated,mapOf(1 to ep,2 to info.toString(Charsets.UTF_8),3 to Base64.encodeToString(session.token,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).take(5).uppercase(java.util.Locale.ROOT),4 to incoming,6 to session.token.copyOf(),7 to info,9 to 0)))
                deadline.cancel(false)
                val accepted = awaitAcceptance(channel,{ runCatching { link.close() } },timer,session.token) {
                    check(session.decision.await(DECISION_SECONDS,TimeUnit.SECONDS)) { "Application did not accept peer" }
                    session.accepted
                }
                if (!accepted) return@execute
                session.token.fill(0); session.connected = true
                inUse = switchUid?.let(RetainedSessionUse::transport)
                session.receiver = receiver(session,ep)
                event(lifecycle,"IConnectionLifecycleListener",3,DctRecord(ClassicSchemas.connected,mapOf(1 to ep,2 to 0,4 to 0)))
                val keepAlive = timer.scheduleAtFixedRate({ runCatching { channel.send(V1Frame.newBuilder().setType(V1Frame.FrameType.KEEP_ALIVE).setKeepAlive(KeepAliveFrame.getDefaultInstance()).build()) }.onFailure { runCatching { link.close() } } },5,5,TimeUnit.SECONDS)
                try {
                    // As Nearby's advertiser, kick off the bandwidth upgrade once both sides accepted.
                    if (offersUpgrades(session) && ClassicUpgradeListener.WIFI_LAN in session.peerMediums) offerUpgrade(session,ep)
                    while (!closed.get()) { if (!handleFrame(session,ep,channel.receive())) break }
                } finally { keepAlive.cancel(false) }
            } catch (error: Exception) {
                diagnosticFailure=channel.diagnosticPhase+":"+error.javaClass.simpleName
                endpoint?.let { if (!session.connected) event(lifecycle,"IConnectionLifecycleListener",3,DctRecord(ClassicSchemas.connected,mapOf(1 to it,2 to 8012,4 to 0))) }
            } finally { deadline.cancel(false); inUse?.close(); endpoint?.let { if (sessions.remove(it,session)) event(lifecycle,"IConnectionLifecycleListener",4,DctRecord(ClassicSchemas.disconnected,mapOf(1 to it,2 to 0,6 to 8012))) }; session.close(); connectionSlots.release() }
        } } catch(_: RejectedExecutionException) { connectionSlots.release(); link.close() }
    }
    /** One frame on an established connection; false ends it. */
    private fun handleFrame(session: Session, endpoint: String, frame: V1Frame): Boolean {
        when(classicFrameAction(frame)) {
            ClassicFrameAction.PAYLOAD -> session.receiver!!.receive(frame.payloadTransfer)
            ClassicFrameAction.KEEP_ALIVE -> Unit
            ClassicFrameAction.DISCONNECT -> return false
            ClassicFrameAction.UPGRADE_PATH_AVAILABLE -> upgrade(session,endpoint,frame.bandwidthUpgradeNegotiation)
            ClassicFrameAction.UPGRADE_RETRY -> upgradeRetry(session,endpoint,frame.bandwidthUpgradeRetry)
            ClassicFrameAction.IGNORE -> Log.i(TAG, "Ignoring classic frame ${frame.type}${if (frame.hasBandwidthUpgradeNegotiation()) " "+frame.bandwidthUpgradeNegotiation.eventType else ""}")
        }
        return true
    }
    /** Frames that may still arrive on the prior medium while an upgrade drains it. */
    private fun drainPriorChannel(session: Session, frame: V1Frame) {
        when(frame.type) {
            V1Frame.FrameType.PAYLOAD_TRANSFER -> session.receiver!!.receive(frame.payloadTransfer)
            V1Frame.FrameType.KEEP_ALIVE -> Unit
            V1Frame.FrameType.DISCONNECTION -> throw IOException("Peer disconnected during bandwidth upgrade")
            else -> Log.i(TAG, "Ignoring ${frame.type} during bandwidth upgrade")
        }
    }
    /** GMS sends its upgrade mediums (BANDWIDTH_UPGRADE_RETRY) once it is on Wi-Fi, so the
     * initiator can retry. As the advertiser we offer Wi-Fi LAN again if it is in the list. */
    private fun upgradeRetry(session: Session, endpoint: String, retry: BandwidthUpgradeRetryFrame) {
        val mediums = retry.supportedMediumList.map { it.number }.toSet()
        Log.i(TAG, "Peer bandwidth-upgrade retry mediums=${retry.supportedMediumList} request=${retry.isRequest}")
        session.peerMediums = session.peerMediums + mediums
        if (retry.isRequest) session.channel.answerUpgradeRetry(session.localMediums.mapNotNull { BandwidthUpgradeRetryFrame.Medium.forNumber(it) })
        if (offersUpgrades(session) && ClassicUpgradeListener.WIFI_LAN in mediums) offerUpgrade(session,endpoint)
    }
    /** Initiating is limited to Switch's copy transport (CYC-911) until a phone run proves it; other flows,
     * such as the legacy target bootstrap, keep their prior medium. */
    private fun offersUpgrades(session: Session) = session.incoming && switchUid != null
    /** A frame on the prior medium while our offer is open. GMS sends BANDWIDTH_UPGRADE_RETRY about 500 ms after
     * connecting, usually inside this window: its request is answered and the open offer stands. */
    private fun duringOffer(session: Session, frame: V1Frame) {
        if (frame.type == V1Frame.FrameType.BANDWIDTH_UPGRADE_RETRY) {
            val retry = frame.bandwidthUpgradeRetry
            Log.i(TAG, "Peer bandwidth-upgrade retry during open offer mediums=${retry.supportedMediumList} request=${retry.isRequest}")
            session.peerMediums = session.peerMediums + retry.supportedMediumList.map { it.number }
            if (retry.isRequest) session.channel.answerUpgradeRetry(session.localMediums.mapNotNull { BandwidthUpgradeRetryFrame.Medium.forNumber(it) })
        } else drainPriorChannel(session, frame)
    }
    /** Initiator side of the bandwidth upgrade, for a connection we accepted as advertiser:
     * offer Wi-Fi LAN, accept the peer's connection and hand the endpoint's traffic over to
     * it. Runs on the session's reader; prior-medium frames keep being handled while the
     * offer is open, and the peer's UPGRADE_FAILURE ends the offer. Any failure before the
     * handoff commits keeps the authenticated prior medium. */
    private fun offerUpgrade(session: Session, endpoint: String) {
        if (session.upgraded || session.link.medium == ClassicUpgradeListener.WIFI_LAN || session.upgradeOffers >= MAX_UPGRADE_OFFERS) return
        val listener = try { upgradeListener() } catch (e: Exception) { Log.w(TAG, "Wi-Fi LAN upgrade listener failed (${e.javaClass.simpleName})"); null }
        if (listener == null) { Log.i(TAG, "No Wi-Fi LAN address to offer for bandwidth upgrade"); return }
        session.upgradeOffers++
        val link = try {
            session.upgrades.add(listener)
            // A failure handling ordinary traffic (or the peer's DISCONNECTION) still ends the session.
            try { awaitUpgradeConnection(session.channel, session.link.input, listener, upgradeAcceptMs) {
                try { duringOffer(session,it) } catch (e: Exception) { runCatching { session.channel.close() }; throw e }
            } }
            finally { session.upgrades.remove(listener); runCatching { listener.close() } }
        } catch (e: Exception) {
            if (!session.channel.isConnected) throw e
            Log.i(TAG, "Wi-Fi LAN bandwidth upgrade not taken (${e.message ?: e.javaClass.simpleName})")
            bandwidthFailed(session,endpoint)
            return
        }
        session.upgrades.add(link)
        try { session.channel.acceptUpgrade(link.input,link.output) { drainPriorChannel(session,it) } } catch(e: Exception) {
            session.upgrades.remove(link); runCatching { link.close() }
            if(!session.channel.isConnected) throw e
            Log.i(TAG, "Wi-Fi LAN bandwidth upgrade failed before handoff (${e.javaClass.simpleName})")
            bandwidthFailed(session,endpoint)
            return
        }
        session.upgraded = true
        Log.i(TAG, "Bandwidth upgraded to Wi-Fi LAN as initiator")
        event(lifecycle,"IConnectionLifecycleListener",5,DctRecord(ClassicSchemas.bandwidth,mapOf(1 to endpoint,2 to 3,3 to link.medium,4 to 0,12 to 1)))
    }
    /** Test entry: [link] arrives as an incoming connection while [listener] receives lifecycle events. */
    internal fun acceptIncomingForTest(link: ClassicRadio.Link, listener: IBinder) { lifecycle = listener; open(link,null) }
    private fun bandwidthFailed(session: Session, endpoint: String) =
        event(lifecycle,"IConnectionLifecycleListener",5,DctRecord(ClassicSchemas.bandwidth,mapOf(1 to endpoint,2 to 0,3 to session.link.medium,4 to 0,12 to 2)))
    private fun upgrade(session: Session, endpoint: String, message: BandwidthUpgradeNegotiationFrame) {
        val path=message.upgradePathInfo
        val link=try { ClassicUpgradeConnector(context).connect(path) } catch (_: Exception) {
            session.channel.rejectUpgrade(message)
            bandwidthFailed(session,endpoint)
            return
        }
        session.upgrades.add(link)
        try { session.channel.upgrade(link.input,link.output,localId,path.supportsClientIntroductionAck) { drainPriorChannel(session,it) } } catch(e: Exception) {
            session.upgrades.remove(link); runCatching { link.close() }
            if(!session.channel.isConnected) throw e
            // Failure before LAST_WRITE must preserve the authenticated prior medium.
            session.channel.rejectUpgrade(message)
            bandwidthFailed(session,endpoint)
            return
        }
        session.upgraded = true
        event(lifecycle,"IConnectionLifecycleListener",5,DctRecord(ClassicSchemas.bandwidth,mapOf(1 to endpoint,2 to 3,3 to link.medium,4 to 0,12 to 1)))
    }
    private fun receiver(session: Session, endpoint: String): ClassicNearbyPayloads {
        val bytes = ConcurrentHashMap<Long,ByteArrayOutputStream>(); val files = ConcurrentHashMap<Long,File>(); val deliveredBytes = ConcurrentHashMap.newKeySet<Long>(); val headers = ConcurrentHashMap<Long,PayloadTransferFrame.PayloadHeader>()
        fun delivered(header: PayloadTransferFrame.PayloadHeader, fields: Map<Int,Any>) { val record = DctRecord(ClassicSchemas.payload,mapOf(1 to header.id,2 to header.type.number,6 to header.totalSize)+fields); try { event(session.payload,"IPayloadListener",2,DctRecord(ClassicSchemas.received,mapOf(1 to endpoint,2 to record,4 to 0))) } finally { record.close() } }
        return ClassicNearbyPayloads(object : ClassicNearbyPayloads.Sink {
            override fun open(header: PayloadTransferFrame.PayloadHeader): OutputStream {
                headers[header.id] = header
                return when(header.type) {
                    PayloadTransferFrame.PayloadHeader.PayloadType.BYTES -> ByteArrayOutputStream().also { bytes[header.id] = it }
                    PayloadTransferFrame.PayloadHeader.PayloadType.FILE -> { val folder = File(context.cacheDir,"classic-nearby").apply { check(exists() || mkdirs()) }; require(header.totalSize<0 || header.totalSize<=folder.usableSpace-256L*1024*1024) { "Insufficient storage for Nearby file" }; val file = File.createTempFile("incoming-",".payload",folder); files[header.id] = file; session.cacheFiles.add(file)
                        val uri=androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".migration.classic.files",file)
                        context.packageManager.getPackagesForUid(ownerUid)?.forEach { context.grantUriPermission(it,uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        delivered(header,mapOf(4 to ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY),5 to file.absolutePath,8 to uri)); ClassicStorageOutputStream(FileOutputStream(file)) { (folder.usableSpace-256L*1024*1024).coerceAtLeast(0) } }
                    PayloadTransferFrame.PayloadHeader.PayloadType.STREAM -> { val pipe = ParcelFileDescriptor.createPipe(); delivered(header,mapOf(4 to pipe[0])); DeadlineFdStreams.output(pipe[1]) }
                    else -> throw IOException("Unsupported payload")
                }.also { session.outputs[header.id] = it }
            }
            override fun progress(id: Long, count: Long) {
                val header=headers[id]
                if(header?.type == PayloadTransferFrame.PayloadHeader.PayloadType.BYTES && count==header.totalSize && deliveredBytes.add(id)) bytes.remove(id)?.let { delivered(header,ClassicParcelBytes.fields(context.cacheDir,it.toByteArray())) }
                update(session,endpoint,id,3,header?.totalSize ?: -1,count)
            }
            override fun complete(id: Long, count: Long) { session.outputs.remove(id); val header = requireNotNull(headers.remove(id)); bytes.remove(id)?.let { delivered(header,ClassicParcelBytes.fields(context.cacheDir,it.toByteArray())) }; files.remove(id)?.let { session.cacheFiles.remove(it) }; deliveredBytes.remove(id); update(session,endpoint,id,1,header.totalSize,count) }
            override fun cancelled(id: Long) { session.cancelled.add(id); failed(id) }
            override fun failed(id: Long) { session.outputs.remove(id)?.let { runCatching { it.close() } }; bytes.remove(id); files.remove(id)?.let { session.cacheFiles.remove(it); it.delete() }; deliveredBytes.remove(id); val header = headers.remove(id); update(session,endpoint,id,if(session.cancelled.remove(id))4 else 2,header?.totalSize ?: -1,0) }
        },16L*1024*1024*1024)
    }
    private fun disconnect(endpoint: String, reason: Int) { sessions.remove(endpoint)?.let { session -> session.close(); event(lifecycle,"IConnectionLifecycleListener",4,DctRecord(ClassicSchemas.disconnected,mapOf(1 to endpoint,2 to 0,6 to reason))) } }
    fun handle(code: Int, data: Parcel, reply: Parcel?): Boolean = dispatch(code,data,reply)
    override fun dispatch(code: Int, data: Parcel, reply: Parcel?): Boolean {
        val schema = when(code) { 2001 -> ClassicSchemas.start; 2003 -> ClassicSchemas.discovery; 2005 -> ClassicSchemas.request; 2006 -> ClassicSchemas.accept; 2007 -> ClassicSchemas.reject; 2008 -> ClassicSchemas.send; 2009 -> ClassicSchemas.endpoint; 2012 -> ClassicSchemas.cancel; 2002,2004,2010,2011,2014 -> ClassicSchemas.result; else -> return false }
        val request = requireNotNull(DctRecord.nullable(data,schema)); data.requireEnd()
        try {
            check(!closed.get()); val callback = request.binder(1)
            when(code) {
                2001 -> {
                    lifecycle = requireNotNull(request.binder(7)); watch(lifecycle!!)
                    localInfo = request.bytes(8) ?: (request.text(3) ?: "").toByteArray(); require(localInfo.size <= 131)
                    if(radio?.isAdvertising==true) { event(callback,"IStartAdvertisingResultListener",2,DctRecord(ClassicSchemas.started,mapOf(1 to ClassicStatus.ALREADY_ADVERTISING,2 to localId))); reply?.writeNoException(); return true }
                    if(radio!=null && service!=request.text(4)) { event(callback,"IStartAdvertisingResultListener",2,DctRecord(ClassicSchemas.started,mapOf(1 to ClassicStatus.ACTIVE_STRATEGY,2 to localId))); reply?.writeNoException(); return true }
                    val options=request.obj(6); val selected=options?.fields?.get(24) as? IntArray
                    fun enabled(field: Int, medium: Int)=if(selected!=null && selected.isNotEmpty())medium in selected else options?.fields?.get(field) as? Boolean ?: true
                    val lan=enabled(9,5); val bt=enabled(4,2); val ble=enabled(5,4)
                    val radio=getRadio(requireNotNull(request.text(4)),pcp(options))
                    val ready=mediumResults((if(lan)1 else 0)+(if(ble)1 else 0)) { status -> event(callback,"IStartAdvertisingResultListener",2,DctRecord(ClassicSchemas.started,mapOf(1 to status,2 to localId))) }
                    if(lan)radio.advertiseLan(ready)
                    if(bt)radio.listenBluetooth()
                    if(ble)radio.advertiseBle(options?.fields?.get(8) as? android.os.ParcelUuid,ready)
                }
                2003 -> {
                    discoveryListener = requireNotNull(request.binder(6)); watch(discoveryListener!!)
                    if(radio?.isDiscovering==true) { result(callback,ClassicStatus.ALREADY_DISCOVERING); reply?.writeNoException(); return true }
                    if(radio!=null && service!=request.text(3)) { result(callback,ClassicStatus.ACTIVE_STRATEGY); reply?.writeNoException(); return true }
                    val options=request.obj(5); val selected=options?.fields?.get(16) as? IntArray
                    fun enabled(field: Int, medium: Int)=if(selected!=null && selected.isNotEmpty())medium in selected else options?.fields?.get(field) as? Boolean ?: true
                    val lan=enabled(8,5); val ble=enabled(4,4)
                    val radio=getRadio(requireNotNull(request.text(3)),pcp(options))
                    val ready=mediumResults((if(lan)1 else 0)+(if(ble)1 else 0)) { result(callback,it) }
                    if(lan)radio.discoverLan(ready)
                    if(ble)radio.scanBle(options?.fields?.get(6) as? android.os.ParcelUuid,ready)
                }
                2005 -> { lifecycle = requireNotNull(request.binder(7)); watch(lifecycle!!); val peer = peers[request.text(5)]; if(peer == null) result(callback,ClassicStatus.ENDPOINT_UNKNOWN) else { localInfo = request.bytes(8) ?: (request.text(4) ?: "").toByteArray(); workers.execute { reportConnection({ open(requireNotNull(radio).connect(peer),peer) }, { result(callback,it) }, ::close) } } }
                2006 -> { val session = sessions[request.text(3)]; if(session == null || session.connected) result(callback,if(session==null)ClassicStatus.OUT_OF_ORDER else ClassicStatus.ALREADY_CONNECTED) else { session.payload = requireNotNull(request.binder(5)); watch(session.payload!!); session.accepted = true; session.decision.countDown(); result(callback,0) } }
                2007 -> { val session = sessions[request.text(2)]; if(session == null) result(callback,ClassicStatus.ENDPOINT_UNKNOWN) else { session.accepted = false; session.decision.countDown(); result(callback,0) } }
                2008 -> sendPayload(request,callback)
                2009 -> request.text(1)?.let { disconnect(it,0) }
                2002 -> { radio?.stopAdvertising(); result(callback,0) }
                2004 -> { radio?.stopDiscovery(); peers.clear(); result(callback,0) }
                2010,2011 -> { sessions.keys.toList().forEach { disconnect(it,0) }; radio?.close(); radio = null; peers.clear(); result(callback,0) }
                2012 -> {
                    val id=request.long(2); var found=transfers[id]?.let { it.cancel(); true } ?: false
                    sessions.forEach { (_,session) ->
                        session.outputs[id]?.let { session.cancelled.add(id); it.close(); found=true; runCatching { session.receiver?.cancel(id) } }
                        if(found)runCatching { session.channel.send(V1Frame.newBuilder().setType(V1Frame.FrameType.PAYLOAD_TRANSFER).setPayloadTransfer(PayloadTransferFrame.newBuilder().setPacketType(PayloadTransferFrame.PacketType.CONTROL).setPayloadHeader(PayloadTransferFrame.PayloadHeader.newBuilder().setId(id)).setControlMessage(PayloadTransferFrame.ControlMessage.newBuilder().setEvent(PayloadTransferFrame.ControlMessage.EventType.PAYLOAD_CANCELED))).build()) }
                    }
                    result(callback,if(found)0 else ClassicStatus.PAYLOAD_UNKNOWN)
                }
                2014 -> { reply?.writeNoException(); reply?.writeString(localId); return true }
            }
            reply?.writeNoException(); return true
        } finally { request.close() }
    }
    private fun sendPayload(request: DctRecord, callback: IBinder?) {
        val ids = (request.fields[2] as? Array<*>)?.filterIsInstance<String>() ?: emptyList()
        val targets = ids.map { sessions[it] }
        val payload = requireNotNull(request.obj(3)); val id=payload.long(1); val type=payload.int(2)
        if(ids.size!=ids.toSet().size) { result(callback,13); return }
        if(ids.isEmpty() || ids.size>4 || targets.any { it?.connected!=true } || transfers.size>=16) { result(callback,ClassicStatus.NOT_CONNECTED); return }
        if(type !in 1..3) { result(callback,ClassicStatus.PAYLOAD_TYPE); return }
        val wrapper=if(type==1)payload.obj(11)?.fields?.get(1) as? ParcelFileDescriptor else null
        val inline=if(type==1 && wrapper==null)requireNotNull(payload.bytes(3)).also { require(it.size<=ClassicParcelBytes.MAX_BYTES) } else null
        val fileDescriptor=wrapper ?: payload.fields[4] as? ParcelFileDescriptor
        val input: InputStream=if(inline!=null)ByteArrayInputStream(inline) else if(fileDescriptor!=null)DeadlineFdStreams.input(ParcelFileDescriptor.dup(fileDescriptor.fileDescriptor)) else {
            val uri=payload.fields[8] as? android.net.Uri
            if(type!=2 || uri?.scheme!="content") { result(callback,ClassicStatus.PAYLOAD_TYPE); return }
            val provider=context.packageManager.resolveContentProvider(requireNotNull(uri.authority),0)
            if(provider?.applicationInfo?.uid!=ownerUid && context.checkUriPermission(uri,-1,ownerUid,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)!=android.content.pm.PackageManager.PERMISSION_GRANTED) { result(callback,13); return }
            val owned=try { context.contentResolver.openFileDescriptor(uri,"r") } catch(_: Exception) { null }
            if(owned==null) { result(callback,8013); return }
            DeadlineFdStreams.input(owned)
        }
        val completion=if(type==3)(payload.fields[7] as? ParcelFileDescriptor)?.let { DeadlineFdStreams.input(ParcelFileDescriptor.dup(it.fileDescriptor)) } else null
        val outgoing=Outgoing(input,completion)
        if(transfers.putIfAbsent(id,outgoing)!=null) { outgoing.close(); result(callback,ClassicStatus.PAYLOAD_UNKNOWN); return }
        val declared=(payload.fields[6] as? Long)?.takeIf { it>=0 } ?: -1
        try { workers.execute {
            var offset=0L; var total=-1L
            val builder=PayloadTransferFrame.PayloadHeader.newBuilder().setId(id).setType(PayloadTransferFrame.PayloadHeader.PayloadType.forNumber(type))
            try {
                val body=if(type==1)inline ?: ClassicParcelBytes.read(input) else null
                total=body?.size?.toLong() ?: declared
                val header=builder.setTotalSize(total).build()
                fun chunk(bytes: ByteArray, last: Boolean) {
                    check(!outgoing.cancelled.get())
                    require(offset <= 16L*1024*1024*1024-bytes.size)
                    val frame=V1Frame.newBuilder().setType(V1Frame.FrameType.PAYLOAD_TRANSFER).setPayloadTransfer(PayloadTransferFrame.newBuilder().setPacketType(PayloadTransferFrame.PacketType.DATA).setPayloadHeader(header).setPayloadChunk(PayloadTransferFrame.PayloadChunk.newBuilder().setOffset(offset).setFlags(if(last)1 else 0).setBody(ByteString.copyFrom(bytes)))).build()
                    targets.forEach { it!!.channel.send(frame) }; offset+=bytes.size
                }
                if(body!=null) {
                    // GMS creates incoming BYTES from the whole first nonfinal DATA.
                    chunk(body,false)
                } else {
                    val buffer=ByteArray(32768)
                    while(true) { val count=input.read(buffer); if(count==-1)break; if(count==0)continue; chunk(buffer.copyOf(count),false); ids.zip(targets).forEach { (ep,s) -> update(s!!,ep,id,3,total,offset) } }
                    if(completion!=null)DeadlineFdStreams.requireProducerSuccess(completion)
                }
                require(total == -1L || total==offset)
                chunk(byteArrayOf(),true)
                ids.zip(targets).forEach { (ep,s) -> update(s!!,ep,id,1,total,offset) }
            } catch(_: Exception) {
                ids.zip(targets).forEach { (ep,s) ->
                    update(s!!,ep,id,if(outgoing.cancelled.get())4 else 2,total,offset)
                    runCatching { s.channel.send(V1Frame.newBuilder().setType(V1Frame.FrameType.PAYLOAD_TRANSFER).setPayloadTransfer(PayloadTransferFrame.newBuilder().setPacketType(PayloadTransferFrame.PacketType.CONTROL).setPayloadHeader(builder).setControlMessage(PayloadTransferFrame.ControlMessage.newBuilder().setEvent(if(outgoing.cancelled.get())PayloadTransferFrame.ControlMessage.EventType.PAYLOAD_CANCELED else PayloadTransferFrame.ControlMessage.EventType.PAYLOAD_ERROR).setOffset(offset))).build()) }
                }
            } finally { transfers.remove(id,outgoing); outgoing.close() }
        } } catch(_: RejectedExecutionException) { transfers.remove(id,outgoing); outgoing.close(); result(callback,13); return }
        result(callback,0)
    }
    override fun close() { if(!closed.compareAndSet(false,true)) return; radio?.close(); sessions.values.toList().forEach { it.close() }; sessions.clear(); transfers.values.toList().forEach { runCatching { it.close() } }; transfers.clear(); deaths.forEach { (binder,death) -> runCatching { binder.unlinkToDeath(death,0) } }; deaths.clear(); timer.shutdownNow(); workers.shutdownNow() }
}
