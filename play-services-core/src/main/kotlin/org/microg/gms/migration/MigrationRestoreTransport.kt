/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.content.Context
import android.net.Uri
import android.os.Binder

/** Transfer Phone owns the durable lease on the owner's selected backup transport. */
internal class MigrationRestoreTransport(private val context: Context) {
    fun acquire() {
        val identity = Binder.clearCallingIdentity()
        try {
            val result = context.contentResolver.call(AUTHORITY, "acquire", null, null)
            check(result?.getBoolean("success") == true) { "Migration restore transport acquire failed" }
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    companion object {
        private val AUTHORITY = Uri.parse("content://ai.cyclon.migration.restore")
    }
}
