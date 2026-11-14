/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar

import org.microg.gms.cyclon.DeviceAccount
import android.accounts.Account
import android.accounts.AccountManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Calendar sync defaults on, download only, for every visible Google account without a saved choice, including accounts
 * that existed before this update; and who keeps the calendar in sync is decided again (CYC-2073). An app targeting
 * Android 8+ gets only the exempt package broadcasts in its manifest: PACKAGE_FULLY_REMOVED and PACKAGE_DATA_CLEARED (which
 * revokes the app's permissions). Install, enable, disable and permission changes reach ours otherwise: the Google Calendar
 * app requests a sync when it starts, which reaches ours while ours holds the slot, and [CalendarHandoverWorker] checks
 * hourly while Google's adapter holds it.
 */
class CalendarSyncAccountsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val accounts = intent.action in setOf(AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
        val googleCalendar = intent.action in setOf(Intent.ACTION_PACKAGE_FULLY_REMOVED, Intent.ACTION_PACKAGE_DATA_CLEARED) &&
            intent.data?.schemeSpecificPart == CalendarHandover.GOOGLE_CALENDAR
        if (!accounts && !googleCalendar) return
        val pending = goAsync()
        worker.execute {
            try {
                if (accounts) runCatching { defaultAccounts(context).forEach { CalendarSyncPreferences(context, it).ensureDefaults() } }
                runCatching { CalendarHandoverWorker.evaluate(context) }
            } finally { pending.finish() }
        }
    }
    companion object {
        private val worker = Executors.newSingleThreadExecutor()
        /** The accounts the default applies to: never Cyclon's device account, even while a test phone shows it. */
        fun defaultAccounts(context: Context): List<Account> =
            AccountManager.get(context).getAccountsByType("com.google").filterNot { DeviceAccount.isDevice(context, it) }
    }
}

/** While Google Calendar's adapter holds the slot ours runs no sync, so this notices when it can no longer sync. */
class CalendarHandoverWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result { evaluate(applicationContext, hourly = true); return Result.success() }
    companion object {
        private const val NAME = "calendar-handover"
        /** [CalendarHandover.evaluate], with the hourly check scheduled while Google's adapter holds the slot: before ours
         * disables itself, so a process death in between still leaves a check that decides again. */
        fun evaluate(context: Context, hourly: Boolean = false): CalendarHandover.Owner {
            val work = WorkManager.getInstance(context)
            fun schedule() = work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CalendarHandoverWorker>(1, TimeUnit.HOURS).build()).result.get()
            val owner = CalendarHandover.evaluate(context, hourly) { schedule() }
            if (owner == CalendarHandover.Owner.GOOGLE) schedule() else work.cancelUniqueWork(NAME)
            return owner
        }
    }
}
