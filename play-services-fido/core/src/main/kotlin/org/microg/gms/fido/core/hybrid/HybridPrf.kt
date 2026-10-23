/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.fido.core.hybrid

import com.google.android.gms.fido.fido2.api.common.AuthenticationExtensions
import com.google.android.gms.fido.fido2.api.common.AuthenticationExtensionsPrfOutputs
import com.google.android.gms.fido.fido2.api.common.PrfExtension
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType

/** GMS btno/PrfExtension: CTAP inputs have already received the WebAuthn PRF domain hash. */
object HybridPrf {
    fun parse(extensions: Map<String, CBORObject>, create: Boolean): AuthenticationExtensions? {
        require(extensions.keys.all { it == "prf" }) { "Unsupported hybrid extension" }
        val prf = extensions["prf"] ?: return null
        require(prf.type == CBORType.Map && prf.keys.all { it.type == CBORType.TextString && it.AsString() in setOf("eval", "evalByCredential") })
        val entries = ArrayList<ByteArray?>()
        fun values(value: CBORObject): ByteArray {
            require(value.type == CBORType.Map && value.keys.all { it.type == CBORType.TextString && it.AsString() in setOf("first", "second") })
            val first = value["first"] ?: throw IllegalArgumentException("Missing first PRF value")
            require(first.type == CBORType.ByteString)
            val a = first.GetByteString(); require(a.size == 32)
            val second = value["second"] ?: return a
            require(second.type == CBORType.ByteString)
            val b = second.GetByteString(); require(b.size == 32)
            return a + b
        }
        prf["eval"]?.let { entries.add(null); entries.add(values(it)) }
        prf["evalByCredential"]?.let { by ->
            require(!create && by.type == CBORType.Map && by.size() <= 200)
            by.keys.forEach { key ->
                require(key.type == CBORType.ByteString)
                val id = key.GetByteString(); require(id.size in 1..1024)
                entries.add(id); entries.add(values(by[key]))
            }
        }
        require(create || entries.isNotEmpty())
        val extension = PrfExtension(entries.toTypedArray())
        return AuthenticationExtensions(null, null, null, null, null, null, null, null, null, extension, null, null, null)
    }
    /** UnsignedExtensionOutputs, embedded at makeCredential key 6 / getAssertion key 8. */
    @Suppress("UNUSED_PARAMETER")
    fun output(output: AuthenticationExtensionsPrfOutputs?, create: Boolean): CBORObject? {
        output ?: return null
        val value = CBORObject.NewMap()
        value.set("enabled", CBORObject.FromObject(output.isEnabled))
        val first = output.first
        if (first != null) {
            val result = CBORObject.NewMap().apply {
                set("first", CBORObject.FromObject(first))
                output.second?.let { set("second", CBORObject.FromObject(it)) }
            }
            value.set("results", result)
        }
        return CBORObject.NewMap().apply { set("prf", value) }
    }
}
