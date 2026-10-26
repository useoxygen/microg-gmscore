/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*

/** Identity server scalar 1 makes reencryption an independently observable unchanged-point operation. */
internal class LeakCheckTestServer(private val leaked: Boolean = true) : LeakCheckTransport {
    var calls = 0
    var before: (LeakCheckMethod, ByteArray) -> Unit = { _, _ -> }
    val context = Proto.field(4, byteArrayOf(9, 8, 7)) + Proto.field(99, "opaque-future-context")
    override fun invoke(method: LeakCheckMethod, request: ByteArray): ByteArray {
        calls++; before(method, request)
        assertFalse(String(request, Charsets.ISO_8859_1).contains("password123"))
        assertFalse(String(request, Charsets.ISO_8859_1).contains("fixture.invalid"))
        val proto = Proto.decode(request)
        val bucket = Proto.decode(proto.one(if (method == LeakCheckMethod.MATCHES) 1 else 2))
        assertEquals(26L, bucket.integer(2)); assertEquals(4, bucket.one(1).size)
        assertEquals(0, bucket.one(1).last().toInt() and 0x3f)
        if (method == LeakCheckMethod.MATCHES) {
            assertEquals(2L, proto.integer(2)); assertNull(proto.optional(3))
            val hash = LeakCheckCrypto.passwordHash("user", "password123")
            val digest = try { LeakCheckCrypto.sha256(LeakCheckCrypto.hashToPoint(hash).getEncoded(true)) } finally { hash.fill(0) }
            return Proto.field(4, context) + if (leaked) Proto.field(1, digest.copyOfRange(0, 16)) else byteArrayOf()
        }
        assertEquals(2L, proto.integer(4)); assertArrayEquals(context, proto.one(3))
        assertEquals(33, proto.one(1).size)
        return Proto.field(1, proto.one(1))
    }
}
