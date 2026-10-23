/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.BuildConfig
import org.microg.gms.checkin.LastCheckinInfo
import org.microg.gms.common.Constants
import org.microg.gms.gcm.GcmDatabase
import org.microg.gms.gcm.GcmPrefs
import org.microg.gms.gcm.RegisterRequest

/** App-scoped push registration, while every account retains its own encrypted SyncEntities identity. */
internal object PasswordDeviceRegistration {
    fun requestPushConnection(context: Context) {
        if (!GcmPrefs.get(context).isEnabled) return
        // The normal receiver owns check-in and per-network consent. Do not force a
        // connection, change messaging settings or replace another subsystem's token.
        context.sendBroadcast(Intent(context, org.microg.gms.gcm.TriggerReceiver::class.java)
            .setAction("org.microg.gms.gcm.RECONNECT"))
    }

    fun info(context: Context, previous: DeviceRegistration?): SyncDeviceInfo {
        val packageManager = context.packageManager
        val formFactor = when {
            packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) -> 3
            packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH) -> 4
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) -> 5
            context.resources.configuration.smallestScreenWidthDp >= 600 -> 2
            else -> 1
        }
        return SyncDeviceInfo(Build.MODEL.takeUnless { it.isNullOrBlank() } ?: "Unknown Device",
            Build.MANUFACTURER.takeUnless { it.isNullOrBlank() } ?: "Unknown",
            BuildConfig.VERSION_CODE.toString(), formFactor, pushToken(context, previous))
    }

    private fun pushToken(context: Context, previous: DeviceRegistration?): String? {
        if (!GcmPrefs.get(context).isEnabled) return null
        val cached = previous?.info?.let { Proto.decode(it).optionalString(7) }
        val age = System.currentTimeMillis() - (previous?.updatedMillis ?: 0)
        if (!cached.isNullOrEmpty() && age >= 0 && age < SyncDeviceInfo.REFRESH_SECONDS * 1000) return cached
        val checkin = LastCheckinInfo.read(context)
        if (checkin.lastCheckin <= 0) return cached
        val database = GcmDatabase(context)
        try {
            // Respect existing messaging consent. Do not change another subsystem's token or settings.
            if (database.getApp(Constants.GMS_PACKAGE_NAME)?.allowRegister != true) return null
            val sender = PasswordInvalidation.SENDER
            return try {
                RegisterRequest().build(context).checkin(checkin)
                    .app(Constants.GMS_PACKAGE_NAME, Constants.GMS_PACKAGE_SIGNATURE_SHA1, BuildConfig.VERSION_CODE)
                    .sender(sender).extraParam("subscription", sender).extraParam("subtype", sender).extraParam("scope", "GCM")
                    .response.token?.takeIf { it.isNotEmpty() && it.length <= 16_384 } ?: cached
            } catch (_: java.io.IOException) { cached }
        } finally { database.close() }
    }
}
