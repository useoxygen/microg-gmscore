/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
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

internal const val WIFI_EXPORT_DESCRIPTOR = "com.google.android.gms.backup.d2d.internal.IOsMigrationWifiExportService"
internal const val SOURCE_XOS_DESCRIPTOR = "com.google.android.gms.quickstart.internal.ISourceXosApiService"
private const val SOURCE_XOS_CALLBACK = "com.google.android.gms.quickstart.internal.ISourceXosApiCallbacks"

/** Android-source APIs are separate from the iPhone-import session producer. */
class SwitchExportService : LifecycleService() {
    private val broker = object : AbstractGmsServiceBroker(EnumSet.of(GmsService.BACKUP, GmsService.QUICKSTART)) {
        override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
            val caller = PackageUtils.getAndCheckCallingPackage(this@SwitchExportService, request.packageName)
            if (caller != SWITCH_PACKAGE || !ExtendedPackageInfo(this@SwitchExportService, caller)
                    .hasGooglePackagePermission(GooglePackagePermission.ACCOUNT)) {
                callback.onPostInitComplete(ConnectionResult.DEVELOPER_ERROR, null, null)
                return
            }
            val wifi = service == GmsService.BACKUP
            // XOS export is declared but not advertised as implemented.
            val features = if (wifi) arrayOf(Feature("os_migration_wifi_export", 1L)) else emptyArray()
            callback.onPostInitCompleteWithConnectionInfo(ConnectionResult.SUCCESS,
                ExportStateBinder(Binder.getCallingUid(), wifi), ConnectionInfo().apply { this.features = features })
        }
    }
    override fun onBind(intent: Intent): IBinder { super.onBind(intent); return broker }
}

internal class ExportStateBinder(uid: Int, private val wifi: Boolean) :
    MigrationBinder(if (wifi) WIFI_EXPORT_DESCRIPTOR else SOURCE_XOS_DESCRIPTOR, uid) {
    override fun dispatch(code: Int, data: Parcel, reply: Parcel?): Boolean {
        if (wifi) {
            if (code != 1) return false
            data.requireEnd()
            requireNotNull(reply).writeNoException()
            reply.writeList(emptyList<Any>()) // no Wi-Fi export records managed by microG
            return true
        }
        if (code != 1 && code != 2) return false
        val callback = requireNotNull(data.readStrongBinder())
        data.readString()
        data.skipSafeParcelable() // attribution
        data.requireEnd()
        sendCallback(callback, SOURCE_XOS_CALLBACK, code) {
            writeNullable(Status(17, "Android-source XOS export is unavailable"))
            if (code == 1) writeString(null) else writeInt(0)
            writeInt(0) // attribution
        }
        reply?.writeNoException()
        return true
    }
}
