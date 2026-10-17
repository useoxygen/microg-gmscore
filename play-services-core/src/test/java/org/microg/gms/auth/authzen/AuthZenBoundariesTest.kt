/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.authzen

import com.google.android.gms.BuildConfig
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.cryptauth.CryptAuthUserKey
import org.microg.gms.cryptauth.enrollKeysRequest
import org.microg.gms.cryptauth.syncSingleKeyRequests
import securegcm.Type
import java.io.File
import java.security.interfaces.ECPublicKey

/** Envelope classification, decoder bounds, the feature flag, diagnostics shape and the Workspace regression. */
class AuthZenBoundariesTest {
    private val payload = ByteArray(40) { (it * 11).toByte() }

    @Test
    fun envelopeClassification() {
        assertSame(AuthZenEnvelope.Gnots, classifyEnvelope(SecureGcm.SENDER_ID, "notifications", "AAAA"))
        assertSame(AuthZenEnvelope.Other, classifyEnvelope(SecureGcm.SENDER_ID, null, null))
        for (encoded in listOf(payload.toByteString().base64Url(), payload.toByteString().base64Url().trimEnd('='), payload.toByteString().base64())) {
            assertArrayEquals(payload, (classifyEnvelope(SecureGcm.SENDER_ID, null, encoded) as AuthZenEnvelope.SecureGcm).payload)
        }
        fun rejection(from: String?, value: String) = (classifyEnvelope(from, null, value) as AuthZenEnvelope.Rejected).reason
        assertEquals(EnvelopeRejection.SENDER, rejection(null, "AAAA"))
        assertEquals(EnvelopeRejection.SENDER, rejection("16502139086", "AAAA"))
        assertEquals(EnvelopeRejection.EMPTY, rejection(SecureGcm.SENDER_ID, ""))
        assertEquals(EnvelopeRejection.ENCODING, rejection(SecureGcm.SENDER_ID, "*not base64*"))
        assertEquals(EnvelopeRejection.TOO_LARGE, rejection(SecureGcm.SENDER_ID, "A".repeat(MAX_ENCODED_PAYLOAD + 1)))
    }

    private val profile = SyntheticProfile()
    private val decoder = AuthZenDecoder(listOf(profile))

    private fun reason(body: ByteArray, type: Type = Type.TX_REQUEST, version: Int = 1) = (decoder.decode(type, version, body) as Decoded.Unsupported).reason

    @Test
    fun decoderAcceptsPlainAndNumberRequestsAndKeepsTheOriginalBytes() {
        val body = syntheticRequestBody()
        val decoded = decoder.decode(Type.TX_REQUEST, 1, body) as Decoded.Request
        assertArrayEquals(body, decoded.request.originalBody)
        assertEquals(32, decoded.request.correctNumber)
        val plain = decoder.decode(Type.TX_REQUEST, 1, syntheticRequestBody(choices = emptyList(), correct = null)) as Decoded.Request
        assertTrue(plain.request.numberChoices.isEmpty())
    }

    @Test
    fun decoderRejectsWhatItCannotFullySupport() {
        assertEquals(UnsupportedReason.NO_VERIFIED_PROFILE, (AuthZenDecoder().decode(Type.TX_REQUEST, 1, syntheticRequestBody()) as Decoded.Unsupported).reason)
        assertEquals(UnsupportedReason.UNKNOWN_TYPE, (decoder.decode(null, 1, syntheticRequestBody()) as Decoded.Unsupported).reason)
        assertEquals(UnsupportedReason.UNKNOWN_TYPE, reason(syntheticRequestBody(), Type.TX_SYNC_RESPONSE))
        assertEquals(UnsupportedReason.UNSUPPORTED_VERSION, reason(syntheticRequestBody(), version = 2))
        assertEquals(UnsupportedReason.UNSUPPORTED_VERSION, reason(syntheticRequestBody(), version = -1))
        assertEquals(UnsupportedReason.UNSUPPORTED_CONTENT, reason("{\"unsupportedTrigger\":true}".toByteArray()))
        assertEquals(UnsupportedReason.UNSUPPORTED_CONTENT, reason("not json".toByteArray()))
        assertEquals(UnsupportedReason.INVALID_EXPIRY, reason(syntheticRequestBody(expires = 1_800_000_000_000L)))
        assertEquals(UnsupportedReason.INVALID_EXPIRY, reason(syntheticRequestBody(expires = 1_800_000_000_000L + 3600_000L)))
        assertEquals(UnsupportedReason.INVALID_CHOICES, reason(syntheticRequestBody(choices = listOf(32, 32, 81))))
        assertEquals(UnsupportedReason.INVALID_CHOICES, reason(syntheticRequestBody(choices = listOf(32))))
        assertEquals(UnsupportedReason.INVALID_CHOICES, reason(syntheticRequestBody(correct = 99)))
        assertEquals(UnsupportedReason.INVALID_CHOICES, reason(syntheticRequestBody(correct = null)))
        assertEquals(UnsupportedReason.INVALID_CHOICES, reason(syntheticRequestBody(choices = emptyList(), correct = 5)))
        assertEquals(UnsupportedReason.INVALID_DISPLAY, reason(syntheticRequestBody(display = listOf("a\u0000b"))))
        assertEquals(UnsupportedReason.INVALID_DISPLAY, reason(syntheticRequestBody(display = listOf("x".repeat(500)))))
        assertEquals(UnsupportedReason.INVALID_TRANSACTION_ID, reason(syntheticRequestBody(id = "")))
        assertEquals(UnsupportedReason.INVALID_TRANSACTION_ID, reason(syntheticRequestBody(id = "x".repeat(65))))
    }

