/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.net.URLEncoder

/** GMS janv/agph/aqhn/aqid: packaged lookup token in xreauth, mm.setReauthResult(status,rapt). */
class PasskeyReauthPolicy(private val accountId: String) {
    init { if (accountId.isBlank() || accountId.length > 256) throw ProtocolException() }
    fun target(accountName: String, lookup: String): String {
        if (accountName.isBlank() || accountName.length > 1024 || lookup.isBlank() || lookup.length > 16_384) throw ProtocolException()
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        return "https://accounts.google.com/embedded/xreauth/android?xoauth_display_name=Android%20Phone&Email=${encode(accountName)}&plt=${encode(lookup)}"
    }
    fun result(origin: String, mainFrame: Boolean, currentAccountId: String, status: Int, rapt: String): String {
        if (!mainFrame || !TrustedVaultRecoveryPolicy.exactGoogleOrigin(origin) || currentAccountId != accountId ||
            status != 1 || rapt.isBlank() || rapt.length > 16_384) throw VaultLockedException()
        return rapt
    }
    override fun toString(): String = "PasskeyReauthPolicy[redacted]"
}

/** Only the pre-mutation device-key request can ask for this retry. A second challenge is terminal. */
object PasskeyReauthRetry {
    suspend fun <T> run(operation: suspend (String?) -> T, reauthenticate: suspend (String) -> String): T = try {
        operation(null)
    } catch (challenge: PasskeyReauthRequiredException) {
        val proof = reauthenticate(challenge.packagedLookupToken)
        if (proof.isBlank() || proof.length > 16_384) throw VaultLockedException()
        operation(proof)
    }
}
