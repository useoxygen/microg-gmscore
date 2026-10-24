/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PasswordEncryptionBridgeKeysTest {
    private fun key(epoch: Number, bytes: ByteArray): JSONObject = JSONObject().put("epoch", epoch).put("key", JSONObject().also { data ->
        bytes.forEachIndexed { i, b -> data.put(i.toString(), b.toInt() and 255) }
    })
    private fun input(vararg keys: JSONObject) = JSONObject().put("chromesync", JSONArray(keys.toList())).toString()
    private fun rejects(raw: String) {
        try { PasswordEncryptionBridgeKeys.parse(raw).forEach(VersionedTrustedVaultKey::destroy); fail("Malformed relay accepted") }
        catch (_: Exception) { /* No partially parsed key escapes. */ }
    }
    @Test fun sourceTypedArrayLiteralKeepsNewEpochAndNeverInfersBootstrapEpoch() {
        val old = key(0, ByteArray(16)); val fresh = key(4, ByteArray(32) { (it + 128).toByte() })
        val parsed = PasswordEncryptionBridgeKeys.parse(input(old, fresh))
        try { assertEquals(listOf(4), parsed.map { it.epoch }); assertArrayEquals(ByteArray(32) { (it + 128).toByte() }, parsed.single().material) }
        finally { parsed.forEach(VersionedTrustedVaultKey::destroy) }
    }
    @Test fun fractionalDuplicateNegativeAndOverflowEpochsRefuse() {
        val bytes = ByteArray(32) { 3 }
        rejects(input(key(4.5, bytes))); rejects(input(key(-1, bytes))); rejects(input(key(2147483648L, bytes)))
        rejects(input(key(4, bytes), key(4, bytes)))
    }
    @Test fun sparseOutOfRangeAndFractionalTypedArraysRefuse() {
        rejects(input(key(4, ByteArray(32)).apply { getJSONObject("key").remove("17") }))
        rejects(input(key(4, ByteArray(32)).apply { getJSONObject("key").put("17", 256) }))
        rejects(input(key(4, ByteArray(32)).apply { getJSONObject("key").put("17", 3.5) }))
        rejects(input(key(4, ByteArray(32)).apply { getJSONObject("key").put("17", "3") }))
    }
    @Test fun unrelatedAndAdditionalDomainCannotBeAdopted() {
        rejects(JSONObject().put("hw_protected", JSONArray().put(key(4, ByteArray(32)))).toString())
        rejects(JSONObject(input(key(4, ByteArray(32)))).put("hw_protected", JSONArray()).toString())
    }
    @Test fun zeroEpochRequiresSourceZeroBootstrapAndActualVersionedKey() {
        rejects(input(key(0, ByteArray(16))))
        rejects(input(key(0, ByteArray(16) { 1 }), key(4, ByteArray(32))))
        rejects(input(key(0, ByteArray(32)), key(4, ByteArray(32))))
    }
    @Test fun oversizedBridgePayloadAndKeyAreBoundedBeforeParsingOrRetention() {
        rejects(" ".repeat(512 * 1024 + 1)); rejects(input(key(4, ByteArray(4097))))
    }
}
