/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people.sync

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ContactsDiagnosticsTest {
    @Test fun apiReasonsAreAllowlistedWithoutRetainingMessagesOrMetadata() {
        val body = """{"error":{"message":"secret bearer and contact name", "details":[
          {"reason":"secret", "metadata":{"consumer":"private"}},
          {"reason":"SERVICE_DISABLED","metadata":{"token":"secret"}}]}}"""
        assertEquals("SERVICE_DISABLED", ContactsDiagnostics.apiReason(body))
        assertNull(ContactsDiagnostics.apiReason("""{"error":{"errors":[{"reason":"secret"}]}}"""))
        assertNull(ContactsDiagnostics.apiReason("secret malformed body"))
        assertEquals("PERMISSION_DENIED", ContactsDiagnostics.apiStatus("""{"error":{"status":"PERMISSION_DENIED"}}"""))
        assertNull(ContactsDiagnostics.apiStatus("""{"error":{"status":"secret"}}"""))
        assertEquals("stage=api failure=API_DENIED http_status=403 reason=SERVICE_DISABLED api_code=UNKNOWN",
            ContactsDiagnostics.activation(ActivationStage.API, ApiException(403, reason = ContactsDiagnostics.apiReason(body))))
    }
    @Test fun authDiagnosticsAcceptOnlyExactErrorEnums() {
        assertEquals("BadAuthentication", ContactsDiagnostics.authError("Error=BadAuthentication"))
        assertEquals("BadAuthentication", ContactsDiagnostics.authError("Error=BadAuthentication\nToken=secret"))
        assertEquals("UNKNOWN", ContactsDiagnostics.authError("secret BadAuthentication"))
        assertFalse(ContactsDiagnostics.activation(ActivationStage.AUTHORIZATION,
            IOException("account, password and secret")).contains("secret"))
    }
    @Test fun accessCheckIsOnlyOneMinimalGetWithoutSyncOrMutation() {
        var requests = 0
        val api = PeopleApi(HttpTransport { method, url, body ->
            requests++
            assertEquals("GET", method); assertNull(body)
            assertTrue(url.contains("personFields=metadata")); assertTrue(url.contains("pageSize=1"))
            assertFalse(url.contains("requestSyncToken")); assertFalse(url.contains("syncToken"))
            HttpReply(200, """{"connections":[{"malformed":true}]}""")
        }) { false }
        api.checkAccess()
        assertEquals(1, requests)
    }
}
