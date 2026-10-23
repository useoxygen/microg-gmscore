/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AffiliationRefreshTest {
    private val web = AffiliationFacet.parse("https://example.invalid")
    private val other = AffiliationFacet.parse("https://other.invalid")
    private class Store : AffiliationCacheStore {
        var value: ByteArray? = null
        var writes = 0
        override fun read() = value?.copyOf()
        override fun write(value: ByteArray) { this.value = value.copyOf(); writes++ }
    }
    private fun response() = Proto.field(1, Proto.field(1, Proto.field(1, web.id)) + Proto.field(1, Proto.field(1, other.id)))

    @Test fun encryptedStoreBoundaryReopensWithoutRefetchingOrExtendingTheHardExpiry() {
        val store = Store()
        var now = 1000L
        var calls = 0
        val transport = AffiliationTransport { calls++; response() }
        val initial = AffiliationRefresh(store, transport) { now }.refresh(listOf(web))!!
        assertEquals(setOf(web, other), initial.credentialSharingFacets(web)); assertEquals(2, store.writes)
        now += 1
        assertEquals(setOf(web, other), AffiliationRefresh(store, transport) { now }.refresh(listOf(web))!!.credentialSharingFacets(web))
        assertEquals(1, calls)
        assertNull(AffiliationRefresh(Store(), null) { now }.cached(listOf(web)))
        now = 1000L + AffiliationRefresh.FRESH_MILLIS
        assertNull(AffiliationRefresh(store, null) { now }.cached(listOf(web)))
    }

    @Test fun aFailedRefreshPreservesFreshAuthorityButNeverExtendsIt() {
        val store = Store()
        var now = 1000L
        AffiliationRefresh(store, AffiliationTransport { response() }) { now }.refresh(listOf(web))
        now += AffiliationRefresh.REFRESH_MILLIS
        val engine = AffiliationRefresh(store, AffiliationTransport { throw AffiliationLookupException(true) }) { now }
        assertThrows(AffiliationLookupException::class.java) { engine.refresh(listOf(web)) }
        assertEquals(setOf(web, other), engine.cached(listOf(web))!!.credentialSharingFacets(web))
        now = 1000L + AffiliationRefresh.FRESH_MILLIS
        assertNull(engine.cached(listOf(web)))
    }

    @Test fun failureAndProcessDeathFencesSurviveReopeningAndBoundNewFacetQueries() {
        val store = Store()
        var now = 1000L
        var calls = 0
        val failed = AffiliationTransport { calls++; throw AffiliationLookupException(true) }
        assertThrows(AffiliationLookupException::class.java) { AffiliationRefresh(store, failed) { now }.refresh(listOf(web)) }
        assertNull(AffiliationRefresh(store, failed) { now }.refresh(listOf(other))); assertEquals(1, calls)
        now += AffiliationRefresh.MIN_RETRY_MILLIS
        assertThrows(AffiliationLookupException::class.java) { AffiliationRefresh(store, failed) { now }.refresh(listOf(web)) }
        now += AffiliationRefresh.MIN_RETRY_MILLIS
        assertNull(AffiliationRefresh(store, failed) { now }.refresh(listOf(web))); assertEquals(2, calls)
    }

    @Test fun malformedResponsesCannotReplaceTheLastCompleteSnapshot() {
        val store = Store()
        var now = 1000L
        AffiliationRefresh(store, AffiliationTransport { response() }) { now }.refresh(listOf(web))
        now += AffiliationRefresh.REFRESH_MILLIS
        val engine = AffiliationRefresh(store, AffiliationTransport { byteArrayOf(0) }) { now }
        assertThrows(ProtocolException::class.java) { engine.refresh(listOf(web)) }
        assertEquals(setOf(web, other), engine.cached(listOf(web))!!.credentialSharingFacets(web))
        assertNull(engine.cached(listOf(AffiliationFacet.parse("https://unknown.invalid"))))
    }

    @Test fun clockRollbackInvalidatesCachedAuthorityAndPersistsOneNewAttemptFence() {
        val store = Store()
        var now = 1000L
        AffiliationRefresh(store, AffiliationTransport { response() }) { now }.refresh(listOf(web))
        now = 999L
        var calls = 0
        val engine = AffiliationRefresh(store, AffiliationTransport { calls++; throw AffiliationLookupException(true) }) { now }
        assertNull(engine.cached(listOf(web)))
        assertThrows(AffiliationLookupException::class.java) { engine.refresh(listOf(web)) }
        assertNull(engine.refresh(listOf(web))); assertEquals(1, calls)
    }

    @Test fun noConfiguredAdmissionMakesNoNetworkRequestOrDurableClaim() {
        val store = Store()
        assertNull(AffiliationRefresh(store, null) { 1000L }.refresh(listOf(web)))
        assertEquals(0, store.writes)
        assertThrows(IllegalArgumentException::class.java) { GoogleAffiliationTransport("") }
    }

    @Test fun dedicatedGoogleRequestContainsOnlyHashPrefixesAndNoAccountCredentialHeaders() {
        var intercepted = false
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            intercepted = true
            val request = chain.request()
            assertEquals("www.googleapis.com", request.url.host)
            assertEquals("fixture-dedicated-key", request.url.queryParameter("key"))
            assertNull(request.header("Authorization")); assertNull(request.header("Cookie"))
            assertEquals("application/x-protobuf", request.body!!.contentType().toString())
            val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
            assertArrayEquals(AffiliationLookup.request(listOf(web)), buffer.readByteArray())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response().toResponseBody("application/x-protobuf".toMediaType())).build()
        }).build()
        assertArrayEquals(response(), GoogleAffiliationTransport("fixture-dedicated-key", client).fetch(AffiliationLookup.request(listOf(web))))
        assertTrue(intercepted)
    }

    @Test fun redirectsAdmissionFailuresAndOversizedResponsesAreNotAuthoritative() {
        for (status in listOf(302, 403, 429, 503)) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                    .header("Location", "https://untrusted.invalid/").body(byteArrayOf().toResponseBody()).build()
            }.build()
            val error = assertThrows(AffiliationLookupException::class.java) {
                GoogleAffiliationTransport("fixture-dedicated-key", client).fetch(AffiliationLookup.request(listOf(web)))
            }
            assertEquals(status in listOf(429, 503), error.retryable)
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(ByteArray(AffiliationResponse.MAX_RESPONSE_BYTES + 1).toResponseBody()).build()
        }.build()
        assertThrows(ProtocolException::class.java) {
            GoogleAffiliationTransport("fixture-dedicated-key", client).fetch(AffiliationLookup.request(listOf(web)))
        }
    }
}
