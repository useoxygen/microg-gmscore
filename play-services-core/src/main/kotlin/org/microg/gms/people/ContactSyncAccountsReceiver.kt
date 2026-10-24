/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.accounts.AccountManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors

/** Google-account contacts sync defaults on; explicit saved account choices are preserved. */
class ContactSyncAccountsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf("android.accounts.LOGIN_ACCOUNTS_CHANGED", Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        worker.execute {
            try { AccountManager.get(context).getAccountsByType("com.google").forEach { ContactSyncPreferences(context, it).ensureDefaults() } }
            finally { pending.finish() }
        }
    }
    companion object { private val worker = Executors.newSingleThreadExecutor() }
}
