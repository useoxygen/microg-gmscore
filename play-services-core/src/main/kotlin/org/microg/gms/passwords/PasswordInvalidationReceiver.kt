/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.AccountManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.microg.gms.gcm.GcmConstants

/** Non-exported: only the internal authenticated MCS delivery path can invoke this receiver. */
class PasswordInvalidationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GcmConstants.ACTION_C2DM_RECEIVE) return
        val from = intent.getStringExtra(GcmConstants.EXTRA_FROM)
        if (from != null && from.substringBefore('@') != PasswordInvalidation.SENDER) return
        val sender = intent.getStringExtra("google.c.sender.id") ?: from?.substringBefore('@')
        val encoded = intent.getStringExtra("payload") ?: return
        if (encoded.length > 90_000) return
        val hint = try {
            PasswordInvalidation.decode(sender, intent.getStringExtra("cache_guid"), Base64.decode(encoded, Base64.DEFAULT))
        } catch (_: Exception) { return }
        if (hint.types.none { it in setOf(SyncDeviceInfo.PASSWORDS, SyncDeviceInfo.PREFERENCES, SyncDeviceInfo.NIGORI) }) return
        // The receiver's default/main process owns WorkManager. Enqueue is asynchronous and durable.
        val pending = goAsync()
        try {
            WorkManager.getInstance(context).enqueueUniqueWork("password-invalidation-${PasswordRepository.hash(hint.cacheGuid)}",
                ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<PasswordInvalidationWorker>()
                    .setInputData(workDataOf("cacheGuid" to hint.cacheGuid, "types" to hint.types.toIntArray(),
                        "digest" to hint.digest)).build())
                .result.addListener({ pending.finish() }, java.util.concurrent.Executor { it.run() })
        } catch (_: Exception) { pending.finish() }
    }
}

class PasswordInvalidationWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val guid = inputData.getString("cacheGuid")?.takeIf { it.isNotEmpty() && it.length <= 1024 } ?: return Result.failure()
        val types = inputData.getIntArray("types")?.takeIf { it.isNotEmpty() && it.size <= 128 && it.all { type -> type > 0 } }
            ?.toSet() ?: return Result.failure()
        val digest = inputData.getByteArray("digest")?.takeIf { it.size == 32 } ?: return Result.failure()
        var retry = false
        for (account in AccountManager.get(applicationContext).getAccountsByType("com.google")) {
            try {
                val repository = PasswordRepository(applicationContext, account)
                if (!repository.enabled) continue
                val pending = repository.store.locked { session ->
                    val state = session.load()
                    if (state.deviceRegistration?.cacheGuid != guid) false else {
                        state.invalidations = state.invalidations.receive(id.toString(), types, digest, System.currentTimeMillis())
                        session.save(state)
                        state.invalidations.pending
                    }
                }
                // Persist before enqueue. An interrupted worker retries with the same ID and
                // replays an unfinished obligation without inventing another remote mutation.
                if (pending) repository.schedule()
            } catch (_: VaultLockedException) { /* Removed or revoked accounts cannot receive a fill or sync. */ }
            catch (_: Exception) { retry = true }
        }
        return if (retry) Result.retry() else Result.success()
    }
}
