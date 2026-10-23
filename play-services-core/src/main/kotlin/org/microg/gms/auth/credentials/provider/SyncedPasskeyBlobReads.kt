/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.credentials.provider

import org.json.JSONObject
import org.microg.gms.passwords.PasskeyBlobReadRequest
import org.microg.gms.passwords.PasskeyBlobReadResult
import org.microg.gms.passwords.PasskeySecret
import org.microg.gms.passwords.ProtocolException
import java.util.Base64

/** Existing authenticated data only. This does not advertise blob creation, capacity or writes. */
internal object SyncedPasskeyBlobReads {
    fun request(raw: String): PasskeyBlobReadRequest? {
        val json = JSONObject(raw)
        val spellings = listOf("extensions", "authenticationExtensions").filter(json::has)
        if (spellings.size > 1) throw ProtocolException()
        val extensions = spellings.singleOrNull()?.let { json.optJSONObject(it) ?: throw ProtocolException() }
            ?: return null
        val credBlob = if (extensions.has("getCredBlob")) {
            if (extensions.opt("getCredBlob") != true) throw ProtocolException()
            true
        } else false
        val largeBlob = if (extensions.has("largeBlob")) {
            val value = extensions.optJSONObject("largeBlob") ?: throw ProtocolException()
            if (value.length() != 1 || value.opt("read") != true) throw ProtocolException()
            true
        } else false
        return if (credBlob || largeBlob) PasskeyBlobReadRequest(credBlob, largeBlob) else null
    }

    /** Called only inside withSecret; the full secret copy is erased before returning derived data. */
    fun read(secret: PasskeySecret, request: PasskeyBlobReadRequest?): PasskeyBlobReadResult? {
        if (request == null) return null
        val encoded = secret.encode()
        return try { request.read(encoded) } finally { encoded.fill(0) }
    }

    fun output(raw: String, request: PasskeyBlobReadRequest?, result: PasskeyBlobReadResult?): String {
        if (request == null) return raw
        if (result == null) throw ProtocolException()
        val json = JSONObject(raw)
        val extensions = if (json.has("clientExtensionResults"))
            json.getJSONObject("clientExtensionResults") else JSONObject().also { json.put("clientExtensionResults", it) }
        val base64 = Base64.getUrlEncoder().withoutPadding()
        if (request.credBlob) extensions.put("getCredBlob",
            base64.encodeToString(result.credBlob ?: throw ProtocolException()))
        if (request.largeBlob) extensions.put("largeBlob", JSONObject().apply {
            result.largeBlob?.let { put("blob", base64.encodeToString(it)) }
        })
        return json.toString()
    }
}
