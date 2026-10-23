/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.credentials.provider.SyncedPasskeyBlobReads
import org.microg.gms.auth.credentials.provider.SyncedPasskeyRequestSupport
import org.microg.gms.fido.core.protocol.AuthenticatorData
import org.microg.gms.fido.core.protocol.encodeCredBlobAuthenticationExtension
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class NativeSyncedPasskeyBlobReadTest {
    @Test fun bothRawSpellingsAcceptOnlyExplicitReadsAndRejectCreationWritesOrMalformedRequests() {
        assertNull(SyncedPasskeyBlobReads.request("{}"))
        for (field in listOf("extensions", "authenticationExtensions")) {
            val reads = JSONObject().put("getCredBlob", true).put("largeBlob", JSONObject().put("read", true))
            val request = SyncedPasskeyBlobReads.request(JSONObject().put(field, reads).toString())!!
            assertTrue(request.credBlob); assertTrue(request.largeBlob)
            assertTrue(SyncedPasskeyRequestSupport.get(JSONObject().put(field, reads).toString()))
            assertFalse(SyncedPasskeyRequestSupport.create(JSONObject().put(field, reads)
                .put("pubKeyCredParams", org.json.JSONArray("[{\"type\":\"public-key\",\"alg\":-7}]")).toString()))
            val invalid = listOf(
                JSONObject().put("getCredBlob", "true"), JSONObject().put("getCredBlob", false),
                JSONObject().put("largeBlob", JSONObject().put("write", "AA")),
                JSONObject().put("largeBlob", JSONObject().put("read", true).put("write", "AA")),
                JSONObject().put("largeBlob", JSONObject().put("support", "required")),
                JSONObject().put("largeBlob", JSONObject().put("read", "true")),
                JSONObject().put("largeBlob", JSONObject()), JSONObject().put("largeBlob", JSONObject.NULL))
            invalid.forEach { value -> assertThrows(Exception::class.java) {
                SyncedPasskeyBlobReads.request(JSONObject().put(field, value).toString())
            } }
            invalid.forEach { value -> assertFalse(SyncedPasskeyRequestSupport.get(JSONObject().put(field, value).toString())) }
        }
        assertThrows(Exception::class.java) { SyncedPasskeyBlobReads.request(
            "{\"extensions\":{},\"authenticationExtensions\":{\"getCredBlob\":true}}") }
    }

    @Test fun jsonReadResultsPreservePrfAndOtherResponseFieldsWithoutAdvertisingWritesOrCapacity() {
        val request = PasskeyBlobReadRequest(true, true)
        request.read(Proto.field(3, byteArrayOf(0, 1, -1))).use { result ->
            val response = SyncedPasskeyBlobReads.output(
                "{\"id\":\"existing\",\"response\":{\"signature\":\"kept\"},\"clientExtensionResults\":{\"prf\":{\"results\":{\"first\":\"kept\"}}}}",
                request, result)
            val json = JSONObject(response)
            assertEquals("existing", json.getString("id"))
            assertEquals("kept", json.getJSONObject("response").getString("signature"))
            val extensions = json.getJSONObject("clientExtensionResults")
            assertEquals("AAH_", extensions.getString("getCredBlob"))
            assertEquals("kept", extensions.getJSONObject("prf").getJSONObject("results").getString("first"))
            val large = extensions.getJSONObject("largeBlob")
            assertEquals(0, large.length()); assertFalse(large.has("written")); assertFalse(large.has("supported"))
        }
        PasskeyBlobReadRequest(true, false).read(byteArrayOf()).use { result ->
            assertEquals("", JSONObject(SyncedPasskeyBlobReads.output("{}", PasskeyBlobReadRequest(true, false), result))
                .getJSONObject("clientExtensionResults").getString("getCredBlob"))
        }
    }

    @Test fun credBlobIsInsideSignedAuthenticatorDataAndChangingItInvalidatesTheSignature() {
        // Independent canonical CBOR literals, including a present empty credential blob.
        assertArrayEquals(hex("a16863726564426c6f6240"), encodeCredBlobAuthenticationExtension(byteArrayOf()))
        val extensions = encodeCredBlobAuthenticationExtension(byteArrayOf(1, 2, 3))
        assertArrayEquals(hex("a16863726564426c6f6243010203"), extensions)
        val auth = AuthenticatorData(ByteArray(32) { 7 }, true, true, 0, extensions = extensions).apply {
            backupEligible = true; backedUp = true
        }.encode()
        assertEquals(0x9d, auth[32].toInt() and 255)
        assertArrayEquals(extensions, auth.copyOfRange(37, auth.size))
        val keys = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
        val clientHash = ByteArray(32) { 9 }
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private); update(auth + clientHash); sign()
        }
        fun verifies(data: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
            initVerify(keys.public); update(data + clientHash); verify(signature)
        }
        assertTrue(verifies(auth))
        assertFalse(verifies(auth.copyOf().apply { this[lastIndex] = 4 }))
        assertFalse(verifies(auth.copyOfRange(0, 37)))
        runCatching { keys.private.destroy() }
    }
    private fun hex(raw: String) = raw.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
