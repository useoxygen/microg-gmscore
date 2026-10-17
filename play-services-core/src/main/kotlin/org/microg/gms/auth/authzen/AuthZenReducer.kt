/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.authzen

import okio.ByteString.Companion.toByteString

internal enum class AuthZenState(val terminal: Boolean) {
    VERIFIED_PENDING(false),
    PRESENTED(false),
    AUTHENTICATING(false),
    DECISION_AUTHORIZED(false),
    SENDING(false),
    APPROVED_CONFIRMED(true),
    DENIED_CONFIRMED(true),
    CANCELED(true),
    EXPIRED(true),
    UNSUPPORTED(true),
    OUTCOME_UNKNOWN(true),
}

/** One clock reading: wall time, monotonic time since boot, and an id that changes with every boot. */
internal class AuthZenNow(val wallMillis: Long, val elapsedMillis: Long, val bootId: String)

/**
 * One transaction. Records are keyed by account, key generation and verified transaction id; [handle] is a random
 * local id that notifications and the Activity use instead. Terminal records keep no request, only a [digest]
 * tombstone so that a resent copy is recognized.
 */
internal data class AuthZenRecord(
    val handle: String,
    val account: String,
    val generation: Long,
    val transactionId: String,
    /** SHA-256 of the request's original bytes; null for a cancellation that arrived before its request. */
    val digest: String?,
    val profile: String?,
    val state: AuthZenState,
    val request: AuthZenRequest?,
    /** [AuthZenRequest.numberChoices] in the order shown, shuffled once when the request arrived. */
    val displayChoices: List<Int>,
    val bootId: String,
    val deadlineElapsedMillis: Long,
    val serverExpiresAtMillis: Long,
    val updatedWallMillis: Long,
    val decision: AuthZenDecision? = null,
    val authAttempt: String? = null,
    val operation: String? = null,
)

internal sealed class AuthZenAction {
    /** [handle] and [displayChoices] are random and made by the caller, which keeps the reducer pure. */
    class Receive(val account: String, val generation: Long, val profile: String, val request: AuthZenRequest, val digest: String, val handle: String, val displayChoices: List<Int>) : AuthZenAction()
    class Cancel(val account: String, val generation: Long, val transactionId: String, val handle: String) : AuthZenAction()
    /** The owner opened the request. Any half-finished owner authentication is discarded. */
    class Present(val handle: String) : AuthZenAction()
    /** The owner tapped Yes and, when required, a number; [attempt] names the owner authentication that follows. */
    class Approve(val handle: String, val selectedNumber: Int?, val attempt: String) : AuthZenAction()
    class AuthResult(val handle: String, val attempt: String, val digest: String?, val generation: Long, val success: Boolean) : AuthZenAction()
    class Deny(val handle: String) : AuthZenAction()
    /** Last checks before a reply leaves the phone. */
    class BeginSend(val handle: String, val operation: String, val accountPresent: Boolean, val optedIn: Boolean, val currentGeneration: Long) : AuthZenAction()
    class SendFinished(val handle: String, val operation: String, val outcome: SendOutcome) : AuthZenAction()
    class AccountRemoved(val account: String) : AuthZenAction()
    /** Only expiry, reboot and tombstone cleanup, which every action also applies first. */
    data object Reconcile : AuthZenAction()
}

internal enum class AuthZenRejection { TOO_MANY_FOR_ACCOUNT, TOO_MANY_FOR_DEVICE, ALREADY_EXPIRED }

internal enum class AuthZenIgnored { NOT_FOUND, WRONG_STATE, STALE_ATTEMPT, NUMBER_REQUIRED }

internal sealed class AuthZenResult {
    class Inserted(val record: AuthZenRecord) : AuthZenResult()
    /** The same transaction again, or one already ended; nothing changed. */
    class Duplicate(val record: AuthZenRecord) : AuthZenResult()
    /** Same transaction id, different authenticated bytes: the stored request ended as unsupported. */
    data object Conflict : AuthZenResult()
    class Rejected(val reason: AuthZenRejection) : AuthZenResult()
    class Updated(val record: AuthZenRecord) : AuthZenResult()
    class Ignored(val reason: AuthZenIgnored, val record: AuthZenRecord?) : AuthZenResult()
}

