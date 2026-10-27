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
import androidx.core.app.NotificationCompat

/** A stable notification and durable receipt prevent repeated alerts after job/process restarts. */
internal object MigrationNotifications {
    private const val CHANNEL = "migration-results"
    private const val ID = 700

    /** A review prompt is not a completed-transfer receipt and never closes the selection itself. */
    @Synchronized fun selectionOpen(context: Context, sessionId: String, summary: Bundle) {
        if (summary.getBoolean("selection_closed") || !summary.getBoolean("installs_settled")) {
            clearSelectionOpen(context, sessionId)
            return
        }
        if (summary.getBoolean("complete")) return
        val prefs = context.getSharedPreferences("migration-notifications", Context.MODE_PRIVATE)
        if (prefs.getBoolean(sessionId, false) || prefs.getBoolean("$sessionId:review_active", false)) return
        val manager = availableManager(context) ?: return
        val text = "Your app transfer is still open. Review your apps and finish selection when you are ready."
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Finish app selection")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openResults(context, sessionId))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            // A later chunk can replace this prompt with active installs. Restore it quietly once those settle.
            .setSilent(prefs.getBoolean("$sessionId:review_notified", false))
            .build()
        try {
            manager.notify("migration:$sessionId", ID, notification)
            check(prefs.edit().putBoolean("$sessionId:review_notified", true)
                .putBoolean("$sessionId:review_active", true).commit())
        } catch (_: SecurityException) {
            // Permission recovery must be able to post the prompt later.
        }
    }

    @Synchronized fun clearSelectionOpen(context: Context, sessionId: String) {
        val prefs = context.getSharedPreferences("migration-notifications", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("$sessionId:review_active", false)) return
        // This tag is also used by the final summary. Never cancel an already completed notification.
        if (!prefs.getBoolean(sessionId, false)) {
            context.getSystemService(NotificationManager::class.java).cancel("migration:$sessionId", ID)
        }
        check(prefs.edit().putBoolean("$sessionId:review_active", false).commit())
    }

    @Synchronized fun completed(context: Context, sessionId: String, summary: Bundle) {
        if (!summary.getBoolean("complete")) return
        val prefs = context.getSharedPreferences("migration-notifications", Context.MODE_PRIVATE)
        if (prefs.getBoolean(sessionId, false)) return
        val manager = availableManager(context) ?: return
        val results = summary.getParcelableArray("cyclon_app_results")?.map { it as Bundle }.orEmpty()
        val installed = results.count { it.getBoolean("installed") }
        val text = if (installed == results.size) "$installed apps installed" else "$installed of ${results.size} apps installed. Review the remaining apps."
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Transferred apps are ready to review")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openResults(context, sessionId))
            .setAutoCancel(true)
            // Completion deserves its own alert even when it replaces the non-terminal review prompt.
            .setOnlyAlertOnce(!prefs.getBoolean("$sessionId:review_active", false))
            .build()
        try {
            manager.notify("migration:$sessionId", ID, notification)
            check(prefs.edit().putBoolean(sessionId, true).putBoolean("$sessionId:review_active", false).commit())
        } catch (_: SecurityException) {
            // Keep the receipt unset so a later observation can notify after permission is granted.
        }
    }

    private fun availableManager(context: Context): NotificationManager? {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !manager.areNotificationsEnabled()) return null
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Transferred apps", NotificationManager.IMPORTANCE_DEFAULT))
        return manager.takeUnless { it.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE }
    }

    private fun openResults(context: Context, sessionId: String): PendingIntent {
        val intent = Intent(context, MigrationResultsActivity::class.java)
            .setAction("ai.cyclon.migration.APP_RESULTS")
            .setData(Uri.Builder().scheme("migration-results").authority("session").appendPath(sessionId).build())
            .putExtra("session_id", sessionId)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
