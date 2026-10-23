/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import androidx.lifecycle.LifecycleService
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.Feature
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import org.microg.gms.AbstractGmsServiceBroker
import org.microg.gms.common.GmsService
import org.microg.gms.common.GooglePackagePermission
import org.microg.gms.common.PackageUtils
import org.microg.gms.utils.ExtendedPackageInfo
import java.util.EnumSet

private const val TAG = "GmsSwitchSupport"
internal const val SWITCH_PACKAGE = "com.google.android.apps.restore"
internal const val PAIR_CALLBACK = "com.google.android.gms.smartdevice.postsetup.internal.IPostSetupPairCallbacks"
internal const val TARGET_DESCRIPTOR = "com.google.android.gms.smartdevice.d2d.internal.ITargetDeviceService"
internal const val POST_DESCRIPTOR = "com.google.android.gms.smartdevice.postsetup.internal.IPostSetupService"

/** Only the optional, empty-state queries used before iOS import are supported here. */
class SwitchSupportService : LifecycleService() {
    private val broker = object : AbstractGmsServiceBroker(EnumSet.of(
        GmsService.SMARTDEVICE_D2D_TARGET_DEVICE, GmsService.SMARTDEVICE_POST_SETUP
    )) {
        override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
            val caller = PackageUtils.getAndCheckCallingPackage(this@SwitchSupportService, request.packageName)
            if (caller != SWITCH_PACKAGE || !ExtendedPackageInfo(this@SwitchSupportService, caller)
                    .hasGooglePackagePermission(GooglePackagePermission.ACCOUNT)) {
                callback.onPostInitComplete(ConnectionResult.DEVELOPER_ERROR, null, null)
                return
            }
            val target = service == GmsService.SMARTDEVICE_D2D_TARGET_DEVICE
            val features = if (target) arrayOf(Feature("post_setup_restore_api", 1L))
                else arrayOf(Feature("post_setup_3p_restore_api", 1L))
            callback.onPostInitCompleteWithConnectionInfo(ConnectionResult.SUCCESS,
                EmptySetupStateBinder(Binder.getCallingUid(), target), ConnectionInfo().apply { this.features = features })
        }
    }

    override fun onBind(intent: Intent): IBinder { super.onBind(intent); return broker }
}

internal class EmptySetupStateBinder(uid: Int, private val target: Boolean) :
    MigrationBinder(if (target) TARGET_DESCRIPTOR else POST_DESCRIPTOR, uid) {
    override fun dispatch(code: Int, data: Parcel, reply: Parcel?): Boolean {
        // Target tx21: quick-start restore info. PostSetup tx7: third-party restore info.
        if (code != if (target) 21 else 7) return false
        val callback = requireNotNull(data.readStrongBinder()) { "Missing setup-state callback" }
        data.skipSafeParcelable() // attribution
        data.requireEnd()
        sendCallback(callback, PAIR_CALLBACK, 6) {
            writeNullable(Status.SUCCESS)
            writeNullable(Bundle())
        }
        Log.d(TAG, "Empty optional setup state returned: ${if (target) "target" else "postsetup"}")
        reply?.writeNoException()
        return true
    }
}
