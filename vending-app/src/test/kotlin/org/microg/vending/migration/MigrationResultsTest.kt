/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationResultsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun result(status: String, error: String? = null, installed: Boolean = false) = Bundle().apply {
        putString("status", status); putString("error", error); putBoolean("installed", installed)
    }
    private fun summary(complete: Boolean = true, settled: Boolean = complete, closed: Boolean = complete) = Bundle().apply {
        putBoolean("complete", complete)
        putBoolean("installs_settled", settled)
        putBoolean("selection_closed", closed)
        putParcelableArray("cyclon_app_results", arrayOf(result("installed", installed = true), result("failed", "not_found")))
    }
    @Before fun clear() {
        context.getSharedPreferences("migration-notifications", 0).edit().clear().commit()
        manager.cancelAll()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
    @Test fun progressHasNoCompletionNotification() {
        MigrationNotifications.completed(context, "transfer-one", summary(complete = false))
        assertEquals(0, manager.activeNotifications.size)
        assertFalse(context.getSharedPreferences("migration-notifications", 0).getBoolean("transfer-one", false))
    }
    @Test fun completionPostsOneNotificationPerSessionAndOpensItsResults() {
        MigrationNotifications.completed(context, "transfer-one", summary())
        val notification = manager.activeNotifications.single().notification
        assertEquals("1 of 2 apps installed. Review the remaining apps.", notification.extras.getCharSequence("android.text").toString())
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals("ai.cyclon.migration.APP_RESULTS", intent.action)
        assertEquals("transfer-one", intent.getStringExtra("session_id"))
        assertEquals(MigrationResultsActivity::class.java.name, intent.component!!.className)
        manager.cancelAll()
        MigrationNotifications.completed(context, "transfer-one", summary())
        assertEquals(0, manager.activeNotifications.size)
        MigrationNotifications.completed(context, "transfer-two", summary())
        assertEquals(1, manager.activeNotifications.size)
        assertEquals("transfer-two", shadowOf(manager.activeNotifications.single().notification.contentIntent).savedIntent.getStringExtra("session_id"))
    }
    @Test fun deniedNotificationCanBeDeliveredAfterPermissionIsGranted() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        MigrationNotifications.completed(context, "transfer-one", summary())
        assertEquals(0, manager.activeNotifications.size)
        assertFalse(context.getSharedPreferences("migration-notifications", 0).getBoolean("transfer-one", false))
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        MigrationNotifications.completed(context, "transfer-one", summary())
        assertEquals(1, manager.activeNotifications.size)
    }
    @Test fun settledOpenSelectionPostsReviewPromptWithoutClaimingCompletion() {
        MigrationNotifications.selectionOpen(context, "transfer-one", summary(complete = false, settled = true))
        val active = manager.activeNotifications.single()
        val notification = active.notification
        assertEquals("Finish app selection", notification.extras.getCharSequence("android.title").toString())
        assertTrue(notification.extras.getCharSequence("android.text").toString().contains("still open"))
        assertEquals("migration:transfer-one", active.tag)
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals("ai.cyclon.migration.APP_RESULTS", intent.action)
        assertEquals("transfer-one", intent.getStringExtra("session_id"))
        assertEquals(MigrationResultsActivity::class.java.name, intent.component!!.className)
        assertTrue(notification.contentIntent.isImmutable)
        val prefs = context.getSharedPreferences("migration-notifications", 0)
        assertTrue(prefs.getBoolean("transfer-one:review_notified", false))
        assertFalse(prefs.getBoolean("transfer-one", false))
    }
    @Test fun reviewPromptIsNotRepeatedAfterOwnerDismissesIt() {
        val state = summary(complete = false, settled = true)
        MigrationNotifications.selectionOpen(context, "transfer-one", state)
        manager.cancelAll()
        repeat(3) { MigrationNotifications.selectionOpen(context, "transfer-one", state) }
        assertEquals(0, manager.activeNotifications.size)
        assertFalse(context.getSharedPreferences("migration-notifications", 0).getBoolean("transfer-one", false))
    }
    @Test fun reviewPromptClearsDuringAnotherChunkAndCanReturnAfterThatChunkSettles() {
        val state = summary(complete = false, settled = true)
        MigrationNotifications.selectionOpen(context, "transfer-one", state)
        MigrationNotifications.selectionOpen(context, "transfer-one", summary(complete = false))
        assertEquals(0, manager.activeNotifications.size)
        val prefs = context.getSharedPreferences("migration-notifications", 0)
        assertTrue(prefs.getBoolean("transfer-one:review_notified", false))
        assertFalse(prefs.getBoolean("transfer-one:review_active", false))
        MigrationNotifications.selectionOpen(context, "transfer-one", state)
        assertEquals(1, manager.activeNotifications.size)
        MigrationNotifications.selectionOpen(context, "transfer-one", state)
        assertEquals(1, manager.activeNotifications.size)
        assertFalse(prefs.getBoolean("transfer-one", false))
    }
    @Test fun closedSelectionClearsOnlyTheReviewPromptAndLeavesFinalDeliveryAvailable() {
        MigrationNotifications.selectionOpen(context, "transfer-one", summary(complete = false, settled = true))
        MigrationNotifications.selectionOpen(context, "transfer-one", summary(complete = false, closed = true))
        assertEquals(0, manager.activeNotifications.size)
        assertFalse(context.getSharedPreferences("migration-notifications", 0).getBoolean("transfer-one", false))
        MigrationNotifications.completed(context, "transfer-one", summary())
        assertEquals(1, manager.activeNotifications.size)
        MigrationNotifications.selectionOpen(context, "transfer-one", summary())
        assertEquals(1, manager.activeNotifications.size)
    }
    @Test fun completionReplacesTheReviewPromptAndStillHasItsOwnOnceOnlyReceipt() {
        MigrationNotifications.selectionOpen(context, "transfer-one", summary(complete = false, settled = true))
        val before = manager.activeNotifications.single()
        MigrationNotifications.completed(context, "transfer-one", summary())
        val after = manager.activeNotifications.single()
        assertEquals(before.tag, after.tag)
        assertEquals(before.id, after.id)
        assertEquals("Transferred apps are ready to review", after.notification.extras.getCharSequence("android.title").toString())
        assertEquals(0, after.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        val prefs = context.getSharedPreferences("migration-notifications", 0)
        assertTrue(prefs.getBoolean("transfer-one", false))
        assertFalse(prefs.getBoolean("transfer-one:review_active", false))
        MigrationNotifications.clearSelectionOpen(context, "transfer-one")
        assertEquals(1, manager.activeNotifications.size)
        manager.cancelAll()
        MigrationNotifications.completed(context, "transfer-one", summary())
        MigrationNotifications.selectionOpen(context, "transfer-one", summary(complete = false, settled = true))
        assertEquals(0, manager.activeNotifications.size)
    }
    @Test fun reviewPromptDoesNotAppearDuringInstallsOrAfterSelectionClosure() {
        MigrationNotifications.selectionOpen(context, "transfer-one", summary(complete = false))
        MigrationNotifications.selectionOpen(context, "transfer-two", summary(complete = false, settled = true, closed = true))
        assertEquals(0, manager.activeNotifications.size)
    }
    @Test fun deniedReviewNotificationCanBeDeliveredAfterPermissionIsGranted() {
        val state = summary(complete = false, settled = true)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        MigrationNotifications.selectionOpen(context, "transfer-one", state)
        val prefs = context.getSharedPreferences("migration-notifications", 0)
        assertEquals(0, manager.activeNotifications.size)
        assertFalse(prefs.getBoolean("transfer-one:review_notified", false))
        assertFalse(prefs.getBoolean("transfer-one", false))
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        MigrationNotifications.selectionOpen(context, "transfer-one", state)
        assertEquals(1, manager.activeNotifications.size)
        assertTrue(prefs.getBoolean("transfer-one:review_notified", false))
        assertFalse(prefs.getBoolean("transfer-one", false))
    }
    @Test fun anEmptySettledSelectionStillOffersOwnerRecoveryInsteadOfClaimingZeroAppsInstalled() {
        val state = summary(complete = false, settled = true).apply { putParcelableArray("cyclon_app_results", emptyArray<Bundle>()) }
        MigrationNotifications.selectionOpen(context, "transfer-one", state)
        val notification = manager.activeNotifications.single().notification
        assertEquals("Finish app selection", notification.extras.getCharSequence("android.title").toString())
        assertFalse(notification.extras.getCharSequence("android.text").toString().contains("installed"))
    }
    @Test fun failureReasonsAreReadableAndDoNotExposeUnknownCodes() {
        assertTrue(MigrationResultText.description(result("failed", "not_found")).contains("not available"))
        assertTrue(MigrationResultText.description(result("failed", "rate_limited")).contains("Try again later"))
        assertTrue(MigrationResultText.description(result("removed", "owner_removed")).contains("will not be installed again"))
        assertFalse(MigrationResultText.description(result("failed", "UNRECOGNIZED_INTERNAL_123")).contains("UNRECOGNIZED_INTERNAL_123"))
        assertEquals("Installed and verified", MigrationResultText.description(result("installed", installed = true)))
        assertEquals("Downloading", MigrationResultText.description(result("downloading")))
    }
}
