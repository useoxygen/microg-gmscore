/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.authzen

import org.json.JSONObject
import securegcm.Type

/**
 * A sign-in request after authentication and decoding, in Cyclon's own terms. Profiles fill it from Google's bytes;
 * nothing else does. Times are the server's clock in milliseconds.
 */
internal class AuthZenRequest(
    val transactionId: ByteArray,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    /** The challenge numbers to offer, or empty for a plain Yes/No prompt. */
    val numberChoices: List<Int>,
    /** Which of [numberChoices] the browser shows; null exactly when [numberChoices] is empty. Never shown or logged. */
    val correctNumber: Int?,
    /** Plain display strings the profile verified, for example a browser or approximate location, in order. */
    val display: List<String>,
    /** The verified plaintext exactly as received, for binding the reply to this request. */
    val originalBody: ByteArray,
)

/** The owner's answer. Dismissing is not an answer. */
internal enum class AuthZenDecision { APPROVE, DENY, MISMATCH }

/** How a sent reply ended, as the profile reads the server's response. */
internal enum class SendOutcome {
    /** The server confirmed it recorded the answer. */
    CONFIRMED,
    /** The request provably did not reach the server; the decision may be sent again until expiry. */
    NOT_DELIVERED,
    /** It may have reached the server; never resend, reconcile through a verified state sync instead. */
    UNKNOWN,
    /** The server refused the route (for example 403 or a policy or attestation requirement). */
    REJECTED,
    /** The server says the transaction already expired or ended. */
    EXPIRED,
}

internal class AuthZenDecodeException(val reason: UnsupportedReason) : Exception()

internal enum class UnsupportedReason {
    /** No transaction profile is verified against current Google traffic; see docs/google-prompt.md. */
    NO_VERIFIED_PROFILE,
    UNKNOWN_TYPE,
    UNSUPPORTED_VERSION,
    /** A profile found a required field, version, action or trigger it does not support. */
    UNSUPPORTED_CONTENT,
    MISSING_FIELD,
    INVALID_EXPIRY,
    INVALID_CHOICES,
    INVALID_DISPLAY,
    INVALID_TRANSACTION_ID,
}

/**
 * One version of Google's transaction format, decoded and encoded by Cyclon's own code. A profile may be added to
 * [AuthZenProfiles.verified] only after its schema was confirmed against a current request and reply on an authorized
 * dev phone (docs/google-prompt.md, "Before enabling"). Historical field maps must not be registered as current.
 */
internal interface AuthZenProfile {
    val name: String

    /** Whether this profile handles a payload with these authenticated metadata values. */
    fun accepts(type: Type, version: Int): Boolean

    /** @throws AuthZenDecodeException for anything the profile does not fully support */
    fun decodeRequest(body: ByteArray): AuthZenRequest

    /** The transaction id of a verified cancellation. */
    fun decodeCancel(body: ByteArray): ByteArray

    /** The reply plaintext, bound to [request]'s [AuthZenRequest.originalBody]; signcrypted by the sender. */
    fun encodeReply(request: AuthZenRequest, decision: AuthZenDecision, decidedAtMillis: Long): ByteArray

    /** The JSON body for the reply endpoint, carrying the signcrypted reply. */
    fun replyEnvelope(account: String, signcryptedReply: ByteArray): JSONObject

    /** What the server's parsed reply response means. An unrecognized response is [SendOutcome.UNKNOWN]. */
    fun interpretReply(response: JSONObject): SendOutcome
}

internal object AuthZenProfiles {
    /** Empty until a profile is verified on device. While empty the route reports [AuthZenBlocker.PROTOCOL_NOT_VERIFIED]. */
    val verified: List<AuthZenProfile> = emptyList()
}

internal sealed class Decoded {
    class Request(val profile: AuthZenProfile, val request: AuthZenRequest) : Decoded()
    class Cancel(val profile: AuthZenProfile, val transactionId: ByteArray) : Decoded()
    class Unsupported(val reason: UnsupportedReason) : Decoded()
}

/**
 * Turns an authenticated SecureGcm payload into a request or cancellation, failing closed: unknown types, versions
 * and profiles are unsupported, and every decoded request is checked against Cyclon's own bounds whatever the
 * profile returned. Those bounds are DESIGN choices, not Google values.
 */
internal class AuthZenDecoder(private val profiles: List<AuthZenProfile> = AuthZenProfiles.verified) {

    fun decode(type: Type?, version: Int, body: ByteArray): Decoded {
        if (type == null) return Decoded.Unsupported(UnsupportedReason.UNKNOWN_TYPE)
        if (version != SecureGcm.VERSION) return Decoded.Unsupported(UnsupportedReason.UNSUPPORTED_VERSION)
        if (type != Type.TX_REQUEST && type != Type.TX_CANCEL_REQUEST) return Decoded.Unsupported(UnsupportedReason.UNKNOWN_TYPE)
        val profile = profiles.firstOrNull { it.accepts(type, version) } ?: return Decoded.Unsupported(UnsupportedReason.NO_VERIFIED_PROFILE)
        return try {
            if (type == Type.TX_CANCEL_REQUEST) {
                Decoded.Cancel(profile, profile.decodeCancel(body).also { checkTransactionId(it) })
            } else {
                Decoded.Request(profile, profile.decodeRequest(body).also { validate(it, body) })
            }
        } catch (e: AuthZenDecodeException) {
            Decoded.Unsupported(e.reason)
        } catch (e: Exception) {
            Decoded.Unsupported(UnsupportedReason.UNSUPPORTED_CONTENT)
        }
    }

    private fun checkTransactionId(id: ByteArray) {
        if (id.isEmpty() || id.size > MAX_TRANSACTION_ID) throw AuthZenDecodeException(UnsupportedReason.INVALID_TRANSACTION_ID)
    }

    private fun validate(request: AuthZenRequest, body: ByteArray) {
        checkTransactionId(request.transactionId)
        if (!request.originalBody.contentEquals(body)) throw AuthZenDecodeException(UnsupportedReason.UNSUPPORTED_CONTENT)
        val lifetime = request.expiresAtMillis - request.createdAtMillis
        if (request.createdAtMillis <= 0 || lifetime <= 0 || lifetime > MAX_LIFETIME_MILLIS) throw AuthZenDecodeException(UnsupportedReason.INVALID_EXPIRY)
        val choices = request.numberChoices
        if (choices.isEmpty()) {
            if (request.correctNumber != null) throw AuthZenDecodeException(UnsupportedReason.INVALID_CHOICES)
        } else {
            if (choices.size !in MIN_CHOICES..MAX_CHOICES || choices.toSet().size != choices.size) throw AuthZenDecodeException(UnsupportedReason.INVALID_CHOICES)
            if (choices.any { it !in 0..MAX_NUMBER } || request.correctNumber !in choices) throw AuthZenDecodeException(UnsupportedReason.INVALID_CHOICES)
        }
        if (request.display.size > MAX_DISPLAY_LINES || request.display.any { it.length > MAX_DISPLAY_LENGTH || it.any(Char::isISOControl) }) {
            throw AuthZenDecodeException(UnsupportedReason.INVALID_DISPLAY)
        }
    }

    companion object {
        const val MAX_TRANSACTION_ID = 64
        /** Longer lifetimes are refused; Google's real lifetime is UNKNOWN. */
        const val MAX_LIFETIME_MILLIS = 15 * 60_000L
        const val MIN_CHOICES = 2
        const val MAX_CHOICES = 9
        const val MAX_NUMBER = 999
        const val MAX_DISPLAY_LINES = 6
        const val MAX_DISPLAY_LENGTH = 200
    }
}
