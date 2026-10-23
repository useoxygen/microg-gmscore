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
            val receipt = repository.refresh()
            if (receipt.conflicts > 0 || receipt.indeterminate > 0) Result.failure() else Result.success()
        } catch (failure: PasswordRpcException) {
            if (failure.code in setOf(4, 8, 13, 14)) Result.retry() else Result.failure()
        } catch (_: VaultLockedException) { Result.failure() }
        catch (_: Exception) { Result.failure() }
    }
}
