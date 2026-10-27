/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar

import android.Manifest
import android.accounts.AccountManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** System companion contract. Calls never grant OAuth consent or change an owner's saved sync choice. */
class CalendarTransferProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = requireNotNull(context)
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid()) {
            context.enforceCallingPermission(PERMISSION, "System transfer caller required")
            val systemCompanion = context.packageManager.getPackagesForUid(uid).orEmpty().any { name ->
                name == COMPANION && context.packageManager.getApplicationInfo(name, 0).flags and
                    (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            }
            if (uid != Process.SYSTEM_UID && !systemCompanion) throw SecurityException("System transfer caller required")
        }
        require(method in setOf("status", "beginMigration", "enableAfterTransfer")) { "Unknown calendar transfer method" }
        // Switch exports into an explicit Google account. Never guess a different account for the companion.
        val account = AccountManager.get(context).getAccountsByType("com.google").firstOrNull { it.name == arg }
            ?: return Bundle().apply { putString("result", "no_account") }
        val prefs = CalendarSyncPreferences(context, account)
        if (method == "beginMigration") prefs.beginMigration()
        val permissionsGranted = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        val result = when {
            prefs.hasSavedChoice && !prefs.enabled -> "saved_off"
            !prefs.enabled -> "consent_required"
            !permissionsGranted -> "permission_required"
            else -> "enabled"
        }
        if (method == "enableAfterTransfer" && result == "enabled") prefs.refreshAfterTransfer()
        return Bundle().apply {
            putString("result", result)
            putBoolean("enabled", prefs.enabled)
            putBoolean("two_way", prefs.twoWay)
            putBoolean("uploads_held", prefs.uploadsHeld)
            putParcelable("settings_intent", Intent().setClassName(context.packageName, SETTINGS)
                .putExtra("account_name", account.name))
        }
    }
    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = throw UnsupportedOperationException()
    companion object {
        const val AUTHORITY = "org.microg.gms.migration.calendar"
        const val PERMISSION = "android.permission.NETWORK_SETUP_WIZARD"
        const val COMPANION = "ai.cyclon.migration"
        const val SETTINGS = "org.microg.gms.calendar.CalendarSyncSettings"
    }
}
