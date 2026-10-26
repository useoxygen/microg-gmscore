/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.credentials.provider.SyncedPasskeyRequestSupport
import com.google.android.gms.fido.fido2.api.common.AuthenticationExtensionsPrfOutputs
import java.util.Base64

class SyncedPasskeyPrfTest {
    private val secret = ByteArray(32) { (it + 1).toByte() }
    private val id = byteArrayOf(1, 2, 3)
    private fun b64(raw: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    @Test fun actualRawParserSelectsCredentialSpecificValuesAndReturnsStandardNestedResults() {
        val json = JSONObject("""{"prf":{"eval":{"first":""},"evalByCredential":{"${b64(id)}":{"first":"AQ","second":"Ag"}}}}""")
        val request = SyncedPasskeyPrf.json(json, false)!!
        request.evaluate(secret, id).use { actual ->
            val expected = PasskeyPrfRequest.normalized(PasskeyPrfValues.raw(byteArrayOf(1), byteArrayOf(2)), create = false)
            expected.evaluate(secret, id).use { assertArrayEquals(it.first, actual.first); assertArrayEquals(it.second, actual.second) }
            val output = SyncedPasskeyPrf.json(actual.enabled, actual.first, actual.second)
            assertFalse(output.has("enabled")); assertFalse(output.has("first")); assertFalse(output.has("second"))
            assertEquals(b64(actual.first!!), output.getJSONObject("results").getString("first"))
            assertEquals(b64(actual.second!!), output.getJSONObject("results").getString("second"))
        }
        request.evaluate(secret, byteArrayOf(99)).use { actual -> assertNotNull(actual.first); assertNull(actual.second) }
    }
    @Test fun sourceNativeParcelLayoutUsesOneConcatenatedFieldAndRedactedDiagnostics() {
        val first = ByteArray(32) { 1 }; val second = ByteArray(32) { 2 }
        val output = AuthenticationExtensionsPrfOutputs(true, first, second)
        assertArrayEquals(first + second, output.results)
        assertArrayEquals(first, output.first); assertArrayEquals(second, output.second)
        first.fill(0); second.fill(0)
        assertTrue(output.first!!.all { it == 1.toByte() }); assertTrue(output.second!!.all { it == 2.toByte() })
        assertEquals("AuthenticationExtensionsPrfOutputs[redacted]", output.toString())
        assertArrayEquals(output.first, AuthenticationExtensionsPrfOutputs(true, output.results).first)
        assertThrows(IllegalArgumentException::class.java) { AuthenticationExtensionsPrfOutputs(true, ByteArray(31)) }
    }
    @Test fun malformedRawInputsAndUnsupportedCreationMapsCannotBeSilentlyIgnored() {
        val invalid = listOf("null", "true", "[]", "1", "{\"unknown\":{}}", "{\"eval\":null}", "{\"eval\":{}}",
            "{\"eval\":{\"second\":\"AQ\"}}", "{\"eval\":{\"first\":true}}", "{\"eval\":{\"first\":\"$\"}}",
            "{\"eval\":{\"first\":\"AQ\",\"unknown\":true}}", "{\"evalByCredential\":null}",
            "{\"evalByCredential\":{\"\":{\"first\":\"AQ\"}}}", "{\"evalByCredential\":{\"AQ\":{}}}",
            "{\"evalByCredential\":{\"AQ\":{\"first\":\"AQ\"},\"AQ==\":{\"first\":\"Ag\"}}}")
        for (raw in invalid) assertThrows(ProtocolException::class.java) { SyncedPasskeyPrf.json(JSONObject("{\"prf\":$raw}"), false) }
        assertThrows(ProtocolException::class.java) { SyncedPasskeyPrf.json(JSONObject("""{"prf":{"evalByCredential":{}}}"""), true) }
    }
    @Test fun compatibleCodecKeepsStrictUrlAlphabetAndJavaPaddingAdmission() {
        val accepted = listOf("", "AQ", "AQ==", "AQI", "AQI=", "AQID", "-_8", "-_8=")
        for (encoded in accepted) {
            val raw = Base64.getUrlDecoder().decode(encoded)
            val parsed = SyncedPasskeyPrf.json(JSONObject().put("prf", JSONObject().put("eval", JSONObject().put("first", encoded))), false)!!
            val expected = PasskeyPrfRequest.normalized(PasskeyPrfValues.raw(raw), create = false)
            parsed.evaluate(secret, id).use { actual -> expected.evaluate(secret, id).use {
                assertArrayEquals(it.first, actual.first)
            } }
        }
        for (encoded in listOf("A", "AQ=", "AQ===", "AQ==\n", " AQ==", "+/8=", "AQ==AA")) {
            assertThrows(IllegalArgumentException::class.java) { Base64.getUrlDecoder().decode(encoded) }
            assertThrows(ProtocolException::class.java) {
                SyncedPasskeyPrf.json(JSONObject().put("prf", JSONObject().put("eval", JSONObject().put("first", encoded))), false)
            }
        }
    }
    @Test fun capabilityOnlyCreationRequiresRealSecretAndReturnsEnabledWithoutInventingResults() {
        val request = SyncedPasskeyPrf.json(JSONObject("""{"prf":{}}"""), true)!!
        request.evaluate(secret, id).use { result ->
            val output = SyncedPasskeyPrf.json(result.enabled, result.first, result.second)
            assertTrue(output.getBoolean("enabled")); assertFalse(output.has("results"))
        }
        PasskeySecret(ByteArray(32), null).use { assertThrows(VaultLockedException::class.java) { it.prf(request, id) } }
    }
    @Test fun bothRawSpellingsValidatePrfBeforeAdvertisingAndRejectAlreadyHashedAliasOrShadows() {
        for (field in listOf("extensions", "authenticationExtensions")) {
            val valid = JSONObject().put(field, JSONObject("""{"prf":{"eval":{"first":"AQ"}}}"""))
            assertTrue(SyncedPasskeyRequestSupport.get(valid.toString()))
            val bad = JSONObject().put(field, JSONObject("""{"prf":{"eval":{"first":true}}}"""))
            assertFalse(SyncedPasskeyRequestSupport.get(bad.toString()))
            assertFalse(SyncedPasskeyRequestSupport.get(JSONObject().put(field, JSONObject("""{"prfAlreadyHashed":{}}""")).toString()))
            val both = JSONObject(valid.toString()).put(if (field == "extensions") "authenticationExtensions" else "extensions", JSONObject())
            assertFalse(SyncedPasskeyRequestSupport.get(both.toString()))
        }
    }
}
