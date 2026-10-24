/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.util.Base64
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
    val payload = mapOf(1 to L, 2 to I, 3 to B, 4 to DctRecord.Kind.Fd, 5 to S, 6 to L, 7 to DctRecord.Kind.Fd)
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
internal class ClassicNearbyBinder(private val context: Context, uid: Int) : MigrationBinder(NEARBY_DESCRIPTOR,uid), Closeable {
    private val closed = AtomicBoolean(false)
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
        override fun close() { decision.countDown(); outputs.values.toList().forEach { runCatching { it.close() } }; outputs.clear(); upgrades.toList().forEach { runCatching { it.close() } }; upgrades.clear(); runCatching { link.close() }; receiver?.close(); runCatching { channel.close() }; cacheFiles.toList().forEach { it.delete() }; cacheFiles.clear(); token.fill(0) }
    }
    private fun watch(binder: IBinder) { if (deaths.containsKey(binder)) return; val death = IBinder.DeathRecipient { close() }; binder.linkToDeath(death,0); if (deaths.putIfAbsent(binder,death) != null) binder.unlinkToDeath(death,0) }
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
        if(count==0) { complete(8000); done=true }
        return { status -> synchronized(complete) { if(!done) { remaining--; if(status==0 || remaining==0) { done=true; complete(if(status==0)0 else 8000) } } } }
    }
    private fun pcp(options: DctRecord?): Int = when(options?.obj(1)?.int(4,3) ?: 3) { 3 -> 2; 2 -> 1; 1 -> 3; else -> throw IllegalArgumentException("Unsupported strategy") }
    private var discoveryListener: IBinder? = null
    private fun open(link: ClassicRadio.Link, peer: ClassicRadio.Peer?) {
        if (closed.get() || !connectionSlots.tryAcquire()) { link.close(); return }
        try { workers.execute {
            val channel = ClassicNearbyChannel(link.input,link.output); val session = Session(link,channel); var endpoint: String? = null
            val deadline = timer.schedule({ runCatching { link.close() } },30,TimeUnit.SECONDS)
            try {
                val info: ByteArray
                val incoming = peer == null
                if (incoming) { val (request,token) = channel.receiveConnection(); endpoint = request.endpointId; info = request.endpointInfo.toByteArray().takeIf { it.isNotEmpty() } ?: request.endpointName.toByteArray(); session.token = token }
                else { endpoint = requireNotNull(peer).endpoint.id; info = peer.endpoint.info; session.token = channel.requestConnection(localId,localInfo,endpoint) }
                val ep = requireNotNull(endpoint)
                check(sessions.putIfAbsent(ep,session) == null)
                event(lifecycle,"IConnectionLifecycleListener",2,DctRecord(ClassicSchemas.initiated,mapOf(1 to ep,2 to info.toString(Charsets.UTF_8),3 to Base64.encodeToString(session.token,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).take(5).uppercase(java.util.Locale.ROOT),4 to incoming,6 to session.token.copyOf(),7 to info,9 to 0)))
                check(session.decision.await(25,TimeUnit.SECONDS)) { "Application did not accept peer" }
                if (!session.accepted) { channel.reject(); return@execute }
                channel.accept(session.token); session.token.fill(0); session.connected = true; deadline.cancel(false)
                session.receiver = receiver(session,ep)
                event(lifecycle,"IConnectionLifecycleListener",3,DctRecord(ClassicSchemas.connected,mapOf(1 to ep,2 to 0,4 to 0)))
                val keepAlive = timer.scheduleAtFixedRate({ runCatching { channel.send(V1Frame.newBuilder().setType(V1Frame.FrameType.KEEP_ALIVE).setKeepAlive(KeepAliveFrame.getDefaultInstance()).build()) }.onFailure { runCatching { link.close() } } },5,5,TimeUnit.SECONDS)
                try { while (!closed.get()) {
                    val frame = channel.receive()
                    when(frame.type) {
                        V1Frame.FrameType.PAYLOAD_TRANSFER -> session.receiver!!.receive(frame.payloadTransfer)
                        V1Frame.FrameType.KEEP_ALIVE -> Unit
                        V1Frame.FrameType.DISCONNECTION -> break
                        V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION -> if(frame.bandwidthUpgradeNegotiation.eventType == BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE) upgrade(session,ep,frame.bandwidthUpgradeNegotiation)
                        else -> throw IOException("Unsupported classic frame")
                    }
                } } finally { keepAlive.cancel(false) }
            } catch (_: Exception) {
                endpoint?.let { if (!session.connected) event(lifecycle,"IConnectionLifecycleListener",3,DctRecord(ClassicSchemas.connected,mapOf(1 to it,2 to 8012,4 to 0))) }
            } finally { deadline.cancel(false); endpoint?.let { if (sessions.remove(it,session)) event(lifecycle,"IConnectionLifecycleListener",4,DctRecord(ClassicSchemas.disconnected,mapOf(1 to it,2 to 0,6 to 8012))) }; session.close(); connectionSlots.release() }
        } } catch(_: RejectedExecutionException) { connectionSlots.release(); link.close() }
    }
    private fun upgrade(session: Session, endpoint: String, message: BandwidthUpgradeNegotiationFrame) {
        val path=message.upgradePathInfo
        val link=try { ClassicUpgradeConnector(context).connect(path) } catch (_: Exception) {
            session.channel.rejectUpgrade(message)
            event(lifecycle,"IConnectionLifecycleListener",5,DctRecord(ClassicSchemas.bandwidth,mapOf(1 to endpoint,2 to 0,3 to session.link.medium,4 to 0,12 to 2)))
            return
        }
        session.upgrades.add(link)
        try { session.channel.upgrade(link.input,link.output,localId,path.supportsClientIntroductionAck) { frame ->
            when(frame.type) {
                V1Frame.FrameType.PAYLOAD_TRANSFER -> session.receiver!!.receive(frame.payloadTransfer)
                V1Frame.FrameType.KEEP_ALIVE -> Unit
                else -> throw IOException("Unexpected frame while draining prior channel")
            }
        } } catch(e: Exception) {
            session.upgrades.remove(link); runCatching { link.close() }
            if(!session.channel.isConnected) throw e
            // Failure before LAST_WRITE must preserve the authenticated prior medium.
            session.channel.rejectUpgrade(message)
            event(lifecycle,"IConnectionLifecycleListener",5,DctRecord(ClassicSchemas.bandwidth,mapOf(1 to endpoint,2 to 0,3 to session.link.medium,4 to 0,12 to 2)))
            return
        }
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
                    PayloadTransferFrame.PayloadHeader.PayloadType.FILE -> { val folder = File(context.cacheDir,"classic-nearby").apply { check(exists() || mkdirs()) }; val file = File.createTempFile("incoming-",".payload",folder); files[header.id] = file; session.cacheFiles.add(file); delivered(header,mapOf(4 to ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY),5 to file.absolutePath)); FileOutputStream(file) }
                    PayloadTransferFrame.PayloadHeader.PayloadType.STREAM -> { val pipe = ParcelFileDescriptor.createPipe(); delivered(header,mapOf(4 to pipe[0])); DeadlineFdStreams.output(pipe[1]) }
                    else -> throw IOException("Unsupported payload")
                }.also { session.outputs[header.id] = it }
            }
            override fun progress(id: Long, count: Long) {
                val header=headers[id]
                if(header?.type == PayloadTransferFrame.PayloadHeader.PayloadType.BYTES && count==header.totalSize && deliveredBytes.add(id)) bytes.remove(id)?.let { delivered(header,mapOf(3 to it.toByteArray())) }
                update(session,endpoint,id,3,header?.totalSize ?: -1,count)
            }
            override fun complete(id: Long, count: Long) { session.outputs.remove(id); val header = requireNotNull(headers.remove(id)); bytes.remove(id)?.let { delivered(header,mapOf(3 to it.toByteArray())) }; files.remove(id)?.let { session.cacheFiles.remove(it) }; deliveredBytes.remove(id); update(session,endpoint,id,1,header.totalSize,count) }
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
            check(!closed.get()); val callback = request.binder(1); callback?.let(::watch)
            when(code) {
                2001 -> {
                    lifecycle = requireNotNull(request.binder(7)); watch(lifecycle!!)
                    localInfo = request.bytes(8) ?: (request.text(3) ?: "").toByteArray(); require(localInfo.size <= 131)
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
                    val options=request.obj(5); val selected=options?.fields?.get(16) as? IntArray
                    fun enabled(field: Int, medium: Int)=if(selected!=null && selected.isNotEmpty())medium in selected else options?.fields?.get(field) as? Boolean ?: true
                    val lan=enabled(8,5); val ble=enabled(4,4)
                    val radio=getRadio(requireNotNull(request.text(3)),pcp(options))
                    val ready=mediumResults((if(lan)1 else 0)+(if(ble)1 else 0)) { result(callback,it) }
                    if(lan)radio.discoverLan(ready)
                    if(ble)radio.scanBle(options?.fields?.get(6) as? android.os.ParcelUuid,ready)
                }
                2005 -> { lifecycle = requireNotNull(request.binder(7)); watch(lifecycle!!); val peer = peers[request.text(5)]; if(peer == null) result(callback,8005) else { localInfo = request.bytes(8) ?: (request.text(4) ?: "").toByteArray(); workers.execute { try { val link = requireNotNull(radio).connect(peer); open(link,peer); result(callback,0) } catch (_: Exception) { result(callback,8012) } } } }
                2006 -> { val session = sessions[request.text(3)]; if(session == null || session.connected) result(callback,8009) else { session.payload = requireNotNull(request.binder(5)); watch(session.payload!!); session.accepted = true; session.decision.countDown(); result(callback,0) } }
                2007 -> { val session = sessions[request.text(2)]; if(session == null) result(callback,8005) else { session.accepted = false; session.decision.countDown(); result(callback,0) } }
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
                    result(callback,if(found)0 else 8005)
                }
                2014 -> { reply?.writeNoException(); reply?.writeString(localId); return true }
            }
            reply?.writeNoException(); return true
        } finally { request.close() }
    }
    private fun sendPayload(request: DctRecord, callback: IBinder?) {
        val ids = (request.fields[2] as? Array<*>)?.filterIsInstance<String>() ?: emptyList()
        val target = ids.map { sessions[it] }; val payload = requireNotNull(request.obj(3)); val id = payload.long(1); val type = payload.int(2)
        if(ids.isEmpty() || ids.size > 4 || target.any { it?.connected != true } || transfers.containsKey(id) || transfers.size>=16) { result(callback,8005); return }
        val input: InputStream = when(type) { 1 -> ByteArrayInputStream(requireNotNull(payload.bytes(3)).also { require(it.size <= 1048576) }); 2,3 -> DeadlineFdStreams.input(ParcelFileDescriptor.dup(requireNotNull(payload.fields[4] as? ParcelFileDescriptor).fileDescriptor)); else -> { result(callback,8017); return } }
        val completion = if(type==3) (payload.fields[7] as? ParcelFileDescriptor)?.let { DeadlineFdStreams.input(ParcelFileDescriptor.dup(it.fileDescriptor)) } else null
        val outgoing=Outgoing(input,completion)
        if(transfers.putIfAbsent(id,outgoing)!=null) { outgoing.close(); result(callback,8005); return }
        try { workers.execute {
            val total = if(type == 1) payload.bytes(3)!!.size.toLong() else payload.long(6).takeIf { it > 0 } ?: -1
            val header = PayloadTransferFrame.PayloadHeader.newBuilder().setId(id).setType(PayloadTransferFrame.PayloadHeader.PayloadType.forNumber(type)).setTotalSize(total).build(); var offset = 0L
            try {
                if(type==1 && total==0L) target.forEach { session -> session!!.channel.send(V1Frame.newBuilder().setType(V1Frame.FrameType.PAYLOAD_TRANSFER).setPayloadTransfer(PayloadTransferFrame.newBuilder().setPacketType(PayloadTransferFrame.PacketType.DATA).setPayloadHeader(header).setPayloadChunk(PayloadTransferFrame.PayloadChunk.newBuilder().setOffset(0).setFlags(0).setBody(ByteString.EMPTY))).build()) }
                input.use { val buffer = ByteArray(if(type==1) payload.bytes(3)!!.size.coerceAtLeast(1) else 32768); while(true) { val count = it.read(buffer); val last = count == -1; if(last && completion!=null)require(completion.read()==1) { "Nearby stream producer failed" }; if(!last) require(offset <= 16L*1024*1024*1024-count); val chunk = PayloadTransferFrame.PayloadChunk.newBuilder().setOffset(offset).setFlags(if(last)1 else 0).setBody(if(count<0)ByteString.EMPTY else ByteString.copyFrom(buffer,0,count)); target.forEach { session -> session!!.channel.send(V1Frame.newBuilder().setType(V1Frame.FrameType.PAYLOAD_TRANSFER).setPayloadTransfer(PayloadTransferFrame.newBuilder().setPacketType(PayloadTransferFrame.PacketType.DATA).setPayloadHeader(header).setPayloadChunk(chunk)).build()) }; if(last) { if(count>0)offset+=count; require(total == -1L || total == offset); break }; offset += count; ids.zip(target).forEach { (ep,s) -> update(s!!,ep,id,3,total,offset) } } }
                ids.zip(target).forEach { (ep,s) -> update(s!!,ep,id,1,total,offset) }
            } catch (_: Exception) { ids.zip(target).forEach { (ep,s) -> update(s!!,ep,id,if(outgoing.cancelled.get())4 else 2,total,offset); runCatching { s.channel.send(V1Frame.newBuilder().setType(V1Frame.FrameType.PAYLOAD_TRANSFER).setPayloadTransfer(PayloadTransferFrame.newBuilder().setPacketType(PayloadTransferFrame.PacketType.CONTROL).setPayloadHeader(header).setControlMessage(PayloadTransferFrame.ControlMessage.newBuilder().setEvent(if(outgoing.cancelled.get())PayloadTransferFrame.ControlMessage.EventType.PAYLOAD_CANCELED else PayloadTransferFrame.ControlMessage.EventType.PAYLOAD_ERROR).setOffset(offset))).build()) } } }
            finally { transfers.remove(id,outgoing); outgoing.close() }
        }
        } catch(_: RejectedExecutionException) { transfers.remove(id,outgoing); outgoing.close(); result(callback,8000); return }
        result(callback,0)
    }
    override fun close() { if(!closed.compareAndSet(false,true)) return; radio?.close(); sessions.values.toList().forEach { it.close() }; sessions.clear(); transfers.values.toList().forEach { runCatching { it.close() } }; transfers.clear(); deaths.forEach { (binder,death) -> runCatching { binder.unlinkToDeath(death,0) } }; deaths.clear(); timer.shutdownNow(); workers.shutdownNow() }
}
