/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom.utils

import org.microg.gms.auth.folsom.DomainData
import org.microg.gms.auth.folsom.Keys

/** A durable generation, independent of timestamps and jitter. */
internal object FolsomStaleKeyPolicy {
    fun generation(domain: DomainData): Long = (domain.keyRefreshGeneration ?: 0L).also {
        if (it < 0) throw TinkEncryptionException("Invalid recovery refresh generation")
    }
    fun stale(generation: Long): Boolean = generation and 1L == 1L
    fun mark(domain: DomainData): DomainData {
        val before = generation(domain)
        return domain.copy(keyRefreshGeneration = advance(before, if (stale(before)) 2 else 1))
    }
    /** New exact-account owner keys also invalidate an older in-flight server response. */
    fun fresh(domain: DomainData): DomainData {
        val before = generation(domain)
        return domain.copy(keyRefreshGeneration = advance(before, if (stale(before)) 1 else 2))
    }
    /** Reset erases keys and metadata, but its generation must never permit an old refresh token to recur. */
    fun reset(domain: DomainData, userId: String): DomainData =
        DomainData(googleUserId = userId, keyRefreshGeneration = generation(fresh(domain)))
    fun usable(keys: List<Keys>): Boolean = keys.isNotEmpty() && keys.all {
        (it.keyVersion ?: 0) > 0 && (it.keyMaterial?.size ?: 0) in 1..4096
    }
    private fun advance(before: Long, amount: Int): Long {
        if (before > Long.MAX_VALUE - amount) throw TinkEncryptionException("Recovery refresh generation exhausted")
        return before + amount
    }
}

internal class FolsomKeySnapshot(val generation: Long, val keys: List<Keys>) {
    override fun toString(): String = "FolsomKeySnapshot[redacted]"
}

/** Shared by the real Binder/native consumer. Failed fetch/commit never acknowledges the stale mark. */
internal class FolsomKeyRefresh(
    private val read: () -> FolsomKeySnapshot,
    private val prepare: (FolsomKeySnapshot, Boolean) -> FolsomKeySnapshot,
    private val fetch: suspend () -> List<Keys>,
    private val commit: (FolsomKeySnapshot, List<Keys>) -> Unit
) {
    suspend fun load(force: Boolean): List<Keys> {
        val cached = read()
        if (!force && !FolsomStaleKeyPolicy.stale(cached.generation) && FolsomStaleKeyPolicy.usable(cached.keys)) return cached.keys
        val started = prepare(cached, force)
        val refreshed = fetch()
        if (!FolsomStaleKeyPolicy.usable(refreshed)) throw TinkEncryptionException("Fresh recovery keys unavailable")
        commit(started, refreshed)
        return refreshed
    }
}
