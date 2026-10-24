/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import org.microg.gms.migration.android.AndroidTargetDeviceBinder
import org.microg.gms.migration.nearby.DctRecord
import org.microg.gms.migration.nearby.DctSchemas
import org.microg.gms.migration.session.DctSessions
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

/** Signature-bound Switch target contracts. No Android paired state or account result is manufactured. */
class SwitchSupportService : LifecycleService() {
    private val targets = mutableListOf<AndroidTargetDeviceBinder>()
    private val broker = object : AbstractGmsServiceBroker(EnumSet.of(
        GmsService.SMARTDEVICE_D2D_TARGET_DEVICE, GmsService.SMARTDEVICE_POST_SETUP,
        GmsService.SMARTDEVICE_TARGET_DIRECT_TRANSFER
    )) {
        override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
            val caller = PackageUtils.getAndCheckCallingPackage(this@SwitchSupportService, request.packageName)
            if (caller != SWITCH_PACKAGE || !ExtendedPackageInfo(this@SwitchSupportService, caller)
                    .hasGooglePackagePermission(GooglePackagePermission.ACCOUNT)) {
                callback.onPostInitComplete(ConnectionResult.DEVELOPER_ERROR, null, null)
                return
            }
            val direct = service == GmsService.SMARTDEVICE_TARGET_DIRECT_TRANSFER
            val target = service == GmsService.SMARTDEVICE_D2D_TARGET_DEVICE || direct
            val features = if (direct) emptyArray() else if (target) arrayOf(Feature("post_setup_restore_api", 1L), Feature("post_setup_pair", 4L), Feature("post_setup_clean_restore_api", 1L))
                else arrayOf(Feature("post_setup_3p_restore_api", 1L), Feature("post_setup_api", 1L))
            val binder = if (target) AndroidTargetDeviceBinder(Binder.getCallingUid(), direct = direct).also {
                synchronized(targets) { targets.add(it) }
            } else EmptySetupStateBinder(Binder.getCallingUid(), false)
            callback.onPostInitCompleteWithConnectionInfo(ConnectionResult.SUCCESS, binder,
                ConnectionInfo().apply { this.features = features })
        }
    }

    override fun onBind(intent: Intent): IBinder { super.onBind(intent); return broker }
    override fun onDestroy() {
        synchronized(targets) { targets.forEach { it.close() }; targets.clear() }
        super.onDestroy()
    }
}

internal class EmptySetupStateBinder(uid: Int, private val target: Boolean) :
    MigrationBinder(if (target) TARGET_DESCRIPTOR else POST_DESCRIPTOR, uid) {
    override fun dispatch(code: Int, data: Parcel, reply: Parcel?): Boolean {
        if (!target && code == 9) {
            val callback = requireNotNull(data.readStrongBinder())
            require(data.dataAvail() <= 65536)
            requireNotNull(data.readString()); requireNotNull(data.readString())
            val present = data.readInt()
            require(present in 0..1)
            if (present == 1) Bundle.CREATOR.createFromParcel(data) // Keep contents lazy; never invoke/start them.
            data.skipApiMetadata(); data.requireEnd()
            sendCallback(callback, PAIR_CALLBACK, 8) {
                writeNullable(Status(10590)) // No authenticated Android source session.
                writeNullable(DctRecord(mapOf(1 to DctRecord.Kind.IntValue), mapOf(1 to 0)))
            }
            reply?.writeNoException(); return true
        }
        if (!target && code == 1) {
            val callback = requireNotNull(data.readStrongBinder())
            data.skipApiMetadata(); data.requireEnd()
            val retained = DctSessions.retained()
            sendCallback(callback, PAIR_CALLBACK, 1) {
                writeNullable(if (retained == null) Status(10590) else Status.SUCCESS)
                writeNullable(DctRecord(DctSchemas.hint, if (retained == null) emptyMap() else
                    mapOf(9 to retained.session.sessionId, 10 to retained.session.password)))
            }
            reply?.writeNoException(); return true
        }
        // Target tx21: quick-start restore info. PostSetup tx7: third-party restore info.
        if (code != if (target) 21 else 7) return false
        val callback = requireNotNull(data.readStrongBinder()) { "Missing setup-state callback" }
        data.skipApiMetadata()
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
