/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.ResultReceiver
import org.microg.gms.common.GooglePackagePermission
import org.microg.gms.utils.ExtendedPackageInfo
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Android Switch's Play setup wire contract. Installs belong to Aurora's durable download queue. */
class PlaySetupService : Service() {
    private val work = Executors.newSingleThreadExecutor()
    private val lookups = Executors.newFixedThreadPool(8)
    private val queue by lazy { MigrationInstallQueue(this) }
    private val trusted: Boolean get() {
        val info = packageManager.getApplicationInfo(SWITCH, 0)
        return info.uid == Binder.getCallingUid() && ExtendedPackageInfo(this, SWITCH)
            .hasGooglePackagePermission(GooglePackagePermission.ACCOUNT)
    }
    override fun onBind(intent: Intent): IBinder = object : Binder() {
        init { attachInterface(null, DESCRIPTOR) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(DESCRIPTOR); return true }
            check(trusted) { "Untrusted Play setup caller" }
            data.enforceInterface(DESCRIPTOR)
            val result: Bundle?
            val identity = Binder.clearCallingIdentity()
            try {
                result = when (code) {
                    3 -> { end(data); reply!!.writeNoException(); reply.writeInt(0); return true }
                    7, 22 -> {
                        data.readString() // Account name is intentionally not forwarded to Aurora.
                        val packages = validate(data.createStringArray()?.toList().orEmpty()); end(data)
                        compatible(packages)
                    }
                    8 -> {
                        val documents = data.createTypedArray(Bundle.CREATOR)?.toList().orEmpty(); end(data)
                        select(documents); reply?.writeNoException(); return true
                    }
                    12 -> {
                        val options = readBundle(data); end(data)
                        select(options.getParcelableArray("documents")?.map { it as Bundle }.orEmpty())
                        Bundle().apply { putBoolean("accepted", true) }
                    }
                    9 -> {
                        val receiver = readReceiver(data); end(data)
                        work.execute { receiver?.send(1, summary()) }
                        reply?.writeNoException(); return true
                    }
                    // No Google backup, paid-account library, or recommended preinstalls are claimed.
                    4, 5 -> { data.readString(); end(data); emptyDocuments() }
                    6 -> { data.readString(); data.readLong(); end(data); emptyDocuments() }
                    21 -> { end(data); emptyDocuments() }
                    1 -> { val r = readReceiver(data); end(data); r?.send(1, Bundle()); reply?.writeNoException(); return true }
                    2 -> { end(data); reply?.writeNoException(); return true }
                    13, 15, 16 -> {
                        val r = readReceiver(data); readBundle(data); end(data)
                        // This store runs no auto-update work during setup; pause/resume is a no-op.
                        if (code == 13) r?.send(1, Bundle())
                        Bundle()
                    }
                    14, 17, 18, 19 -> { readBundle(data); end(data); Bundle() }
                    else -> return false
                }
                reply!!.writeNoException(); reply.writeInt(if (result == null) 0 else 1)
                result?.writeToParcel(reply, 0)
                return true
            } finally { Binder.restoreCallingIdentity(identity) }
        }
    }

    private fun compatible(names: List<String>): Bundle {
        val calls = names.map { name -> lookups.submit<Bundle> { bridge(1, name) } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        val docs = calls.mapIndexed { index, task ->
            try { task.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS) }
            catch (_: Exception) { task.cancel(true); unavailable(names[index], "lookup_timeout") }
        }
        return catalog(docs)
    }
    private fun select(documents: List<Bundle>) {
        validate(documents.map { requireNotNull(it.getString("package_name")) })
        queue.select(documents)
    }
    private fun summary(): Bundle = queue.summary()
    private fun bridge(operation: Int, name: String): Bundle = try {
        queue.bridge(operation, name)
    } catch (_: Exception) { unavailable(name, "store_unavailable") }

    override fun onDestroy() { work.shutdown(); lookups.shutdownNow(); super.onDestroy() }
    companion object {
        const val DESCRIPTOR = "com.android.vending.setup.IPlaySetupServiceV2"
        private const val SWITCH = "com.google.android.apps.restore"
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        internal fun catalog(documents: List<Bundle>) = Bundle().apply {
            fun group(items: List<Bundle>) = arrayOf(Bundle().apply {
                putString("title", "Apps from your previous phone")
                putParcelableArray("documents", items.toTypedArray())
            })
            // Only real offers reach the selectable list, even on Switch versions that skip availability checks.
            putParcelableArray("document_groups", group(documents.filter { it.getInt("availability") == 1 }))
            putParcelableArray("unrestorable_document_groups", group(documents.filter { it.getInt("availability") != 1 }))
        }
        private fun validate(names: List<String>): List<String> {
            require(names.size <= 1000 && names.all { it.length <= 255 && PACKAGE.matches(it) })
            return names.distinct()
        }
        private fun end(data: Parcel) { require(data.dataAvail() == 0) { "Unexpected setup request fields" } }
        private fun readBundle(data: Parcel): Bundle = if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else Bundle()
        private fun readReceiver(data: Parcel): ResultReceiver? = if (data.readInt() != 0) ResultReceiver.CREATOR.createFromParcel(data) else null
        private fun emptyDocuments() = Bundle().apply { putParcelableArray("document_groups", emptyArray<Bundle>()) }
        private fun unavailable(name: String, reason: String) = Bundle().apply {
            putString("package_name", name); putString("title", name); putInt("availability", 2)
            putString("status", "unavailable"); putString("error", reason); putBoolean("installed", false)
        }
    }
}
