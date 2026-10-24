/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.net.URI
import java.net.URLDecoder

/** Chrome145 authenticator_request_window: a committed main-frame200/204 on the PIN reset origin. */
class EnclavePinReauthPolicy(private val accountId: String) {
    init { if (accountId.isBlank() || accountId.length > 256) throw ProtocolException() }
    fun result(url: String, mainFrame: Boolean, currentAccountId: String, status: Int): String {
        if (!mainFrame || currentAccountId != accountId || status !in setOf(200, 204) || !proofCandidate(url)) throw VaultLockedException()
        val uri = URI(url)
        if (uri.rawFragment != null) throw VaultLockedException()
        val tokens = query(uri).filter { it.first == "rapt" }.map { it.second }
        val token = tokens.singleOrNull() ?: throw VaultLockedException()
        if (token.isBlank() || token.length > 16_384 || token.any { it.code < 32 || it.code == 127 }) throw VaultLockedException()
        return token
    }
    fun proofCandidate(value: String): Boolean = try {
        val uri = URI(value)
        value.length <= 32_768 && allowedUri(uri) && uri.host == "passwords.google.com" && query(uri).any { it.first == "rapt" }
    } catch (_: Exception) { false }
    override fun toString() = "EnclavePinReauthPolicy[redacted]"
    companion object {
        const val TARGET = "https://passwords.google.com/encryption/pin/reset"
        private fun allowedUri(uri: URI) = uri.scheme == "https" && uri.host in setOf("accounts.google.com", "passwords.google.com") &&
            uri.userInfo == null && uri.port in setOf(-1, 443)
        fun navigationAllowed(value: String): Boolean = try { value.length <= 32_768 && allowedUri(URI(value)) } catch (_: Exception) { false }
        private fun query(uri: URI): List<Pair<String, String>> = uri.rawQuery.orEmpty().split('&').map { item ->
            val parts = item.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
    }
}
