/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import android.accounts.Account
import android.accounts.AccountManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.concurrent.Executors

/**
 * Cyclon's own device Google account (CYC-927). It is signed in only so microG can register the phone with Google, and
 * it is never the owner's. Owners and apps must never see it: Google's Switch would otherwise copy an iPhone's
 * contacts, calendars and notes into it (it takes the first Google account), apps would offer it for "Continue with
 * Google", and microG's settings would list it. microG itself keeps using it for check-in and registration.
 *
 * Hiding works on two levels. Android's account visibility hides it from every other app, privileged ones included
 * ([hideFromApps]: explicit NOT_VISIBLE for every package that has a setting, and both legacy defaults, which decide
 * for apps without one). microG, the account's own authenticator, always sees it, so microG's screens and the answers
 * it gives apps drop it themselves ([visible]). On a test phone `adb shell setprop debug.cyclon.show_device_account 1`
 * shows it again in microG's own screens (a debug.* property only the shell can set); other apps stay hidden.
 */
object DeviceAccount {
    private const val TAG = "CyclonDeviceAccount"
    const val SHOW_PROPERTY = "debug.cyclon.show_device_account"
    /** Set on the account itself once seen, so a later rename of the list keeps knowing it. */
    const val MARKER = "cyclon_device_account"
    /** The addresses Cyclon signs in as its device account. */
    val ADDRESSES = setOf("cyclondevice@gmail.com")

    fun isDevice(context: Context, account: Account?): Boolean {
        if (account == null || account.type != "com.google") return false
        if (account.name.trim().lowercase() in ADDRESSES) return true
        return runCatching { AccountManager.get(context).getUserData(account, MARKER) == "1" }.getOrDefault(false)
    }

    fun isDevice(context: Context, name: String?): Boolean =
        name != null && isDevice(context, Account(name, "com.google"))

    /** Whether the device account is shown anyway (adb on a test phone). */
    fun shown(): Boolean = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
            .invoke(null, SHOW_PROPERTY, "") as String == "1"
    }.getOrDefault(false)

    /** [accounts] without the device account, for anything an owner or an app sees. */
    fun visible(context: Context, accounts: Array<Account>?): Array<Account> =
        if (accounts == null) emptyArray() else if (shown()) accounts else accounts.filterNot { isDevice(context, it) }.toTypedArray()

    fun visible(context: Context, accounts: List<Account>): List<Account> =
        if (shown()) accounts else accounts.filterNot { isDevice(context, it) }

    /** The owner's Google accounts as microG would list them, in AccountManager order. */
    fun googleAccounts(context: Context): Array<Account> =
        visible(context, AccountManager.get(context).getAccountsByType("com.google"))

    /** Whether microG may make [account] visible to another app (never the device account). */
    fun mayShow(context: Context, account: Account?): Boolean = shown() || !isDevice(context, account)

    /**
     * Makes every device account NOT_VISIBLE to every other app and marks it. Idempotent; runs on boot, on account
     * changes and when microG is updated (DeviceAccountReceiver).
     */
    fun hideFromApps(context: Context) {
        if (Build.VERSION.SDK_INT < 26 || shown()) return
        val manager = AccountManager.get(context)
        for (account in manager.getAccountsByType("com.google")) {
            if (!isDevice(context, account)) continue
            runCatching {
                if (manager.getUserData(account, MARKER) != "1") manager.setUserData(account, MARKER, "1")
                val packages = manager.getPackagesAndVisibilityForAccount(account).keys +
                    setOf(AccountManager.PACKAGE_NAME_KEY_LEGACY_VISIBLE, AccountManager.PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE)
                var changed = 0
                for (pkg in packages) {
                    if (pkg == context.packageName) continue
                    if (manager.getAccountVisibility(account, pkg) != AccountManager.VISIBILITY_NOT_VISIBLE &&
                        manager.setAccountVisibility(account, pkg, AccountManager.VISIBILITY_NOT_VISIBLE)) changed++
                }
                if (changed > 0) Log.i(TAG, "Hid Cyclon's device account from $changed packages")
            }.onFailure { Log.w(TAG, "Could not hide Cyclon's device account", it) }
        }
    }
}

/** Keeps [DeviceAccount] hidden from apps whenever accounts change, at boot and after a microG update. */
class DeviceAccountReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        worker.execute { try { DeviceAccount.hideFromApps(context) } finally { pending.finish() } }
    }
    companion object {
        private val ACTIONS = setOf(AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION, Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED)
        private val worker = Executors.newSingleThreadExecutor()
    }
}
