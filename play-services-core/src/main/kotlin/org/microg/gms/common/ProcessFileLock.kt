/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.common

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * A blocking cross-process file lock that waits out the kernel's false deadlock reports (CYC-1606).
 *
 * Linux tracks fcntl locks per process, not per thread, so its deadlock check can refuse a wait that is not a deadlock:
 * one thread of the main process holds a vault lock while another of its threads waits for an account lock that the
 * `:ui` process holds, and `:ui` now waits for the vault lock. The thread holding the vault lock never waits for `:ui`,
 * so it finishes and lets go, but the kernel has already answered `:ui` with EDEADLK. Turning on password sync starts two
 * sync jobs and the screen's own sync at once, and that is how the first sync said "Could not sync".
 */
object ProcessFileLock {
    /** Every retry, waited one after another: about 25 seconds in all, longer than a sync usually holds a lock. */
    internal val WAITS_MILLIS = listOf(25L, 50L, 100L, 200L, 400L, 800L) + List(23) { 1000L }

    fun lock(channel: FileChannel): FileLock = retrying { channel.lock() }

    internal fun <T> retrying(sleep: (Long) -> Unit = Thread::sleep, lock: () -> T): T {
        for (wait in WAITS_MILLIS) {
            try { return lock() } catch (failure: IOException) {
                if (!deadlockReported(failure)) throw failure
            }
            sleep(wait)
        }
        return lock()
    }

    /** Java gives no errno: bionic and glibc both word EDEADLK this way, and no other lock failure does. */
    internal fun deadlockReported(failure: IOException) = failure.message?.contains("deadlock", ignoreCase = true) == true
}
