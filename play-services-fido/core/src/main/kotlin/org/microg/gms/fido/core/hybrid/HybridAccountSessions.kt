/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.fido.core.hybrid

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Process-local authority from an authenticated caBLE transcript, never reconstructed from an Intent. */
object HybridAccountSessions {
    const val KEY_REQUEST = "hybridAccountRequest"
    const val CONTEXT = "cable://authenticated"
    private class Request(val transcript: ByteArray, val wireDigest: ByteArray, val digest: ByteArray, val alive: () -> Boolean, val issued: Long, val now: () -> Long, var claimed: Boolean = false)
    private val requests = HashMap<String, Request>()
    private fun digest(transcript: ByteArray, wireDigest: ByteArray, options: ByteArray, rp: String, hash: ByteArray, create: Boolean): ByteArray {
        require(transcript.size == 32 && wireDigest.size == 32 && hash.size == 32 && options.size in 1..512 * 1024)
        require(rp.isNotBlank() && rp.length <= 253 && !rp.contains(":") && !rp.contains("/") && !rp.contains("@"))
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUTF("caBLE account request v1"); out.write(transcript); out.write(wireDigest); out.writeUTF(rp)
            out.write(hash); out.writeBoolean(create); out.writeInt(options.size); out.write(options)
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
    }
    internal class Session internal constructor(private val transcript: ByteArray, private val alive: () -> Boolean, private val now: () -> Long) {
        private var token: String? = null
        private var revoked = false
        @Synchronized fun issue(options: ByteArray, rp: String, hash: ByteArray, create: Boolean, wireDigest: ByteArray): String {
            check(!revoked && token == null && alive())
            return UUID.randomUUID().toString().also { value ->
                synchronized(requests) {
                    check(requests.size < 32)
                    requests[value] = Request(transcript.copyOf(), wireDigest.copyOf(), digest(transcript, wireDigest, options, rp, hash, create), alive, now(), now)
                }
                token = value
            }
        }
        @Synchronized fun complete(value: String) {
            check(token == value)
            synchronized(requests) { requests.remove(value) }
            token = null
        }
        @Synchronized fun revoke() {
            revoked = true
            token?.let { synchronized(requests) { requests.remove(it) } }
            token = null; transcript.fill(0)
        }
    }
    internal fun open(transcript: ByteArray, now: () -> Long = System::nanoTime, alive: () -> Boolean): Session {
        check(transcript.size == 32 && alive())
        return Session(transcript.copyOf(), alive, now)
    }
    /** Repeated checks bind the full immutable request. claim is allowed exactly once before issuing the private vault ticket. */
    fun requireLive(token: String?, options: ByteArray, rp: String, hash: ByteArray?, create: Boolean, claim: Boolean = false,
        claimed: Boolean = false) = synchronized(requests) {
        val request = requests[token] ?: throw SecurityException("Hybrid request expired")
        val elapsed = request.now() - request.issued
        if (elapsed < 0 || elapsed > 600_000_000_000L || !request.alive()) {
            requests.remove(token); throw SecurityException("Hybrid request expired")
        }
        val supplied = request.digest
        if (hash == null || !MessageDigest.isEqual(supplied, digest(request.transcript, request.wireDigest, options, rp, hash, create)) ||
            (claimed && !request.claimed) || (claim && request.claimed)) throw SecurityException("Hybrid request mismatch")
        if (claim) request.claimed = true
    }
    internal fun issue(session: Session, options: ByteArray, rp: String, hash: ByteArray, create: Boolean, wireDigest: ByteArray): String =
        session.issue(options, rp, hash, create, wireDigest)
    internal fun complete(session: Session, token: String) { session.complete(token) }
}
