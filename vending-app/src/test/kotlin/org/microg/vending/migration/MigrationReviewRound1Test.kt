/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.os.ResultReceiver
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Recovery scenarios from review round 1, using Aurora's actual per-app status keys. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationReviewRound1Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val rows = mutableMapOf<String, Bundle>()
    private val targetVersions = mutableMapOf<String, Long>()
    private val installs = mutableListOf<String>()

    private fun document(name: String = APP, version: Long = 12) = Bundle().apply {
        putString("package_name", name)
        putString("title", "Example app")
        putLong("version_code", version)
    }

    private fun receipt(status: String, rowVersion: Long = 12, installedVersion: Long = 0,
        packagePresent: Boolean = installedVersion > 0, name: String = APP) = Bundle().apply {
        putString("package_name", name)
        putString("status", status)
        putLong("version_code", rowVersion)
        putLong("installed_version_code", installedVersion)
        putBoolean("package_present", packagePresent)
        putBoolean("installed", packagePresent && (status == "installed" || status == "none"))
    }

    private fun queue() = MigrationInstallQueue(context, controlCall = { _, _ ->
        Bundle().apply {
            putInt("bridge_version", 2)
            putBoolean("install_options", true)
            putBoolean("automatic_update_hold", true)
            putBoolean("anonymous_prepare", true)
            putBoolean("owner_approved_retry", true)
            putBoolean("success", true)
            putBoolean("ready", true)
        }
    }, storeCall = { operation, name ->
        when (operation) {
            2 -> {
                installs += name
                val queued = receipt("queued", targetVersions[name] ?: 12, name = name)
                rows[name] = queued
                Bundle(queued)
            }
            3 -> Bundle(rows[name] ?: receipt("none", 0, name = name))
            else -> error("Unexpected store operation $operation")
        }
    })

    private fun select(queue: MigrationInstallQueue, version: Long = 12, close: Boolean = true): String {
        targetVersions[APP] = version
        val id = queue.beginTransfer()
        queue.select(listOf(document(version = version)))
        if (close) queue.finishSelection(id)
        return id
    }

    private fun result(summary: Bundle, name: String = APP): Bundle =
        summary.getParcelableArray("cyclon_app_results")!!.map { it as Bundle }
            .single { it.getString("package_name") == name }

    private fun receiver(results: MutableList<Pair<Int, Bundle>>) = object : ResultReceiver(null) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            results += resultCode to Bundle(resultData ?: Bundle())
        }
    }

    @Before fun clear() {
        context.getSharedPreferences("migration-installs", 0).edit().clear().commit()
        context.getSharedPreferences("migration-notifications", 0).edit().clear().commit()
        MigrationSetupReceivers.clearForTests()
        notifications.cancelAll()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        rows.clear(); targetVersions.clear(); installs.clear()
    }

    @Test fun olderActiveDownloadKeepsNewerTargetUntilItCanBeDispatched() {
        rows[APP] = receipt("downloading", rowVersion = 12)
        val queue = queue()
        val id = select(queue, version = 13)

        queue.dispatch(APP, id)
        assertEquals(setOf(APP), queue.pending(id))
        assertTrue(installs.isEmpty())
        assertFalse(queue.summary(id).getBoolean("complete"))

        // Settling the pre-existing v12 row must not turn the selected v13 into a stuck receipt.
        rows[APP] = receipt("installed", rowVersion = 12, installedVersion = 12)
        queue.dispatch(APP, id)
        assertEquals(listOf(APP), installs)
        assertTrue(queue.pending(id).isEmpty())
        assertFalse(queue.summary(id).getBoolean("complete"))
        assertFalse(result(queue.summary(id)).getBoolean("installed"))

        rows[APP] = receipt("installed", rowVersion = 13, installedVersion = 13)
        val complete = queue.summary(id)
        assertTrue(complete.getBoolean("complete"))
        assertTrue(complete.getBoolean("all_installed"))
        assertTrue(queue.retryDocuments(id).isEmpty())
        assertEquals(1, installs.size)
    }

    @Test fun removedOlderDownloadDoesNotAuthorizeThePendingNewerInstall() {
        rows[APP] = receipt("downloading", rowVersion = 12)
        val queue = queue()
        val id = select(queue, version = 13)
        queue.dispatch(APP, id)
        assertEquals(setOf(APP), queue.pending(id))

        // Aurora still remembers the successful old attempt, but the owner has removed its package.
        rows[APP] = receipt("installed", rowVersion = 12, packagePresent = false)
        queue.dispatch(APP, id)
        val complete = queue.summary(id)
        assertTrue(complete.getBoolean("complete"))
        assertFalse(complete.getBoolean("all_installed"))
        assertEquals("removed_by_owner", result(complete).getString("error"))
        assertTrue(queue.pending(id).isEmpty())
        assertTrue(queue.retryDocuments(id).isEmpty())
        assertTrue(installs.isEmpty())
    }

    @Test fun interruptedAcknowledgedInstallBecomesReviewableAndCanBeExplicitlyResumed() {
        val original = queue()
        val id = select(original)
        original.dispatch(APP, id)
        assertTrue(original.pending(id).isEmpty())
        assertEquals(1, installs.size)

        rows[APP] = receipt("awaiting_install")
        val restarted = queue()
        val review = restarted.summary(id)
        assertTrue(review.getBoolean("complete"))
        assertFalse(review.getBoolean("all_installed"))
        assertEquals("owner_action_required", result(review).getString("status"))
        assertEquals("installation_confirmation_required", result(review).getString("error"))
        assertTrue(result(review).getBoolean("terminal"))
        assertEquals(listOf(APP), restarted.retryDocuments(id).map { it.getString("package_name") })
        assertEquals(1, installs.size)

        val completions = mutableListOf<Pair<Int, Bundle>>()
        MigrationSetupReceivers.hold(receiver(completions), id)
        MigrationSetupReceivers.publish(restarted)
        assertEquals(listOf(1), completions.map { it.first })
        assertFalse(completions.single().second.getBoolean("all_installed"))
        MigrationNotifications.completed(context, id, review)
        assertEquals(1, notifications.activeNotifications.size)

        restarted.retry(id, APP)
        restarted.dispatch(APP, id)
        assertEquals(2, installs.size)
        assertFalse(restarted.summary(id).getBoolean("complete"))
        rows[APP] = receipt("installed", installedVersion = 12)
        assertTrue(restarted.summary(id).getBoolean("all_installed"))
    }

    @Test fun disappearedAcknowledgedReceiptRequiresApprovalInsteadOfAnAutomaticReinstall() {
        val original = queue()
        val id = select(original)
        original.dispatch(APP, id)
        assertEquals(1, installs.size)
        assertTrue(original.pending(id).isEmpty())

        rows.remove(APP)
        val restarted = queue()
        val review = restarted.summary(id)
        assertTrue(review.getBoolean("complete"))
        assertFalse(review.getBoolean("all_installed"))
        assertEquals("owner_action_required", result(review).getString("status"))
        assertEquals("installation_requires_review", result(review).getString("error"))
        assertEquals(listOf(APP), restarted.retryDocuments(id).map { it.getString("package_name") })
        restarted.dispatch(APP, id)
        restarted.summary(id)
        assertEquals(1, installs.size)

        restarted.retry(id, APP)
        assertEquals(setOf(APP), restarted.pending(id))
        restarted.dispatch(APP, id)
        assertEquals(2, installs.size)
        assertFalse(restarted.summary(id).getBoolean("complete"))
    }

    @Test fun oldDraftAcknowledgedReceiptWithoutNewMarkerStillBecomesOwnerReview() {
        val id = "old-draft-missing-receipt"
        val prefs = context.getSharedPreferences("migration-installs", 0)
        check(prefs.edit().putString("sessions", id).putString("current_session", id)
            .putBoolean("$id:selected", true).putStringSet("$id:packages", setOf(APP))
            .putStringSet("$id:pending", emptySet()).putString("$id:$APP:status", "queued")
            .putLong("$id:$APP:expectedVersion", 12).commit())
        assertFalse(prefs.contains("$id:$APP:acknowledged"))

        val restarted = queue()
        restarted.finishSelection(id)
        val review = restarted.summary(id)
        assertTrue(review.getBoolean("complete"))
        assertFalse(review.getBoolean("all_installed"))
        assertEquals("owner_action_required", result(review).getString("status"))
        assertEquals("installation_requires_review", result(review).getString("error"))
        assertTrue(result(review).getBoolean("terminal"))
        assertEquals(listOf(APP), restarted.retryDocuments(id).map { it.getString("package_name") })
        restarted.dispatch(APP, id)
        assertTrue(installs.isEmpty())
    }

    @Test fun oldDraftSwallowedTargetIsRestoredAndAdvancedAfterTheOlderAttemptSettles() {
        val id = "old-draft-swallowed-target"
        val prefs = context.getSharedPreferences("migration-installs", 0)
        check(prefs.edit().putString("sessions", id).putString("current_session", id)
            .putBoolean("$id:selected", true).putStringSet("$id:packages", setOf(APP))
            .putStringSet("$id:pending", emptySet()).putString("$id:$APP:status", "downloading")
            .putLong("$id:$APP:expectedVersion", 13).commit())
        assertFalse(prefs.contains("$id:$APP:acknowledged"))
        targetVersions[APP] = 13
        rows[APP] = receipt("downloading", rowVersion = 12)

        val recovered = queue()
        recovered.finishSelection(id)
        assertFalse(recovered.summary(id).getBoolean("complete"))
        assertEquals(setOf(APP), recovered.pending(id))
        assertTrue(installs.isEmpty())

        // The repaired target must remain durable across another process restart.
        val restarted = queue()
        assertEquals(setOf(APP), restarted.pending(id))
        rows[APP] = receipt("installed", rowVersion = 12, installedVersion = 12)
        restarted.dispatch(APP, id)
        assertEquals(listOf(APP), installs)
        assertTrue(restarted.pending(id).isEmpty())
        val installingNewer = restarted.summary(id)
        assertFalse(installingNewer.getBoolean("complete"))
        assertNotEquals("removed_by_owner", result(installingNewer).getString("error"))

        rows[APP] = receipt("installed", rowVersion = 13, installedVersion = 13)
        val complete = restarted.summary(id)
        assertTrue(complete.getBoolean("complete"))
        assertTrue(complete.getBoolean("all_installed"))
        assertTrue(restarted.retryDocuments(id).isEmpty())
        assertEquals(1, installs.size)
    }

    @Test fun disappearedDownloadRowStillAcceptsAVerifiedInstalledPackage() {
        val original = queue()
        val id = select(original)
        original.dispatch(APP, id)
        rows[APP] = receipt("none", rowVersion = 0, installedVersion = 13)

        val restarted = queue()
        val complete = restarted.summary(id)
        assertTrue(complete.getBoolean("complete"))
        assertTrue(complete.getBoolean("all_installed"))
        assertTrue(restarted.retryDocuments(id).isEmpty())
        assertEquals(1, installs.size)
    }

    @Test fun completedFirstChunkCannotReleaseReceiversOrNotifyBeforeSelectionCloses() {
        val queue = queue()
        val id = select(queue, close = false)
        queue.dispatch(APP, id)
        rows[APP] = receipt("installed", installedVersion = 12)
        val progress = mutableListOf<Pair<Int, Bundle>>()
        MigrationSetupReceivers.register(receiver(progress), id)

        val betweenChunks = queue.summary(id)
        assertTrue(betweenChunks.getBoolean("all_installed"))
        assertFalse(betweenChunks.getBoolean("complete"))
        MigrationSetupReceivers.publish(queue)
        MigrationNotifications.completed(context, id, betweenChunks)
        assertEquals(listOf(2), progress.map { it.first })
        assertEquals(0, notifications.activeNotifications.size)
        assertFalse(context.getSharedPreferences("migration-notifications", 0).getBoolean(id, false))

        targetVersions[SECOND_APP] = 4
        queue.select(listOf(document(SECOND_APP, 4)))
        queue.dispatch(SECOND_APP, id)
        rows[SECOND_APP] = receipt("installed", rowVersion = 4, installedVersion = 4, name = SECOND_APP)
        val beforeClosure = queue.summary(id)
        assertEquals(setOf(APP, SECOND_APP), queue.selected(id))
        assertTrue(beforeClosure.getBoolean("all_installed"))
        assertFalse(beforeClosure.getBoolean("complete"))
        MigrationSetupReceivers.publish(queue)
        MigrationNotifications.completed(context, id, beforeClosure)
        assertFalse(progress.any { it.first == 1 })
        assertEquals(0, notifications.activeNotifications.size)

        queue.finishSelection(id)
        val complete = queue.summary(id)
        assertTrue(complete.getBoolean("complete"))
        assertTrue(complete.getBoolean("all_installed"))
        MigrationSetupReceivers.publish(queue)
        MigrationNotifications.completed(context, id, complete)
        MigrationSetupReceivers.publish(queue)
        MigrationNotifications.completed(context, id, complete)
        assertEquals(1, progress.count { it.first == 1 })
        assertEquals(1, notifications.activeNotifications.size)
        val opened = shadowOf(notifications.activeNotifications.single().notification.contentIntent).savedIntent
        assertEquals(id, opened.getStringExtra("session_id"))
    }

    @Test fun emptySelectionOnlyCompletesAfterExplicitClosure() {
        val queue = queue()
        val id = queue.beginTransfer()
        assertFalse(queue.summary(id).getBoolean("complete"))
        queue.select(emptyList())
        assertFalse(queue.summary(id).getBoolean("complete"))
        queue.finishSelection(id)
        assertTrue(queue.summary(id).getBoolean("complete"))
        assertTrue(installs.isEmpty())
    }

    companion object {
        private const val APP = "org.example.app"
        private const val SECOND_APP = "org.example.second"
    }
}
