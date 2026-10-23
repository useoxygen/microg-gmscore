/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.app.Service
import android.accounts.Account
import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.util.Log
import org.microg.gms.common.GooglePackagePermission
import org.microg.gms.common.PackageUtils
import org.microg.gms.utils.ExtendedPackageInfo

private const val HELPER = "com.google.android.gms.backup.migrate.helper.ID2dMigrateHelper"
private const val BROKER = "com.google.android.gms.backup.migrate.helper.ID2dMigrateHelperBroker"
private const val BROKER_CALLBACK = "com.google.android.gms.backup.migrate.helper.ID2dMigrateHelperBrokerCallbacks"
private const val BOOLEAN_CALLBACK = "com.google.android.gms.backup.migrate.helper.IBooleanCallbacks"
private const val VOID_CALLBACK = "com.google.android.gms.backup.migrate.helper.IVoidCallbacks"

/** No Google backup/restore staging exists in microG. Query it as absent; don't fake a restore. */
class MigrateHelperService : Service() {
    override fun onBind(intent: Intent): IBinder = object : MigrationBinder(BROKER, null) {
        override fun dispatch(code: Int, data: Parcel, reply: Parcel?): Boolean {
            requireSwitchCaller()
            if (code != 1) return false
            require(data.readInt() == 1) { "Missing migrate-helper version request" }
            require(data.createByteArray()?.contentEquals(byteArrayOf(8, 1)) == true) { "Unsupported migrate-helper version" }
            val callback = requireNotNull(data.readStrongBinder())
            data.requireEnd()
            val helper = EmptyMigrateHelper(Binder.getCallingUid())
            sendCallback(callback, BROKER_CALLBACK, 1) {
                writeInt(1) // nullable Parcelable containing a protobuf byte array
                writeByteArray(byteArrayOf(8, 1)) // field 1: broker protocol version 1
                writeStrongBinder(helper)
            }
            reply?.writeNoException()
            Log.d("GmsSwitchSupport", "Migrate-helper v1 connected; no Google restore staging")
            return true
        }
    }

    private fun requireSwitchCaller() {
        val caller = PackageUtils.getAndCheckCallingPackage(this, SWITCH_PACKAGE)
        check(caller == SWITCH_PACKAGE && ExtendedPackageInfo(this, caller)
            .hasGooglePackagePermission(GooglePackagePermission.ACCOUNT)) { "Untrusted migration caller" }
    }
}

internal class EmptyMigrateHelper(uid: Int) : MigrationBinder(HELPER, uid) {
    override fun dispatch(code: Int, data: Parcel, reply: Parcel?): Boolean {
        when (code) {
            1 -> {
                val callback = requireNotNull(data.readStrongBinder())
                data.requireEnd()
                sendCallback(callback, VOID_CALLBACK, 1)
            }
            2, 8 -> {
                data.readString() // requested restore artifact; none have been staged
                val callback = requireNotNull(data.readStrongBinder())
                data.requireEnd()
                sendCallback(callback, BOOLEAN_CALLBACK, 1) { writeInt(0) }
            }
            6, 7 -> {
                val callback = requireNotNull(data.readStrongBinder())
                data.requireEnd()
                // Google restore metadata generation is unavailable. Failure, never fabricated files.
                sendCallback(callback, VOID_CALLBACK, 2)
            }
            3 -> {
                data.readString()
                val file = if (data.readInt() != 0) ParcelFileDescriptor.CREATOR.createFromParcel(data) else null
                try {
                    val callback = requireNotNull(data.readStrongBinder())
                    data.requireEnd()
                    sendCallback(callback, VOID_CALLBACK, 2) // bytes were not staged
                } finally { file?.close() }
            }
            4, 5, 11 -> {
                data.readString()
                val callback = requireNotNull(data.readStrongBinder())
                data.requireEnd()
                sendCallback(callback, VOID_CALLBACK, if (code == 11) 1 else 2)
            }
            9, 10 -> {
                if (data.readInt() != 0) Account.CREATOR.createFromParcel(data)
                repeat(if (code == 9) 3 else 5) { data.createByteArray() }
                if (code == 10) { data.readInt(); data.createByteArray() }
                if (data.readInt() != 0) ComponentName.CREATOR.createFromParcel(data)
                val callback = requireNotNull(data.readStrongBinder())
                data.requireEnd()
                sendCallback(callback, VOID_CALLBACK, 2) // credential recovery is unsupported
            }
            else -> return false
        }
        reply?.writeNoException()
        return true
    }
}
