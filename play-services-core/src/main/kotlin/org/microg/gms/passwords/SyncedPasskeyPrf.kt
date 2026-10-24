/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import com.google.android.gms.fido.fido2.api.common.AuthenticationExtensionsPrfOutputs
import com.google.android.gms.fido.fido2.api.common.PrfExtension
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/** Native request/result adapter. JSON carries raw WebAuthn inputs; the legacy parcel already carries normalized inputs. */
internal object SyncedPasskeyPrf {
    fun json(extensions: JSONObject, create: Boolean): PasskeyPrfRequest? {
        if (!extensions.has("prf")) return null
        val prf = extensions.optJSONObject("prf") ?: throw ProtocolException()
        if (prf.keys().asSequence().any { it !in setOf("eval", "evalByCredential") }) throw ProtocolException()
        val fallback = if (prf.has("eval")) values(prf.optJSONObject("eval") ?: throw ProtocolException()) else null
        val selected = ArrayList<Pair<ByteArray, PasskeyPrfValues>>()
        if (prf.has("evalByCredential")) {
            if (create) throw ProtocolException()
            val byId = prf.optJSONObject("evalByCredential") ?: throw ProtocolException()
            if (byId.length() > 1000) throw ProtocolException()
            for (id in byId.keys()) selected.add(decode(id, 2048) to values(byId.optJSONObject(id) ?: throw ProtocolException()))
        }
        return PasskeyPrfRequest.normalized(fallback, selected, create)
    }
    private fun values(value: JSONObject): PasskeyPrfValues {
        if (!value.has("first") || value.keys().asSequence().any { it !in setOf("first", "second") }) throw ProtocolException()
        val first = value.opt("first") as? String ?: throw ProtocolException()
        val second = if (value.has("second")) value.opt("second") as? String ?: throw ProtocolException() else null
        return PasskeyPrfValues.raw(decode(first), second?.let { decode(it) })
    }
    private fun decode(value: String, max: Int = Proto.MAX_BYTES): ByteArray {
        if (value.length > max) throw ProtocolException()
        // Okio works below API26; retain the strict Java URL decoder's alphabet/padding admission.
        if (!Regex("[A-Za-z0-9_-]*={0,2}").matches(value) || value.length % 4 == 1 ||
            value.endsWith('=') && value.length % 4 != 0) throw ProtocolException()
        return value.decodeBase64()?.toByteArray() ?: throw ProtocolException()
    }
    fun legacy(extension: PrfExtension?, create: Boolean): PasskeyPrfRequest? = extension?.let {
        PasskeyPrfRequest.legacy(it.entries.toList(), create)
    }
    fun output(result: PasskeyPrfResult) = AuthenticationExtensionsPrfOutputs(result.enabled, result.first, result.second)
    /** The vault commits this replacement and reopens it before any caller evaluates PRF. */
    fun migrate(secret: PasskeySecret): ByteArray? {
        val encoded = secret.encode()
        return try { PasskeyPrfMigration.upgrade(encoded) } finally { encoded.fill(0) }
    }
    fun json(enabled: Boolean, first: ByteArray?, second: ByteArray?): JSONObject = JSONObject().apply {
        if (enabled) put("enabled", true)
        if (second != null && first == null || first?.size?.let { it != 32 } == true || second?.size?.let { it != 32 } == true)
            throw ProtocolException()
        if (first != null) put("results", JSONObject().apply {
            put("first", first.toByteString().base64Url().trimEnd('='))
            if (second != null) put("second", second.toByteString().base64Url().trimEnd('='))
        })
    }
}
