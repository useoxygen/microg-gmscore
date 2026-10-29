/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.microg.gms.cyclon.DeviceAccount
import android.accounts.AccountManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

class PasswordAccountRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.accounts.action.ACCOUNT_REMOVED" && intent.action != AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION) return
        WorkManager.getInstance(context).enqueueUniqueWork("password-account-cleanup", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PasswordAccountCleanupWorker>().build())
    }
}

class PasswordAccountCleanupWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result { return try {
        PasswordOwnerOperations.purgeRemovedAccounts(applicationContext)
        SyncedPasskeyVault.purgeRemovedAccounts(applicationContext)
        org.microg.gms.auth.folsom.utils.LocalKeyManager.getInstance(applicationContext).purgeRemovedAccounts()
        EnclavePinIdentity.purgeRemovedAccounts(applicationContext)
        val accounts = AccountManager.get(applicationContext)
        val active = DeviceAccount.visible(applicationContext, accounts.getAccountsByType("com.google")).map { account ->
            val id = accounts.getUserData(account, "GoogleUserId")?.takeIf { it.isNotBlank() } ?: return Result.retry()
            PasswordRepository.hash("google:$id")
        }.toSet()
        val owned = AndroidVaultStore.accountVaults(applicationContext)
        for (removed in owned.filter { it !in active }) {
            // Recheck after discovery; another account setup may have completed in the meantime.
            val current = DeviceAccount.visible(applicationContext, accounts.getAccountsByType("com.google"))
            if (current.any { account -> accounts.getUserData(account, "GoogleUserId")?.let {
                PasswordRepository.hash("google:$it") == removed
            } != false }) return Result.retry()
            WorkManager.getInstance(applicationContext).cancelUniqueWork("password-periodic-$removed")
            WorkManager.getInstance(applicationContext).cancelUniqueWork("password-now-$removed")
            AndroidVaultStore.eraseAccountVault(applicationContext, removed)
        }
        Result.success()
    } catch (_: Exception) { Result.retry() } }
}
