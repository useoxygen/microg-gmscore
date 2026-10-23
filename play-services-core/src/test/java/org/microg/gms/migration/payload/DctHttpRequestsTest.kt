/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.payload

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class DctHttpRequestsTest {
    private val base = "https://[fe80::1]:10234/".toHttpUrl()
    @Test fun nullAndEmptyBodiesRemainGetAndPostRespectively() {
        val get = DctHttpRequests.build(base, "/notifications?ack", null, emptyMap())
        val post = DctHttpRequests.build(base, "/communications", byteArrayOf().toRequestBody(), emptyMap())
        assertEquals("GET", get.method)
        assertNull(get.body)
        assertEquals("ack", get.url.encodedQuery)
        assertEquals("POST", post.method)
        assertEquals(0L, post.body!!.contentLength())
        assertEquals("v1", post.header("x-dct-version"))
    }
    @Test fun controlBodyHasNoAdditionalNearbyLengthPrefix() {
        val bytes = byteArrayOf(10, 2, 10, 0)
        val body = bytes.toRequestBody("application/vnd.dct.control+proto".toMediaType())
        val request = DctHttpRequests.build(base, "/controls", body, emptyMap())
        val encoded = Buffer().also { request.body!!.writeTo(it) }.readByteArray()
        assertArrayEquals(bytes, encoded)
        assertEquals("application/vnd.dct.control+proto", request.body!!.contentType().toString())
    }
    @Test fun applicationMetadataIsPreservedWithoutReplacingTransportHeaders() {
        val request = DctHttpRequests.build(base, "/_callback/communications", null,
            mapOf("X-OS-Migration-Metadata" to "base64; CAE="))
        assertEquals("base64; CAE=", request.header("X-OS-Migration-Metadata"))
        assertThrows(IllegalArgumentException::class.java) {
            DctHttpRequests.build(base, "/_callback", null, mapOf("Host" to "example.com"))
        }
    }
    @Test fun applicationPathsCannotRedirectToAnotherPeerOrInjectHeaders() {
        listOf("https://example.com/", "//example.com/", "/\\example.com/", "/x\r\nHost: example.com", "/#fragment").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { DctHttpRequests.build(base, path, null, emptyMap()) }
        }
    }
}
