/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.accountaction

/**
 * Bounds the CryptAuth screen lock proof per account.
 *
 * Apps keep requesting tokens while Google answers `DeviceManagementScreenlockRequired`. Without a
 * bound, each request starts another SyncKeys/EnrollKeys round trip until CryptAuth answers 429
 * RESOURCE_EXHAUSTED, which then also blocks a proof that would work. Based on the 3 attempts per
 * hour of microG PR #3834, which matches the server's `retryAttempts: 3`, plus:
 * - one proof at a time per account; concurrent requests fail fast instead of waiting, so a slow
 *   proof never holds binder threads;
 * - requests right after a successful proof only retry their token, because Google may still be
 *   applying the enrollment;
 * - a short pause after a failed proof, so requests that arrived together don't spend the hour's
 *   attempts at once;
 * - a full window of rest after the server rate limits the account.
 *
 * The caller keeps the bounds across processes and restarts with [save] and [restore].
 */
class CryptAuthAttempts(
    private val maxAttempts: Int = 3,
    private val windowMillis: Long = HOUR_MILLIS,
    private val reuseSuccessMillis: Long = 60_000L,
    private val failureCooldownMillis: Long = 5 * 60_000L,
    private val rateLimitedMillis: Long = HOUR_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed class Decision {
        /** Perform the proof, then report its outcome with [record]. */
        data object Run : Decision()

        /** A proof just succeeded; retry the token without another round trip. */
        data object RetryTokenOnly : Decision()

        /** Another request is proving the screen lock for this account right now. */
        data object InProgress : Decision()

        /** Do not contact CryptAuth before [untilMillis]. */
        data class Wait(val untilMillis: Long, val reason: WaitReason) : Decision()
    }

    enum class WaitReason { RATE_LIMITED, RECENT_FAILURE, ATTEMPTS_EXHAUSTED }

    enum class Outcome { SUCCEEDED, FAILED, RATE_LIMITED }

    private class AccountState {
        val attempts = ArrayDeque<Long>()
        var running = false
        var lastSuccess: Long? = null
        var lastFailure: Long? = null
        var rateLimitedUntil: Long? = null
    }

    private val accounts = HashMap<String, AccountState>()

    @Synchronized
    fun decide(accountName: String): Decision {
        val now = clock()
        val state = accounts.getOrPut(accountName) { AccountState() }
        if (state.running) return Decision.InProgress
        state.rateLimitedUntil?.let { if (now < it) return Decision.Wait(it, WaitReason.RATE_LIMITED) }
        state.lastSuccess?.let { if (now - it < reuseSuccessMillis) return Decision.RetryTokenOnly }
        state.lastFailure?.let { if (now - it < failureCooldownMillis) return Decision.Wait(it + failureCooldownMillis, WaitReason.RECENT_FAILURE) }
        while (state.attempts.isNotEmpty() && now - state.attempts.first() >= windowMillis) state.attempts.removeFirst()
        if (state.attempts.size >= maxAttempts) return Decision.Wait(state.attempts.first() + windowMillis, WaitReason.ATTEMPTS_EXHAUSTED)
        state.attempts.addLast(now)
        state.running = true
        return Decision.Run
    }

    /** Ends the proof started by [decide] returning [Decision.Run]. */
    @Synchronized
    fun record(accountName: String, outcome: Outcome) {
        val now = clock()
        val state = accounts.getOrPut(accountName) { AccountState() }
        state.running = false
        when (outcome) {
            Outcome.SUCCEEDED -> state.lastSuccess = now
            Outcome.FAILED -> state.lastFailure = now
            Outcome.RATE_LIMITED -> state.rateLimitedUntil = now + rateLimitedMillis
        }
    }

    /** The account's bounds as one line for [restore]. Whether a proof is running isn't saved. */
    @Synchronized
    fun save(accountName: String): String {
        val state = accounts[accountName] ?: return ""
        return listOf(state.attempts.joinToString(","), state.lastSuccess, state.lastFailure, state.rateLimitedUntil)
            .joinToString(";") { it?.toString() ?: "" }
    }

    /**
     * Replaces the account's bounds with a [save]d line. An unreadable line counts as no history;
     * times after now (the clock was set back) are dropped, so they can't block for longer.
     */
    @Synchronized
    fun restore(accountName: String, line: String?) {
        val now = clock()
        val state = accounts.getOrPut(accountName) { AccountState() }
        val parts = line?.split(";")?.takeIf { it.size == 4 }
        fun past(value: String?) = value?.toLongOrNull()?.takeIf { it <= now }
        state.attempts.clear()
        parts?.get(0)?.split(",")?.mapNotNull(::past)?.sorted()?.let { state.attempts.addAll(it) }
        state.lastSuccess = past(parts?.get(1))
        state.lastFailure = past(parts?.get(2))
        state.rateLimitedUntil = parts?.get(3)?.toLongOrNull()?.takeIf { it <= now + rateLimitedMillis }
    }

    companion object {
        const val HOUR_MILLIS = 60 * 60 * 1000L
    }
}
