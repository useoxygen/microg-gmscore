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
 * "Account action required" goes away once Google issues the account a token (CYC-1185), and only that notice: the
 * re-auth and unsupported-management notices share its id. (The notices' texts are resources, which these tests run
 * without, so they post through the same helper with a plain notification.)
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

    private fun notice() = Notification.Builder(context, ACCOUNT_NOTIFICATION_CHANNEL_ID).build()
    private fun shown() = manager.activeNotifications.map { (it.tag ?: "") + "#" + it.id }.toSet()
    private fun postAction(account: Account) = context.postAccountActionNotification(account, notice())
    private fun postUntagged(account: Account) = manager.notify(account.hashCode(), notice())

    @Test
    fun theActionNoticeIsPostedUnderItsOwnTag() {
        postAction(account)
        assertEquals(setOf("$ACCOUNT_ACTION_TAG#${account.hashCode()}"), shown())
    }

    @Test
    fun aTokenClearsTheAccountsActionNoticeOnly() {
        postAction(account)
        postAction(other)
        postUntagged(account) // the re-auth or unsupported-management notice, same id

        assertTrue(context.clearAccountActionNotification(account))

        assertEquals(setOf("#${account.hashCode()}", "$ACCOUNT_ACTION_TAG#${other.hashCode()}"), shown())
    }

    @Test
    fun withoutAnActionNoticeNothingIsCleared() {
        postUntagged(account)
        assertFalse(context.clearAccountActionNotification(account))
        assertEquals(setOf("#${account.hashCode()}"), shown())
    }

    @Test
    fun signingInAgainClearsEveryNoticeOfTheAccount() {
        postAction(account)
        postUntagged(account)
        context.cancelAccountNotificationChannel(account)
        assertEquals(emptySet<String>(), shown())
    }
}
