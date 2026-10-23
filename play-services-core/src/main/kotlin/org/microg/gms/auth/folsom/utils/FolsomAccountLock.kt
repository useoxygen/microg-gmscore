/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom.utils

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/** The same account lock protects recovery, reset, and consumption of cached recovery authority across processes. */
internal object FolsomAccountLock {
    private val mutexes = ConcurrentHashMap<String, Any>()
    fun <T> withLock(directory: File, lockName: String, action: () -> T): T {
        require(Regex("[0-9a-f]{64}").matches(lockName))
        val file = File(directory, "folsom-enroll-$lockName.lock")
        val mutex = mutexes.getOrPut(file.canonicalPath) { Any() }
        return synchronized(mutex) {
            RandomAccessFile(file, "rw").use { lock -> lock.channel.lock().use { action() } }
        }
    }
}
