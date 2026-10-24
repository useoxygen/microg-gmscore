/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.io.Closeable

internal class DctScanner(context: Context, private val found: (BluetoothDevice, Map<Int, ByteArray>) -> Unit,
                          private val failure: () -> Unit) : Closeable {
    private val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
    @Volatile private var closed = false
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (closed) return
            val data = result.scanRecord?.getServiceData(ParcelUuid.fromString(DctAdvertisement.SERVICE_UUID)) ?: return
            val elements = runCatching { DctAdvertisement.decode(data) }.getOrNull() ?: return
            if (elements[1]?.size != 6 || elements[4]?.size != 2) return
            found(result.device, elements)
        }
        override fun onScanFailed(errorCode: Int) { if (!closed) { close(); failure() } }
    }
    fun start() {
        check(!closed)
        requireNotNull(scanner).startScan(listOf(ScanFilter.Builder()
            .setServiceData(ParcelUuid.fromString(DctAdvertisement.SERVICE_UUID), byteArrayOf()).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
    }
    override fun close() { if (!closed) { closed = true; scanner?.stopScan(callback) } }
}
