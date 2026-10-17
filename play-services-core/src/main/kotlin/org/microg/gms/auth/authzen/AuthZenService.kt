/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.authzen

import securegcm.GcmMetadata
import securegcm.Type
import java.security.KeyPair
import java.security.SecureRandom

private const val MAX_KEY_HANDLE = 64
private const val MAX_ACCOUNTS = 8

internal fun SecureRandom.handle(): String = ByteArray(16).also { nextBytes(it) }.hex()

/**
 * Ingress for SecureGcm `P` messages: classify, look up the account's key by the (unverified) handle, verify and
 * decrypt, decode with a verified profile, store, then post the generic notification. Nothing here can approve.
 *
 * While [blocker] reports a reason, a message is only counted (and, in lab capture, its shape logged).
 */
internal class AuthZenIngress(
    private val diagnostics: AuthZenDiagnostics,
    private val blocker: () -> AuthZenBlocker?,
    private val labCapture: () -> Boolean,
    private val accounts: () -> List<String>,
    private val keys: AuthZenKeyRegistry,
    private val decoder: AuthZenDecoder,
    private val repository: AuthZenRepository,
    private val notifier: Notifier,
    private val alerts: AlertLimiter,
    private val now: () -> AuthZenNow,
    private val random: SecureRandom = SecureRandom(),
    private val senderAllowed: (String?) -> Boolean = { it == SecureGcm.SENDER_ID },
) {
    interface Notifier {
        /** Posts the generic prompt notification; false when the owner blocked notifications. */
        fun show(record: AuthZenRecord): Boolean
        fun cancel(handle: String)
    }

    /** @return false for messages that are not SecureGcm (GNOTS and others), which the caller handles as before */
    fun onMessage(from: String?, gcmBody: String?, securePayload: String?): Boolean {
        val envelope = classifyEnvelope(from, gcmBody, securePayload, senderAllowed)
        if (envelope is AuthZenEnvelope.Gnots || envelope is AuthZenEnvelope.Other) return false
        val session = diagnostics.newSession()
        if (envelope is AuthZenEnvelope.Rejected) {
            diagnostics.event(AuthZenEvent.ENVELOPE_REJECTED, session, "reason" to envelope.reason)
            return true
        }
        val payload = (envelope as AuthZenEnvelope.SecureGcm).payload
        diagnostics.event(AuthZenEvent.INGRESS, session, "route" to Route.SECUREGCM, "size_bucket" to sizeBucket(payload.size))
        val message = try {
            SecureMessageParser.decode(payload)
        } catch (e: SecureMessageException) {
            diagnostics.event(AuthZenEvent.CRYPTO_FAILED, session, "stage" to CryptoStage.FRAMING)
            return true
        }
        val capture = labCapture()
        if (capture) logUnverifiedShape(session, payload, message)
        blocker()?.let {
            diagnostics.event(AuthZenEvent.READINESS, session, "reason" to it)
            return true
        }
        val handle = try {
            SecureGcm.unverifiedKeyHandle(message)
        } catch (e: SecureMessageException) {
            null
        }
        if (handle == null || handle.isEmpty() || handle.size > MAX_KEY_HANDLE) {
            diagnostics.event(AuthZenEvent.CRYPTO_FAILED, session, "stage" to CryptoStage.HEADER)
            return true
        }
        val candidates = accounts().take(MAX_ACCOUNTS).mapNotNull { account -> keys.serverKey(account, handle)?.let { account to it } }
        diagnostics.event(AuthZenEvent.KEY_LOOKUP, session, "found" to (candidates.size == 1), "matches" to candidates.size)
        // One key handle in two accounts is ambiguous; neither account is guessed.
        val (account, masterKey) = candidates.singleOrNull() ?: return true
        val verified = try {
            SecureGcm.verifyDecryptServerMessage(message, masterKey)
        } catch (e: SecureMessageException) {
            diagnostics.event(AuthZenEvent.CRYPTO_FAILED, session, "stage" to CryptoStage.VERIFY)
            return true
        }
        diagnostics.event(AuthZenEvent.CRYPTO_VERIFIED, session, "type" to verified.type, "version" to verified.version)
        if (capture) diagnostics.event(AuthZenEvent.SHAPE, session, "part" to ShapePart.PLAINTEXT, "fields" to ProtoShape.of(verified.body))
        val generation = keys.keys(account).generation
        when (val decoded = decoder.decode(verified.type, verified.version, verified.body)) {
            is Decoded.Unsupported -> diagnostics.event(AuthZenEvent.UNSUPPORTED, session, "reason" to decoded.reason)
            is Decoded.Cancel -> {
                val result = repository.apply(AuthZenAction.Cancel(account, generation, decoded.transactionId.hex(), random.handle()), now())
                diagnostics.event(AuthZenEvent.STORE, session, "kind" to StoreKind.CANCEL, "result" to result.kind())
                if (result is AuthZenResult.Updated) notifier.cancel(result.record.handle)
            }
            is Decoded.Request -> {
                val request = decoded.request
                val digest = SecureMessageCrypto.sha256(request.originalBody).hex()
                val choices = request.numberChoices.shuffled(random)
                val result = repository.apply(AuthZenAction.Receive(account, generation, decoded.profile.name, request, digest, random.handle(), choices), now())
                diagnostics.event(AuthZenEvent.STORE, session, "kind" to StoreKind.REQUEST, "result" to result.kind())
                if (result is AuthZenResult.Inserted) {
                    if (!alerts.allow(account, now().elapsedMillis)) {
                        diagnostics.event(AuthZenEvent.UI_POSTED, session, "suppressed" to true)
                    } else if (notifier.show(result.record)) {
                        diagnostics.event(AuthZenEvent.UI_POSTED, session, "suppressed" to false)
                    } else {
                        diagnostics.event(AuthZenEvent.NOTIFICATIONS_BLOCKED, session)
                    }
                }
            }
        }
        return true
    }

    /** Lab capture: what the unauthenticated envelope looks like. Scheme and type numbers are protocol constants. */
    private fun logUnverifiedShape(session: String, payload: ByteArray, message: securemessage.SecureMessage) {
        diagnostics.event(AuthZenEvent.SHAPE, session, "part" to ShapePart.ENVELOPE, "fields" to ProtoShape.of(payload))
        val header = try {
            SecureMessageParser.unverifiedHeader(message)
        } catch (e: SecureMessageException) {
            return diagnostics.event(AuthZenEvent.SHAPE, session, "part" to ShapePart.HEADER, "readable" to false)
        }
        val metadata = header.public_metadata?.let {
            try {
                GcmMetadata.ADAPTER.decode(it)
            } catch (e: Exception) {
                null
            }
        }
        diagnostics.event(
            AuthZenEvent.SHAPE, session, "part" to ShapePart.HEADER, "verified" to false,
            "sig" to header.signature_scheme, "enc" to header.encryption_scheme,
            "key_id_bucket" to sizeBucket(header.verification_key_id?.size ?: 0), "has_iv" to (header.iv != null),
            "type" to metadata?.type, "version" to metadata?.version,
        )
    }

    private enum class Route { SECUREGCM }
    private enum class CryptoStage { FRAMING, HEADER, VERIFY }
    private enum class ShapePart { ENVELOPE, HEADER, PLAINTEXT }
    private enum class StoreKind { REQUEST, CANCEL }
}

