/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

enum class LeakCheckMethod(val rpc: String) { MATCHES("LookupLeakMatches"), REENCRYPT("ReencryptLookupHash") }
fun interface LeakCheckTransport { fun invoke(method: LeakCheckMethod, request: ByteArray): ByteArray }
class LeakCheckCancelledException : Exception("Password assessment cancelled")

/** Exact GMS hzuv/hzuw/hzvf/hzvg/hzvi/hzvh wire layouts; no username, password or realm field. */
class LeakCheckProtocol(private val transport: LeakCheckTransport, private val check: () -> Unit = {}) {
    fun check(canonicalized: String, password: String): Boolean {
        if (canonicalized.isEmpty() || password.isEmpty()) throw ProtocolException()
        check()
        val prefix = LeakCheckCrypto.bucket(canonicalized)
        val bucket = Proto.field(1, prefix) + Proto.integer(2, 26)
        val lookup = Proto.decode(transport.invoke(LeakCheckMethod.MATCHES, Proto.field(1, bucket) + Proto.integer(2, USE_CASE)))
        check()
        val matches = lookup.bytes(1)
        if (matches.size > 100_000 || matches.any { it.size !in 1..32 }) throw ProtocolException()
        val context = lookup.optional(4) ?: byteArrayOf()
        // Context is opaque; preserve all fields and bind request two to this exact response.
        Proto.decode(context).optional(4)?.let { if (it.size > 64 * 1024) throw ProtocolException() }
        if (context.size > 64 * 1024) throw ProtocolException()
        val hash = LeakCheckCrypto.passwordHash(canonicalized, password)
        val cipher = LeakCheckCipher.create()
        try {
            check()
            val request = Proto.field(1, cipher.encrypt(hash)) + Proto.field(2, bucket) + Proto.field(3, context) + Proto.integer(4, USE_CASE)
            val response = Proto.decode(transport.invoke(LeakCheckMethod.REENCRYPT, request)).one(1)
            check()
            val unblinded = cipher.unblind(response)
            val digest = try { LeakCheckCrypto.sha256(unblinded) } finally { unblinded.fill(0) }
            return try { matches.any { match -> digest.copyOfRange(0, match.size).contentEquals(match) } }
            finally { digest.fill(0) }
        } finally { hash.fill(0); cipher.destroy(); prefix.fill(0) }
    }
    private companion object { const val USE_CASE = 2L /* CREDENTIAL_MANAGER_SYNCED_PASSWORDS_CHECKUP */ }
}
