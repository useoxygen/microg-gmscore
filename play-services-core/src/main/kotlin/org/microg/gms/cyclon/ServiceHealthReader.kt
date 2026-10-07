/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import android.Manifest
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.microg.gms.location.LocationSettings
import org.microg.gms.people.ContactSyncPreferences
import org.microg.gms.people.sync.SyncMode
import org.microg.gms.settings.SettingsContract

/** Local, read-only observations. No sign-in, probe, sync request, reconnect, or location request. */
internal class ServiceHealthReader(private val context: Context) {
    suspend fun read(): ServiceHealthSnapshot = withContext(Dispatchers.IO) {
        val enabled = observe {
            SettingsContract.getSettings(context, SettingsContract.Gcm.getContentUri(context),
                arrayOf(SettingsContract.Gcm.ENABLE_GCM)) { it.getInt(0) != 0 }
        }
        // McsService is in :persistent; its static snapshot in the UI process would always be disconnected.
        val connected = if (enabled == true) observe {
            context.contentResolver.call(Uri.parse("content://${context.packageName}.cyclon.push"),
                PushStateProvider.METHOD_STATE, null, null)?.takeIf {
                it.getInt(PushStateProvider.KEY_VERSION) == PushStateProvider.VERSION && it.containsKey("connected")
            }?.getBoolean("connected")
        } else null
        val contacts = observe {
            val accounts = AccountManager.get(context).getAccountsByType("com.google")
            check(accounts.size <= 100)
            accounts.map { account ->
                val prefs = ContactSyncPreferences(context, account)
                ContactHealthObservation(prefs.mode != SyncMode.OFF,
                    observe { ContentResolver.getSyncAutomatically(account, ContactSyncPreferences.AUTHORITY) &&
                        ContentResolver.getIsSyncable(account, ContactSyncPreferences.AUTHORITY) > 0 },
                    contactHealthReason(prefs.status), prefs.lastSuccess)
            }
        }
        val masterSync = observe { ContentResolver.getMasterSyncAutomatically() }
        val contactsPermission = observe {
            listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS).all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }
        val location = observe {
            val settings = LocationSettings(context)
            settings.wifiIchnaea || settings.wifiLearning || settings.wifiCaching ||
                settings.cellIchnaea || settings.cellLearning || settings.cellCaching
        }
        val systemLocation = observe {
            LocationManagerCompat.isLocationEnabled(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
        }
        val permission = observe {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                (Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)
        }
        // Read only the switch and last check-in; device identifiers and credentials never enter the snapshot.
        val registration = observe {
            val keys = arrayOf(SettingsContract.CheckIn.ENABLED, SettingsContract.CheckIn.LAST_CHECK_IN)
            SettingsContract.getSettings(context, SettingsContract.CheckIn.getContentUri(context), keys) { cursor ->
                cursor.getInt(0).let { it != 0 } to cursor.getLong(1)
            }
        }
        ServiceHealthSnapshot(
            pushHealth(enabled, connected), contactsHealth(contacts, masterSync, contactsPermission),
            locationHealth(systemLocation, location, permission), registrationHealth(registration?.first, registration?.second),
            System.currentTimeMillis(), BuildConfig.VERSION_CODE.toLong(), observe {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo("com.android.vending", 0)
                if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            }, Build.VERSION.SDK_INT
        )
    }

    private inline fun <T> observe(block: () -> T): T? = try { block() } catch (_: Exception) { null }
}
