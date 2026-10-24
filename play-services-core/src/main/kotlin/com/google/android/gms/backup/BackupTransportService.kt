/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package com.google.android.gms.backup

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.microg.gms.migration.backup.MigrationBackupTransport

/** Platform-only binding, using the component explicitly selected by Android Switch. */
class BackupTransportService : Service() {
    private lateinit var transport: MigrationBackupTransport
    override fun onCreate() { super.onCreate(); transport = MigrationBackupTransport(this) }
    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == "android.backup.TRANSPORT_HOST") transport.binder else null
    override fun onDestroy() { transport.close(); super.onDestroy() }
}
