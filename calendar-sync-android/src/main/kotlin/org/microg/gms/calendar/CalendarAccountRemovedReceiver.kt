/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar

import android.accounts.AccountManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reconcile against native accounts; broadcast extras are never trusted as an account inventory. */
class CalendarAccountRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        if (intent.action == AccountManager.ACTION_ACCOUNT_REMOVED || intent.action == AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION) {
            // An unreadable account list must retain preferences rather than withdraw valid opt-ins.
            runCatching { CalendarSyncPreferences.forgetRemovedAccounts(context) }
        }
    }
}