internal enum class StoreResultKind { INSERTED, DUPLICATE, CONFLICT, REJECTED, UPDATED, IGNORED, NONE }

internal fun AuthZenResult?.kind(): StoreResultKind = when (this) {
    is AuthZenResult.Inserted -> StoreResultKind.INSERTED
    is AuthZenResult.Duplicate -> StoreResultKind.DUPLICATE
    AuthZenResult.Conflict -> StoreResultKind.CONFLICT
    is AuthZenResult.Rejected -> StoreResultKind.REJECTED
    is AuthZenResult.Updated -> StoreResultKind.UPDATED
    is AuthZenResult.Ignored -> StoreResultKind.IGNORED
    null -> StoreResultKind.NONE
}

/** At most [perAccount] and [perDevice] alerts per [windowMillis] (DESIGN values); excess requests stay silent. */
internal class AlertLimiter(private val perAccount: Int = 3, private val perDevice: Int = 10, private val windowMillis: Long = 60_000L) {
    private val alerts = ArrayDeque<Pair<String, Long>>()

    @Synchronized
    fun allow(account: String, elapsedMillis: Long): Boolean {
        while (alerts.isNotEmpty() && elapsedMillis - alerts.first().second >= windowMillis) alerts.removeFirst()
        if (alerts.size >= perDevice || alerts.count { it.first == account } >= perAccount) return false
        alerts.addLast(account to elapsedMillis)
        return true
    }
}

