/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/** NETWORK_SETUP_WIZARD protects this endpoint in the manifest. No caller may release the upload hold. */
class ContactMigrationPrivacyProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val privacy = ContactMigrationPrivacy(requireNotNull(context))
        return when (method) {
            "beginMigration" -> { privacy.beginMigration(); Bundle().apply { putBoolean("uploads_held", true) } }
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
