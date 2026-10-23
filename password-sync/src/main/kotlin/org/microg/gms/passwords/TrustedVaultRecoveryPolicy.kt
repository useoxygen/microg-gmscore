/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.net.URI

/** Shared by the native owner flow and its message receiver. No origin prefix/suffix shortcuts. */
class TrustedVaultRecoveryPolicy(val accountId: String, val domain: String) {
    init { if (accountId.isBlank() || accountId.length > 256 || domain.isBlank() || domain.length > 256) throw ProtocolException() }
    fun acceptsMessage(sourceOrigin: String, mainFrame: Boolean, deliveredAccountId: String, deliveredDomain: String): Boolean =
        mainFrame && exactGoogleOrigin(sourceOrigin) && accountId == deliveredAccountId && domain == deliveredDomain
    /** Google's bridge can deliver several domains; only the explicitly requested domain is imported. */
    fun selectsKeys(deliveredDomains: Set<String>): Boolean = deliveredDomains.size in 1..32 && domain in deliveredDomains
    companion object {
        const val ORIGIN = "https://accounts.google.com"
        fun exactGoogleOrigin(value: String): Boolean = try {
            val uri = URI(value)
            uri.scheme == "https" && uri.host == "accounts.google.com" && uri.userInfo == null && uri.port in setOf(-1, 443) &&
                uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in setOf("", "/")
        } catch (_: Exception) { false }
        fun navigationAllowed(value: String): Boolean = try {
            val uri = URI(value)
            uri.scheme == "https" && uri.host == "accounts.google.com" && uri.userInfo == null && uri.port in setOf(-1, 443)
        } catch (_: Exception) { false }
    }
}
