/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.content.Context
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** One owner approval authorizes one enqueue, including when Aurora loses its reply. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationReviewRound2Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("migration-installs", Context.MODE_PRIVATE)
    private var row: Bundle? = null
    private var acceptedInstalls = 0
    private var loseReply = false
    private var throwReply = false
    private var targetVersion = 12L
    private lateinit var sessionId: String

    private fun receipt(status: String, version: Long = targetVersion, installedVersion: Long = 0) = Bundle().apply {
        putString("package_name", APP)
        putString("status", status)
        putLong("version_code", version)
        putLong("installed_version_code", installedVersion)
        putBoolean("package_present", installedVersion > 0)
        putBoolean("installed", installedVersion > 0 && status in setOf("installed", "none"))
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
                // Observe the durable state at the IPC boundary, before any acknowledgement can clear it.
                assertTrue(prefs.getBoolean("$sessionId:$APP:uncertain", false))
                assertFalse("Approval must already be consumed before enqueue IPC",
                    prefs.getBoolean("$sessionId:$APP:owner_retry", false))
                acceptedInstalls++
                if (loseReply) {
                    // Aurora accepted and installed, then the owner removed the app and its receipt.
                    // FakeStore never observed the successful install before its reply was lost.
                    row = receipt("installed", installedVersion = targetVersion)
                    row = null
                    if (throwReply) throw IllegalStateException("Enqueue reply lost")
                    MigrationInstallQueue.unavailable(name, "store_unavailable")
                } else {
                    receipt("queued").also { row = Bundle(it) }
                }
            }
            3 -> Bundle(row ?: receipt("none", version = 0))
            else -> error("Unexpected store operation $operation")
        }
    })

    private fun select(queue: MigrationInstallQueue) {
        sessionId = queue.beginTransfer()
        queue.select(listOf(Bundle().apply {
            putString("package_name", APP)
            putLong("version_code", targetVersion)
        }))
        queue.finishSelection(sessionId)
    }

    private fun result(summary: Bundle) = summary.getParcelableArray("cyclon_app_results")!!.single() as Bundle

    @Before fun clear() {
        check(prefs.edit().clear().commit())
        row = null
        acceptedInstalls = 0
        loseReply = false
        throwReply = false
        targetVersion = 12
    }

    private fun assertLostApprovedRetryCannotBeReplayed(throwFailure: Boolean) {
        val original = queue()
        select(original)
        assertTrue(original.dispatch(APP, sessionId))
        assertEquals(1, acceptedInstalls)

        row = null
        val missing = original.summary(sessionId)
        assertEquals("owner_action_required", result(missing).getString("status"))
        assertEquals("installation_requires_review", result(missing).getString("error"))

        original.retry(sessionId, APP)
        assertTrue(prefs.getBoolean("$sessionId:$APP:owner_retry", false))
        loseReply = true
        throwReply = throwFailure
        assertFalse(original.dispatch(APP, sessionId))
        assertEquals(2, acceptedInstalls)
        assertTrue(prefs.getBoolean("$sessionId:$APP:uncertain", false))
        assertFalse(prefs.getBoolean("$sessionId:$APP:owner_retry", false))
        assertEquals(setOf(APP), original.pending(sessionId))

        // Reconstruct the queue without another approval. A missing receipt must not replay tx2.
        val restarted = queue()
        loseReply = false
        assertTrue(restarted.dispatch(APP, sessionId))
        val review = restarted.summary(sessionId)
        assertTrue(review.getBoolean("complete"))
        assertFalse(review.getBoolean("all_installed"))
        assertEquals("owner_action_required", result(review).getString("status"))
        assertEquals("installation_requires_review", result(review).getString("error"))
        assertTrue(restarted.pending(sessionId).isEmpty())
        assertFalse(prefs.getBoolean("$sessionId:$APP:owner_retry", false))
        repeat(3) { restarted.dispatch(APP, sessionId); restarted.summary(sessionId) }
        assertEquals("One owner Retry must not authorize a second enqueue", 2, acceptedInstalls)

        // A fresh, separate owner action can authorize exactly one new request.
        restarted.retry(sessionId, APP)
        assertTrue(restarted.dispatch(APP, sessionId))
        assertEquals(3, acceptedInstalls)
        assertFalse(restarted.summary(sessionId).getBoolean("complete"))
        row = receipt("installed", installedVersion = targetVersion)
        val complete = restarted.summary(sessionId)
        assertTrue(complete.getBoolean("complete"))
        assertTrue(complete.getBoolean("all_installed"))
        assertEquals(3, acceptedInstalls)
    }

    @Test fun ownerRetryIsConsumedBeforeAnAcceptedEnqueueReturnsAnUnavailableReply() {
        assertLostApprovedRetryCannotBeReplayed(throwFailure = false)
    }

    @Test fun ownerRetryIsConsumedBeforeAnAcceptedEnqueueThrowsWhileReturningItsReply() {
        assertLostApprovedRetryCannotBeReplayed(throwFailure = true)
    }

    @Test fun waitingForAnOlderActiveAttemptRetainsApprovalUntilANewEnqueueIsSent() {
        targetVersion = 13
        val original = queue()
        select(original)
        assertTrue(original.dispatch(APP, sessionId))
        row = null
        assertEquals("owner_action_required", result(original.summary(sessionId)).getString("status"))
        original.retry(sessionId, APP)

        row = receipt("downloading", version = 12)
        assertFalse(original.dispatch(APP, sessionId))
        assertEquals(1, acceptedInstalls)
        assertTrue(prefs.getBoolean("$sessionId:$APP:owner_retry", false))
        assertFalse(prefs.getBoolean("$sessionId:$APP:uncertain", false))
        assertEquals(setOf(APP), original.pending(sessionId))

        row = receipt("installed", version = 12, installedVersion = 12)
        assertTrue(original.dispatch(APP, sessionId))
        assertEquals(2, acceptedInstalls)
        assertFalse(prefs.getBoolean("$sessionId:$APP:owner_retry", false))
        assertTrue(original.pending(sessionId).isEmpty())
        row = receipt("installed", installedVersion = targetVersion)
        assertTrue(original.summary(sessionId).getBoolean("all_installed"))
    }

    companion object { private const val APP = "org.example.app" }
}
