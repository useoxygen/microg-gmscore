/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cryptauth

import android.content.Context
import android.os.Build.VERSION.SDK_INT
import okio.ByteString.Companion.encodeUtf8
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.locks.ReentrantLock

private const val LOCK_DIRECTORY = "cryptauth_locks"

private val localLocks = HashMap<String, ReentrantLock>()

/** Files for one account's CryptAuth state that every GmsCore process shares, outside auto backup. */
internal fun Context.cryptAuthStateFile(kind: String, accountName: String): File {
    val base = if (SDK_INT >= 21) noBackupFilesDir else filesDir
    val directory = File(base, LOCK_DIRECTORY).apply { mkdirs() }
    return File(directory, "$kind-" + accountName.encodeUtf8().sha256().hex())
}

/**
 * Runs [block] while holding the account's [kind] lock in all GmsCore processes: account errors
 * are resolved both in the default process and in `:ui`. A file lock is released when its process
 * dies, so a crashed proof never leaves the account locked.
 *
 * @param wait block until the lock is free; otherwise return `null` at once when it is held
 */
internal fun <T> Context.withCryptAuthLock(kind: String, accountName: String, wait: Boolean, block: () -> T): T? {
    val file = cryptAuthStateFile("$kind.lock", accountName)
    // File locks belong to the process, so threads of one process also need a local lock.
    val local = synchronized(localLocks) { localLocks.getOrPut(file.name) { ReentrantLock() } }
    if (wait) local.lock() else if (!local.tryLock()) return null
    try {
        RandomAccessFile(file, "rw").use { access ->
            val lock = (if (wait) access.channel.lock() else access.channel.tryLock()) ?: return null
            try {
                return block()
            } finally {
                lock.release()
            }
        }
    } finally {
        local.unlock()
    }
}
