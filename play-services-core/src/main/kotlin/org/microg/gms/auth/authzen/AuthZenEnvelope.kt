/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.authzen

import okio.ByteString.Companion.decodeBase64

/** GNOTS tickles carry `gcmb=notifications`; they belong to the existing two-step route. */
private const val GNOTS_BODY = "notifications"

/** DESIGN bound, not a Google value: larger encoded payloads are refused before any parsing or cryptography. */
internal const val MAX_ENCODED_PAYLOAD = 64 * 1024

internal enum class EnvelopeRejection { SENDER, TOO_LARGE, EMPTY, ENCODING }

/** What a GmsCore push message is, judged from transport metadata only. Nothing here is authenticated. */
internal sealed class AuthZenEnvelope {
    /** A SecureGcm message: the decoded `P` app data, still to be verified. */
    class SecureGcm(val payload: ByteArray) : AuthZenEnvelope()

    /** A GNOTS tickle, left to the two-step route unchanged. */
    data object Gnots : AuthZenEnvelope()

    /** Neither; another GmsCore feature's message. */
    data object Other : AuthZenEnvelope()

    class Rejected(val reason: EnvelopeRejection) : AuthZenEnvelope()
}

/**
 * Classifies a message by its transport fields. GNOTS wins when `gcmb=notifications` is present, so this class never
 * changes the two-step route. [senderAllowed] decides which `from` values may carry SecureGcm messages; production
 * accepts only [SecureGcm.SENDER_ID] (the current Android sender is UNKNOWN, see docs/google-prompt.md).
 */
internal fun classifyEnvelope(
    from: String?,
    gcmBody: String?,
    securePayload: String?,
    senderAllowed: (String?) -> Boolean = { it == SecureGcm.SENDER_ID },
): AuthZenEnvelope {
    if (gcmBody == GNOTS_BODY) return AuthZenEnvelope.Gnots
    if (securePayload == null) return AuthZenEnvelope.Other
    if (!senderAllowed(from)) return AuthZenEnvelope.Rejected(EnvelopeRejection.SENDER)
    if (securePayload.length > MAX_ENCODED_PAYLOAD) return AuthZenEnvelope.Rejected(EnvelopeRejection.TOO_LARGE)
    if (securePayload.isEmpty()) return AuthZenEnvelope.Rejected(EnvelopeRejection.EMPTY)
    // Historically URL-safe Base64; okio also accepts the standard alphabet and missing padding.
    val decoded = securePayload.decodeBase64() ?: return AuthZenEnvelope.Rejected(EnvelopeRejection.ENCODING)
    if (decoded.size == 0) return AuthZenEnvelope.Rejected(EnvelopeRejection.EMPTY)
    return AuthZenEnvelope.SecureGcm(decoded.toByteArray())
}
