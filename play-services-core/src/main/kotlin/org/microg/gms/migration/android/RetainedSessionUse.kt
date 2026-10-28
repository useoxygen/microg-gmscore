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
 * transport closes, finished or failed, which also ends the lease of the calls made before it started. A call made
 * after that belongs to a newer attempt (Switch reconnecting while the old transport winds down) and keeps its lease.
 * Binding alone says nothing: Switch's API connections come and go while its copy runs.
 */
internal object RetainedSessionUse {
    const val LEASE_MS = 120_000L
    private class Call(val at: Long, val sequence: Long)
    private var sequence = 0L
    private val transports = HashMap<Int, Int>()
    private val calls = HashMap<Int, Call>()

    @Synchronized fun called(uid: Int, now: Long = SystemClock.elapsedRealtime()) { calls[uid] = Call(now, ++sequence) }

    @Synchronized fun transport(uid: Int): Closeable {
        transports[uid] = (transports[uid] ?: 0) + 1
        val started = sequence
        val open = AtomicBoolean(true)
        return Closeable { if (open.compareAndSet(true, false)) ended(uid, started) }
    }

    @Synchronized private fun ended(uid: Int, started: Long) {
        // Whichever transport ends, the calls it outlived are spent; transports still open keep the pairing anyway.
        if ((calls[uid]?.sequence ?: 0) <= started) calls.remove(uid)
        val left = (transports[uid] ?: 1) - 1
        if (left > 0) transports[uid] = left else transports.remove(uid)
    }

    @Synchronized fun inUse(uid: Int, now: Long = SystemClock.elapsedRealtime()): Boolean =
        (transports[uid] ?: 0) > 0 || calls[uid]?.let { now - it.at < LEASE_MS } == true

    @Synchronized internal fun reset() { transports.clear(); calls.clear() }
}