/**
 * The transaction state machine, pure: [reduce] maps stored records, an action and a clock reading to new records.
 * The repository stores the result atomically, so every transition is a compare-and-set on the stored state.
 *
 * Bounds are DESIGN choices (report §5.5), not Google values.
 */
internal class AuthZenReducer(
    private val maxPendingPerAccount: Int = 3,
    private val maxPendingPerDevice: Int = 10,
    private val maxTombstones: Int = 100,
    private val tombstoneMillis: Long = 24 * 3600_000L,
    /** A request whose server expiry is this far behind the phone's wall clock arrived too late. */
    private val clockSkewMillis: Long = 2 * 60_000L,
    private val sendGraceMillis: Long = 60_000L,
) {

    fun reduce(records: List<AuthZenRecord>, action: AuthZenAction, now: AuthZenNow): Pair<List<AuthZenRecord>, AuthZenResult?> {
        val current = reconcile(records, now)
        return when (action) {
            is AuthZenAction.Reconcile -> current to null
            is AuthZenAction.Receive -> receive(current, action, now)
            is AuthZenAction.Cancel -> cancel(current, action, now)
            is AuthZenAction.AccountRemoved -> current.filter { it.account != action.account } to null
            else -> transition(current, handleOf(action), now) { record -> step(record, action, now) }
        }
    }

    private fun handleOf(action: AuthZenAction): String = when (action) {
        is AuthZenAction.Present -> action.handle
        is AuthZenAction.Approve -> action.handle
        is AuthZenAction.AuthResult -> action.handle
        is AuthZenAction.Deny -> action.handle
        is AuthZenAction.BeginSend -> action.handle
        is AuthZenAction.SendFinished -> action.handle
        else -> error("not a transition")
    }

    private fun step(record: AuthZenRecord, action: AuthZenAction, now: AuthZenNow): Step = when (action) {
        is AuthZenAction.Present -> when (record.state) {
            AuthZenState.VERIFIED_PENDING, AuthZenState.PRESENTED, AuthZenState.AUTHENTICATING -> Step.To(record.copy(state = AuthZenState.PRESENTED, authAttempt = null))
            else -> Step.Ignore(AuthZenIgnored.WRONG_STATE)
        }

        is AuthZenAction.Approve -> when {
            record.state != AuthZenState.PRESENTED -> Step.Ignore(AuthZenIgnored.WRONG_STATE)
            record.request!!.numberChoices.isEmpty() ->
                if (action.selectedNumber != null) Step.Ignore(AuthZenIgnored.WRONG_STATE)
                else Step.To(record.copy(state = AuthZenState.AUTHENTICATING, authAttempt = action.attempt))
            action.selectedNumber == null -> Step.Ignore(AuthZenIgnored.NUMBER_REQUIRED)
            // A wrong number ends the approval attempt; there is no second try.
            action.selectedNumber != record.request.correctNumber -> Step.To(record.copy(state = AuthZenState.DECISION_AUTHORIZED, decision = AuthZenDecision.MISMATCH))
            else -> Step.To(record.copy(state = AuthZenState.AUTHENTICATING, authAttempt = action.attempt))
        }

        // Only the attempt started for this very request, key generation and request bytes counts, and only once.
        is AuthZenAction.AuthResult -> when {
            record.state != AuthZenState.AUTHENTICATING || record.authAttempt != action.attempt ||
                record.digest != action.digest || record.generation != action.generation -> Step.Ignore(AuthZenIgnored.STALE_ATTEMPT)
            action.success -> Step.To(record.copy(state = AuthZenState.DECISION_AUTHORIZED, decision = AuthZenDecision.APPROVE, authAttempt = null))
            else -> Step.To(record.copy(state = AuthZenState.PRESENTED, authAttempt = null))
        }

        is AuthZenAction.Deny -> when (record.state) {
            AuthZenState.VERIFIED_PENDING, AuthZenState.PRESENTED, AuthZenState.AUTHENTICATING ->
                Step.To(record.copy(state = AuthZenState.DECISION_AUTHORIZED, decision = AuthZenDecision.DENY, authAttempt = null))
            else -> Step.Ignore(AuthZenIgnored.WRONG_STATE)
        }

        is AuthZenAction.BeginSend -> when {
            record.state != AuthZenState.DECISION_AUTHORIZED -> Step.Ignore(AuthZenIgnored.WRONG_STATE)
            !action.accountPresent || !action.optedIn || action.currentGeneration != record.generation -> Step.To(ended(record, AuthZenState.CANCELED, now))
            else -> Step.To(record.copy(state = AuthZenState.SENDING, operation = action.operation))
        }

        is AuthZenAction.SendFinished -> when {
            record.state != AuthZenState.SENDING || record.operation != action.operation -> Step.Ignore(AuthZenIgnored.STALE_ATTEMPT)
            else -> when (action.outcome) {
                SendOutcome.CONFIRMED -> Step.To(ended(record, if (record.decision == AuthZenDecision.APPROVE) AuthZenState.APPROVED_CONFIRMED else AuthZenState.DENIED_CONFIRMED, now))
                SendOutcome.NOT_DELIVERED -> Step.To(record.copy(state = AuthZenState.DECISION_AUTHORIZED, operation = null))
                SendOutcome.UNKNOWN -> Step.To(ended(record, AuthZenState.OUTCOME_UNKNOWN, now))
                SendOutcome.REJECTED -> Step.To(ended(record, AuthZenState.UNSUPPORTED, now))
                SendOutcome.EXPIRED -> Step.To(ended(record, AuthZenState.EXPIRED, now))
            }
        }

        else -> Step.Ignore(AuthZenIgnored.WRONG_STATE)
    }

    private sealed class Step {
        class To(val record: AuthZenRecord) : Step()
        class Ignore(val reason: AuthZenIgnored) : Step()
    }

    private fun transition(records: List<AuthZenRecord>, handle: String, now: AuthZenNow, step: (AuthZenRecord) -> Step): Pair<List<AuthZenRecord>, AuthZenResult> {
        val record = records.firstOrNull { it.handle == handle } ?: return records to AuthZenResult.Ignored(AuthZenIgnored.NOT_FOUND, null)
        if (record.state.terminal) return records to AuthZenResult.Ignored(AuthZenIgnored.WRONG_STATE, record)
        return when (val next = step(record)) {
            is Step.Ignore -> records to AuthZenResult.Ignored(next.reason, record)
            is Step.To -> {
                val updated = next.record.copy(updatedWallMillis = now.wallMillis)
                records.map { if (it.handle == handle) updated else it } to AuthZenResult.Updated(updated)
            }
        }
    }

    private fun receive(records: List<AuthZenRecord>, action: AuthZenAction.Receive, now: AuthZenNow): Pair<List<AuthZenRecord>, AuthZenResult> {
        val request = action.request
        val transactionId = request.transactionId.hex()
        val existing = records.firstOrNull { it.account == action.account && it.generation == action.generation && it.transactionId == transactionId }
        if (existing != null) {
            if (existing.digest == null || existing.digest == action.digest) return records to AuthZenResult.Duplicate(existing)
            val ended = if (existing.state.terminal) records else records.map { if (it === existing) ended(it, AuthZenState.UNSUPPORTED, now) else it }
            return ended to AuthZenResult.Conflict
        }
        val lifetime = (request.expiresAtMillis - request.createdAtMillis).coerceAtMost(AuthZenDecoder.MAX_LIFETIME_MILLIS)
        val record = AuthZenRecord(
            handle = action.handle,
            account = action.account,
            generation = action.generation,
            transactionId = transactionId,
            digest = action.digest,
            profile = action.profile,
            state = AuthZenState.VERIFIED_PENDING,
            request = request,
            displayChoices = action.displayChoices,
            bootId = now.bootId,
            deadlineElapsedMillis = now.elapsedMillis + lifetime,
            serverExpiresAtMillis = request.expiresAtMillis,
            updatedWallMillis = now.wallMillis,
        )
        if (expired(record, now)) return trim(records + ended(record, AuthZenState.EXPIRED, now)) to AuthZenResult.Rejected(AuthZenRejection.ALREADY_EXPIRED)
        val pending = records.filter { !it.state.terminal }
        // Dropping an excess request never approves it; the browser falls back to another method.
        if (pending.count { it.account == action.account } >= maxPendingPerAccount) return records to AuthZenResult.Rejected(AuthZenRejection.TOO_MANY_FOR_ACCOUNT)
        if (pending.size >= maxPendingPerDevice) return records to AuthZenResult.Rejected(AuthZenRejection.TOO_MANY_FOR_DEVICE)
        return (records + record) to AuthZenResult.Inserted(record)
    }

    private fun cancel(records: List<AuthZenRecord>, action: AuthZenAction.Cancel, now: AuthZenNow): Pair<List<AuthZenRecord>, AuthZenResult> {
        val existing = records.firstOrNull { it.account == action.account && it.generation == action.generation && it.transactionId == action.transactionId }
        if (existing == null) {
            // Cancellation first: remember it, so that the request arriving later is never shown.
            val tombstone = AuthZenRecord(
                handle = action.handle, account = action.account, generation = action.generation, transactionId = action.transactionId,
                digest = null, profile = null, state = AuthZenState.CANCELED, request = null, displayChoices = emptyList(),
                bootId = now.bootId, deadlineElapsedMillis = now.elapsedMillis, serverExpiresAtMillis = 0, updatedWallMillis = now.wallMillis,
            )
            return trim(records + tombstone) to AuthZenResult.Inserted(tombstone)
        }
        if (existing.state.terminal) return records to AuthZenResult.Duplicate(existing)
        // A reply in flight is settled by its result, not by the cancellation.
        if (existing.state == AuthZenState.SENDING) return records to AuthZenResult.Ignored(AuthZenIgnored.WRONG_STATE, existing)
        val canceled = ended(existing, AuthZenState.CANCELED, now)
        return records.map { if (it === existing) canceled else it } to AuthZenResult.Updated(canceled)
    }

    /** Expiry, reboot and tombstone cleanup. */
    fun reconcile(records: List<AuthZenRecord>, now: AuthZenNow): List<AuthZenRecord> {
        val updated = records.map { record ->
            when {
                record.state.terminal -> record
                // After a reboot no pending approval is trusted again; a reply that may have left is unknown.
                record.bootId != now.bootId -> ended(record, if (record.state == AuthZenState.SENDING) AuthZenState.OUTCOME_UNKNOWN else AuthZenState.EXPIRED, now)
                record.state != AuthZenState.SENDING && expired(record, now) -> ended(record, AuthZenState.EXPIRED, now)
                // A send that never reported back (the process died) may have reached the server.
                record.state == AuthZenState.SENDING && now.elapsedMillis >= record.deadlineElapsedMillis + sendGraceMillis -> ended(record, AuthZenState.OUTCOME_UNKNOWN, now)
                else -> record
            }
        }.filter { !it.state.terminal || now.wallMillis - it.updatedWallMillis < tombstoneMillis }
        return trim(updated)
    }

    /** Server expiry anchored to the monotonic clock at receipt, and the phone's wall clock for late deliveries. */
    fun expired(record: AuthZenRecord, now: AuthZenNow): Boolean =
        now.elapsedMillis >= record.deadlineElapsedMillis || now.wallMillis >= record.serverExpiresAtMillis + clockSkewMillis

    private fun ended(record: AuthZenRecord, state: AuthZenState, now: AuthZenNow) =
        record.copy(state = state, request = null, displayChoices = emptyList(), authAttempt = null, updatedWallMillis = now.wallMillis)

    private fun trim(records: List<AuthZenRecord>): List<AuthZenRecord> {
        val tombstones = records.filter { it.state.terminal }
        if (tombstones.size <= maxTombstones) return records
        val drop = tombstones.sortedBy { it.updatedWallMillis }.take(tombstones.size - maxTombstones).map { it.handle }.toSet()
        return records.filter { it.handle !in drop }
    }
}

internal fun ByteArray.hex(): String = toByteString().hex()
