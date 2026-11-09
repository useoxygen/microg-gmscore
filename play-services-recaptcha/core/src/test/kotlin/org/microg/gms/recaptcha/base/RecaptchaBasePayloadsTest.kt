/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.recaptcha.base

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class RecaptchaBasePayloadsTest {
    // Synthetic values only. The DroidGuard result is base64("synthetic-droidguard-output").
    private val guardResult = "c3ludGhldGljLWRyb2lkZ3VhcmQtb3V0cHV0"
    private val executeId = "synthetic-execute-id"

    /**
     * Golden string-form payload for the values above, as an independently built wrapper: outer field 1 holds one
     * variable (field 1 = base64 signal responses, field 4 = 1/NONE), outer field 2 holds the execute ID; the signal
     * responses hold the DroidGuard string in response field 11 and signal type 33 in field 2.
     */
    private val goldenPayload =
        "CjwKOENpWmFKR016YkhWa1IyaHNaRWRzYWt4WFVubGlNbXhyV2pOV2FHTnRVWFJpTTFZd1kwaFdNQkFoIAESFHN5bnRoZXRpYy1leGVjdXRlLWlk"

    @Test
    fun stringFormMatchesGoldenBytes() {
        val built = RecaptchaBasePayloads.build(guardResult, executeId)
        assertEquals(goldenPayload, built.payload)
    }

    @Test
    fun stringFormFieldsDecodeIndependently() {
        val built = RecaptchaBasePayloads.build(guardResult, executeId)
        // Standard alphabet, padded, no line wrapping.
        assertTrue(built.payload.matches(Regex("^[A-Za-z0-9+/]*={0,2}$")))
        assertEquals(0, built.payload.length % 4)

        val outer = parseProto(Base64.getDecoder().decode(built.payload))
        assertEquals(listOf(1, 2), outer.numbers())
        assertEquals(executeId, outer.single(2).string)

        val variable = parseProto(outer.single(1).bytes!!)
        assertEquals(listOf(1, 4), variable.numbers())
        assertEquals(0, variable.single(4).wireType)
        assertEquals(1L, variable.single(4).varint)
        val signalsBase64 = variable.single(1).string
        assertTrue(signalsBase64.matches(Regex("^[A-Za-z0-9+/]*={0,2}$")))

        assertSignalResponses(Base64.getDecoder().decode(signalsBase64))
    }

    @Test
    fun byteFormCarriesTheSameSignalsInField6() {
        val built = RecaptchaBasePayloads.build(guardResult, executeId)
        val outer = parseProto(built.payloadBytes)
        assertEquals(listOf(1, 2), outer.numbers())
        assertEquals(executeId, outer.single(2).string)

        val variable = parseProto(outer.single(1).bytes!!)
        assertEquals(listOf(4, 6), variable.numbers())
        assertEquals(1L, variable.single(4).varint)
        assertSignalResponses(variable.single(6).bytes!!)

        // Both forms wrap byte-identical signal responses.
        val stringVariable = parseProto(parseProto(Base64.getDecoder().decode(built.payload)).single(1).bytes!!)
        assertArrayEquals(Base64.getDecoder().decode(stringVariable.single(1).string), variable.single(6).bytes)
    }

    private fun assertSignalResponses(bytes: ByteArray) {
        val signals = parseProto(bytes)
        assertEquals(listOf(1, 2), signals.numbers())
        assertEquals(0, signals.single(2).wireType)
        assertEquals(33L, signals.single(2).varint)
        val response = parseProto(signals.single(1).bytes!!)
        assertEquals(listOf(11), response.numbers())
        assertEquals(2, response.single(11).wireType)
        // The DroidGuard result is carried unchanged.
        assertEquals(guardResult, response.single(11).string)
    }

    @Test
    fun executeIdIsAttachedAndNotTheNonce() {
        val built = RecaptchaBasePayloads.build(guardResult, "synthetic-id-2")
        val text = String(Base64.getDecoder().decode(built.payload), Charsets.ISO_8859_1)
        assertTrue(text.contains("synthetic-id-2"))
        assertFalse(text.contains("synthetic-nonce"))
    }

    @Test
    fun emptyExecuteIdIsStillWrittenAsField2() {
        val outer = parseProto(Base64.getDecoder().decode(RecaptchaBasePayloads.build(guardResult, "").payload))
        assertEquals(listOf(1, 2), outer.numbers())
        assertEquals("", outer.single(2).string)
    }

    @Test
    fun urlSafeUnpaddedGuardResultIsAcceptedUnchanged() {
        // microG's DroidGuard client returns URL-safe base64 without padding.
        val urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(-5, -1, -2, 1, 2))
        assertTrue(urlSafe.contains('-') || urlSafe.contains('_'))
        val built = RecaptchaBasePayloads.build(urlSafe, executeId)
        val variable = parseProto(parseProto(built.payloadBytes).single(1).bytes!!)
        val response = parseProto(parseProto(variable.single(6).bytes!!).single(1).bytes!!)
        assertEquals(urlSafe, response.single(11).string)
    }

    @Test
    fun rejectsUnusableGuardResults() {
        val errorMarker = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("ERROR : Initialization failed: synthetic".toByteArray())
        val paddedErrorMarker = Base64.getEncoder().encodeToString("ERROR : Snapshot timeout: 60000 ms".toByteArray())
        for (bad in listOf<String?>(null, "", errorMarker, paddedErrorMarker, "not base64!", "c3lu dGg=", "=")) {
            try {
                RecaptchaBasePayloads.build(bad ?: "", executeId)
                fail("accepted unusable DroidGuard result")
            } catch (e: InvalidGuardResultException) {
                // The message must never echo the result.
                if (!bad.isNullOrEmpty()) assertFalse(e.message!!.contains(bad))
            }
        }
    }
}
