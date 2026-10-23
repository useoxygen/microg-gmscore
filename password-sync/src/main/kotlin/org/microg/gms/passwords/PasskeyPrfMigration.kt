/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** GMS ajoh / iskq.l / cged.b. The caller must durably commit this replacement before releasing a PRF. */
object PasskeyPrfMigration {
    fun upgrade(encrypted: ByteArray): ByteArray? {
        if (encrypted.size > 128 * 1024) throw ProtocolException()
        val message = Proto.decode(encrypted)
        val existing = message.optional(2)
        try {
            if (existing != null && existing.isNotEmpty()) {
                if (existing.size != 32) throw ProtocolException()
                return null
            }
        } finally { existing?.fill(0) }
        val pkcs8 = message.one(1)
        if (pkcs8.size !in 32..4096) { pkcs8.fill(0); throw ProtocolException() }
        var extracted: ByteArray? = null
        var derived: ByteArray? = null
        try {
            // A legacy credential's actual private key is the source IKM. No public credential ID participates.
            val key = try { KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8)) }
                catch (_: Exception) { throw ProtocolException() }
            runCatching { key.destroy() }
            fun hmac(key: ByteArray, value: ByteArray) = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256")); doFinal(value)
            }
            extracted = hmac(ByteArray(32), pkcs8)
            derived = hmac(extracted, "derived PRF HMAC secret".toByteArray(Charsets.UTF_8) + byteArrayOf(1))
            return message.replace(2, derived).encode()
        } finally { pkcs8.fill(0); extracted?.fill(0); derived?.fill(0) }
    }
}
