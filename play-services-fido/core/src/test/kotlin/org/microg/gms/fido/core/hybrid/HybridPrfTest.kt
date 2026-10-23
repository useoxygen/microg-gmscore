/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.fido.core.hybrid

import com.google.android.gms.fido.fido2.api.common.AuthenticationExtensions
import com.google.android.gms.fido.fido2.api.common.AuthenticationExtensionsPrfOutputs
import com.google.android.gms.fido.fido2.api.common.PrfExtension
import com.google.android.gms.fido.fido2.api.common.FidoAppIdExtension
import com.upokecenter.cbor.CBORObject
import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.fido.core.protocol.msgs.*

/** Independent CBOR producer fixtures shaped by pinned btno and btmd, not a parser round-trip. */
class HybridPrfTest {
    private fun map(vararg fields: Pair<Any, Any>): CBORObject = CBORObject.NewMap().apply { fields.forEach { (key, value) -> set(CBORObject.FromObject(key), CBORObject.FromObject(value)) } }
    private val first = ByteArray(32) { it.toByte() }
    private val second = ByteArray(32) { (it + 32).toByte() }
    @Test fun sourceCtapAlreadyHashedBytesSurviveBothGetExtensionDecodingAndCredentialSelection() {
        val id = byteArrayOf(7, 8, 9)
        val prf = map("eval" to map("first" to first), "evalByCredential" to map(id to map("first" to second, "second" to first)))
        val wire = map(1 to "example.invalid", 2 to ByteArray(32), 4 to map("prf" to prf))
        val decoded = AuthenticatorGetAssertionRequest.decodeFromCbor(wire)
        val entries = HybridPrf.parse(decoded.extensions, false)!!.prfExtension.entries
        assertEquals(4, entries.size); assertNull(entries[0]); assertArrayEquals(first, entries[1])
        assertArrayEquals(id, entries[2]); assertArrayEquals(second + first, entries[3])
    }
    @Test fun createCapabilityOnlyIsValidButGetRequiresRealEvaluationAndRejectsMalformedOrUnsupportedInputs() {
        assertEquals(0, HybridPrf.parse(mapOf("prf" to map()), true)!!.prfExtension.entries.size)
        assertThrows(IllegalArgumentException::class.java) { HybridPrf.parse(mapOf("prf" to map()), false) }
        for (bad in listOf(map("eval" to map("first" to ByteArray(31))), map("eval" to map("first" to true)),
                map("eval" to map("second" to second)), map("evalByCredential" to map("text-id" to map("first" to first))),
                map("unexpected" to true))) {
            assertThrows(IllegalArgumentException::class.java) { HybridPrf.parse(mapOf("prf" to bad), false) }
        }
        assertThrows(IllegalArgumentException::class.java) { HybridPrf.parse(mapOf("payment" to map()), true) }
        assertThrows(IllegalArgumentException::class.java) { HybridPrf.parse(mapOf("prf" to map("evalByCredential" to map(byteArrayOf(1) to map("first" to first)))), true) }
    }
    @Test fun sourceUnsignedOutputsUseMakeKeySixGetKeyEightAndNestedBinaryResults() {
        val create = HybridPrf.output(AuthenticationExtensionsPrfOutputs(true, first, second), true)!!
        val made = AuthenticatorMakeCredentialResponse(byteArrayOf(1), "none", map(), create).encodePayloadAsCbor()
        assertTrue(made[6]["prf"]["enabled"].AsBoolean())
        assertArrayEquals(first, made[6]["prf"]["results"]["first"].GetByteString())
        assertNull(made[8]); assertNull(made[6]["prf"]["first"])
        val get = HybridPrf.output(AuthenticationExtensionsPrfOutputs(false, first, second), false)!!
        val signed = AuthenticatorGetAssertionResponse(null, byteArrayOf(2), byteArrayOf(3), null, null, get, true).encodePayloadAsCbor()
        assertTrue(signed[6].AsBoolean()); assertArrayEquals(second, signed[8]["prf"]["results"]["second"].GetByteString())
        assertFalse(signed[8]["prf"]["enabled"].AsBoolean())
        val unavailable = HybridPrf.output(AuthenticationExtensionsPrfOutputs(false, null), false)!!
        assertFalse(unavailable["prf"]["enabled"].AsBoolean()); assertNull(unavailable["prf"]["results"])
        assertFalse(HybridPrf.output(AuthenticationExtensionsPrfOutputs(false, null), true)!!["prf"]["enabled"].AsBoolean())
    }
    @Test fun actualSdkBuilderPreservesChosenPrfAndOtherBuilderFields() {
        val prf = PrfExtension(arrayOf(null, first))
        val appId = FidoAppIdExtension("https://example.invalid/app-id")
        val actual = AuthenticationExtensions.Builder().setFido2Extension(appId).setPrfExtension(prf).build()
        assertSame(appId, actual.fidoAppIdExtension)
        assertSame(prf, actual.prfExtension)
        assertArrayEquals(first, actual.prfExtension.entries[1])
    }
}
