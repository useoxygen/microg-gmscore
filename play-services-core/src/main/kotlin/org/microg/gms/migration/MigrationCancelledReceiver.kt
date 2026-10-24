/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.microg.gms.migration.session.DctSessions

/** BACKUP sender permission is enforced by the manifest; unprivileged apps cannot cancel sessions. */
class MigrationCancelledReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.google.android.gms.smartdevice.OS_MIGRATION_CANCELLED") DctSessions.retained()?.close()
    }
}