/** What the confirmation screen should do next. */
internal sealed class ConsentStep {
    /** Show the stored record; nothing was decided. */
    class Show(val record: AuthZenRecord) : ConsentStep()
    /** Ask the owner to authenticate; the result must come back with exactly this binding. */
    class Authenticate(val binding: OwnerAuthBinding) : ConsentStep()
    /** The decision is stored; [report] says what happened to the reply. */
    class Submitted(val report: SendReport) : ConsentStep()
    /** The request is gone, ended or cannot take this action. */
    class Closed(val record: AuthZenRecord?) : ConsentStep()
}

/** Ties an owner authentication to one request: one attempt id, the request bytes and the key generation. */
internal class OwnerAuthBinding(val handle: String, val attempt: String, val digest: String?, val generation: Long)

/**
 * The owner's side of a transaction, independent of Android so that its guarantees are tested on the host: opening,
 * rendering, resuming or unlocking never decides anything; Yes needs the right number when there is one and then a
 * fresh owner authentication bound to this request; No and a wrong number need no authentication; dismissing is
 * nothing at all.
 */
internal class AuthZenConsent(
    private val repository: AuthZenRepository,
    private val sender: AuthZenSender,
    private val diagnostics: AuthZenDiagnostics,
    private val now: () -> AuthZenNow,
    private val random: SecureRandom = SecureRandom(),
) {
    fun open(handle: String): ConsentStep {
        val result = repository.apply(AuthZenAction.Present(handle), now())
        val record = (result as? AuthZenResult.Updated)?.record ?: (result as? AuthZenResult.Ignored)?.record
        return when {
            record == null || record.state.terminal -> ConsentStep.Closed(record)
            // Reopened after deciding: the stored decision is sent (again only if it provably never left).
            record.state == AuthZenState.DECISION_AUTHORIZED -> ConsentStep.Submitted(sender.send(handle))
            record.state == AuthZenState.SENDING -> ConsentStep.Submitted(SendReport.Stopped(AuthZenState.SENDING))
            else -> ConsentStep.Show(record)
        }
    }

    /** The owner tapped Yes, and [selectedNumber] when the request has numbers. */
    fun approve(handle: String, selectedNumber: Int?): ConsentStep {
        val attempt = random.handle()
        val record = when (val result = repository.apply(AuthZenAction.Approve(handle, selectedNumber, attempt), now())) {
            is AuthZenResult.Updated -> result.record
            is AuthZenResult.Ignored -> return result.record?.takeIf { !it.state.terminal }?.let { ConsentStep.Show(it) } ?: ConsentStep.Closed(result.record)
            else -> return ConsentStep.Closed(null)
        }
        if (record.state == AuthZenState.DECISION_AUTHORIZED) {
            diagnostics.event(AuthZenEvent.DECISION, null, "decision" to record.decision)
            return ConsentStep.Submitted(sender.send(handle))
        }
        return ConsentStep.Authenticate(OwnerAuthBinding(handle, attempt, record.digest, record.generation))
    }

    fun authenticated(binding: OwnerAuthBinding, result: OwnerAuthResult): ConsentStep {
        diagnostics.event(AuthZenEvent.OWNER_AUTH, null, "result" to result)
        val action = AuthZenAction.AuthResult(binding.handle, binding.attempt, binding.digest, binding.generation, result == OwnerAuthResult.SUCCESS)
        return when (val stored = repository.apply(action, now())) {
            is AuthZenResult.Updated -> if (stored.record.state == AuthZenState.DECISION_AUTHORIZED) {
                diagnostics.event(AuthZenEvent.DECISION, null, "decision" to stored.record.decision)
                ConsentStep.Submitted(sender.send(binding.handle))
            } else ConsentStep.Show(stored.record)
            is AuthZenResult.Ignored -> stored.record?.takeIf { !it.state.terminal }?.let { ConsentStep.Show(it) } ?: ConsentStep.Closed(stored.record)
            else -> ConsentStep.Closed(null)
        }
    }

    /** The owner tapped "No, it's not me". */
    fun deny(handle: String): ConsentStep = when (val result = repository.apply(AuthZenAction.Deny(handle), now())) {
        is AuthZenResult.Updated -> {
            diagnostics.event(AuthZenEvent.DECISION, null, "decision" to AuthZenDecision.DENY)
            ConsentStep.Submitted(sender.send(handle))
        }
        is AuthZenResult.Ignored -> ConsentStep.Closed(result.record)
        else -> ConsentStep.Closed(null)
    }
}

