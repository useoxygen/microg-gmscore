/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ResultReceiver
import org.microg.gms.common.GooglePackagePermission
import org.microg.gms.utils.ExtendedPackageInfo
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Android Switch's Play setup wire contract. Installs belong to Aurora's durable download queue. */
class PlaySetupService : Service() {
    private val work = Executors.newSingleThreadScheduledExecutor()
    // The queue opens on the first binder call: its lock may be held by an install, and onBind runs on the main thread.
    private val protocol by lazy { PlaySetupProtocol({ MigrationInstallQueue(this) }, { trusted }, { task -> work.execute(task) }) }
    private val trusted: Boolean get() = runCatching {
        packageManager.getApplicationInfo(SWITCH, 0).uid == Binder.getCallingUid() && ExtendedPackageInfo(this, SWITCH)
            .hasGooglePackagePermission(GooglePackagePermission.ACCOUNT)
    }.getOrDefault(false)
    override fun onCreate() {
        super.onCreate()
        work.scheduleWithFixedDelay({ runCatching { protocol.publish() } }, 2, 2, TimeUnit.SECONDS)
    }
    override fun onBind(intent: Intent): IBinder = protocol
    override fun onDestroy() { protocol.close(); work.shutdown(); super.onDestroy() }
    companion object {
        const val DESCRIPTOR = "com.android.vending.setup.IPlaySetupServiceV2"
        private const val SWITCH = "com.google.android.apps.restore"
        internal fun catalog(documents: List<Bundle>) = Bundle().apply {
            fun group(items: List<Bundle>) = arrayOf(Bundle().apply {
                putString("title", "Apps from your previous phone")
                putParcelableArray("documents", items.toTypedArray())
            })
            putParcelableArray("document_groups", group(documents.filter { it.getInt("availability") == 1 }))
            putParcelableArray("unrestorable_document_groups", group(documents.filter { it.getInt("availability") != 1 }))
        }
    }
}

