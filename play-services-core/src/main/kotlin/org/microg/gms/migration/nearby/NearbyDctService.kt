/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.nearby

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import androidx.lifecycle.LifecycleService
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.Feature
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import org.microg.gms.AbstractGmsServiceBroker
import org.microg.gms.common.GmsService
import org.microg.gms.common.GooglePackagePermission
import org.microg.gms.common.PackageUtils
import org.microg.gms.migration.*
import org.microg.gms.migration.controller.DctController
import org.microg.gms.migration.dct.DeviceInfoResponse
import org.microg.gms.migration.payload.DctPayloadBridge
import org.microg.gms.migration.session.DctSession
import org.microg.gms.migration.session.DctSessions
import org.microg.gms.utils.ExtendedPackageInfo
import java.io.Closeable
import java.util.EnumSet
import org.microg.gms.migration.ble.DctScanner

internal const val NEARBY_DESCRIPTOR = "com.google.android.gms.nearby.internal.connection.INearbyConnectionService"
private const val PREFIX = "com.google.android.gms.nearby.internal.connection."

class NearbyDctService : LifecycleService() {
    private val clients = mutableListOf<NearbyDctBinder>()
    private val broker = object : AbstractGmsServiceBroker(EnumSet.of(GmsService.NEARBY_CONNECTIONS)) {
        override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
            val caller = PackageUtils.getAndCheckCallingPackage(this@NearbyDctService, request.packageName)
            if (caller != SWITCH_PACKAGE || !ExtendedPackageInfo(this@NearbyDctService, caller)
                    .hasGooglePackagePermission(GooglePackagePermission.ACCOUNT)) {
                callback.onPostInitComplete(ConnectionResult.DEVELOPER_ERROR, null, null); return
            }
            val binder = NearbyDctBinder(this@NearbyDctService, Binder.getCallingUid())
            synchronized(clients) { clients.add(binder) }
            callback.onPostInitCompleteWithConnectionInfo(ConnectionResult.SUCCESS, binder, ConnectionInfo().apply {
                features = arrayOf(Feature("nearby_connections_v3", 1L))
            })
        }
    }
    override fun onBind(intent: Intent): IBinder { super.onBind(intent); return broker }
    override fun onDestroy() { synchronized(clients) { clients.forEach { it.close() }; clients.clear() }; super.onDestroy() }
}

