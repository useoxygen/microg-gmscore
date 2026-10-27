/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Durable, separate receipts for each transfer. A status query never authorizes another install. */
internal class MigrationInstallQueue(private val context: Context,
    private val controlCall: ((Int, Bundle) -> Bundle)? = null,
    private val storeCall: ((Int, String) -> Bundle)? = null) {
    private val prefs get() = context.getSharedPreferences("migration-installs", Context.MODE_PRIVATE)
    init {
        synchronized(LOCK) {
            if (!prefs.contains("sessions") && prefs.contains("packages")) {
                val id = "previous-transfer"
                val names = prefs.getStringSet("packages", emptySet()).orEmpty().toSet()
                val ambiguous = prefs.getStringSet("pending", emptySet()).orEmpty()
                val edit = prefs.edit().putString("sessions", id).putString("current_session", id)
                    .putBoolean("$id:selected", true).putStringSet("$id:packages", names)
                for (name in names) {
                    for (key in listOf("status", "error")) edit.putString("$id:$name:$key", prefs.getString("$name:$key", null))
                    edit.putLong("$id:$name:expectedVersion", prefs.getLong("$name:expectedVersion", 0))
                    edit.putBoolean("$id:$name:ever_installed", prefs.getBoolean("$name:installed", false))
                    if (name in ambiguous) {
                        // Legacy requests have no session identity. Require an owner retry rather than replaying an unknown install.
                        edit.putString("$id:$name:status", "failed").putString("$id:$name:error", "previous_transfer_requires_review")
                            .putBoolean("$id:$name:terminal", true)
                    }
                }
                check(edit.commit())
            }
        }
    }
    fun currentSessionId(): String? = prefs.getString("current_session", null)
    fun sessions(): List<String> = prefs.getString("sessions", "").orEmpty().split(',').filter { it.isNotEmpty() }
    fun beginTransfer(): String = synchronized(LOCK) {
        val id = UUID.randomUUID().toString()
        check(prefs.edit().putString("current_session", id).putString("sessions", (listOf(id) + sessions()).joinToString(",")).commit())
        id
    }
    fun startPausedTransfer(): String = synchronized(LOCK) {
        currentSessionId()?.let { if (prefs.getBoolean("$it:pause_requested", false)) resume(it) }
        beginTransfer()
    }
    fun catalogSession(): String = synchronized(LOCK) {
        val id = currentSessionId()
        if (id == null) beginTransfer() else if (prefs.getBoolean("$id:selected", false)) {
            val held = prefs.getBoolean("$id:pause_requested", false)
            val next = beginTransfer()
            if (held) { resume(id); pause(next) }
            next
        } else id
    }
    private fun session(): String = currentSessionId() ?: beginTransfer()
    fun options(id: String): Bundle = Bundle().apply {
        putString("session_id", id)
        for (key in listOf("documents_type", "restore_source", "disable_pre_archiving")) {
            val stored = prefs.getString("$id:option:$key", null) ?: continue
            if (key == "disable_pre_archiving") putBoolean(key, stored.toBoolean()) else putInt(key, stored.toInt())
        }
    }
    fun pause(id: String): Bundle {
        val result = control(4, options(id))
        if (result.getBoolean("success")) check(prefs.edit().putBoolean("$id:pause_requested", true).commit())
        return result
    }
    fun resume(id: String): Bundle {
        check(prefs.edit().putBoolean("$id:pause_requested", false).commit())
        return control(5, options(id))
    }
    fun renewPause(id: String) { if (prefs.getBoolean("$id:pause_requested", false)) control(4, options(id)) }
    fun releaseCompletedPause(id: String) { if (prefs.getBoolean("$id:pause_requested", false)) resume(id) }
    fun recordOptions(options: Bundle) = synchronized(LOCK) {
        val id = session(); val edit = prefs.edit()
        for (key in listOf("documents_type", "restore_source", "disable_pre_archiving")) {
            if (options.containsKey(key)) edit.putString("$id:option:$key", options.get(key).toString())
        }
        check(edit.commit())
    }
    fun recordCatalog(documents: List<Bundle>) = synchronized(LOCK) {
        val id = session(); val edit = prefs.edit()
        val excluded = documents.filter { it.getInt("availability") != 1 }
        val names = documents.map { it.getString("package_name")!! }.toSet()
        edit.putStringSet("$id:excluded", (prefs.getStringSet("$id:excluded", emptySet()).orEmpty() - names) + excluded.map { it.getString("package_name")!! })
        for (doc in documents) metadata(edit, id, doc)
        check(edit.commit())
    }
    private fun metadata(edit: android.content.SharedPreferences.Editor, id: String, doc: Bundle) {
        val name = requireNotNull(doc.getString("package_name"))
        for (key in listOf("title", "icon_url", "developer_name", "error", "status")) {
            doc.getString(key)?.let { edit.putString("$id:$name:$key", it) }
        }
        if (doc.containsKey("version_code")) edit.putLong("$id:$name:expectedVersion", doc.getLong("version_code"))
    }
    fun select(documents: List<Bundle>) = synchronized(LOCK) {
        val id = session()
        val names = documents.map { requireNotNull(it.getString("package_name")) }.toSet()
        val fresh = names - selected(id)
        val edit = prefs.edit().putBoolean("$id:selected", true).putStringSet("$id:packages", selected(id) + names)
            .putStringSet("$id:pending", pending(id) + fresh)
        for (doc in documents) metadata(edit, id, doc)
        check(edit.commit()); changed(id); schedule()
    }
    fun finishSelection(id: String) = synchronized(LOCK) {
        if (!prefs.getBoolean("$id:selected", false)) {
            check(prefs.edit().putBoolean("$id:selected", true).commit())
            changed(id); schedule()
        }
    }
    fun selected(id: String = currentSessionId().orEmpty()): Set<String> = prefs.getStringSet("$id:packages", emptySet()).orEmpty().toSet()
    fun pending(id: String = currentSessionId().orEmpty()): Set<String> = prefs.getStringSet("$id:pending", emptySet()).orEmpty().toSet()
    fun save(name: String, result: Bundle, dispatched: Boolean = false, id: String = session()) = synchronized(LOCK) {
        val edit = prefs.edit()
        for (key in listOf("status", "error")) edit.putString("$id:$name:$key", result.getString(key) ?: if (key == "error") result.getString("errorCode") else "unknown")
        edit.putBoolean("$id:$name:installed", result.getBoolean("installed"))
        edit.putBoolean("$id:$name:terminal", result.getBoolean("terminal"))
        edit.putLong("$id:$name:installedVersion", result.getLong("installed_version_code"))
        if (result.getBoolean("installed") && result.getLong("installed_version_code") >= prefs.getLong("$id:$name:expectedVersion", 0))
            edit.putBoolean("$id:$name:ever_installed", true)
        if (dispatched) edit.putStringSet("$id:pending", pending(id) - name).putBoolean("$id:$name:uncertain", false)
        check(edit.commit())
    }
    fun retryDocuments(id: String = currentSessionId().orEmpty()): List<Bundle> = selected(id).filter { name ->
        prefs.getString("$id:$name:status", null) in setOf("failed", "cancelled", "unavailable") &&
            !prefs.getBoolean("$id:$name:ever_installed", false)
    }.map { name -> document(id, name) }
    fun retry(id: String, packageName: String? = null) = synchronized(LOCK) {
        require(id in sessions())
        val names = retryDocuments(id).map { it.getString("package_name")!! }.filter { packageName == null || it == packageName }
        if (names.isNotEmpty()) control(6, options(id).apply { putBoolean("force_refresh", true) })
        val edit = prefs.edit().putStringSet("$id:pending", pending(id) + names)
        for (name in names) edit.putBoolean("$id:$name:terminal", false).putBoolean("$id:$name:uncertain", false)
        check(edit.commit())
        changed(id); schedule()
    }
    fun needsEnqueue(name: String, state: Bundle, id: String = session()): Boolean {
        if (prefs.getBoolean("$id:$name:ever_installed", false)) return false
        val expected = prefs.getLong("$id:$name:expectedVersion", 0)
        if (state.getBoolean("installed") && state.getLong("installed_version_code") >= expected) return false
        val status = state.getString("status")
        if (status == "installed" && state.getBoolean("installed") && state.getLong("installed_version_code") < expected) return true
        return status in setOf("failed", "cancelled", "unavailable") ||
            (status == "none" && (!state.getBoolean("installed") ||
                state.getLong("installed_version_code") < prefs.getLong("$id:$name:expectedVersion", 0)))
    }
    private fun document(id: String, name: String) = Bundle().apply {
        putString("package_name", name)
        for (key in listOf("title", "icon_url", "developer_name", "error", "status")) putString(key, prefs.getString("$id:$name:$key", null))
        putLong("version_code", prefs.getLong("$id:$name:expectedVersion", 0))
        putLong("installed_version_code", prefs.getLong("$id:$name:installedVersion", 0))
        putBoolean("installed", prefs.getBoolean("$id:$name:installed", false))
        putBoolean("terminal", prefs.getBoolean("$id:$name:terminal", false))
    }
    fun summary(id: String = currentSessionId().orEmpty()): Bundle = synchronized(LOCK) {
        val results = selected(id).sorted().map { name ->
            val saved = document(id, name)
            if (saved.getBoolean("terminal") && name !in pending(id)) return@map saved
            val result = saved.apply {
                val current = bridge(3, name)
                if (current.getString("status") != "none") { remove("error"); remove("errorCode") }
                // Missing row metadata must not erase names/artwork from catalog matching.
                if (current.getString("title") == name) current.remove("title")
                if (current.getString("icon_url").isNullOrEmpty()) current.remove("icon_url")
                putAll(current)
            }
            if (result.getString("status") == "installed" && !result.getBoolean("installed") && !result.getBoolean("package_present")) {
                result.putString("status", "removed"); result.putString("error", "removed_by_owner")
                check(prefs.edit().putBoolean("$id:$name:ever_installed", true).commit())
            }
            val expected = prefs.getLong("$id:$name:expectedVersion", 0)
            if (expected > 0 && result.getLong("installed_version_code") < expected) result.putBoolean("installed", false)
            if (prefs.getBoolean("$id:$name:ever_installed", false) && !result.getBoolean("installed") && result.getString("error") != "store_unavailable") {
                result.putString("status", "removed"); result.putString("error", "removed_by_owner")
            } else if (result.getString("status") == "none" && prefs.getString("$id:$name:error", null) != null) {
                result.putString("status", prefs.getString("$id:$name:status", "unavailable"))
                result.putString("error", prefs.getString("$id:$name:error", null))
            }
            val terminal = result.getBoolean("installed") || (name !in pending(id) &&
                result.getString("status") in setOf("failed", "cancelled", "unavailable", "removed") && result.getString("error") != "store_unavailable")
            result.putBoolean("terminal", terminal)
            save(name, result, id = id)
            result
        } + prefs.getStringSet("$id:excluded", emptySet()).orEmpty().filter { it !in selected(id) }.sorted().map { name ->
            document(id, name).apply { putBoolean("terminal", true); putBoolean("excluded", true) }
        }
        Bundle().apply {
            putString("session_id", id); putParcelableArray("cyclon_app_results", results.toTypedArray())
            putBoolean("all_installed", results.all { it.getBoolean("installed") })
            putBoolean("complete", prefs.getBoolean("$id:selected", false) && pending(id).isEmpty() && results.all { it.getBoolean("terminal") })
            putBundle("setup_options", options(id))
            putString("cyclon_hold_state", if (getBoolean("complete")) "complete" else "installing")
        }
    }
    fun dispatch(name: String, id: String = session()): Boolean = synchronized(LOCK) {
        if (name !in pending(id)) return true
        // An earlier successful install remains a receipt even if the owner subsequently removes it.
        if (prefs.getBoolean("$id:$name:ever_installed", false)) {
            save(name, unavailable(name, "removed_by_owner").apply { putString("status", "removed") }, true, id)
            return true
        }
        val current = bridge(3, name)
        if (current.getString("error") == "store_unavailable") return false
        val result = if (prefs.getBoolean("$id:$name:uncertain", false)) {
            if (current.getString("status") == "none" && needsEnqueue(name, current, id)) {
                unavailable(name, "installation_requires_review").apply { putString("status", "failed") }
            } else current
        } else if (needsEnqueue(name, current, id)) {
            // Commit BEFORE IPC. An uncertain reply never authorizes another enqueue after the owner removed the app.
            check(prefs.edit().putBoolean("$id:$name:uncertain", true).commit())
            bridge(2, name, options(id))
        } else current
        if (result.getString("error") == "store_unavailable") return false
        save(name, result, dispatched = true, id = id); changed(id)
        true
    }
    fun bridge(operation: Int, name: String, options: Bundle? = null): Bundle = try {
        storeCall?.invoke(operation, name) ?: AuroraMigrationClient(context).use { it.call(operation, name, options) }
    } catch (_: Exception) { unavailable(name, "store_unavailable") }
    fun control(operation: Int, options: Bundle = options(session())): Bundle = try {
        controlCall?.invoke(operation, options) ?: AuroraMigrationClient(context).use { it.control(operation, options) }
    } catch (_: Exception) { Bundle().apply { putBoolean("success", false); putBoolean("recoverable", true); putString("error", "store_unavailable") } }
    fun changed(id: String) { context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName).putExtra("session_id", id)) }
    private fun schedule() {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        check(scheduler.schedule(JobInfo.Builder(JOB, ComponentName(context, MigrationInstallJob::class.java))
            .setPersisted(true).build()) == JobScheduler.RESULT_SUCCESS)
    }
    companion object {
        const val ACTION_CHANGED = "org.microg.vending.migration.CHANGED"
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
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(8)
                do {
                    retry = false
                    for (id in queue.sessions()) {
                        for (name in queue.pending(id).sorted()) {
                            if (cancelled.get()) return@execute
                            if (!queue.dispatch(name, id)) retry = true
                        }
                        val state = queue.summary(id)
                        queue.changed(id)
                        if (state.getBoolean("complete")) {
                            queue.releaseCompletedPause(id)
                            MigrationNotifications.completed(this, id, state)
                        } else if (queue.selected(id).isNotEmpty()) {
                            retry = true
                            queue.renewPause(id)
                        }
                    }
                    MigrationSetupReceivers.publish(queue)
                    if (retry && !cancelled.get()) Thread.sleep(2000)
                } while (retry && !cancelled.get() && System.nanoTime() < deadline)
            } catch (_: Exception) { retry = true }
            if (!cancelled.get()) runCatching { jobFinished(params, retry) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped.set(true); return true }
    override fun onDestroy() { stopped.set(true); executor.shutdownNow(); super.onDestroy() }
}
