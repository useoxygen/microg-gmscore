/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.*
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** BLE discovery/control plane with regular FEF3 GATT slot reads and the explicit
 * fast-service UUID path. Payloads use classic RFCOMM/LAN, not an invented BLE
 * socket protocol. Owns only this object's scan, advertisements and GATT handles. */
@SuppressLint("MissingPermission")
internal class ClassicBleRadio(private val context: Context, private val service: String,
    private val localId: String, private val info: ByteArray, private val pcp: Int,
    private val found: (ClassicRadio.Peer) -> Unit) : Closeable {
    private val adapter get()=(context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val handler=Handler(Looper.getMainLooper())
    private val closed=AtomicBoolean(false)
    private var advertiser: AdvertiseCallback?=null
    private var scanner: ScanCallback?=null
    private var server: BluetoothGattServer?=null
    private val mtus=ConcurrentHashMap<String,Int>()
    private val clients=ConcurrentHashMap<String,BluetoothGatt>()
    private val fetched=ConcurrentHashMap<String,Long>()
    private val serviceUuid=UUID.fromString("0000fef3-0000-1000-8000-00805f9b34fb")
    private fun slotUuid(slot: Int)=UUID(0x3000,Long.MIN_VALUE or slot.toLong())
    fun advertise(fastUuid: ParcelUuid?, ready: (Int) -> Unit) {
        try {
            check(!closed.get() && advertiser==null && adapter.isEnabled)
            val bytes=if(fastUuid!=null) ClassicBleCodec.fast(service,localId,info,pcp) else ClassicBleCodec.regular(service,localId,info,pcp,adapter.address)
            val begin={
                if(!closed.get()) {
                    val callback=object: AdvertiseCallback() { override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { ready(0) }; override fun onStartFailure(errorCode: Int) { ready(8000); stopAdvertising() } }
                    advertiser=callback
                    adapter.bluetoothLeAdvertiser.startAdvertising(AdvertiseSettings.Builder().setConnectable(fastUuid==null).setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).build(),AdvertiseData.Builder().addServiceData(fastUuid ?: ParcelUuid(serviceUuid),if(fastUuid==null)ClassicBleCodec.header(service,bytes) else bytes).build(),callback)
                }
            }
            if(fastUuid!=null) { begin(); return }
            val callback=object: BluetoothGattServerCallback() {
                override fun onServiceAdded(status: Int, service: BluetoothGattService) { if(status==BluetoothGatt.GATT_SUCCESS)begin() else { ready(8000); stopAdvertising() } }
                override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { if(mtus.size<16) mtus[device.address]=mtu.coerceIn(23,517) }
                override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) { if(newState==BluetoothProfile.STATE_DISCONNECTED)mtus.remove(device.address) }
                override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
                    val owned=server ?: return
                    if(closed.get() || characteristic.uuid!=slotUuid(0) || offset !in 0..bytes.size) { owned.sendResponse(device,requestId,BluetoothGatt.GATT_INVALID_OFFSET,offset,null); return }
                    // Android splits long reads using request offset and the negotiated MTU.
                    owned.sendResponse(device,requestId,BluetoothGatt.GATT_SUCCESS,offset,bytes.copyOfRange(offset,minOf(bytes.size,offset+(mtus[device.address] ?: 23)-1)))
                }
            }
            server=(context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).openGattServer(context,callback)
            val gattService=BluetoothGattService(serviceUuid,BluetoothGattService.SERVICE_TYPE_PRIMARY).apply { addCharacteristic(BluetoothGattCharacteristic(slotUuid(0),BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_READ)) }
            check(requireNotNull(server).addService(gattService))
        } catch(_: Exception) { ready(8000); stopAdvertising() }
    }
    fun scan(fastUuid: ParcelUuid?, ready: (Int) -> Unit) {
        try {
            check(!closed.get() && scanner==null && adapter.isEnabled)
            val uuid=fastUuid ?: ParcelUuid(serviceUuid)
            val callback=object: ScanCallback() {
                override fun onScanFailed(errorCode: Int) { ready(8000) }
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if(closed.get())return
                    val data=result.scanRecord?.getServiceData(uuid) ?: return
                    if(fastUuid!=null) { publish(data); return }
                    // Extended advertisements carry the medium record directly.
                    if(data.size>0 && data[0].toInt() and 0x1c != 0) { publish(data); return }
                    val slots=runCatching { ClassicBleCodec.slots(service,data) }.getOrDefault(0)
                    if(slots in 1..15) fetch(result.device,slots)
                }
            }
            scanner=callback
            adapter.bluetoothLeScanner.startScan(listOf(ScanFilter.Builder().setServiceData(uuid,byteArrayOf()).build()),ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),callback)
            ready(0) // Android startScan has no success callback; failures arrive onScanFailed.
        } catch(_: Exception) { ready(8000); stopDiscovery() }
    }
    private fun publish(bytes: ByteArray) { runCatching {
        val (endpoint,address)=ClassicBleCodec.parse(service,bytes)
        // A BLE random address is not evidence of an RFCOMM address.
        val bt=address
        if(!closed.get() && endpoint.id!=localId && endpoint.pcp==pcp)found(ClassicRadio.Peer(endpoint,bluetooth=bt))
    } }
    private fun fetch(device: BluetoothDevice, slots: Int) {
        synchronized(clients) {
            if(closed.get() || clients.size>=2 || fetched.size>=256 || (fetched[device.address] ?: 0)>SystemClock.elapsedRealtime()-60000)return
            fetched[device.address]=SystemClock.elapsedRealtime()
            var index=0
            val callback=object: BluetoothGattCallback() {
                private fun finish(gatt: BluetoothGatt) { clients.remove(device.address,gatt); runCatching { gatt.disconnect() }; gatt.close() }
                private fun next(gatt: BluetoothGatt) {
                    while(index<slots) { val characteristic=gatt.getService(serviceUuid)?.getCharacteristic(slotUuid(index++)); if(characteristic!=null && gatt.readCharacteristic(characteristic))return }
                    finish(gatt)
                }
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) { if(closed.get() || status!=BluetoothGatt.GATT_SUCCESS || newState!=BluetoothProfile.STATE_CONNECTED)finish(gatt) else if(!gatt.discoverServices())finish(gatt) }
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) { if(status!=BluetoothGatt.GATT_SUCCESS || closed.get())finish(gatt) else next(gatt) }
                @Deprecated("API33 callback also supported")
                override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { read(gatt,characteristic.value,status) }
                override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) { read(gatt,value,status) }
                private fun read(gatt: BluetoothGatt, value: ByteArray?, status: Int) { if(status==BluetoothGatt.GATT_SUCCESS && value!=null)publish(value); if(!closed.get())next(gatt) else finish(gatt) }
            }
            val gatt=device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE) ?: return
            clients[device.address]=gatt
            handler.postDelayed({ if(clients.remove(device.address,gatt)) { runCatching { gatt.disconnect() }; gatt.close() } },15000)
        }
    }
    fun stopAdvertising() { advertiser?.let { runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(it) } }; advertiser=null; server?.close(); server=null; mtus.clear() }
    fun stopDiscovery() { scanner?.let { runCatching { adapter.bluetoothLeScanner?.stopScan(it) } }; scanner=null; clients.values.toList().forEach { runCatching { it.disconnect(); it.close() } }; clients.clear(); fetched.clear() }
    override fun close() { if(closed.compareAndSet(false,true)) { stopAdvertising(); stopDiscovery() } }
}
