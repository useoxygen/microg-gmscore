/*
 * SPDX-FileCopyrightText: 2024 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.common

import android.os.Binder
import android.os.Parcel
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.Feature
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import com.google.android.gms.common.internal.TelemetryData
import com.google.android.gms.common.internal.service.IClientTelemetryService
import org.microg.gms.BaseService

private const val TAG = "ClientTelemetryService"

/** The telemetry features current Play services clients require (client library 25.36+). */
internal val TELEMETRY_FEATURES = arrayOf(
    Feature("CLIENT_TELEMETRY", 1),
    Feature("CLIENT_NOTIFICATION_TELEMETRY", 1),
    Feature("CLIENT_THROTTLING_TELEMETRY", 1)
)

class ClientTelemetryService : BaseService(TAG, GmsService.CLIENT_TELEMETRY) {
    override fun handleServiceRequest(callback: IGmsCallbacks?, request: GetServiceRequest?, service: GmsService?) {
        callback?.onPostInitCompleteWithConnectionInfo(
            ConnectionResult.SUCCESS,
            ClientTelemetryServiceImpl(lifecycle).asBinder(),
            ConnectionInfo().apply {
                features = TELEMETRY_FEATURES
            }
        )
    }
}

class ClientTelemetryServiceImpl(override val lifecycle: Lifecycle) : IClientTelemetryService.Stub(), LifecycleOwner {

    override fun log(data: TelemetryData?) {
        Log.d(TAG, "log: $data")
    }

}

/**
 * Notification and throttling telemetry (services 449 and 483). Google's client libraries
 * 25.36+ report notification and throttling statistics here; below that Play services
 * version they post "Update Google Play services" instead. Both interfaces are one oneway
 * call (code 1) with one parcelable. Reports are discarded: nothing leaves the phone.
 */
abstract class DiscardingTelemetryService(tag: String, service: GmsService, private val descriptor: String) :
    BaseService(tag, service) {
    override fun handleServiceRequest(callback: IGmsCallbacks?, request: GetServiceRequest?, service: GmsService?) {
        callback?.onPostInitCompleteWithConnectionInfo(
            ConnectionResult.SUCCESS,
            DiscardingTelemetryBinder(descriptor),
            ConnectionInfo().apply { features = TELEMETRY_FEATURES }
        )
    }
}

class ClientNotificationTelemetryService : DiscardingTelemetryService(
    "NotificationTelemetry", GmsService.TELEMETRY_NOTIFICATION,
    "com.google.android.gms.common.internal.service.IClientNotificationTelemetryService"
)

class ClientThrottlingTelemetryService : DiscardingTelemetryService(
    "ThrottlingTelemetry", GmsService.TELEMETRY_THROTTLING,
    "com.google.android.gms.common.internal.service.IClientThrottlingTelemetryService"
)

internal class DiscardingTelemetryBinder(private val descriptor: String) : Binder() {
    init { attachInterface(null, descriptor) }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == 1) {
            data.enforceInterface(descriptor)
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }
}
