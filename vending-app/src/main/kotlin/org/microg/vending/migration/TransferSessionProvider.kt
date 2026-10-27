/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** The platform Transfer Phone companion closes selection when the Switch wizard returns. */
class TransferSessionProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = requireNotNull(context)
        val uid = Binder.getCallingUid()
        val manager = context.packageManager
        val companion = try { manager.getApplicationInfo(COMPANION, 0) }
        catch (_: PackageManager.NameNotFoundException) { throw SecurityException("Transfer Phone is not installed") }
        checkCaller(companion, uid, manager)
        require(arg == null) { "Unexpected transfer argument" }
        val outcome: String?
        val expectedSession: String?
        when (method) {
            "getCurrentSession" -> {
                require(extras == null || extras.isEmpty) { "Unexpected session query fields" }
                outcome = null; expectedSession = null
            }
            "transferEnded" -> {
                val request = requireNotNull(extras) { "Missing transfer fields" }
                outcome = request.getString("outcome")
                require(outcome == "success" || outcome == "cancelled") { "Expected success or cancelled outcome" }
                expectedSession = request.getString("session_id")
                require(!request.containsKey("session_id") || expectedSession != null) { "Invalid session identity" }
            }
            else -> throw IllegalArgumentException("Unknown transfer method")
        }
        // The original remote UID must be checked before local scheduling or preference writes use our identity.
        val identity = Binder.clearCallingIdentity()
        try {
            val queue = MigrationInstallQueue(context)
            return if (method == "getCurrentSession") Bundle().apply { putString("session_id", queue.currentSessionId()) }
                else queue.transferEnded(requireNotNull(outcome), expectedSession)
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    private fun checkCaller(companion: ApplicationInfo, uid: Int, manager: PackageManager) {
        val system = companion.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val platform = uid == Process.SYSTEM_UID || manager.checkSignatures("android", COMPANION) == PackageManager.SIGNATURE_MATCH
        if (companion.uid != uid || !system || !platform) throw SecurityException("Only the platform Transfer Phone companion may close a transfer")
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = unsupported()
    override fun getType(uri: Uri): String? = unsupported()
    override fun insert(uri: Uri, values: ContentValues?): Uri? = unsupported()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = unsupported()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = unsupported()
    private fun unsupported(): Nothing = throw UnsupportedOperationException("Only transfer method calls are supported")

    companion object {
        const val AUTHORITY = "com.android.vending.migration"
        private const val COMPANION = "ai.cyclon.migration"
    }
}
