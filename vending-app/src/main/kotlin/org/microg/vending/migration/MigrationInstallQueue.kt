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
    /** Tests: the options each faked [storeCall] request would carry to Aurora. */
    internal var sentOptions: ((Int, Bundle?) -> Unit)? = null
    private val prefs get() = context.getSharedPreferences("migration-installs", Context.MODE_PRIVATE)
    /** Play answered 429 since the job last cleared this; stop the round and wait before asking again. */
    @Volatile var throttled = false
    /** Pending installs across sessions. Apps Play refused most recently go last, so one cannot block the rest. */
    fun dispatchOrder(): List<Pair<String, String>> = sessions().flatMap { id -> pending(id).sorted().map { id to it } }
        .sortedBy { (id, name) -> prefs.getLong("$id:$name:throttled_at", 0) }
    init {
        // PlaySetupService opens the queue on the main thread, which must never wait for an install in flight.
        if (legacy()) synchronized(LOCK) {
            if (legacy()) {
                val id = "previous-transfer"
                val names = prefs.getStringSet("packages", emptySet()).orEmpty().toSet()
                val ambiguous = prefs.getStringSet("pending", emptySet()).orEmpty()
                val edit = prefs.edit().putString("sessions", id).putString("current_session", id)
                    .putBoolean("$id:selected", true).putBoolean("$id:selection_closed", true).putStringSet("$id:packages", names)
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
    private fun legacy() = !prefs.contains("sessions") && prefs.contains("packages")
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
    fun requireUpdateHold() { check(prefs.edit().putBoolean("requires_update_hold", true).commit()) }
    fun storeReady(): Boolean = !prefs.getBoolean("requires_update_hold", false) || control(7, Bundle()).getBoolean("automatic_update_hold")
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
        check(storeReady()) { "Update App Store before transferring apps" }
        val id = session()
        check(!prefs.getBoolean("$id:selection_closed", false)) { "App selection is already closed" }
        val names = documents.map { requireNotNull(it.getString("package_name")) }.toSet()
        val fresh = names - selected(id)
        val edit = prefs.edit().putBoolean("$id:selected", true).putStringSet("$id:packages", selected(id) + names)
            .putStringSet("$id:pending", pending(id) + fresh)
        for (doc in documents) metadata(edit, id, doc)
        check(edit.commit()); changed(id); schedule()
    }
    fun finishSelection(id: String) = synchronized(LOCK) {
        require(id in sessions())
        if (!prefs.getBoolean("$id:selection_closed", false)) {
            check(prefs.edit().putBoolean("$id:selected", true).putBoolean("$id:selection_closed", true).commit())
            changed(id); schedule()
        }
    }
    /** A trusted companion's wizard return closes input; it does not certify any app installation. */
    fun transferEnded(outcome: String, expectedSessionId: String? = null): Bundle = synchronized(LOCK) {
        require(outcome in setOf("success", "cancelled")) { "Invalid transfer outcome" }
        val id = currentSessionId()
        if (id == null) return Bundle().apply { putBoolean("accepted", false); putString("error", "no_active_session") }
        if (expectedSessionId != null && expectedSessionId != id) return Bundle().apply {
            putBoolean("accepted", false); putString("error", "stale_session"); putString("session_id", id)
        }
        val alreadyClosed = prefs.getBoolean("$id:selection_closed", false)
        val edit = prefs.edit().putBoolean("$id:selected", true).putBoolean("$id:selection_closed", true)
        if (!prefs.contains("$id:transfer_outcome")) edit.putString("$id:transfer_outcome", outcome)
        check(edit.commit())
        changed(id); schedule()
        Bundle().apply {
            putBoolean("accepted", true); putString("session_id", id); putBoolean("selection_closed", true)
            putBoolean("already_closed", alreadyClosed)
            putString("transfer_outcome", prefs.getString("$id:transfer_outcome", null))
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
            .putBoolean("$id:$name:acknowledged", true).putBoolean("$id:$name:owner_retry", false)
        check(edit.commit())
    }
    fun retryDocuments(id: String = currentSessionId().orEmpty()): List<Bundle> = selected(id).filter { name ->
        prefs.getString("$id:$name:status", null) in setOf("failed", "cancelled", "unavailable", "owner_action_required") &&
            !prefs.getBoolean("$id:$name:ever_installed", false)
    }.map { name -> document(id, name) }
    /** Whether Aurora can install with the phone's Google account once the owner approves it. */
    fun ownerAccountSupported(): Boolean = control(7, Bundle()).getBoolean("owner_account_fallback")
    /** [ownerAccount]: the owner approved their Google account for these apps, the fallback after anonymous tries. */
    fun retry(id: String, packageName: String? = null, ownerAccount: Boolean = false) = synchronized(LOCK) {
        require(id in sessions())
        val names = retryDocuments(id).map { it.getString("package_name")!! }.filter { packageName == null || it == packageName }
        if (names.isNotEmpty() && !ownerAccount) control(6, options(id).apply { putBoolean("force_refresh", true) })
        val edit = prefs.edit().putStringSet("$id:pending", pending(id) + names)
        for (name in names) edit.putBoolean("$id:$name:terminal", false).putBoolean("$id:$name:uncertain", false)
            .putBoolean("$id:$name:owner_retry", true).putBoolean("$id:$name:owner_account", ownerAccount)
            .putInt("$id:$name:throttle_count", 0)
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
    private fun satisfies(name: String, result: Bundle, id: String): Boolean = result.getBoolean("installed") &&
        result.getLong("installed_version_code") >= prefs.getLong("$id:$name:expectedVersion", 0)
    private fun removed(result: Bundle): Boolean = result.getString("status") == "installed" &&
        !result.getBoolean("installed") && !result.getBoolean("package_present")
    private fun olderAttempt(name: String, result: Bundle, id: String): Boolean {
        val expected = prefs.getLong("$id:$name:expectedVersion", 0)
        return !satisfies(name, result, id) && expected > result.getLong("version_code") &&
            (result.getString("status") in ACTIVE || (result.getString("status") == "installed" && result.getBoolean("package_present")))
    }
    private fun ownerReview(result: Bundle, reason: String): Bundle = Bundle(result).apply {
        putString("status", "owner_action_required"); putString("error", reason)
        putBoolean("installed", false); putBoolean("terminal", true)
    }
    private fun removedResult(result: Bundle) = Bundle(result).apply {
        putString("status", "removed"); putString("error", "removed_by_owner")
        putBoolean("installed", false); putBoolean("terminal", true)
    }
    fun summary(id: String = currentSessionId().orEmpty()): Bundle = synchronized(LOCK) {
        val results = selected(id).sorted().map { name ->
            val saved = document(id, name)
            if (saved.getBoolean("terminal") && name !in pending(id)) return@map saved
            var result = saved.apply {
                val current = bridge(3, name)
                if (current.getString("status") != "none") { remove("error"); remove("errorCode") }
                if (current.getString("title") == name) current.remove("title")
                if (current.getString("icon_url").isNullOrEmpty()) current.remove("icon_url")
                putAll(current)
            }
            val acknowledged = prefs.getBoolean("$id:$name:acknowledged", name !in pending(id) && prefs.contains("$id:$name:status"))
            val ownerRetry = prefs.getBoolean("$id:$name:owner_retry", false)
            val status = result.getString("status")
            if (removed(result) || (prefs.getBoolean("$id:$name:ever_installed", false) &&
                    !satisfies(name, result, id) && result.getString("error") != "store_unavailable")) {
                result = removedResult(result)
                check(prefs.edit().putBoolean("$id:$name:ever_installed", true).commit())
                save(name, result, dispatched = true, id = id)
            } else if (!satisfies(name, result, id) && status == "awaiting_install" && !ownerRetry) {
                result = ownerReview(result, "installation_confirmation_required")
                save(name, result, dispatched = true, id = id)
            } else if (!satisfies(name, result, id) && status == "none" && acknowledged && !ownerRetry) {
                // An acknowledged row can disappear after restart/owner cleanup. Its absence is not install consent.
                val previous = prefs.getString("$id:$name:status", null)
                result = if (previous in TERMINAL && prefs.getString("$id:$name:error", null) != null) {
                    result.apply { putString("status", previous); putString("error", prefs.getString("$id:$name:error", null)) }
                } else ownerReview(result, "installation_requires_review")
                save(name, result, dispatched = true, id = id)
            } else if (olderAttempt(name, result, id) && !ownerRetry) {
                // Also repairs receipts adopted by the previous version, which dropped the newer pending target.
                check(prefs.edit().putStringSet("$id:pending", pending(id) + name)
                    .putBoolean("$id:$name:acknowledged", true).commit())
            } else if (status == "none" && prefs.getString("$id:$name:error", null) != null && !ownerRetry) {
                result.putString("status", prefs.getString("$id:$name:status", "unavailable"))
                result.putString("error", prefs.getString("$id:$name:error", null))
            } else if (status == "installed" && !satisfies(name, result, id) && name !in pending(id)) {
                result = ownerReview(result, "installation_verification_required")
            }
            if (!satisfies(name, result, id)) result.putBoolean("installed", false)
            val terminal = result.getBoolean("installed") || (name !in pending(id) &&
                result.getString("status") in TERMINAL && result.getString("error") != "store_unavailable")
            result.putBoolean("terminal", terminal)
            save(name, result, id = id)
            result
        } + prefs.getStringSet("$id:excluded", emptySet()).orEmpty().filter { it !in selected(id) }.sorted().map { name ->
            document(id, name).apply { putBoolean("terminal", true); putBoolean("excluded", true) }
        }
        Bundle().apply {
            putString("session_id", id); putParcelableArray("cyclon_app_results", results.toTypedArray())
            putString("transfer_outcome", prefs.getString("$id:transfer_outcome", null))
            putBoolean("all_installed", results.all { it.getBoolean("installed") })
            putBoolean("selection_closed", prefs.getBoolean("$id:selection_closed", false))
            putBoolean("installs_settled", pending(id).isEmpty() && results.all { it.getBoolean("terminal") })
            putBoolean("complete", getBoolean("selection_closed") && getBoolean("installs_settled"))
            putBundle("setup_options", options(id))
            putString("cyclon_hold_state", if (getBoolean("complete")) "complete" else "installing")
        }
    }
    /** One pass over every pending install; false if any must be tried again. */
    fun dispatchRound(cancelled: () -> Boolean = { false }): Boolean {
        throttled = false
        var settled = true
        var unfrozen: String? = null
        try {
            val order = dispatchOrder()
            for ((index, entry) in order.withIndex()) {
                val (id, name) = entry
                if (cancelled()) return false
                unfrozen = id
                if (!dispatch(name, id)) settled = false
                if (throttled) return false // Play limits the store, not one session: pause every install.
                // Freeze this session's receipts before another session can touch the same Aurora row.
                if (order.getOrNull(index + 1)?.first != id) { summary(id); unfrozen = null }
            }
            return settled
        } finally {
            // A stop or 429 can end the round mid-session; the next round may start with another session.
            unfrozen?.let { summary(it) }
        }
    }
    fun dispatch(name: String, id: String = session()): Boolean = synchronized(LOCK) {
        if (name !in pending(id)) return true
        val current = bridge(3, name)
        if (current.getString("error") == "store_unavailable") return false
        if (prefs.getBoolean("$id:$name:ever_installed", false)) {
            save(name, if (satisfies(name, current, id)) current else removedResult(current), true, id)
            return true
        }
        val ownerRetry = prefs.getBoolean("$id:$name:owner_retry", false)
        val needsReview = !satisfies(name, current, id) && (
            current.getString("status") == "awaiting_install" || (current.getString("status") == "none" &&
                (prefs.getBoolean("$id:$name:uncertain", false) || prefs.getBoolean("$id:$name:acknowledged", false))))
        var requestedInstall = false
        var result = if (removed(current)) {
            check(prefs.edit().putBoolean("$id:$name:ever_installed", true).commit())
            removedResult(current)
        } else if (needsReview && !ownerRetry) {
            ownerReview(current, if (current.getString("status") == "awaiting_install") "installation_confirmation_required" else "installation_requires_review")
        } else if (olderAttempt(name, current, id) && current.getString("status") in ACTIVE) {
            // Let the old attempt settle, then reconcile this session's still-pending target.
            save(name, current, id = id)
            check(prefs.edit().putBoolean("$id:$name:acknowledged", true).commit())
            return false
        } else if (ownerRetry && current.getString("status") == "awaiting_install" && !control(7, Bundle()).getBoolean("owner_approved_retry")) {
            ownerReview(current, "installation_confirmation_required") // Legacy Aurora: use its owner-facing install UI.
        } else if (needsEnqueue(name, current, id) || (ownerRetry && current.getString("status") == "awaiting_install")) {
            // Consume this single owner approval atomically before IPC. A lost reply must
            // return to review; the captured ownerRetry value applies only to this request.
            check(prefs.edit().putBoolean("$id:$name:uncertain", true)
                .putBoolean("$id:$name:owner_retry", false).commit())
            requestedInstall = true
            bridge(2, name, options(id).apply {
                putLong("minimum_version_code", prefs.getLong("$id:$name:expectedVersion", 0))
                putBoolean("owner_approved_retry", ownerRetry)
                putBoolean("owner_account_approved", prefs.getBoolean("$id:$name:owner_account", false))
            })
        } else current
        if (result.getString("error") == "store_unavailable") return false
        if (requestedInstall && result.getString("error") == RATE_LIMITED) {
            // Aurora refused before enqueuing, so this is no uncertain install. Keep it pending, and keep the owner's retry.
            throttled = true
            val refusals = prefs.getInt("$id:$name:throttle_count", 0) + 1
            check(prefs.edit().putBoolean("$id:$name:uncertain", false).putBoolean("$id:$name:owner_retry", ownerRetry)
                .putLong("$id:$name:throttled_at", System.currentTimeMillis()).putInt("$id:$name:throttle_count", refusals).commit())
            if (refusals < MAX_RATE_LIMITED) return false
            // Play keeps refusing: show the owner a failure they can retry, or install with their Google account.
            result = Bundle(result).apply { putString("status", "unavailable"); putBoolean("terminal", true) }
        }
        if (requestedInstall && result.getString("status") == "installed" && !satisfies(name, result, id) &&
            (result.getBoolean("package_present") || result.getBoolean("installed"))) {
            // Legacy Aurora cannot enforce the selected minimum. Its settled lower offer needs review,
            // not an endless clear/re-add/reinstall cycle.
            result = ownerReview(result, "selected_version_unavailable")
        }
        val retainTarget = olderAttempt(name, result, id) && result.getString("status") in ACTIVE
        save(name, result, dispatched = !retainTarget, id = id)
        if (retainTarget) check(prefs.edit().putBoolean("$id:$name:acknowledged", true).putBoolean("$id:$name:uncertain", false).commit())
        changed(id)
        !retainTarget
    }
    fun bridge(operation: Int, name: String, options: Bundle? = null): Bundle = try {
        storeCall?.let { sentOptions?.invoke(operation, options); it(operation, name) }
            ?: AuroraMigrationClient(context).use { it.call(operation, name, options) }
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
        internal val LOCK = Any()
        private const val JOB = 700714
        private const val RATE_LIMITED = "rate_limited"
        private const val MAX_RATE_LIMITED = 3
        private val ACTIVE = setOf("queued", "purchasing", "downloading", "verifying", "completed", "installing")
        private val TERMINAL = setOf("failed", "cancelled", "unavailable", "removed", "owner_action_required")
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
                    if (!queue.dispatchRound { cancelled.get() }) retry = true
                    if (cancelled.get()) return@execute
                    for (id in queue.sessions()) {
                        val state = queue.summary(id)
                        queue.changed(id)
                        MigrationNotifications.selectionOpen(this, id, state)
                        if (state.getBoolean("complete")) {
                            queue.releaseCompletedPause(id)
                            MigrationNotifications.completed(this, id, state)
                        } else if (queue.selected(id).isNotEmpty()) {
                            // Settled chunks do not prove the selection closed. Preserve the lease and
                            // receipts without polling forever; the next chunk/explicit close schedules work.
                            if (!state.getBoolean("installs_settled")) retry = true
                            queue.renewPause(id)
                        }
                    }
                    MigrationSetupReceivers.publish(queue)
                    if (retry && !cancelled.get()) Thread.sleep(if (queue.throttled) 30_000 else 2000)
                } while (retry && !cancelled.get() && System.nanoTime() < deadline)
            } catch (_: Exception) { retry = true }
            if (!cancelled.get()) runCatching { jobFinished(params, retry) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped.set(true); return true }
    override fun onDestroy() { stopped.set(true); executor.shutdownNow(); super.onDestroy() }
}
