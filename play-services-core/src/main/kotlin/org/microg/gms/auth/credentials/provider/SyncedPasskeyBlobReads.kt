/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.credentials.provider

import org.json.JSONObject
import org.microg.gms.passwords.PasskeyBlobReadRequest
import org.microg.gms.passwords.PasskeyBlobReadResult
import org.microg.gms.passwords.PasskeySecret
import org.microg.gms.passwords.PasskeyBlobCreateRequest
import org.microg.gms.passwords.PasskeyBlobWriteRequest
import org.microg.gms.passwords.PasskeyPrfMigration
import org.microg.gms.passwords.ProtocolException
import java.util.Base64

/** Standard JSON blob consumer; mutations use the account vault's request-bound durable coordinator. */
internal object SyncedPasskeyBlobReads {
    private fun extensions(raw: String): JSONObject? {
        val json = JSONObject(raw)
        val spellings = listOf("extensions", "authenticationExtensions").filter(json::has)
        if (spellings.size > 1) throw ProtocolException()
        return spellings.singleOrNull()?.let { json.optJSONObject(it) ?: throw ProtocolException() }
    }
    private fun bytes(value: Any?): ByteArray {
        val raw = value as? String ?: throw ProtocolException()
        if (raw.length > 256 * 1024) throw ProtocolException()
        return try { Base64.getUrlDecoder().decode(raw) } catch (_: IllegalArgumentException) { throw ProtocolException() }
    }
    fun create(raw: String): PasskeyBlobCreateRequest? {
        val ext = extensions(raw) ?: return null
        if (!ext.has("credBlob") && !ext.has("largeBlob")) return null
        val credential = if (ext.has("credBlob")) bytes(ext.opt("credBlob")) else null
        return try {
            val support = if (ext.has("largeBlob")) {
                val value = ext.optJSONObject("largeBlob") ?: throw ProtocolException()
                if (value.length() != 1 || value.opt("support") !in setOf("required", "preferred")) throw ProtocolException()
                true
            } else false
            PasskeyBlobCreateRequest(credential, support)
        } finally { credential?.fill(0) }
    }
    fun write(raw: String): PasskeyBlobWriteRequest? {
        val ext = extensions(raw) ?: return null
        if (!ext.has("largeBlob")) return null
        val value = ext.optJSONObject("largeBlob") ?: throw ProtocolException()
        if (!value.has("write")) return null
        if (value.length() != 1 || JSONObject(raw).optJSONArray("allowCredentials")?.length() != 1) throw ProtocolException()
        val rawBytes = bytes(value.opt("write"))
        return try { PasskeyBlobWriteRequest(rawBytes) } finally { rawBytes.fill(0) }
    }
    fun request(raw: String): PasskeyBlobReadRequest? {
        val extensions = extensions(raw) ?: return null
        val credBlob = if (extensions.has("getCredBlob")) {
            if (extensions.opt("getCredBlob") != true) throw ProtocolException()
            true
        } else false
        val largeBlob = if (extensions.has("largeBlob")) {
            val value = extensions.optJSONObject("largeBlob") ?: throw ProtocolException()
            if (value.has("write")) { write(raw).use { }; false } else {
                if (value.length() != 1 || value.opt("read") != true) throw ProtocolException()
                true
            }
        } else false
        return if (credBlob || largeBlob) PasskeyBlobReadRequest(credBlob, largeBlob) else null
    }

    /** Called only inside withSecret; the full secret copy is erased before returning derived data. */
    fun read(secret: PasskeySecret, request: PasskeyBlobReadRequest?): PasskeyBlobReadResult? {
        if (request == null) return null
        val encoded = secret.encode()
        return try { request.read(encoded) } finally { encoded.fill(0) }
    }
    /** PRF migration and the blob write share one caller-bound durable secret mutation. */
    fun update(secret: PasskeySecret, prf: Boolean, write: PasskeyBlobWriteRequest?): ByteArray? {
        val original = secret.encode()
        var migrated: ByteArray? = null
        return try {
            if (prf) migrated = PasskeyPrfMigration.upgrade(original)
            write?.apply(migrated ?: original) ?: migrated?.copyOf()
        } finally { original.fill(0); migrated?.fill(0) }
    }

    fun output(raw: String, request: PasskeyBlobReadRequest?, result: PasskeyBlobReadResult?, written: Boolean? = null): String {
        if (request == null && written == null) return raw
        if (request != null && result == null) throw ProtocolException()
        val json = JSONObject(raw)
        val extensions = if (json.has("clientExtensionResults"))
            json.getJSONObject("clientExtensionResults") else JSONObject().also { json.put("clientExtensionResults", it) }
        val base64 = Base64.getUrlEncoder().withoutPadding()
        if (request?.credBlob == true) extensions.put("getCredBlob",
            base64.encodeToString(result?.credBlob ?: throw ProtocolException()))
        if (request?.largeBlob == true || written != null) extensions.put("largeBlob", JSONObject().apply {
            result?.largeBlob?.let { put("blob", base64.encodeToString(it)) }
            written?.let { put("written", it) }
        })
        return json.toString()
    }
    fun creationOutput(raw: String, request: PasskeyBlobCreateRequest?): String {
        if (request == null) return raw
        val json = JSONObject(raw)
        val out = json.optJSONObject("clientExtensionResults") ?: JSONObject().also { json.put("clientExtensionResults", it) }
        if (request.credBlobRequested) out.put("credBlob", request.credBlobStored)
        if (request.largeBlobSupport) out.put("largeBlob", JSONObject().put("supported", true))
        return json.toString()
    }
}
