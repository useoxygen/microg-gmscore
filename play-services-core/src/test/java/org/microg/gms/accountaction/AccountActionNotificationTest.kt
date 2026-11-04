/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.accountaction

import android.Manifest
import android.accounts.Account
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "Account action required" goes away once its screen lock is proven (CYC-1185), and only that notice: the re-auth
 * and unsupported-management notices share its id, and the newest of them stays. (The notices' texts are resources,
 * which these tests run without, so they post through the same helper with a plain notification.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AccountActionNotificationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val account = Account("owner@example.com", "com.google")
    private val other = Account("second@example.com", "com.google")

    @Before
    fun allowNotifications() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.createNotificationChannel(NotificationChannel(ACCOUNT_NOTIFICATION_CHANNEL_ID, "Accounts", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun notice(title: String) = Notification.Builder(context, ACCOUNT_NOTIFICATION_CHANNEL_ID).setContentTitle(title).build()
    private fun shown() = manager.activeNotifications.map { it.id to it.notification.extras.getString(Notification.EXTRA_TITLE) }.toSet()
    private fun postAction(account: Account) = context.postAccountActionNotification(account, notice("action"))
    /** The re-auth or unsupported-management notice: same id, no action marker. */
    private fun postOther(account: Account) = manager.notify(account.hashCode(), notice("re-auth"))

    @Test
    fun theProvenAccountsActionNoticeIsCleared() {
        postAction(account)
        postAction(other)

        assertTrue(context.clearAccountActionNotification(account))

        assertEquals(setOf(other.hashCode() to "action"), shown())
    }

    @Test
    fun aNewerReAuthOrUnsupportedNoticeOnTheSameIdStays() {
        postAction(account)
        postOther(account) // replaces the action notice, as before this change

        assertFalse(context.clearAccountActionNotification(account))

        assertEquals(setOf(account.hashCode() to "re-auth"), shown())
    }

    @Test
    fun aNewerActionNoticeReplacesTheOthersAndIsClearable() {
        postOther(account)
        postAction(account)

        assertTrue(context.clearAccountActionNotification(account))

        assertEquals(emptySet<Pair<Int, String?>>(), shown())
    }

    @Test
    fun withoutAnActionNoticeNothingIsCleared() {
        assertFalse(context.clearAccountActionNotification(account))
    }
}
