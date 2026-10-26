/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CancellationException
import kotlin.coroutines.*

class PrivatePeopleProbeTest {
    private val context = PrivatePeopleContext("25.24.32")
    private val signature = "0123456789abcdef0123456789abcdef01234567"
    private fun run(block: suspend () -> Unit) {
        var result: Result<Unit>? = null
        block.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(value: Result<Unit>) { result = value }
        })
        checkNotNull(result).getOrThrow()
    }
    @Test fun fixedGetIsOneSmallReadPerAuthorizedCandidateAndNeverFollowsCursors() = run {
        val requests = mutableListOf<String>(); val output = mutableListOf<String>(); var authorizations = 0
        PrivatePeopleProbe.run(context, { variant ->
            authorizations++
            assertTrue(PrivatePeopleProbe.isPrivateService("oauth2:" + variant.scope.scope))
            ProbeGrant("secret-token", PrivatePeopleProbe.authReceipt(ProbeAuthState.TOKEN, 200, null,
                "com.google.android.gms", signature, "com.google.android.gms", signature))
        }, { token, url ->
            assertEquals("secret-token", token); requests.add(url)
            val uri = URI(url)
            assertEquals("https", uri.scheme); assertEquals("people-pa.googleapis.com", uri.host); assertEquals("/v2/people/sync", uri.path)
            val params = uri.rawQuery.split('&').associate { part -> part.split('=', limit = 2).let {
                URLDecoder.decode(it[0], "UTF-8") to URLDecoder.decode(it[1], "UTF-8")
            } }
            assertEquals(mapOf("pageSize" to "1", "syncParameters.fullSyncOption" to "FULL_SYNC_OK",
                "mergedPersonSourceOptions.personModelParams.personModel" to "CONTACT_CENTRIC",
                "requestMask.includeField" to "person.metadata", "fields" to "nextPageToken,nextSyncToken",
                "requestMask.productContactGroup" to "PRODUCT_BESTIES", "mergedPersonSourceOptions.includedProfileStates" to "CORE_ID",
                "context.clientVersion.clientType" to "GMS FSA2",
                "context.clientVersion.clientVersion" to "25.24.32", "context.migrationOptions.useNewRequestMaskBehavior" to "true"), params)
            HttpReply(200, """{"nextPageToken":"secret-page","nextSyncToken":"secret-sync","people":[{"name":"secret-contact"}]}""")
        }, output::add)
        assertEquals(6, authorizations); assertEquals(6, requests.size); assertEquals(6, output.size)
        assertTrue(output.all { it.contains("page_token_present=true sync_token_present=true") })
        assertFalse(output.joinToString().contains("secret"))
        assertEquals(setOf(ProbeIdentity.ACTUAL, ProbeIdentity.GMS_APP), PrivatePeopleProbe.variants.map { it.identity }.toSet())
        assertEquals(3, PrivatePeopleProbe.variants.map { it.scope }.toSet().size)
    }
    @Test fun denialNeverPerformsGetAndDoesNotAbortOtherCandidates() = run {
        val output = mutableListOf<String>(); var attempted = 0
        PrivatePeopleProbe.run(context, { attempted++; ProbeGrant(null, """{"state":"ERROR","http":403,"error":"BadAuthentication","account":"secret"}""") },
            { _, _ -> error("Denied authorization must not read the service") }, output::add)
        assertEquals(6, attempted); assertEquals(6, output.size)
        assertTrue(output.all { it.contains("auth=ERROR auth_http=403 auth_error=BadAuthentication") && it.endsWith("http=NOT_REQUESTED") })
        assertFalse(output.toString().contains("secret"))
    }
    @Test fun untrustedDiagnosticsAndExceptionMessagesNeverReachReceipts() = run {
        val output = mutableListOf<String>()
        PrivatePeopleProbe.run(context, { ProbeGrant("secret", """{"state":"secret","http":"secret","error":"secret","client":"secret","clientSig":"secret","caller":"secret","callerSig":"secret"}""") },
            { _, _ -> throw IOException("secret account and bearer") }, output::add)
        assertFalse(output.joinToString().contains("secret"))
        assertTrue(output.all { it.contains("auth=UNKNOWN") && it.contains("client_sig=UNKNOWN") && it.contains("api_result=NETWORK") })
        assertFalse(ProbeGrant("secret", "secret").toString().contains("secret"))
    }
    @Test fun onlyAllowlistedApiStatusAndReasonAndBooleanTokenShapeSurvive() {
        val denied = PrivatePeopleProbe.apiDisplay(HttpReply(403, """{"error":{"status":"PERMISSION_DENIED","message":"secret","details":[{"reason":"ACCESS_TOKEN_SCOPE_INSUFFICIENT","metadata":{"token":"secret"}}]}}"""))
        assertEquals("http=403 api_status=PERMISSION_DENIED reason=ACCESS_TOKEN_SCOPE_INSUFFICIENT", denied)
        assertEquals("http=403 api_status=UNKNOWN reason=UNKNOWN", PrivatePeopleProbe.apiDisplay(HttpReply(403, """{"error":{"status":"secret","errors":[{"reason":"secret"}]}}""")))
        assertEquals("http=200 api_result=SUCCESS page_token_present=false sync_token_present=false",
            PrivatePeopleProbe.apiDisplay(HttpReply(200, """{"nextPageToken":123,"nextSyncToken":{"secret":true}}""")))
        assertEquals("http=200 api_result=INVALID_RESPONSE", PrivatePeopleProbe.apiDisplay(HttpReply(200, "secret")))
        assertEquals("http=200 api_result=OVERSIZE", PrivatePeopleProbe.apiDisplay(HttpReply(200, "x".repeat(PrivatePeopleProbe.MAX_RESPONSE_BYTES + 1))))
    }
    @Test fun signaturesAndPackagesAreSanitizedAtTheLastBoundary() = run {
        val output = mutableListOf<String>()
        val receipt = PrivatePeopleProbe.authReceipt(ProbeAuthState.ERROR, 9999, "Error=NeedsBrowser\nsecret",
            "com.google.android.gms", signature, "secret", "secret")
        PrivatePeopleProbe.run(context, { ProbeGrant(null, receipt) }, { _, _ -> error("No token") }, output::add)
        assertTrue(output.all { it.contains("auth_http=UNKNOWN auth_error=NeedsBrowser") && it.contains("client_sig=${signature.uppercase()}") && it.contains("caller=UNKNOWN caller_sig=UNKNOWN") })
        assertFalse(output.toString().contains("secret"))
        assertFalse(PrivatePeopleProbe.isPrivateService("oauth2:https://www.googleapis.com/auth/peopleapi.readonly secret"))
        assertFalse(PrivatePeopleProbe.isPrivateService("oauth2:https://www.googleapis.com/auth/contacts"))
    }
    @Test fun cancellationStopsTheMatrixRatherThanBeingSwallowed() {
        var authorizations = 0
        assertThrows(CancellationException::class.java) { run {
            PrivatePeopleProbe.run(context, { authorizations++; throw CancellationException("secret") }, { _, _ -> error("No token") }, { error("Cancelled") })
        } }
        assertEquals(1, authorizations)
    }
}
