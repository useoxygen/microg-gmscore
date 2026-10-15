/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cryptauth

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import securemessage.GenericPublicKey
import securemessage.PublicKeyType
import java.math.BigInteger
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey

class CryptAuthRequestsTest {
    private val key = CryptAuthUserKey.generate()

    // Contains '-' and '_' to cover the URL-safe alphabet as well.
    private val sessionBytes = ByteArray(32) { (it * 37 + 251).toByte() }
    private val session = sessionBytes.toByteString().base64Url()

    @Test
    fun enrollWithoutNewKeyKeepsTheEmptyRequest() {
        val body = enrollKeysRequest(session, null)
        assertEquals(session, body.getString(CRYPTAUTH_FIELD_SESSION_ID))
        assertEquals("", body.getString("clientEphemeralDh"))
        assertEquals(0, body.getJSONArray("enrollSingleKeyRequests").length())
    }

    @Test
    fun enrollSendsTheUserKeyWithVerifiableProof() {
        val requests = enrollKeysRequest(session, key).getJSONArray("enrollSingleKeyRequests")
        assertEquals(1, requests.length())
        val request = requests.getJSONObject(0)
        assertEquals("PublicKey", request.getString("keyName"))

        val material = request.getString("keyMaterial").decodeBase64()!!.toByteArray()
        val publicKey = GenericPublicKey.ADAPTER.decode(material)
        assertEquals(PublicKeyType.EC_P256, publicKey.type)
        val point = (key.keyPair.public as ECPublicKey).w
        assertEquals(33, publicKey.ec_p256_public_key!!.x.size)
        assertEquals(33, publicKey.ec_p256_public_key!!.y.size)
        assertEquals(point.affineX, BigInteger(publicKey.ec_p256_public_key!!.x.toByteArray()))
        assertEquals(point.affineY, BigInteger(publicKey.ec_p256_public_key!!.y.toByteArray()))

        val handle = request.getString("newKeyHandle").decodeBase64()!!.toByteArray()
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(material), handle)

        // Chromium's CryptAuthKeyProofComputerImpl: ECDSA-SHA256 over salt || decoded session bytes.
        val proof = request.getString("keyProof").decodeBase64()!!.toByteArray()
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(key.keyPair.public)
            update("CryptAuth Key Proof".toByteArray())
            update(sessionBytes)
        }
        assertTrue(verifier.verify(proof))
    }

    @Test(expected = IllegalArgumentException::class)
    fun enrollRefusesToSignAnUnreadableSession() {
        enrollKeysRequest("not base64 !", key)
    }

    @Test
    fun syncSendsThePlaceholderUntilAKeyExists() {
        val placeholder = syncSingleKeyRequests(null).getJSONObject(0)
        assertEquals("PublicKey", placeholder.getString("keyName"))
        assertEquals("ZGV2aWNlX2tleQo=", placeholder.getJSONArray("keyHandles").getString(0))

        val enrolled = syncSingleKeyRequests(key).getJSONObject(0)
        assertEquals(key.handle.toByteString().base64(), enrolled.getJSONArray("keyHandles").getString(0))
    }

    @Test
    fun readsKeyCreationInBothEnumForms() {
        assertEquals(UserKeyInstruction.CREATE, userKeyInstruction(syncResponse("ACTIVE", "P256")))
        assertEquals(UserKeyInstruction.CREATE, userKeyInstruction(syncResponse(1, 4)))
        assertEquals(UserKeyInstruction.CREATE, userKeyInstruction(syncResponse("INACTIVE", null)))
        assertEquals(UserKeyInstruction.NONE, userKeyInstruction(syncResponse("NONE", "P256")))
        assertEquals(UserKeyInstruction.NONE, userKeyInstruction(syncResponse(null, null)))
        assertEquals(UserKeyInstruction.NONE, userKeyInstruction(JSONObject().put(CRYPTAUTH_FIELD_SESSION_ID, "abc")))
        assertEquals(UserKeyInstruction.UNSUPPORTED, userKeyInstruction(syncResponse("ACTIVE", "RAW256")))
        assertEquals(UserKeyInstruction.UNSUPPORTED, userKeyInstruction(syncResponse("SOMETHING_NEW", "P256")))
    }

    @Test
    fun storedKeyRoundTrips() {
        val restored = CryptAuthUserKey.deserialize(key.serialize())!!
        assertArrayEquals(key.keyMaterial, restored.keyMaterial)
        assertArrayEquals(key.handle, restored.handle)
        assertNull(CryptAuthUserKey.deserialize(""))
        assertNull(CryptAuthUserKey.deserialize("abc\ndef\n"))
    }

    @Test
    fun coordinatesAreFixedWidthTwosComplement() {
        assertArrayEquals(ByteArray(32) + byteArrayOf(1), BigInteger.ONE.toTwosComplement())
        val highBit = BigInteger(1, ByteArray(32) { 0xff.toByte() })
        val encoded = highBit.toTwosComplement()
        assertEquals(33, encoded.size)
        assertEquals(0, encoded[0].toInt())
        assertEquals(highBit, BigInteger(encoded))
    }

    private fun syncResponse(keyCreation: Any?, keyType: Any?): JSONObject {
        val single = JSONObject().put("keyActions", JSONArray().put("ACTIVATE"))
        if (keyCreation != null) single.put(CRYPTAUTH_FIELD_KEY_CREATION, keyCreation)
        if (keyType != null) single.put(CRYPTAUTH_FIELD_KEY_TYPE, keyType)
        return JSONObject()
            .put(CRYPTAUTH_FIELD_SESSION_ID, session)
            .put(CRYPTAUTH_FIELD_SYNC_SINGLE_KEY_RESPONSES, JSONArray().put(single))
    }
}
