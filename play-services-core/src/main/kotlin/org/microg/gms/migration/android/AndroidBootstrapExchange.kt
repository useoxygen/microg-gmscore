/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import android.util.Base64
import org.json.JSONObject
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom

/** Target's retained-secret producer, after a real UKEY2 transport and explicit
 * user peer verification. Google account credentials are NOT part of this exchange.
 * The caller must pass only bytes decrypted on that same accepted channel.
 * This parser is not exposed via an Intent, exported Binder setter or debug injection. */
internal class AndroidBootstrapExchange(verificationToken: ByteArray,
    private val commit: (AndroidPostSetupState.VerifiedBootstrap) -> Unit) : Closeable {
    private val token = verificationToken.copyOf().also { require(it.size == 32) }
    private var verified = false
    private var sent = false
    private var closed = false
    private val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val id = (SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(1).toString()
    @Synchronized fun confirmPeer(confirmedToken: ByteArray) {
        check(!closed && !verified)
        if(!MessageDigest.isEqual(token,confirmedToken)) { close(); throw SecurityException("Bootstrap peer mismatch") }
        verified = true; token.fill(0)
    }
    @Synchronized fun wifiRequest(): ByteArray {
        check(!closed && verified && !sent); sent = true
        // Pinned GMS gflo.y: request_wifi + SESSION_ID + shared_secret, inside
        // MessagePayload.quickStartPayload. No SSID/password or token is logged.
        val inner = JSONObject().put("request_wifi",true).put("SESSION_ID",id.toLong())
            .put("shared_secret",Base64.encodeToString(secret,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        return JSONObject().put("protocolVersion",3).put("quickStartPayload",Base64.encodeToString(inner.toString().toByteArray(Charsets.UTF_8),Base64.NO_WRAP)).toString().toByteArray(Charsets.UTF_8)
    }
    @Synchronized fun receivedAuthenticatedResponse(bytes: ByteArray) {
        check(!closed && verified && sent); require(bytes.size in 1..65536)
        try {
            val outer = JSONObject(bytes.toString(Charsets.UTF_8)); require(outer.optInt("protocolVersion",3) == 3)
            if(outer.has("accountBootstrapPayload") || outer.has("accountTransferMsg")) throw UnsupportedOperationException("Google account transfer requires server authorization")
            val raw = Base64.decode(outer.getString("quickStartPayload"),Base64.DEFAULT); require(raw.size <= 65536)
            val inner = JSONObject(raw.toString(Charsets.UTF_8))
            // A Wi-Fi result may honestly be Unsupported/empty; it still confirms the
            // source processed the retained-secret request over the verified channel.
            require(inner.optJSONObject("wifi_network") != null) { "Source has not acknowledged Wi-Fi/bootstrap" }
            if(inner.has("SESSION_ID")) require(inner.getLong("SESSION_ID").toString() == id)
            val retained = AndroidPostSetupState.VerifiedBootstrap(id,secret)
            try { commit(retained) } catch(e: Throwable) { retained.close(); throw e }
        } finally { close() }
    }
    @Synchronized override fun close() { closed = true; token.fill(0); secret.fill(0) }
}