    @Test
    fun theNativeRouteIsOffByDefault() {
        assertFalse(BuildConfig.CYCLON_AUTHZEN_NATIVE)
        assertFalse(AuthZenFeature.enabled)
        assertFalse(AuthZenFeature.labCaptureActive())
        assertTrue(AuthZenProfiles.verified.isEmpty())
    }

    @Test
    fun readinessOrderAndMessage() {
        val ready = AuthZenReadinessInput(true, true, 1, 1, true, true)
        assertNull(authZenBlocker(ready))
        assertEquals(AuthZenBlocker.FEATURE_OFF, authZenBlocker(AuthZenReadinessInput(false, false, 0, 0, false, false)))
        assertEquals(AuthZenBlocker.OWNER_OPTED_OUT, authZenBlocker(AuthZenReadinessInput(true, false, 0, 0, true, true)))
        assertEquals(AuthZenBlocker.PROTOCOL_NOT_VERIFIED, authZenBlocker(AuthZenReadinessInput(true, true, AuthZenProfiles.verified.size, 1, true, true)))
        assertEquals(AuthZenBlocker.NO_AUTHZEN_KEY, authZenBlocker(AuthZenReadinessInput(true, true, 1, 0, true, true)))
        assertEquals(AuthZenBlocker.NO_SECURE_LOCK, authZenBlocker(AuthZenReadinessInput(true, true, 1, 1, false, true)))
        assertEquals(AuthZenBlocker.NOTIFICATIONS_BLOCKED, authZenBlocker(AuthZenReadinessInput(true, true, 1, 1, true, false)))
        val strings = File("src/main/res/values/cyclon_authzen.xml").readText()
        assertTrue(strings.contains("Native prompt protocol not yet verified on device."))
    }

    @Test
    fun handlesFromIntentsAreValidated() {
        assertTrue(isAuthZenHandle("0123456789abcdef0123456789abcdef"))
        for (bad in listOf(null, "", "../../x", "0123456789ABCDEF0123456789ABCDEF", "0123456789abcdef0123456789abcde")) assertFalse(isAuthZenHandle(bad))
    }

    @Test
    fun diagnosticsWriteOnlySafeValues() {
        val lines = mutableListOf<String>()
        AuthZenDiagnostics { lines += it }.event(AuthZenEvent.STORE, "s", "text" to "owner@example.com", "count" to 2, "ok" to true, "kind" to AuthZenState.EXPIRED, "missing" to null)
        assertEquals("event=store session=s text=? count=2 ok=true kind=expired missing=none", lines.single())
        assertEquals("[1:0:0,2:2:256,3:5:256]", ProtoShape.of(byteArrayOf(0x08, 0x2a, 0x12, 0x02, 0x41, 0x42, 0x1d, 1, 2, 3, 4)).toString())
        assertEquals("[2:2:256,truncated]", ProtoShape.of(byteArrayOf(0x12, 0x01, 0x41, 0x12, 0x05)).toString())
    }

    /** The Workspace screen-lock proof (fork PR #17) keeps its single `PublicKey` bundle and key identity. */
    @Test
    fun workspaceEnrollmentRequestsAndPublicKeyAreUnchanged() {
        val key = CryptAuthUserKey.generate()
        val sync = syncSingleKeyRequests(key)
        assertEquals(1, sync.length())
        assertEquals("PublicKey", sync.getJSONObject(0).getString("keyName"))
        val enroll = enrollKeysRequest(ByteArray(32).toByteString().base64Url(), key)
        assertEquals("", enroll.getString("clientEphemeralDh"))
        val bundles = enroll.getJSONArray("enrollSingleKeyRequests")
        assertEquals(1, bundles.length())
        assertEquals("PublicKey", bundles.getJSONObject(0).getString("keyName"))
        assertFalse(enroll.toString().contains("authzen", ignoreCase = true))

        // The client-message key id is exactly the enrolled key material; serializing the key keeps its identity.
        assertArrayEquals(key.keyMaterial, (key.keyPair.public as ECPublicKey).toGenericPublicKey().encode())
        val restored = CryptAuthUserKey.deserialize(key.serialize())!!
        assertArrayEquals(key.keyMaterial, restored.keyMaterial)
        assertArrayEquals(key.handle, restored.handle)
    }
}
