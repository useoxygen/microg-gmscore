/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.net.URI
import java.security.MessageDigest

/** Chromium 145 SPC delegates transaction UI/clientDataJSON to the verified browser. */
object PasskeyPayment {
    /** Legacy field 10 and current JSON/payment field 14 are alternate representations. */
    fun enrollment(thirdParty: Boolean?, payment: Boolean?): Boolean {
        if (thirdParty != null && payment != null) throw ProtocolException()
        return thirdParty == true || payment == true
    }
    fun withPaymentSupport(item: SyncedPasskey): SyncedPasskey =
        SyncedPasskey.decode(Proto.decode(item.encode()).replaceInteger(11, 1).encode())
    fun crossOrigin(origin: String, rp: String): Boolean {
        SyncedPasskey.checkedRp(rp)
        val uri = try { URI(origin) } catch (_: Exception) { throw ProtocolException() }
        if (uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.userInfo != null ||
            !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null || uri.port !in -1..65535)
            throw ProtocolException()
        return uri.host != rp && !uri.host.endsWith(".$rp")
    }
    /** No arbitrary app, empty allowlist, or locally fabricated transaction hash can use the SPC exception. */
    fun delegated(browserVerified: Boolean, origin: String, rp: String, clientDataHash: ByteArray?, allowed: List<ByteArray>): Boolean =
        browserVerified && crossOrigin(origin, rp) && clientDataHash?.size == 32 && allowed.isNotEmpty() &&
            allowed.size <= 1000 && allowed.all { it.size in 1..1024 }
    /** Called again with the actual persisted credential while the owner/account/key locks are held. */
    fun authorize(item: SyncedPasskey, browserVerified: Boolean, origin: String, rp: String,
        clientDataHash: ByteArray?, allowed: List<ByteArray>) {
        if (item.rpId != rp) throw ProtocolException()
        if (crossOrigin(origin, rp) && (!item.paymentSupport || !delegated(browserVerified, origin, rp, clientDataHash, allowed) ||
                allowed.none { MessageDigest.isEqual(it, item.credentialId) })) throw ProtocolException()
    }
}
