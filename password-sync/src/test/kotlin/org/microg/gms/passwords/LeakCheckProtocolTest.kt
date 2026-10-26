/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class LeakCheckProtocolTest {
    @Test fun actualTwoRpcBodiesBindTheBucketAndOpaqueContextWithoutRawCredentials() {
        for (leaked in listOf(false, true)) {
            val server = LeakCheckTestServer(leaked)
            assertEquals(leaked, LeakCheckProtocol(server).check("user", "password123"))
            assertEquals(2, server.calls)
        }
    }

    @Test fun cancellationAfterTheFirstResponsePreventsScryptReencryptionAndFurtherRpc() {
        var stopped = false
        val server = LeakCheckTestServer().apply { before = { _, _ -> stopped = true } }
        assertThrows(LeakCheckCancelledException::class.java) {
            LeakCheckProtocol(server) { if (stopped) throw LeakCheckCancelledException() }.check("user", "password123")
        }
        assertEquals(1, server.calls)
    }

    @Test fun noProviderResponseOrInvalidPointCanBeReportedAsAnUncompromisedPassword() {
        for (response in listOf(byteArrayOf(), Proto.field(1, byteArrayOf(0)), Proto.field(1, ByteArray(33)))) {
            var calls = 0
            val server = LeakCheckTransport { method, _ ->
                calls++
                if (method == LeakCheckMethod.MATCHES) Proto.field(4, Proto.field(4, byteArrayOf(1))) else response
            }
            assertThrows(ProtocolException::class.java) { LeakCheckProtocol(server).check("user", "password123") }
            assertEquals(2, calls)
        }
    }

    @Test fun emptyAndOversizedMatchPrefixesAreMalformedRatherThanPositiveOrNegativeAssessments() {
        for (prefix in listOf(byteArrayOf(), ByteArray(33))) {
            var calls = 0
            val server = LeakCheckTransport { _, _ -> calls++; Proto.field(1, prefix) }
            assertThrows(ProtocolException::class.java) { LeakCheckProtocol(server).check("user", "password123") }
            assertEquals(1, calls)
        }
    }

    @Test fun emptyCredentialCannotBeSentOrLabeledFresh() {
        var calls = 0
        val protocol = LeakCheckProtocol(LeakCheckTransport { _, _ -> calls++; error("No credential query expected") })
        assertThrows(ProtocolException::class.java) { protocol.check("", "password123") }
        assertThrows(ProtocolException::class.java) { protocol.check("user", "") }
        assertEquals(0, calls)
    }
}
