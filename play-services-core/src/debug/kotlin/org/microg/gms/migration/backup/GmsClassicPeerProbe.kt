/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.backup

import android.content.*
import android.os.*
import com.google.android.gms.common.internal.GetServiceRequest
import org.microg.gms.migration.*
import org.microg.gms.migration.android.*
import org.microg.gms.migration.nearby.DctRecord
import org.microg.gms.migration.nearby.NEARBY_DESCRIPTOR
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

/** Debug only: a synthetic Nearby SDK client of production GMS, and our real
 * classic Binder/media client, in separate Binder endpoints. No account/phone data.
 * This is Nearby interop, not the Google SmartDevice bootstrap or Switch transfer. */
object GmsClassicPeerProbe {
    private const val PREFIX = "com.google.android.gms.nearby.internal.connection."
    fun start(context: Context, report: (String) -> Unit = { println(it) }) {
        java.io.File(context.filesDir,"nearby-result.txt").writeText("RUNNING\n")
        val worker = Executors.newSingleThreadExecutor()
        val gms = AtomicReference<IBinder>(); val brokerReady = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceDisconnected(name: ComponentName) {}
            override fun onServiceConnected(name: ComponentName, broker: IBinder) {
                val callbacks = object : Binder() {
                    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                        data.enforceInterface("com.google.android.gms.common.internal.IGmsCallbacks")
                        if(code != 1 && code != 3) return false
                        val status = data.readInt(); println("GMS broker status=$status callback=$code")
                        check(status == 0); gms.set(requireNotNull(data.readStrongBinder())); brokerReady.countDown(); reply?.writeNoException(); return true
                    }
                }
                val request = GetServiceRequest(54).apply { packageName = context.packageName; extras = Bundle().apply { putLong("clientId",System.nanoTime() and Long.MAX_VALUE) }; apiFeatures = emptyArray(); defaultFeatures = emptyArray() }
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try { data.writeInterfaceToken("com.google.android.gms.common.internal.IGmsServiceBroker"); data.writeStrongBinder(callbacks); data.writeNullable(request); check(broker.transact(46,data,reply,0)); reply.readException() } finally { data.recycle(); reply.recycle() }
            }
        }
        check(context.bindService(Intent("com.google.android.gms.nearby.connection.service.START").setPackage("com.google.android.gms"),connection,Context.BIND_AUTO_CREATE))
        worker.execute {
            var target: ClassicNearbyBinder? = null
            try {
                check(brokerReady.await(25,TimeUnit.SECONDS)) { "Production GMS broker timed out" }
                val source = requireNotNull(gms.get()); val advertised = CountDownLatch(1); val sourceInitiated = CountDownLatch(1); val targetConnected = CountDownLatch(1); val received = CountDownLatch(1); val echoed = CountDownLatch(1)
                val sourceStream = CountDownLatch(1); val targetStream = CountDownLatch(1)
                val sourceFile=CountDownLatch(1); val targetFile=CountDownLatch(1); val sourceFileComplete=CountDownLatch(1); val targetFileComplete=CountDownLatch(1)
                val streamFixture = ByteArray(100003) { (it % 239).toByte() }
                val sourceToken = AtomicReference<ByteArray>(); val sourceEndpoint = AtomicReference<String>(); val targetPeer = AtomicReference<String>()
                val service = "cyclon.cyc712.probe."+java.util.UUID.randomUUID().toString()
                val fixture = ByteArray(8193) { (it % 251).toByte() }
                fun call(binder: IBinder, code: Int, value: DctRecord) { val data = Parcel.obtain(); val reply = Parcel.obtain(); try { data.writeInterfaceToken(NEARBY_DESCRIPTOR); data.writeNullable(value); check(binder.transact(code,data,reply,0)); reply.readException() } finally { data.recycle(); reply.recycle() } }
                fun callback(name: String, action: (Int,Parcel) -> Unit): IBinder = object : Binder() { override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean { data.enforceInterface(PREFIX+name); action(code,data); reply?.writeNoException(); return true } }
                val result = callback("IResultListener") { _,data -> check(data.readInt()==0) }
                val sourcePayload = callback("IPayloadListener") { code,data ->
                    if(code == 2) { val record = requireNotNull(DctRecord.nullable(data,ClassicSchemas.received)); record.use { val payload = requireNotNull(it.obj(2)); if(payload.int(2)==1) { check(payload.bytes(3)!!.contentEquals(fixture)) { "GMS received a different byte array" }; received.countDown() }
                        else if(payload.int(2)==2) { val fd=ParcelFileDescriptor.dup((payload.fields[4] as ParcelFileDescriptor).fileDescriptor); Thread { try { check(sourceFileComplete.await(15,TimeUnit.SECONDS)); DeadlineFdStreams.input(fd).use { check(it.readBytes().contentEquals(streamFixture)); sourceFile.countDown() } } catch(e: Throwable) { e.printStackTrace() } }.start() }
                        else if(payload.int(2)==3) { val fd=ParcelFileDescriptor.dup((payload.fields[4] as ParcelFileDescriptor).fileDescriptor); Thread { try { DeadlineFdStreams.input(fd).use { stream -> val bytes=stream.readBytes(); check(bytes.contentEquals(streamFixture)); sourceStream.countDown() } } catch(e: Throwable) { e.printStackTrace() } }.start() } } }
                }
                val sourcePayloadWithProgress = callback("IPayloadListener") { code,data ->
                    if(code==3) DctRecord.nullable(data,ClassicSchemas.update)?.use { val progress=it.obj(2)!!; if(progress.long(1)==712005L && progress.int(2)==1)sourceFileComplete.countDown() }
                    else { val copy=Parcel.obtain(); try { copy.writeInterfaceToken(PREFIX+"IPayloadListener"); copy.appendFrom(data,data.dataPosition(),data.dataAvail()); copy.setDataPosition(0); sourcePayload.transact(code,copy,null,IBinder.FLAG_ONEWAY) } finally { copy.recycle() } }
                }
                val sourceLifecycle = callback("IConnectionLifecycleListener") { code,data ->
                    if(code==2) { val record = requireNotNull(DctRecord.nullable(data,ClassicSchemas.initiated)); record.use { sourceEndpoint.set(requireNotNull(it.text(1))); sourceToken.set(requireNotNull(it.bytes(6))); sourceInitiated.countDown() } }
                    if(code==3) { val record = requireNotNull(DctRecord.nullable(data,ClassicSchemas.connected)); record.use { println("GMS connection status="+it.int(2)); check(it.int(2)==0) } }
                }
                val start = callback("IStartAdvertisingResultListener") { _,data -> val record = requireNotNull(DctRecord.nullable(data,ClassicSchemas.started)); record.use { println("GMS LAN advertising status="+it.int(1)); check(it.int(1)==0); advertised.countDown() } }
                // Internal AdvertisingOptions field9 is enableWifiLan (not field7/lowPower).
                val sourceOptions = ClassicSchemas.options + mapOf(9 to DctRecord.Kind.Bool,12 to DctRecord.Kind.Bool)
                call(source,2001,DctRecord(ClassicSchemas.start + mapOf(6 to DctRecord.Kind.Object { sourceOptions }),mapOf(1 to start,3 to "Synthetic source",4 to service,6 to DctRecord(sourceOptions,mapOf(1 to DctRecord(ClassicSchemas.strategy,mapOf(3 to 1,4 to 3)),2 to true,3 to true,4 to true,5 to true,9 to true,12 to false)),7 to sourceLifecycle,8 to "Synthetic source".toByteArray())))
                check(advertised.await(25,TimeUnit.SECONDS)) { "GMS LAN advertising timed out" }
                val targetPayload = callback("IPayloadListener") { code,data -> if(code==2) { val record = requireNotNull(DctRecord.nullable(data,ClassicSchemas.received)); record.use { val payload=it.obj(2)!!; if(payload.int(2)==1) { check(payload.bytes(3)!!.contentEquals(fixture)); echoed.countDown() }
                    else if(payload.int(2)==2) { val fd=ParcelFileDescriptor.dup((payload.fields[4] as ParcelFileDescriptor).fileDescriptor); Thread { try { check(targetFileComplete.await(15,TimeUnit.SECONDS)); DeadlineFdStreams.input(fd).use { check(it.readBytes().contentEquals(streamFixture)); targetFile.countDown() } } catch(e: Throwable) { e.printStackTrace() } }.start() }
                    else if(payload.int(2)==3) { val fd=ParcelFileDescriptor.dup((payload.fields[4] as ParcelFileDescriptor).fileDescriptor); Thread { try { DeadlineFdStreams.input(fd).use { stream -> check(stream.readBytes().contentEquals(streamFixture)); targetStream.countDown() } } catch(e: Throwable) { e.printStackTrace() } }.start() } } } }
                val targetPayloadWithProgress = callback("IPayloadListener") { code,data ->
                    if(code==3) DctRecord.nullable(data,ClassicSchemas.update)?.use { val progress=it.obj(2)!!; if(progress.long(1)==712006L && progress.int(2)==1)targetFileComplete.countDown() }
                    else { val copy=Parcel.obtain(); try { copy.writeInterfaceToken(PREFIX+"IPayloadListener"); copy.appendFrom(data,data.dataPosition(),data.dataAvail()); copy.setDataPosition(0); targetPayload.transact(code,copy,null,IBinder.FLAG_ONEWAY) } finally { copy.recycle() } }
                }
                val targetLifecycle = callback("IConnectionLifecycleListener") { code,data ->
                    if(code==2) { val record = requireNotNull(DctRecord.nullable(data,ClassicSchemas.initiated)); val endpoint = record.text(1)!!; val token = record.bytes(6)!!.copyOf(); record.close()
                        Thread {
                            try { check(sourceInitiated.await(10,TimeUnit.SECONDS)); check(MessageDigest.isEqual(token,sourceToken.get())) { "Production GMS UKEY2 token mismatch" }
                                call(source,2006,DctRecord(ClassicSchemas.accept,mapOf(1 to result,3 to sourceEndpoint.get(),5 to sourcePayloadWithProgress)))
                                call(target!!,2006,DctRecord(ClassicSchemas.accept,mapOf(1 to result,3 to endpoint,5 to targetPayloadWithProgress)))
                            } catch(e: Throwable) { e.printStackTrace(); val message = "FAIL: "+e.javaClass.simpleName+": "+e.message; java.io.File(context.filesDir,"nearby-result.txt").writeText(message+"\n"); report(message) } finally { token.fill(0) }
                        }.start()
                    }
                    if(code==3) { val record = requireNotNull(DctRecord.nullable(data,ClassicSchemas.connected)); record.use { println("Cyclon connection status="+it.int(2)); check(it.int(2)==0); targetConnected.countDown() } }
                }
                target = ClassicNearbyBinder(context,Process.myUid())
                val discovery = callback("IDiscoveryListener") { code,data -> if(code==2) { val record = requireNotNull(DctRecord.nullable(data,ClassicSchemas.found)); val endpoint = record.text(1)!!; targetPeer.set(endpoint); record.close(); call(target!!,2005,DctRecord(ClassicSchemas.request,mapOf(1 to result,4 to "Synthetic target",5 to endpoint,7 to targetLifecycle,8 to "Synthetic target".toByteArray()))) } }
                call(target!!,2003,DctRecord(ClassicSchemas.discovery,mapOf(1 to result,3 to service,5 to DctRecord(ClassicSchemas.options,mapOf(1 to DctRecord(ClassicSchemas.strategy,mapOf(3 to 1,4 to 3)))),6 to discovery)))
                check(targetConnected.await(45,TimeUnit.SECONDS)) { "Production GMS to Cyclon LAN connection timed out" }
                call(target!!,2008,DctRecord(ClassicSchemas.send,mapOf(1 to result,2 to arrayOf(targetPeer.get()),3 to DctRecord(ClassicSchemas.payload,mapOf(1 to 712001L,2 to 1,3 to fixture)))))
                // Target peer ID is captured from discovery, distinct from sourceEndpoint.
                check(received.await(15,TimeUnit.SECONDS)) { "GMS did not receive exact synthetic bytes" }
                call(source,2008,DctRecord(ClassicSchemas.send,mapOf(1 to result,2 to arrayOf(sourceEndpoint.get()),3 to DctRecord(ClassicSchemas.payload,mapOf(1 to 712002L,2 to 1,3 to fixture)))))
                check(echoed.await(15,TimeUnit.SECONDS)) { "Cyclon did not receive exact synthetic bytes" }
                fun streamTo(binder: IBinder, endpoint: String, id: Long) {
                    val pipe=ParcelFileDescriptor.createPipe(); val completion=ParcelFileDescriptor.createPipe()
                    try { call(binder,2008,DctRecord(ClassicSchemas.send,mapOf(1 to result,2 to arrayOf(endpoint),3 to DctRecord(ClassicSchemas.payload,mapOf(1 to id,2 to 3,6 to -1L,4 to pipe[0],7 to completion[0]))))) }
                    finally { pipe[0].close(); completion[0].close() }
                    Thread { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(streamFixture) }; ParcelFileDescriptor.AutoCloseOutputStream(completion[1]).use { it.write(1) } }.start()
                }
                streamTo(target!!,targetPeer.get(),712003L)
                check(sourceStream.await(15,TimeUnit.SECONDS)) { "GMS stream FD bytes differ or stalled" }
                streamTo(source,sourceEndpoint.get(),712004L)
                check(targetStream.await(15,TimeUnit.SECONDS)) { "Cyclon stream FD bytes differ or stalled" }
                fun fileTo(binder: IBinder, endpoint: String, id: Long) {
                    val file=java.io.File(context.cacheDir,"fixture-$id.bin").apply { writeBytes(streamFixture) }
                    ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        call(binder,2008,DctRecord(ClassicSchemas.send,mapOf(1 to result,2 to arrayOf(endpoint),3 to DctRecord(ClassicSchemas.payload,mapOf(1 to id,2 to 2,4 to descriptor,6 to streamFixture.size.toLong())))))
                    }
                }
                fileTo(target!!,targetPeer.get(),712005L)
                check(sourceFile.await(20,TimeUnit.SECONDS)) { "GMS FILE bytes differ or stalled" }
                fileTo(source,sourceEndpoint.get(),712006L)
                check(targetFile.await(20,TimeUnit.SECONDS)) { "Cyclon FILE bytes differ or stalled" }
                call(source,2010,DctRecord(ClassicSchemas.result,mapOf(1 to result)))
                println("PASS: production GMS classic LAN discovery/Binder/UKEY2 acceptance and bidirectional encrypted payload bytes")
                println("Not tested: SmartDevice setup bootstrap, Android Switch, Google accounts, physical radios")
                val message = "PASS: production GMS classic LAN/Binder/UKEY2 and bidirectional encrypted BYTES, 100003-byte STREAM/status pipes and FILE payloads"
                java.io.File(context.filesDir,"nearby-result.txt").writeText(message+"\n")
                report(message)
            } catch(e: Throwable) { e.printStackTrace(); val message = "FAIL: "+e.javaClass.simpleName+": "+e.message; java.io.File(context.filesDir,"nearby-result.txt").writeText(message+"\n"); report(message) }
            finally { target?.close(); runCatching { context.unbindService(connection) }; worker.shutdownNow() }
        }
    }
}
