/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

fun interface AffiliationTransport { fun fetch(request: ByteArray): ByteArray }

class AffiliationLookupException(val retryable: Boolean) : Exception("Affiliation metadata unavailable")

/** Dedicated affiliation API admission only. Password OAuth, cookies and unrelated service keys are never used. */
class GoogleAffiliationTransport(apiKey: String, client: OkHttpClient = OkHttpClient()) : AffiliationTransport {
    private val url = "https://www.googleapis.com/affiliation/v1/affiliation:lookupByHashPrefix".toHttpUrl().newBuilder()
        .addQueryParameter("key", apiKey).build()
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cache(null).retryOnConnectionFailure(false).callTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    init { require(apiKey.matches(Regex("[A-Za-z0-9_-]{10,256}"))) { "Invalid affiliation admission configuration" } }

    override fun fetch(request: ByteArray): ByteArray {
        if (request.isEmpty() || request.size > 64 * 1024) throw ProtocolException()
        try {
            val call = Request.Builder().url(url).header("Cache-Control", "no-store")
                .header("x-goog-ext-174067345-bin", "CgIIAQ==")
                .post(request.toRequestBody("application/x-protobuf".toMediaType())).build()
            return http.newCall(call).execute().use { response ->
                if (response.code != 200) throw AffiliationLookupException(response.code == 429 || response.code in 500..599)
                val body = response.body ?: throw AffiliationLookupException(false)
                if (body.contentLength() > AffiliationResponse.MAX_RESPONSE_BYTES) throw ProtocolException()
                val out = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > AffiliationResponse.MAX_RESPONSE_BYTES - out.size()) throw ProtocolException()
                        out.write(buffer, 0, read)
                    }
                }
                out.toByteArray()
            }
        } catch (_: IOException) { throw AffiliationLookupException(true) }
    }
    override fun toString() = "GoogleAffiliationTransport[redacted]"
}

/** Implementations persist this only inside the same account-bound encrypted transaction as the vault. */
interface AffiliationCacheStore {
    fun read(): ByteArray?
    fun write(value: ByteArray)
}

/** Durable throttling, atomic replacement and a hard freshness fence, independent of password sync success. */
class AffiliationRefresh(private val store: AffiliationCacheStore, private val transport: AffiliationTransport?,
    private val now: () -> Long = System::currentTimeMillis) {
    private class State(val snapshot: AffiliationSnapshot?, val lastAttempt: Long, val nextAttempt: Long, val failures: Int) {
        fun encode() = Proto.integer(1, 1) + (snapshot?.let { Proto.field(2, it.encode()) } ?: byteArrayOf()) +
            Proto.integer(3, lastAttempt) + Proto.integer(4, nextAttempt) + Proto.integer(5, failures.toLong())
    }
    private fun load(): State {
        val bytes = store.read() ?: return State(null, 0, 0, 0)
        val proto = Proto.decode(bytes)
        if (proto.integer(1) != 1L) throw ProtocolException()
        val last = proto.integer(3) ?: throw ProtocolException()
        val next = proto.integer(4) ?: throw ProtocolException()
        val failures = proto.integer(5) ?: throw ProtocolException()
        if (last < 0 || next < last || failures !in 0..16) throw ProtocolException()
        return State(proto.optional(2)?.let(AffiliationSnapshot::decode), last, next, failures.toInt())
    }
    fun cached(facets: Collection<AffiliationFacet>): AffiliationResponse? {
        if (facets.isEmpty()) return null
        val snapshot = load().snapshot ?: return null
        return if (snapshot.covers(facets)) snapshot.at(now()) else null
    }
    fun refresh(facets: Collection<AffiliationFacet>, locale: String = "en", iconSize: Int = 40): AffiliationResponse? {
        if (facets.isEmpty()) return null
        val requested = facets.toSet()
        if (requested.size > AffiliationResponse.MAX_REQUESTED_FACETS) throw ProtocolException()
        val state = load()
        val timestamp = now()
        if (timestamp < 0) throw ProtocolException()
        val cached = state.snapshot?.takeIf { it.covers(requested) }?.at(timestamp)
        val fetch = transport ?: return cached
        // A changed facet set still respects the account-wide throttle. Clock rollback
        // invalidates metadata and permits one fresh attempt with a new persisted fence.
        val nextAttempt = if (cached == null && state.failures == 0) minOf(state.nextAttempt, add(state.lastAttempt, MIN_RETRY_MILLIS))
            else state.nextAttempt
        if (timestamp >= state.lastAttempt && timestamp < nextAttempt) return cached
        val failures = (state.failures + 1).coerceAtMost(16)
        val delay = (MIN_RETRY_MILLIS * (1L shl (failures - 1).coerceAtMost(7))).coerceAtMost(24 * 60 * 60 * 1000L)
        // Persist before I/O, so process death cannot create an unbounded retry loop.
        store.write(State(state.snapshot, timestamp, add(timestamp, delay), failures).encode())
        val raw = fetch.fetch(AffiliationLookup.request(requested, locale, iconSize))
        val snapshot = AffiliationSnapshot.create(raw, requested, timestamp, add(timestamp, FRESH_MILLIS))
        store.write(State(snapshot, timestamp, add(timestamp, REFRESH_MILLIS), 0).encode())
        return snapshot.at(timestamp)
    }
    override fun toString() = "AffiliationRefresh[redacted]"
    companion object {
        const val MIN_RETRY_MILLIS = 10 * 60 * 1000L
        const val REFRESH_MILLIS = 24 * 60 * 60 * 1000L
        const val FRESH_MILLIS = 14 * 24 * 60 * 60 * 1000L
        private fun add(value: Long, increment: Long): Long = if (value > Long.MAX_VALUE - increment) throw ProtocolException() else value + increment
    }
}
