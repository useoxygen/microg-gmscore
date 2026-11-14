/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar

import android.accounts.Account
import android.accounts.AccountManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.cyclon.DeviceAccount
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemProperties

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CalendarSyncAccountsReceiverTest {
    @After fun cleanup() { ShadowSystemProperties.override(DeviceAccount.SHOW_PROPERTY, "") }

    // CYC-2073: the calendar default never applies to Cyclon's device account, even while a test phone shows it.
    @Test fun defaultNeverAppliesToTheDeviceAccount() {
        val context = RuntimeEnvironment.getApplication()
        val owner = Account("owner@example.invalid", "com.google")
        val device = Account(DeviceAccount.ADDRESSES.first(), "com.google")
        AccountManager.get(context).addAccountExplicitly(owner, null, null)
        AccountManager.get(context).addAccountExplicitly(device, null, null)
        ShadowSystemProperties.override(DeviceAccount.SHOW_PROPERTY, "1")
        assertEquals(listOf(owner), CalendarSyncAccountsReceiver.defaultAccounts(context))
    }
}
