/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Normalized WebAuthn PRF inputs. Public salts are never treated as a credential secret. */
class PasskeyPrfValues private constructor(val first: ByteArray, val second: ByteArray?) {
    override fun toString() = "PasskeyPrfValues[redacted]"
    companion object {
        fun raw(first: ByteArray, second: ByteArray? = null): PasskeyPrfValues {
            if (first.size > Proto.MAX_BYTES || second?.size?.let { it > Proto.MAX_BYTES } == true) throw ProtocolException()
            fun normalize(value: ByteArray) = MessageDigest.getInstance("SHA-256")
                .digest("WebAuthn PRF\u0000".toByteArray(Charsets.UTF_8) + value)
            return PasskeyPrfValues(normalize(first), second?.let(::normalize))
        }
        fun hashed(value: ByteArray): PasskeyPrfValues {
            if (value.size != 32 && value.size != 64) throw ProtocolException()
            return PasskeyPrfValues(value.copyOfRange(0, 32), if (value.size == 64) value.copyOfRange(32, 64) else null)
        }
    }
}

class PasskeyPrfResult(val enabled: Boolean, val first: ByteArray?, val second: ByteArray?) : AutoCloseable {
    override fun close() { first?.fill(0); second?.fill(0) }
    override fun toString() = "PasskeyPrfResult[redacted]"
}

/** GMS PrfExtension.d, ajdu.b/f. Selection uses the actual authenticated credential, never list order. */
class PasskeyPrfRequest private constructor(private val fallback: PasskeyPrfValues?,
    private val selected: List<Pair<ByteArray, PasskeyPrfValues>>, val create: Boolean) {
    fun evaluate(secret: ByteArray, credentialId: ByteArray): PasskeyPrfResult {
        if (secret.size != 32 || credentialId.size !in 1..1024) throw ProtocolException()
        val input = selected.singleOrNull { MessageDigest.isEqual(it.first, credentialId) }?.second ?: fallback
        fun hmac(value: ByteArray) = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256")); doFinal(value)
        }
        return PasskeyPrfResult(create, input?.first?.let(::hmac), input?.second?.let(::hmac))
    }
    override fun toString() = "PasskeyPrfRequest[redacted]"
    companion object {
        fun normalized(fallback: PasskeyPrfValues?, selected: List<Pair<ByteArray, PasskeyPrfValues>> = emptyList(), create: Boolean): PasskeyPrfRequest {
            if (selected.size > 1000 || create && selected.isNotEmpty() || selected.any { it.first.size !in 1..1024 } ||
                selected.map { SyncedPasskey.hex(it.first) }.toSet().size != selected.size) throw ProtocolException()
            return PasskeyPrfRequest(fallback, selected.map { it.first.copyOf() to it.second }, create)
        }
        /** Legacy GMS safeparcel carries alternating nullable credential IDs and already-hashed32/64-byte values. */
        fun legacy(entries: List<ByteArray?>, create: Boolean): PasskeyPrfRequest {
            if (entries.size % 2 != 0 || entries.size > 2002) throw ProtocolException()
            var fallback: PasskeyPrfValues? = null
            val selected = ArrayList<Pair<ByteArray, PasskeyPrfValues>>()
            for (index in entries.indices step 2) {
                val values = PasskeyPrfValues.hashed(entries[index + 1] ?: throw ProtocolException())
                val credential = entries[index]
                if (credential == null) {
                    if (index != 0 || fallback != null) throw ProtocolException()
                    fallback = values
                } else selected.add(credential to values)
            }
            return normalized(fallback, selected, create)
        }
    }
}
