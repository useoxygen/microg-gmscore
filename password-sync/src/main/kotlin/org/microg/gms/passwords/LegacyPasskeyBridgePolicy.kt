/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.net.URI
import java.security.MessageDigest

/** The native CM browser release pin, reused without requiring CallingAppInfo or an AANG service. */
object LegacyPasskeyBridgePolicy {
    private val google = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
    private val browsers = setOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary")
    fun browser(packageName: String, certificateSha256: ByteArray, origin: String): Boolean =
        packageName in browsers && SyncedPasskey.hex(certificateSha256) == google && runCatching {
            val uri = URI(origin)
            uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.userInfo == null && uri.rawQuery == null &&
                uri.rawFragment == null && uri.rawPath.isNullOrEmpty() && uri.port in -1..65535
        }.getOrDefault(false)
    fun requestDigest(packageName: String, signer: ByteArray, source: String, origin: String, create: Boolean, options: ByteArray): ByteArray {
        if (packageName.isBlank() || packageName.length > 256 || signer.size != 32 || source !in setOf("app", "browser") ||
            origin.isBlank() || origin.length > 4096 || options.size !in 1..512 * 1024) throw ProtocolException()
        return MessageDigest.getInstance("SHA-256").digest(Proto.field(1, packageName) + Proto.field(2, signer) +
            Proto.field(3, source) + Proto.field(4, origin) + Proto.integer(5, if (create) 1 else 0) + Proto.field(6, options))
    }
    fun systemCaller(systemUid: Int, actualUid: Int, systemPackage: Boolean, packagesForUid: Set<String>): Boolean =
        systemUid >= 0 && actualUid == systemUid && systemPackage && packagesForUid == setOf("com.google.android.gms")
    fun supported(create: Boolean, algorithms: Collection<Int>, attestation: String?, attachment: String?, extensions: Set<String>): Boolean =
        if (create) PasskeyRequestSupport.create(algorithms, attestation, attachment, extensions) && extensions.all { it == "prf" }
        else PasskeyRequestSupport.get(extensions)
    fun owner(grantedAt: Long, now: Long, sameGeneration: Boolean, resumed: Boolean, focused: Boolean,
        secure: Boolean, unlocked: Boolean, interactive: Boolean): Boolean =
        sameGeneration && resumed && focused && secure && unlocked && interactive && now >= grantedAt && now - grantedAt <= 60_000
}
