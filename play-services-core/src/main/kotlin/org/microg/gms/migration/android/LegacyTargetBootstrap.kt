/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import android.content.Context
import android.os.*
import android.util.Base64
import org.json.JSONObject
import org.microg.gms.migration.*
import org.microg.gms.migration.nearby.DctRecord
import org.microg.gms.migration.nearby.NEARBY_DESCRIPTOR
import java.io.Closeable
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** GMS26.41.31 gekf/getz legacy bootstrap. The target issues the secret; a source
 * BootstrapConfigurations flag4 acknowledges post-setup support. This is distinct
 * from the newer QuickStart request_wifi producer. Only accepted Nearby payloads
 * reach this parser. It never imports account credentials or silently completes them. */
internal class LegacyBootstrapExchange(private val options: DctRecord,
    private val commit: (AndroidPostSetupState.VerifiedBootstrap) -> Unit) : Closeable {
    private val secret = ByteArray(32).also(SecureRandom()::nextBytes)
    val sessionId = (SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(1)
    private var sent = false
    private var acknowledged = false
    private var closed = false
    var sourceFlags = 0L; private set
    fun initial(name: String): ByteArray {
        check(!closed && !sent); sent = true
        val json = JSONObject()
        // Preserve known scalar caller options. Nested/account/OOB options require
        // their own implementation; do not advertise them by copying opaque objects.
        val names = mapOf(2 to "protocol",3 to "accountRequirement",6 to "deviceType",10 to "flowType",11 to "transportMedium",13 to "supportsDevicePolicySetup")
        names.forEach { (field,key) -> options.fields[field]?.let { json.put(key,it) } }
        json.put("sessionId",sessionId).put("deviceName",name)
            .put("isWiFiBootstrappable",false).put("supportsAccountTransferImport",false)
            .put("isAccountTransferImportAllowed",false).put("supportsPacketMode",false)
            .put("optionFlags",options.long(18) or (1L shl 11))
            .put("optionFlagsSetIndicator",options.long(20) or (1L shl 11))
            .put("SharedSecret",Base64.encodeToString(secret,Base64.NO_WRAP))
            .put("targetDeviceFingerprint",Build.FINGERPRINT)
        // gelh sets this exact default when option flag8 selects Wi-Fi D2D;
        // source gffo resolves it before reporting its post-transfer action.
        if(options.long(18) and 128L != 0L) json.put("PostTransferAction",JSONObject().put("wifiD2D","intent:#Intent;action=com.google.android.gms.backup.ACTION_WIFI_D2D;package=com.google.android.gms;end"))
        return JSONObject().put("protocolVersion",3).put("bootstrapOptions",json).toString().toByteArray(Charsets.UTF_8)
    }
    /** Returns the source terminal state, or0 for an acknowledged configuration.
     * No peer-supplied secret/session ID can replace our issued values. */
    @Synchronized fun receive(bytes: ByteArray): Int {
        check(sent && !closed); require(bytes.size in 1..65536)
        val message = JSONObject(bytes.toString(Charsets.UTF_8))
        require(message.optInt("protocolVersion",3) == 3)
        if(listOf("accountBootstrapPayload","accountTransferMsg","secondDeviceAuthPayload","folsomDataPayload").any(message::has))
            throw BootstrapFailure(10573)
        if(listOf("workProfilePayload","blockstorePayload","esimActivationPayloads").any(message::has))
            throw BootstrapFailure(10581)
        val state = message.optInt("bootstrapState",0)
        if(state == 1) throw BootstrapFailure(10564)
        if(state == 3) throw BootstrapFailure(message.optJSONObject("deviceStatus")?.optInt("errorCode",10575) ?: 10575)
        message.optJSONObject("bootstrapConfigurations")?.let { configuration ->
            check(!acknowledged) { "Bootstrap configuration replay" }
            sourceFlags = configuration.optLong("optionFlags",0)
            if(sourceFlags and (1L shl 3) != 0L) {
                val retained = AndroidPostSetupState.VerifiedBootstrap(sessionId.toString(),secret)
                try { commit(retained) } catch(e: Throwable) { retained.close(); throw e }
                acknowledged = true
            }
            // Wi-Fi provisioning was explicitly disabled above. Do not claim it
            // succeeded if a peer nevertheless asks us to apply a saved network.
            if(configuration.optString("wifiNetworkSsid").isNotEmpty()) throw BootstrapFailure(10557)
        }
        require(state in listOf(0,2,4)) { "Unsupported bootstrap state" }
        if(state == 2 || state == 4) check(acknowledged) { "Source completed without post-setup acknowledgement" }
        return state
    }
    fun terminal(state: Int, error: Int? = null): ByteArray = JSONObject().put("protocolVersion",3)
        .put("bootstrapState",state).apply { if(error != null) put("deviceStatus",JSONObject().put("errorCode",error)) }.toString().toByteArray(Charsets.UTF_8)
    @Synchronized override fun close() { closed = true; secret.fill(0) }
    class BootstrapFailure(val status: Int) : Exception("SmartDevice bootstrap unavailable ($status)")
    companion object {
        private val I = DctRecord.Kind.IntValue; private val Z = DctRecord.Kind.Bool; private val L = DctRecord.Kind.LongValue
        val schema = mapOf(2 to I,3 to I,4 to Z,6 to I,7 to DctRecord.Kind.Text,9 to Z,10 to I,11 to I,12 to L,13 to Z,14 to Z,15 to Z,16 to Z,17 to I,18 to L,19 to I,20 to L,22 to DctRecord.Kind.Bytes,25 to I,26 to Z,27 to DctRecord.Kind.Text,28 to I,30 to DctRecord.Kind.Text)
    }
}

/** Uses the same classic Binder/media adapter as Switch's Nearby client. Google
 * auto-accepts the transport and forwards its PIN (gekt.b); confirmation remains
 * in the source/target UI, not an invented boolean return from listener tx3. */
internal class LegacyTargetBootstrap(private val context: Context, options: DctRecord,
    private val listener: IBinder, commit: (AndroidPostSetupState.VerifiedBootstrap) -> Unit,
    private val started: (Int) -> Unit) : Closeable {
    private val closed = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val nearby = ClassicNearbyBinder(context,Process.myUid())
    private val exchange = LegacyBootstrapExchange(options,commit)
    private var endpoint: String? = null
    private var finished = false
    val isActive: Boolean get() = !closed.get()
    val name: String = advertisingName(context)
    var pin: String?=null; private set
    private val death = IBinder.DeathRecipient { close() }
    private val prefix = "com.google.android.gms.nearby.internal.connection."
    private val listenerDescriptor = "com.google.android.gms.smartdevice.d2d.internal.ITargetBootstrapListener"
    private fun notify(code: Int, body: Parcel.() -> Unit = {}) {
        if(code == 8) { sendCallback(listener,listenerDescriptor,code,body); return }
        val data=Parcel.obtain(); val reply=Parcel.obtain()
        try { data.writeInterfaceToken(listenerDescriptor); data.body(); check(listener.transact(code,data,reply,0)); reply.readException() }
        finally { data.recycle(); reply.recycle() }
    }
    private fun call(code: Int, value: DctRecord) {
        val data=Parcel.obtain(); val reply=Parcel.obtain()
        try { data.writeInterfaceToken(NEARBY_DESCRIPTOR); data.writeNullable(value); check(nearby.transact(code,data,reply,0)); reply.readException() }
        finally { data.recycle(); reply.recycle() }
    }
    private fun callback(name: String, action: (Int,Parcel) -> Unit): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(prefix+name)
            try { action(code,data) } catch(e: Exception) { worker.execute { fail((e as? LegacyBootstrapExchange.BootstrapFailure)?.status ?: 10557) } }
            reply?.writeNoException(); return true
        }
    }
    private val result = callback("IResultListener") { _,data -> check(data.readInt()==0) }
    private fun send(bytes: ByteArray) { call(2008,DctRecord(ClassicSchemas.send,mapOf(1 to result,2 to arrayOf(requireNotNull(endpoint)),3 to DctRecord(ClassicSchemas.payload,mapOf(1 to SecureRandom().nextLong(),2 to 1,3 to bytes))))) }
    private val payload = callback("IPayloadListener") { code,data ->
        if(code == 2) DctRecord.nullable(data,ClassicSchemas.received)?.use { value ->
            val content=value.obj(2)!!; require(content.int(2)==1)
            val bytes=requireNotNull(content.bytes(3)).copyOf()
            worker.execute { try {
                val state=exchange.receive(bytes)
                if(state == 2 || state == 4) {
                    finished = true
                    val schema=mapOf(2 to DctRecord.Kind.IntValue,7 to DctRecord.Kind.IntValue,8 to DctRecord.Kind.LongValue,9 to DctRecord.Kind.Bool)
                    notify(8) { writeNullable(DctRecord(schema,mapOf(2 to (if(exchange.sourceFlags and (1L shl 7)!=0L)1 else 0),7 to (if(exchange.sourceFlags and 2L!=0L)4 else 0),8 to 0L,9 to false))) }
                    close()
                }
            } catch(e: Exception) { fail((e as? LegacyBootstrapExchange.BootstrapFailure)?.status ?: 10557) } finally { bytes.fill(0) } }
        }
    }
    private val lifecycle = callback("IConnectionLifecycleListener") { code,data ->
        when(code) {
            2 -> DctRecord.nullable(data,ClassicSchemas.initiated)?.use { value ->
                val peer=requireNotNull(value.text(1)); val pin=requireNotNull(value.text(3))
                worker.execute { if(endpoint == null) { endpoint=peer; this.pin=pin; notify(6) { writeString(pin) }; call(2006,DctRecord(ClassicSchemas.accept,mapOf(1 to result,3 to peer,5 to payload))) } else call(2007,DctRecord(ClassicSchemas.reject,mapOf(1 to result,2 to peer))) }
            }
            3 -> DctRecord.nullable(data,ClassicSchemas.connected)?.use { value -> val status=value.int(2); worker.execute {
                if(status != 0) fail(10557) else {
                    call(2002,DctRecord(ClassicSchemas.result,mapOf(1 to result)))
                    val source=DctRecord(emptyMap()); val args=DctRecord(mapOf(2 to DctRecord.Kind.Object { emptyMap() }),mapOf(2 to source))
                    notify(1) { writeNullable(args) }; send(exchange.initial(name))
                }
            } }
            4 -> worker.execute { if(!finished) { runCatching { notify(2) }; close() } }
        }
    }
    fun start() {
        listener.linkToDeath(death,0)
        worker.schedule({ fail(10557) },120,TimeUnit.SECONDS)
        worker.execute { try {
            val advertised=callback("IStartAdvertisingResultListener") { _,data -> DctRecord.nullable(data,ClassicSchemas.started)?.use { started(if(it.int(1)==0)0 else 10557) } }
            val options=DctRecord(ClassicSchemas.options,mapOf(1 to DctRecord(ClassicSchemas.strategy,mapOf(3 to 1,4 to 1))))
            call(2001,DctRecord(ClassicSchemas.start,mapOf(1 to advertised,3 to name,4 to SERVICE_ID,6 to options,7 to lifecycle,8 to name.toByteArray(Charsets.UTF_8))))
        } catch (_: Exception) { started(10557); close() } }
    }
    private fun fail(status: Int) { if(closed.get()) return; runCatching { if(endpoint != null) send(exchange.terminal(3,status)) }; runCatching { notify(7) { writeInt(status) } }; close() }
    override fun close() { if(!closed.compareAndSet(false,true)) return; nearby.close(); exchange.close(); runCatching { listener.unlinkToDeath(death,0) }; worker.shutdownNow(); pin=null }
    companion object {
        const val SERVICE_ID = "com.google.android.gms.smartdevice.NC_ID"
        fun advertisingName(context: Context): String = run {
        val address=runCatching { (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter.address }.getOrNull()
        // gelh/gesc: abs(Objects.hash(adapterAddress)), final three digits.
        val disambiguation=kotlin.math.abs(java.util.Objects.hash(address)).toString().takeLast(3).padStart(3,'0')
        val suffix=" ($disambiguation)"
        var base = android.provider.Settings.Global.getString(context.contentResolver,"device_name") ?: Build.MODEL
        while(base.toByteArray(Charsets.UTF_8).size > 18-suffix.length) base = base.dropLast(1)
        base+suffix
    }
    }
}
