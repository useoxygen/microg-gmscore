/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class PasskeyDeleteWireTest {
    @Test fun deleteUsesOpaqueIdAndClientTokenWithoutTreatingResourceTimestampAsDeleteMetadata() {
        // Pinned bfdz.n / iajg: credential_id=1, client_token=4; optional ioyo=5 is an empty message.
        // This source-shaped ialc carries update_time=8, plus fields deletion must not forward.
        val resource = Proto.field(1, "opaque-credential-id") + Proto.field(2, "opaque-client-tag") +
            Proto.integer(3, 19) + Proto.field(4, byteArrayOf(7, 8)) +
            Proto.field(8, Proto.integer(1, 1_700_000_000) + Proto.integer(2, 123_000_000)) +
            Proto.field(90, "future-resource-field")
        val token = byteArrayOf(1, 2, 3)
        var calls = 0
        val api = PasskeyApi(PasswordTransport { method, request ->
            calls++
            assertEquals(PasswordMethod.DELETE_WEBAUTHN, method)
            // Independent wire golden: field 1 (length 20), UTF-8 ID, field 4 (length 3), token.
            assertArrayEquals(byteArrayOf(0x0a, 0x14) + "opaque-credential-id".toByteArray(Charsets.UTF_8) +
                byteArrayOf(0x22, 0x03, 0x01, 0x02, 0x03), request)
            byteArrayOf()
        })
        val before = resource.copyOf()
        api.delete(resource, token)
        assertEquals(1, calls)
        assertArrayEquals(before, resource)
        assertArrayEquals(byteArrayOf(1, 2, 3), token)
    }

    @Test fun invalidOpaqueIdCannotLaunchDeletion() {
        var calls = 0
        val api = PasskeyApi(PasswordTransport { _, _ -> calls++; byteArrayOf() })
        assertThrows(ProtocolException::class.java) { api.delete(Proto.field(1, ""), byteArrayOf(1)) }
        assertThrows(ProtocolException::class.java) { api.delete(Proto.field(1, "bad\nidentifier"), byteArrayOf(1)) }
        assertEquals(0, calls)
    }
}
