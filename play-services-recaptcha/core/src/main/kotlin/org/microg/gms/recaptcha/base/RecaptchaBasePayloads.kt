/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.recaptcha.base

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/** Signal type Play services reports for a DroidGuard response in the reCAPTCHA Base envelope. */
internal const val SIGNAL_TYPE_DROIDGUARD = 33

private val ERROR_MARKER = "ERROR : ".toByteArray(Charsets.UTF_8)

/** A DroidGuard result that must not be returned to the caller. The message never contains the result itself. */
internal class InvalidGuardResultException(message: String) : Exception(message)

/** Both forms of one execute result, built from the same DroidGuard response. */
internal class RecaptchaBaseResultPayload(val payload: String, val payloadBytes: ByteArray)

internal object RecaptchaBasePayloads {
    /**
     * Accepts only a usable DroidGuard result: non-empty base64 (standard or URL-safe alphabet, padding optional) that
     * decodes to non-empty bytes which are not a DroidGuard `ERROR : ` marker. Throws [InvalidGuardResultException]
     * otherwise.
     */
    fun requireUsableGuardResult(result: String?) {
        if (result.isNullOrEmpty()) throw InvalidGuardResultException("empty DroidGuard result")
        if (result.any { it.isWhitespace() }) throw InvalidGuardResultException("DroidGuard result is not base64")
        val decoded = result.decodeBase64() ?: throw InvalidGuardResultException("DroidGuard result is not base64")
        if (decoded.size == 0) throw InvalidGuardResultException("empty DroidGuard result")
        if (decoded.rangeEquals(0, ERROR_MARKER, 0, ERROR_MARKER.size)) {
            throw InvalidGuardResultException("DroidGuard reported an error")
        }
    }

    /**
     * Wraps a validated DroidGuard [guardResult] the way Play services does: one variable holding the signal responses
     * (DroidGuard response, signal type 33, encoding NONE) and the caller's [executeId]. The string form carries the
     * signal responses as base64 in variable field 1 and is itself base64 encoded; the byte form carries them raw in
     * variable field 6. Base64 is standard, padded and unwrapped.
     */
    fun build(guardResult: String, executeId: String): RecaptchaBaseResultPayload {
        requireUsableGuardResult(guardResult)
        val signals = RecaptchaBaseSignalResponses(
            responses = listOf(RecaptchaBaseSignalResponse(droidguard_response = guardResult)),
            signal_type = SIGNAL_TYPE_DROIDGUARD
        ).encodeByteString()
        val stringForm = RecaptchaBasePayload(
            variables = listOf(
                RecaptchaBaseVariable(
                    response_base64 = signals.base64(),
                    encoding = RecaptchaBaseEncoding.ENCODING_NONE
                )
            ),
            execute_id = executeId
        ).encode()
        val byteForm = RecaptchaBasePayload(
            variables = listOf(
                RecaptchaBaseVariable(
                    encoding = RecaptchaBaseEncoding.ENCODING_NONE,
                    response_bytes = signals
                )
            ),
            execute_id = executeId
        ).encode()
        return RecaptchaBaseResultPayload(stringForm.toByteString().base64(), byteForm)
    }
}
