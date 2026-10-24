/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.app.backup.BackupManager
import android.app.backup.RestoreObserver
import android.app.backup.RestoreSet
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Calls the platform's local-cache transport, never the owner's selected/cloud transport.
 * Platform signature, version, allowBackup and restore-agent checks remain in BackupManager. */
internal class MigrationSystemRestore(private val context: Context,
    private val openSession: () -> Any? = {
        BackupManager::class.java.getMethod("beginRestoreSession", String::class.java, String::class.java)
            .invoke(BackupManager(context), null, "com.google.android.gms/.backup.BackupTransportService")
    }) {
    fun start(callback: (Boolean) -> Unit) {
        val token = MigrationArtifactStore(context).file("restore_token_file").takeIf { it.isFile }
            ?.readText()?.trim()?.toLongOrNull()
        if (token == null || token <= 0) { callback(false); return }
        val finished = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        val session = AtomicReference<Any?>()
        lateinit var timeout: Runnable
        fun finish(success: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            main.removeCallbacks(timeout)
            runCatching { session.get()?.let { it.javaClass.getMethod("endRestoreSession").invoke(it) } }
            callback(success)
        }
        val observer = object : RestoreObserver() {
            // Hidden callback on the runtime RestoreObserver; public SDK omits its declaration.
            // Same JVM method signature overrides the platform callback without packaging an Android class.
            @androidx.annotation.Keep
            @Suppress("unused") fun restoreSetsAvailable(sets: Array<RestoreSet>?) {
                if (finished.get()) return
                if (sets?.any { it.token == token } != true) { finish(false); return }
                try {
                    val current = requireNotNull(session.get())
                    val result = current.javaClass.getMethod("restoreAll", java.lang.Long.TYPE, RestoreObserver::class.java)
                        .invoke(current, token, this) as Int
                    if (result != 0) finish(false)
                } catch (_: Exception) { finish(false) }
            }
            override fun restoreFinished(error: Int) { finish(error == 0) }
        }
        timeout = Runnable { finish(false) }
        main.postDelayed(timeout, 180_000)
        try {
            session.set(openSession())
            val current = requireNotNull(session.get())
            val result = current.javaClass.getMethod("getAvailableRestoreSets", RestoreObserver::class.java)
                .invoke(current, observer) as Int
            if (result != 0) finish(false)
        } catch (_: Exception) { finish(false) }
    }
}