internal sealed class SendReport {
    /** The reply was sent; the record now has [state]. */
    class Sent(val state: AuthZenState) : SendReport()
    /** Nothing was sent because the route is not ready; the decision stays stored until the request expires. */
    class NotReady(val blocker: AuthZenBlocker) : SendReport()
    /** Nothing was sent; the request ended or changed first. */
    class Stopped(val state: AuthZenState?) : SendReport()
}

/**
 * Sends a stored decision: final checks at the send boundary (account, opt-in, key generation, expiry, cancellation),
 * `SENDING` stored before the network call, the reply signcrypted with the account's `PublicKey` and active
 * `authzen` key, one attempt, and an uncertain result kept as OUTCOME_UNKNOWN, never retried blindly.
 */
internal class AuthZenSender(
    private val repository: AuthZenRepository,
    private val keys: AuthZenKeyRegistry,
    private val profiles: List<AuthZenProfile>,
    private val api: AuthZenApi?,
    private val accountPresent: (String) -> Boolean,
    private val optedIn: () -> Boolean,
    /** The account's enrolled `PublicKey` pair and its exact key material, read without creating anything. */
    private val userKey: (String) -> Pair<KeyPair, ByteArray>?,
    private val diagnostics: AuthZenDiagnostics,
    private val now: () -> AuthZenNow,
    private val random: SecureRandom = SecureRandom(),
) {
    fun send(handle: String): SendReport {
        val record = repository.find(handle, now()) ?: return SendReport.Stopped(null)
        if (record.state != AuthZenState.DECISION_AUTHORIZED) return SendReport.Stopped(record.state)
        val profile = profiles.firstOrNull { it.name == record.profile }
        if (profile == null || api == null) {
            diagnostics.event(AuthZenEvent.READINESS, null, "reason" to AuthZenBlocker.PROTOCOL_NOT_VERIFIED)
            return SendReport.NotReady(AuthZenBlocker.PROTOCOL_NOT_VERIFIED)
        }
        val accountKeys = keys.keys(record.account)
        val operation = random.handle()
        val begin = repository.apply(AuthZenAction.BeginSend(handle, operation, accountPresent(record.account), optedIn(), accountKeys.generation), now())
        val sending = (begin as? AuthZenResult.Updated)?.record?.takeIf { it.state == AuthZenState.SENDING }
            ?: return SendReport.Stopped((begin as? AuthZenResult.Updated)?.record?.state ?: record.state)

        var attempted = false
        val outcome = try {
            val masterKey = accountKeys.active?.secretKey
            val (pair, keyMaterial) = userKey(sending.account) ?: (null to null)
            if (masterKey == null || pair == null || keyMaterial == null) {
                SendOutcome.NOT_DELIVERED
            } else {
                val reply = profile.encodeReply(sending.request!!, sending.decision!!, now().wallMillis)
                val signcrypted = SecureGcm.signcryptClientMessage(Type.TX_REPLY, reply, pair, keyMaterial, masterKey, random).encode()
                diagnostics.event(AuthZenEvent.SEND, null, "attempt" to true)
                attempted = true
                api.post(sending.account, AuthZenEndpoint.REPLY_TX, profile.replyEnvelope(sending.account, signcrypted)).toSendOutcome(profile::interpretReply)
            }
        } catch (e: Exception) {
            // Before the network call nothing left the phone; after it, the reply may have arrived.
            if (attempted) SendOutcome.UNKNOWN else SendOutcome.NOT_DELIVERED
        }
        val finished = repository.apply(AuthZenAction.SendFinished(handle, operation, outcome), now())
        val state = (finished as? AuthZenResult.Updated)?.record?.state
        diagnostics.event(AuthZenEvent.RESULT, null, "outcome" to outcome, "state" to state)
        return SendReport.Sent(state ?: AuthZenState.OUTCOME_UNKNOWN)
    }
}

/** How a fresh owner authentication ended. Only SUCCESS for the bound attempt authorizes an approval. */
internal enum class OwnerAuthResult { SUCCESS, CANCELED, FAILED }
