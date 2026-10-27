/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.os.IBinder
import android.os.Parcel
import android.os.ResultReceiver

/** Switch unbinds after each transaction (eja.java), so callbacks must outlive a bound service instance. */
internal object MigrationSetupReceivers {
    private data class Listener(val receiver: ResultReceiver, val session: String)
    private val lock = Any()
    private val listeners = mutableMapOf<IBinder, Listener>()
    private val holds = mutableMapOf<IBinder, Listener>()

    fun register(receiver: ResultReceiver, session: String) {
        val key = key(receiver)
        synchronized(lock) { listeners[key] = Listener(receiver, session) }
    }

    fun unregister(receiver: ResultReceiver) {
        val key = key(receiver)
        synchronized(lock) { listeners.remove(key) }
    }

    fun hold(receiver: ResultReceiver, session: String) {
        val key = key(receiver)
        synchronized(lock) { holds[key] = Listener(receiver, session) }
    }

    internal fun clearForTests() = synchronized(lock) { listeners.clear(); holds.clear() }

    fun publish(queue: MigrationInstallQueue) {
        val (subscribers, waiting) = synchronized(lock) {
            listeners.keys.removeAll { !it.isBinderAlive }
            holds.keys.removeAll { !it.isBinderAlive }
            listeners.toMap() to holds.toMap()
        }
        for (session in (subscribers.values + waiting.values).map { it.session }.distinct()) {
            val summary = runCatching { queue.summary(session) }.getOrNull() ?: continue
            val complete = summary.getBoolean("complete")
            for ((key, listener) in subscribers.filterValues { it.session == session }) {
                val failed = !key.isBinderAlive || runCatching {
                    listener.receiver.send(if (complete) 1 else 2, summary)
                }.isFailure
                if (failed || complete) synchronized(lock) {
                    if (listeners[key] === listener) listeners.remove(key)
                }
            }
            // Legacy runFinalHold receivers finish on every callback; only send its terminal receipt.
            if (complete) for ((key, listener) in waiting.filterValues { it.session == session }) {
                // The service and durable job may publish concurrently. Claim the one terminal delivery first.
                val claimed = synchronized(lock) {
                    if (holds[key] === listener) { holds.remove(key); true } else false
                }
                if (claimed && key.isBinderAlive) runCatching { listener.receiver.send(1, summary) }
            }
        }
    }

    private fun key(receiver: ResultReceiver): IBinder {
        val parcel = Parcel.obtain()
        return try {
            receiver.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readStrongBinder())
        } finally { parcel.recycle() }
    }
}
