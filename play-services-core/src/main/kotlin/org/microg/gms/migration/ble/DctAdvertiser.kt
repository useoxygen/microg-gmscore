/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import java.io.Closeable

/** One connectable advertisement, started only after Android reserves its IPv6 PSM. */
internal class DctAdvertiser(context: Context) : Closeable {
    private val advertiser = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser
    private var active: AdvertiseCallback? = null

    @Synchronized fun start(sessionId: String, psm: Int, onResult: (Int) -> Unit) {
        check(active == null)
        val radio = checkNotNull(advertiser) { "BLE advertising unavailable" }
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                synchronized(this@DctAdvertiser) { if (active === this) onResult(0) }
            }
            override fun onStartFailure(errorCode: Int) {
                synchronized(this@DctAdvertiser) { if (active === this) onResult(errorCode) }
            }
        }
        active = callback
        try {
            radio.startAdvertising(AdvertiseSettings.Builder().setConnectable(true)
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setTimeout(0).build(),
                AdvertiseData.Builder().addServiceData(ParcelUuid.fromString(DctAdvertisement.SERVICE_UUID),
                    DctAdvertisement.target(sessionId, psm)).build(), callback)
        } catch (t: Exception) { close(); throw t }
    }

    @Synchronized override fun close() {
        val callback = active
        active = null
        callback?.let { advertiser?.stopAdvertising(it) }
    }
}
