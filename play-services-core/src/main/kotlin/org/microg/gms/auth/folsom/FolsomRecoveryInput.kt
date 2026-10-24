/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom

import com.google.android.gms.auth.folsom.RecoveryRequest
import org.microg.gms.passwords.Proto
import java.security.MessageDigest

/** Own the SDK's mutable request before leaving its Binder transaction. No hash is serialized. */
internal class FolsomRecoveryInput(request: RecoveryRequest?) : AutoCloseable {
    val accountName: String
    val scopeDigest: ByteArray
    val challenge: ByteArray
    private var hash: ByteArray? = null
    init {
        val value = request ?: throw IllegalArgumentException("Recovery request unavailable")
        val name = value.accountName?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Recovery account unavailable")
        if (!value.accountType.isNullOrEmpty() && value.accountType != "com.google") throw IllegalArgumentException("Recovery account unavailable")
        val parameters = value.vaultParameters?.copyOf() ?: throw IllegalArgumentException("Recovery parameters unavailable")
        val metadata = value.vaultMetadata?.copyOf() ?: throw IllegalArgumentException("Recovery parameters unavailable")
        val originalChallenge = value.vaultChallenge?.copyOf() ?: throw IllegalArgumentException("Recovery challenge unavailable")
        val ownedHash = value.secretHash?.copyOf() ?: throw IllegalArgumentException("Recovery proof unavailable")
        try {
            if (parameters.size !in 1..65536 || metadata.size !in 1..65536 || originalChallenge.size !in 1..65536 || ownedHash.size != 32)
                throw IllegalArgumentException("Recovery request unavailable")
            val handle = Proto.decode(parameters).one(4)
            if (handle.size != 17 || handle[0] !in byteArrayOf(1, 3) || Proto.decode(metadata).integer(1)?.let { it in 1L..3L } != true)
                throw IllegalArgumentException("Recovery parameters unavailable")
            accountName = name
            scopeDigest = MessageDigest.getInstance("SHA-256").digest(Proto.field(1, parameters) + Proto.field(2, metadata))
            challenge = originalChallenge.copyOf()
            hash = ownedHash.copyOf()
        } finally { parameters.fill(0); metadata.fill(0); originalChallenge.fill(0); ownedHash.fill(0) }
    }
    @Synchronized fun ownedHash(): ByteArray = hash?.copyOf() ?: throw IllegalStateException("Recovery request closed")
    @Synchronized override fun close() { hash?.fill(0); hash = null }
    override fun toString() = "FolsomRecoveryInput[redacted]"
}
