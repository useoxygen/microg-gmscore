/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class PasswordSyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val name = inputData.getString("account") ?: return Result.failure()
        return try {
            val repository = PasswordRepository(applicationContext, Account(name, "com.google"))
            if (!repository.enabled) return Result.success()
            // A missing password key must not prevent independent priority-preference sync.
            // Attempt each once; their own durable journals reconcile uncertain writes.
            val outcomes = listOf(runCatching { repository.refresh() }, runCatching { repository.refreshPreferences() })
            if (outcomes.any { outcome -> outcome.getOrNull()?.let { it.conflicts > 0 || it.indeterminate > 0 } == true }) Result.failure()
            else if (outcomes.any { outcome -> (outcome.exceptionOrNull() as? PasswordRpcException)?.code in setOf(4, 8, 13, 14) }) Result.retry()
            else if (outcomes.any { it.isFailure }) Result.failure() else Result.success()
        } catch (failure: PasswordRpcException) {
            if (failure.code in setOf(4, 8, 13, 14)) Result.retry() else Result.failure()
        } catch (_: VaultLockedException) { Result.failure() }
        catch (_: Exception) { Result.failure() }
    }
}
