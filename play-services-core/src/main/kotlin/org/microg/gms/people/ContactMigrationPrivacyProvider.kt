/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import org.microg.gms.cyclon.DeviceAccount
import android.accounts.AccountManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Binder
import android.os.Process

/** call() enforces its own permission as well as the manifest. No caller may release the upload hold. */
class ContactMigrationPrivacyProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (Binder.getCallingUid() != Process.myUid()) requireNotNull(context).enforceCallingPermission(
            "android.permission.NETWORK_SETUP_WIZARD", "Trusted setup caller required")
        val privacy = ContactMigrationPrivacy(requireNotNull(context))
        return when (method) {
            "beginMigration" -> {
                DeviceAccount.googleAccounts(requireNotNull(context)).forEach {
                    ContactSyncPreferences(requireNotNull(context), it).ensureDefaults()
                }
                privacy.beginMigration(); Bundle().apply { putBoolean("uploads_held", privacy.held); putBoolean("developer_controls", privacy.developerControlsEnabled) }
            }
            "status" -> Bundle().apply { putBoolean("uploads_held", privacy.held) }
            else -> null
        }
    }
    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
