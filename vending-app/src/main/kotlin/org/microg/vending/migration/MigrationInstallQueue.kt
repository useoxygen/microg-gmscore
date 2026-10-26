/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Persist selection before dispatch. An uncertain enqueue is reconciled with Aurora, never blindly replayed. */
internal class MigrationInstallQueue(private val context: Context,
    private val storeCall: ((Int, String) -> Bundle)? = null) {
    private val prefs get() = context.getSharedPreferences("migration-installs", Context.MODE_PRIVATE)
    fun select(documents: List<Bundle>) = synchronized(LOCK) {
        val names = documents.map { requireNotNull(it.getString("package_name")) }.toSet()
        val edit = prefs.edit().putStringSet("packages", selected() + names)
            .putStringSet("pending", pending() + names)
        for (doc in documents) edit.putLong("${doc.getString("package_name")}:expectedVersion", doc.getLong("version_code"))
        check(edit.commit())
        schedule()
    }
    fun selected(): Set<String> = prefs.getStringSet("packages", emptySet()).orEmpty().toSet()
    fun pending(): Set<String> = prefs.getStringSet("pending", emptySet()).orEmpty().toSet()
    fun save(name: String, result: Bundle, dispatched: Boolean = false) = synchronized(LOCK) {
        val edit = prefs.edit().putString("$name:status", result.getString("status", "unknown"))
            .putString("$name:error", result.getString("error") ?: result.getString("errorCode"))
            .putBoolean("$name:installed", result.getBoolean("installed"))
        if (dispatched) edit.putStringSet("pending", pending() - name)
        check(edit.commit())
    }
    fun retryDocuments(): List<Bundle> = selected().map { name -> Bundle().apply {
        putString("package_name", name); putLong("version_code", prefs.getLong("$name:expectedVersion", 0))
    } }
    fun needsEnqueue(name: String, state: Bundle): Boolean {
        val status = state.getString("status")
        return status in setOf("failed", "cancelled", "unavailable") ||
            (status == "none" && (!state.getBoolean("installed") ||
                state.getLong("installed_version_code") < prefs.getLong("$name:expectedVersion", 0)))
    }
    fun summary(): Bundle = Bundle().apply {
        val results = selected().sorted().map { name ->
            bridge(3, name).also { result ->
                if (result.getString("status") == "none") {
                    prefs.getString("$name:error", null)?.let { result.putString("error", it) }
                }
                val expected = prefs.getLong("$name:expectedVersion", 0)
                if (expected > 0 && result.getLong("installed_version_code") < expected) result.putBoolean("installed", false)
                save(name, result)
            }
        }
        putParcelableArray("cyclon_app_results", results.toTypedArray())
        putBoolean("all_installed", results.all { it.getBoolean("installed") })
        putString("cyclon_hold_state", "released_to_aurora")
    }
    fun dispatch(name: String): Boolean {
        val current = bridge(3, name)
        if (current.getString("error") == "store_unavailable") return false
        val result = if (needsEnqueue(name, current)) bridge(2, name) else current
        if (result.getString("error") == "store_unavailable") return false
        save(name, result, dispatched = true)
        return true
    }
    fun bridge(operation: Int, name: String): Bundle = try {
        storeCall?.invoke(operation, name) ?: AuroraMigrationClient(context).use { it.call(operation, name) }
    } catch (_: Exception) { unavailable(name, "store_unavailable") }
    private fun schedule() {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        check(scheduler.schedule(JobInfo.Builder(JOB, ComponentName(context, MigrationInstallJob::class.java))
            .setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build()) == JobScheduler.RESULT_SUCCESS) {
            "Could not schedule migration installs"
        }
    }
    companion object {
        private val LOCK = Any()
        private const val JOB = 700714
        fun unavailable(name: String, reason: String) = Bundle().apply {
            putString("package_name", name); putString("title", name); putInt("availability", 2)
            putString("status", "unavailable"); putString("error", reason); putBoolean("installed", false)
        }
    }
}

class MigrationInstallJob : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    private var stopped = AtomicBoolean(false)
    override fun onStartJob(params: JobParameters): Boolean {
        val cancelled = AtomicBoolean(false).also { stopped = it }
        executor.execute {
            val queue = MigrationInstallQueue(this)
            var retry = false
            try {
                for (name in queue.pending().sorted()) {
                    if (cancelled.get()) return@execute
                    if (!queue.dispatch(name)) { retry = true; break }
                }
            } catch (_: Exception) { retry = true }
            if (!cancelled.get()) runCatching { jobFinished(params, retry) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped.set(true); return true }
    override fun onDestroy() { stopped.set(true); executor.shutdownNow(); super.onDestroy() }
}
