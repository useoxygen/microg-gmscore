/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle

/** A stable notification and durable receipt prevent repeated alerts after job/process restarts. */
internal object MigrationNotifications {
    private const val CHANNEL = "migration-results"
    private const val ID = 700

    @Synchronized fun completed(context: Context, sessionId: String, summary: Bundle) {
        if (!summary.getBoolean("complete")) return
        val prefs = context.getSharedPreferences("migration-notifications", Context.MODE_PRIVATE)
        if (prefs.getBoolean(sessionId, false)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Transferred apps", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val results = summary.getParcelableArray("cyclon_app_results")?.map { it as Bundle }.orEmpty()
        val installed = results.count { it.getBoolean("installed") }
        val intent = Intent(context, MigrationResultsActivity::class.java)
            .setAction("ai.cyclon.migration.APP_RESULTS")
            .setData(Uri.Builder().scheme("migration-results").authority("session").appendPath(sessionId).build())
            .putExtra("session_id", sessionId)
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (installed == results.size) "$installed apps installed" else "$installed of ${results.size} apps installed. Review the remaining apps."
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Transferred apps are ready to review")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            manager.notify("migration:$sessionId", ID, notification)
            check(prefs.edit().putBoolean(sessionId, true).commit())
        } catch (_: SecurityException) {
            // Keep the receipt unset so a later observation can notify after permission is granted.
        }
    }
}
