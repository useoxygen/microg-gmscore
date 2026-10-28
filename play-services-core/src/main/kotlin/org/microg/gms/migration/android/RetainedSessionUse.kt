/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether Switch is using its retained pairing, per UID, so the pairing screen keeps it instead of pairing afresh.
 * Only callers the brokers authenticated as Switch report use: a target-service call (getConnectionHint and the
 * post-setup handshake, startRestore) holds it for [LEASE_MS], and each live copy transport holds it until that
 * transport closes, finished or failed, which also ends the lease. Binding alone says nothing: Switch's API
 * connections come and go while its copy runs.
 */
internal object RetainedSessionUse {
    const val LEASE_MS = 120_000L
    private val transports = HashMap<Int, Int>()
    private val calls = HashMap<Int, Long>()

    @Synchronized fun called(uid: Int, now: Long = SystemClock.elapsedRealtime()) { calls[uid] = now }

    @Synchronized fun transport(uid: Int): Closeable {
        transports[uid] = (transports[uid] ?: 0) + 1
        val open = AtomicBoolean(true)
        return Closeable { if (open.compareAndSet(true, false)) ended(uid) }
    }

    @Synchronized private fun ended(uid: Int) {
        val left = (transports[uid] ?: 1) - 1
        if (left > 0) transports[uid] = left else { transports.remove(uid); calls.remove(uid) }
    }

    @Synchronized fun inUse(uid: Int, now: Long = SystemClock.elapsedRealtime()): Boolean =
        (transports[uid] ?: 0) > 0 || calls[uid]?.let { now - it < LEASE_MS } == true

    @Synchronized internal fun reset() { transports.clear(); calls.clear() }
}
