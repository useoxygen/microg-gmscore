/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPOutputStream

class ProtocolTest {
    @Test fun boundedGrpcFramesSupportGzipAndRejectTrailingFrames() {
        val data = Proto.field(8, "synthetic")
        val frame = ByteBuffer.allocate(5 + data.size).put(0).putInt(data.size).put(data).array()
        assertArrayEquals(data, GooglePasswordTransport.decodeFrame(frame, null))
        val compressed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()
        val gzip = ByteBuffer.allocate(5 + compressed.size).put(1).putInt(compressed.size).put(compressed).array()
        assertArrayEquals(data, GooglePasswordTransport.decodeFrame(gzip, "gzip"))
        assertThrows(ProtocolException::class.java) { GooglePasswordTransport.decodeFrame(gzip, "deflate") }
        assertThrows(ProtocolException::class.java) { GooglePasswordTransport.decodeFrame(frame + frame, null) }
        assertThrows(ProtocolException::class.java) { GooglePasswordTransport.decodeFrame(byteArrayOf(0, 0, 0, 0, -1), null) }
    }
    @Test fun apiRequestsMatchDecompiledResourceAndTokenPositions() {
        val calls = ArrayList<Pair<PasswordMethod, Proto>>()
        val parent = "users/me/birthdays/synthetic"
        val entity = Proto.field(1, "$parent/passwords/id") + Proto.field(2, "tag") + Proto.field(5, byteArrayOf(1))
        val token = byteArrayOf(4, 3, 2, 1)
        val api = PasswordApi(PasswordTransport { method, request ->
            calls.add(method to Proto.decode(request))
            if (method == PasswordMethod.BATCH_UPDATE) Proto.field(1, entity) else entity
        })
        api.create(parent, entity, token)
        assertEquals(parent, calls.last().second.string(1))
        assertArrayEquals(token, calls.last().second.one(4))
        api.update(entity, token)
        assertEquals(PasswordMethod.BATCH_UPDATE, calls.last().first)
        assertEquals("$parent/passwords/id", Proto.decode(calls.last().second.one(1)).string(1))
        assertArrayEquals(token, calls.last().second.one(3))
        api.delete("$parent/passwords/id", token)
        assertArrayEquals(token, calls.last().second.one(3))
        api.list(parent, byteArrayOf(5, 6))
        assertArrayEquals(byteArrayOf(5, 6), calls.last().second.one(3))
    }
    @Test fun canonicalResourceValidationRejectsAmbiguousOrCrossUserNames() {
        for (parent in listOf("users/other/birthdays/x", "users/me/birthdays/../x", "users/me/birthdays/", "https://example.invalid")) {
            assertThrows(ProtocolException::class.java) { PasswordApi.checkedParent(parent) }
        }
        assertThrows(ProtocolException::class.java) { PasswordApi.checkedName("users/me/birthdays/x/passwords/a/b") }
        assertThrows(ProtocolException::class.java) { PasswordApi.checkedName("users/me/birthdays/x/passwords/") }
    }
    @Test fun malformedVarintsCannotWrapIntoValidFields() {
        assertThrows(ProtocolException::class.java) { Proto.decode(byteArrayOf(10, -1, -1, -1, -1, -1, -1, -1, -1, -1, 1)) }
        assertThrows(ProtocolException::class.java) { Proto.decode(Proto.field(2, byteArrayOf(-1))) .string(2) }
        assertThrows(ProtocolException::class.java) { Proto.decode(Proto.field(2, "a") + Proto.field(2, "b")).one(2) }
    }
}