/** Parcel layouts: Switch chc.java; transaction names: eja.java. */
internal class PlaySetupProtocol(open: () -> MigrationInstallQueue,
    private val trusted: () -> Boolean, private val execute: (() -> Unit) -> Unit) : Binder() {
    constructor(queue: MigrationInstallQueue, trusted: () -> Boolean, execute: (() -> Unit) -> Unit) : this({ queue }, trusted, execute)
    private val queue by lazy(open)
    private val lookups = Executors.newFixedThreadPool(8)
    init { attachInterface(null, PlaySetupService.DESCRIPTOR) }
    fun close() { lookups.shutdownNow() }
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) { reply?.writeString(PlaySetupService.DESCRIPTOR); return true }
        check(trusted()) { "Untrusted Play setup caller" }
        data.enforceInterface(PlaySetupService.DESCRIPTOR)
        val identity = Binder.clearCallingIdentity()
        try {
            val result = when (code) {
                3 -> { end(data); reply!!.writeNoException(); reply.writeInt(0); return true }
                7, 22 -> {
                    data.readString() // Never forward the owner's account to Aurora.
                    val names = validate(data.createStringArray()?.toList().orEmpty()); end(data)
                    compatible(names)
                }
                8 -> {
                    val docs = data.createTypedArray(Bundle.CREATOR)?.toList().orEmpty(); end(data)
                    select(docs); reply?.writeNoException(); return true
                }
                12 -> {
                    val options = readBundle(data); end(data)
                    val docs = options.getParcelableArray("documents")?.map { it as Bundle }.orEmpty()
                    queue.recordOptions(options)
                    // Only Play application documents can be installed by the Aurora bridge.
                    if (!queue.storeReady()) storeUpdateError() else if (options.getInt("documents_type", 0) !in setOf(0, 3)) Bundle().apply {
                        putBoolean("accepted", false); putBundle("error", Bundle().apply { putString("reason", "unsupported_documents_type"); putString("exception_type", "UnsupportedOperationException") })
                    } else { select(docs); Bundle().apply { putBoolean("accepted", true) } }
                }
                9 -> {
                    val receiver = readReceiver(data); end(data)
                    val id = session()
                    queue.finishSelection(id)
                    if (receiver != null) MigrationSetupReceivers.hold(receiver, id)
                    execute { publish() }
                    reply?.writeNoException(); return true
                }
                // There is no Google backup, paid account library or recommended preinstall claim.
                4, 5 -> { data.readString(); end(data); emptyDocuments() }
                6 -> { data.readString(); data.readLong(); end(data); emptyDocuments() }
                21 -> { end(data); emptyDocuments() }
                1 -> { val r = readReceiver(data); end(data); r?.send(1, Bundle()); reply?.writeNoException(); return true }
                2 -> { end(data); reply?.writeNoException(); return true }
                13 -> {
                    val receiver = readReceiver(data); val options = readBundle(data); end(data)
                    queue.requireUpdateHold()
                    if (!queue.control(7, Bundle()).getBoolean("automatic_update_hold")) {
                        // lql checks lqm.l on this reply before awaiting a callback. Legacy async callers
                        // can ignore this error, so catalog/setup enforce the persisted requirement too.
                        reply!!.writeNoException(); reply.writeInt(1)
                        Bundle().apply { putBundle("error", Bundle().apply { putString("error", "store_update_required") }) }.writeToParcel(reply, 0)
                        return true
                    }
                    val id = queue.startPausedTransfer()
                    options.putString("session_id", id)
                    val state = queue.pause(id)
                    if (!state.getBoolean("success")) Bundle().apply {
                        putBundle("error", Bundle().apply { putString("error", "store_unavailable") })
                    } else {
                        execute { receiver?.send(0, state) }
                        Bundle()
                    }
                }
                14 -> {
                    readBundle(data); end(data)
                    queue.resume(session()).apply {
                        if (!getBoolean("success")) putBundle("error", Bundle().apply { putString("error", "store_update_required") })
                    }
                }
                15, 16 -> {
                    val receiver = readReceiver(data); readBundle(data); end(data)
                    if (receiver != null) {
                        if (code == 15) MigrationSetupReceivers.register(receiver, session()) else MigrationSetupReceivers.unregister(receiver)
                    }
                    if (code == 15) execute { publish() }
                    Bundle()
                }
                19 -> { readBundle(data); end(data); queue.summary() }
                17, 18 -> { val options = readBundle(data); end(data); queue.recordOptions(options); Bundle() }
                else -> return false
            }
            reply!!.writeNoException(); reply.writeInt(1); result.writeToParcel(reply, 0)
            return true
        } finally { Binder.restoreCallingIdentity(identity) }
    }
    private fun session() = queue.currentSessionId() ?: queue.beginTransfer()
    private fun compatible(names: List<String>): Bundle {
        queue.catalogSession()
        if (!queue.storeReady()) {
            val documents = names.map { name -> MigrationInstallQueue.unavailable(name, "store_update_required").apply { putBoolean("recoverable", true) } }
            queue.recordCatalog(documents)
            return PlaySetupService.catalog(documents).apply { putAll(storeUpdateError()) }
        }
        val prepared = queue.control(6)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        val documents = lookUp(names, deadline).toMutableList()
        // One more try for apps that failed for a passing reason (Play's 429, the network, a Google account still being
        // set up), while time is left: Switch drops whatever this batch does not list.
        val retry = documents.indices.filter { documents[it].retryable() }
        if (retry.isNotEmpty() && deadline - System.nanoTime() > TimeUnit.SECONDS.toNanos(RETRY_MIN_SECONDS)) {
            lookUp(retry.map { names[it] }, deadline).forEachIndexed { at, result -> documents[retry[at]] = result }
        }
        for (result in documents) {
            if (!prepared.getBoolean("ready") && result.getString("error") in setOf("anonymous_sign_in_required", "store_unavailable")) {
                result.putBoolean("recoverable", true)
                result.putString("error", prepared.getString("error", "store_unavailable"))
            }
        }
        queue.recordCatalog(documents)
        return PlaySetupService.catalog(documents).apply {
            // Only a store outage fails the whole batch: Switch retries the operation and otherwise drops every app in
            // it. One app the store could not check yet is left out on its own (its result stays retryable) instead.
            if (documents.none { it.getInt("availability") == 1 } && documents.any { it.getBoolean("recoverable") }) {
                // lqm.k/lqj consumes this operation-level error instead of filtering transient outages as incompatible apps.
                putBundle("error", Bundle().apply {
                    putString("reason", "App Store is temporarily unavailable. Retry app matching.")
                    putString("exception_type", "StoreUnavailableException")
                })
            }
        }
    }
    private fun lookUp(names: List<String>, deadline: Long): List<Bundle> {
        val calls = names.map { name -> lookups.submit<Bundle> { queue.bridge(1, name) } }
        return calls.mapIndexed { index, task ->
            try { task.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS) }
            catch (_: Exception) { task.cancel(true); MigrationInstallQueue.unavailable(names[index], "lookup_timeout").apply { putBoolean("recoverable", true) } }
        }
    }
    private fun Bundle.retryable() = getInt("availability") != 1 && getBoolean("recoverable") && getString("error") != "lookup_timeout"
    private fun select(documents: List<Bundle>) {
        validate(documents.map { requireNotNull(it.getString("package_name")) })
        queue.select(documents)
    }
    fun publish() = MigrationSetupReceivers.publish(queue)
    companion object {
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        /** Time left in the 45 s catalog deadline needed for a second lookup of apps that failed for a passing reason. */
        private const val RETRY_MIN_SECONDS = 5L
        private fun validate(names: List<String>): List<String> {
            require(names.size <= 1000 && names.all { it.length <= 255 && PACKAGE.matches(it) })
            return names.distinct()
        }
        private fun end(data: Parcel) { require(data.dataAvail() == 0) { "Unexpected setup request fields" } }
        private fun readBundle(data: Parcel): Bundle = if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else Bundle()
        private fun readReceiver(data: Parcel): ResultReceiver? = if (data.readInt() != 0) ResultReceiver.CREATOR.createFromParcel(data) else null
        private fun storeUpdateError() = Bundle().apply {
            putBoolean("accepted", false)
            putBundle("error", Bundle().apply {
                putString("reason", "Update App Store before transferring apps")
                putString("exception_type", "StoreUpdateRequiredException")
            })
        }
        private fun emptyDocuments() = Bundle().apply { putParcelableArray("document_groups", emptyArray<Bundle>()) }
    }
}