internal class NearbyDctBinder(private val context: Context, uid: Int) : MigrationBinder(NEARBY_DESCRIPTOR, uid), Closeable {
    private var scanner: DctScanner? = null
    private val discovered = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Int>>()
    private var controller: DctController? = null
    private var lifecycle: IBinder? = null
    private var payloadListener: IBinder? = null
    private var startResult: IBinder? = null
    private var bridge: DctPayloadBridge? = null
    private var connected = false
    private var accepted = false
    private var closed = false
    private val deaths = linkedMapOf<IBinder, IBinder.DeathRecipient>()
    private val lock = Any()
    private fun watch(binder: IBinder) {
        synchronized(lock) {
            if (binder in deaths) return
            val death = IBinder.DeathRecipient { close() }
            binder.linkToDeath(death, 0); deaths[binder] = death
        }
    }
    private fun event(binder: IBinder?, name: String, code: Int, value: DctRecord) {
        if (binder == null) return
        try { sendCallback(binder, PREFIX + name, code) { writeNullable(value) } }
        catch (e: Exception) { close() }
    }
    private fun result(binder: IBinder?, status: Int) {
        if (binder != null) sendCallback(binder, PREFIX + "IResultListener", 2) { writeInt(status) }
    }
    private fun device(): DctRecord {
        val active = requireNotNull(controller)
        return DctRecord(DctSchemas.device, buildMap {
            put(1, active.session.sessionId)
            active.peer?.let { put(3, it.device_model); put(4, it.manufacturer) }
        })
    }
    private val listener = object : DctController.Listener {
        override fun advertising(status: Int) {
            val active = controller ?: return
            val callback = startResult; startResult = null
            event(callback, "IStartAdvertisingResultListener", 2, DctRecord(DctSchemas.startResult,
                mapOf(1 to status, 2 to active.session.sessionId)))
        }
        override fun connected(peer: DeviceInfoResponse) {
            connected = true
            event(lifecycle, "IConnectionLifecycleListener", 2, DctRecord(DctSchemas.initiated,
                mapOf(1 to device().text(1)!!, 4 to true, 9 to 3, 13 to device())))
            if (accepted) connectionResult(0)
        }
        override fun bandwidth(medium: Int, status: Int) {
            event(lifecycle, "IConnectionLifecycleListener", 5, DctRecord(DctSchemas.bandwidth,
                mapOf(1 to device().text(1)!!, 2 to 3, 3 to medium, 4 to 3, 11 to device(), 12 to status)))
        }
        override fun oneWay(type: String, bytes: ByteArray) { bridge?.oneWay(type, bytes) ?: controller?.fail(8009) }
        override fun closed(status: Int) {
            if (startResult != null) advertising(status)
            if (!connected) connectionResult(status)
            event(lifecycle, "IConnectionLifecycleListener", 4, DctRecord(DctSchemas.disconnected,
                mapOf(1 to device().text(1)!!, 2 to 3, 5 to device(), 6 to status)))
            bridge?.close(); bridge = null
        }
    }
    private fun connectionResult(status: Int) = event(lifecycle, "IConnectionLifecycleListener", 3,
        DctRecord(DctSchemas.connected, mapOf(1 to device().text(1)!!, 2 to status, 4 to 3, 7 to device())))
    private fun matches(record: DctRecord, deviceField: Int, endpointField: Int? = null): Boolean {
        val endpoint = record.obj(deviceField)?.text(1) ?: endpointField?.let(record::text)
        return endpoint != null && endpoint == controller?.session?.sessionId
    }
    override fun dispatch(code: Int, data: Parcel, reply: Parcel?): Boolean {
        val schema = when (code) {
            3001 -> DctSchemas.start
            3002 -> DctSchemas.discovery
            3005 -> DctSchemas.connect
            2002, 2004, 2010, 2011 -> DctSchemas.result
            2006 -> DctSchemas.accept
            2007 -> DctSchemas.reject
            2008 -> DctSchemas.send
            2009 -> DctSchemas.disconnect
            2012 -> DctSchemas.cancel
            else -> return false
        }
        val request = requireNotNull(DctRecord.nullable(data, schema)); data.requireEnd()
        var transferred = false
        try {
            val callback = request.binder(1)
            when (code) {
                3001 -> {
                    check(!closed)
                    startResult = requireNotNull(callback); watch(startResult!!)
                    lifecycle = requireNotNull(request.binder(4)); watch(lifecycle!!)
                    check(controller == null) { "Client already started" }
                    require(request.text(6) == "com.google.android.gms.nearby.connection.v3.dct.DctDevice")
                    require(request.text(2) == SWITCH_PACKAGE)
                    val existing = DctSessions.retained()
                    val options = request.obj(3)
                    val password = options?.text(15)
                    val sessionId = options?.obj(16)?.list(1)?.firstOrNull { it.int(1) == 1 }?.bytes(3)?.toString(Charsets.UTF_8)
                    if (existing != null) {
                        require(password == null || password == existing.session.password)
                        require(sessionId == null || sessionId == existing.session.sessionId)
                        controller?.remove(listener); controller = existing; existing.add(listener)
                    } else {
                        val credentials = if (password != null && sessionId != null) DctSession.restore(sessionId, password)
                            else DctSession.create()
                        controller = DctSessions.create(context, credentials, SWITCH_PACKAGE).also { it.add(listener); it.start() }
                    }
                }
                3002 -> {
                    require(request.text(5) == "com.google.android.gms.nearby.connection.v3.dct.DctDevice")
                    require(request.text(2) == SWITCH_PACKAGE)
                    val target = requireNotNull(request.binder(4)); watch(target)
                    scanner?.close(); discovered.clear()
                    val serviceId = requireNotNull(request.text(2))
                    scanner = DctScanner(context, { bluetooth, elements ->
                        val endpoint = requireNotNull(elements[1]).toString(Charsets.UTF_8)
                        val psm = requireNotNull(elements[4]); val port = (psm[0].toInt() and 255) or ((psm[1].toInt() and 255) shl 8)
                        if (port != 0 && discovered.putIfAbsent(endpoint, bluetooth.address to port) == null) {
                            val peer = DctRecord(DctSchemas.device, mapOf(1 to endpoint, 2 to elements.map { (type, bytes) ->
                                DctRecord(DctSchemas.element, mapOf(1 to type, 2 to bytes.size, 3 to bytes)) }))
                            event(target, "IDiscoveryListener", 2, DctRecord(DctSchemas.found,
                                mapOf(1 to endpoint, 3 to serviceId, 6 to 3, 8 to peer)))
                        }
                    }, { result(callback, 8000) }).also { it.start() }
                    result(callback, 0)
                }
                3005 -> {
                    val endpoint = requireNotNull(request.obj(9)?.text(1))
                    val remote = discovered[endpoint]
                    if (request.int(5) != 3 || remote == null || DctSessions.retained() != null) result(callback, 8009)
                    else {
                        lifecycle = requireNotNull(request.binder(2)); watch(lifecycle!!)
                        val credentials = DctSession.restore(endpoint, requireNotNull(request.obj(3)?.text(11)))
                        controller = DctSessions.create(context, credentials, requireNotNull(request.text(8)), remote)
                            .also { it.add(listener); it.start() }
                        result(callback, 0)
                    }
                }
                2002 -> { controller?.stopAdvertising(); result(callback, 0) }
                2004 -> { scanner?.close(); scanner = null; result(callback, 0) }
                2006 -> {
                    if (!matches(request, 9, 3) || request.int(6) != 3 || !connected) result(callback, 8009)
                    else {
                        payloadListener = requireNotNull(request.binder(5)); watch(payloadListener!!)
                        bridge?.close()
                        bridge = DctPayloadBridge(context, { controller?.http }, { payload ->
                            event(payloadListener, "IPayloadListener", 2, DctRecord(DctSchemas.received,
                                mapOf(1 to device().text(1)!!, 2 to payload, 4 to 3, 7 to device())))
                        }, { id, status, total, transferredBytes ->
                            event(payloadListener, "IPayloadListener", 3, DctRecord(DctSchemas.update,
                                mapOf(1 to device().text(1)!!, 2 to DctRecord(DctSchemas.transfer,
                                    mapOf(1 to id, 2 to status, 3 to total, 4 to transferredBytes)), 3 to 3, 6 to device())))
                        })
                        accepted = true; controller?.claimActive(); result(callback, 0); connectionResult(0)
                    }
                }
                2007 -> { result(callback, if (matches(request, 6, 2)) 0 else 8005); if (matches(request, 6, 2)) controller?.fail(8004) }
                2008 -> {
                    val payload = requireNotNull(request.obj(3))
                    val status = if (!accepted || !matches(request, 8) || request.int(5) != 3) 8005 else {
                        transferred = true
                        requireNotNull(bridge).send(payload)
                    }
                    result(callback, status)
                }
                2009 -> if (matches(request, 5, 1)) controller?.close()
                2010, 2011 -> { controller?.close(); result(callback, 0); close() }
                2012 -> result(callback, bridge?.cancel(request.long(2)) ?: 8005)
            }
            reply?.writeNoException(); return true
        } finally { if (!transferred) request.close() }
    }
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true; scanner?.close(); scanner = null; bridge?.close(); bridge = null
            deaths.forEach { (binder, death) -> runCatching { binder.unlinkToDeath(death, 0) } }; deaths.clear()
            controller?.release(listener)
            // Switch is the terminal cluster owner. Its Binder death cancels radios, sockets and descriptors.
            controller = null
        }
    }
}
