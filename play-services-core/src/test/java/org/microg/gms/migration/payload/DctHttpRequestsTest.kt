/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.payload

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

class DctHttpRequestsTest {
    private val base = "https://[fe80::1]:10234/".toHttpUrl()
    @Test fun socketConnectRetainsTheResolvedIpv6Scope() {
        val bytes = byteArrayOf(0xfe.toByte(), 0x80.toByte()) + ByteArray(13) + byteArrayOf(1)
        val address = Inet6Address.getByAddress(null, bytes, 7)
        val route = DctPeerRoute(address, 10234)
        var endpoint: InetSocketAddress? = null
        val sockets = object : SocketFactory() {
            override fun createSocket() = object : Socket() {
                override fun connect(remote: SocketAddress, timeout: Int) {
                    endpoint = remote as InetSocketAddress
                    throw IOException("synthetic connect boundary")
                }
            }
            override fun createSocket(h: String, p: Int): Socket = error("unexpected overload")
            override fun createSocket(h: String, p: Int, a: InetAddress, l: Int): Socket = error("unexpected overload")
            override fun createSocket(a: InetAddress, p: Int): Socket = error("unexpected overload")
            override fun createSocket(a: InetAddress, p: Int, l: InetAddress, q: Int): Socket = error("unexpected overload")
        }
        val client = OkHttpClient.Builder().dns(route.dns).socketFactory(sockets)
            .proxy(java.net.Proxy.NO_PROXY).retryOnConnectionFailure(false).build()
        try {
            // Reproduce the original literal-URL scope loss using the real client's routing.
            val literal = Request.Builder().url(base).build()
            assertThrows(IOException::class.java) { client.newCall(literal).execute().close() }
            assertEquals(0, (endpoint!!.address as Inet6Address).scopeId)
            val request = Request.Builder().url(route.base).header("Host", route.authority).build()
            assertThrows(IOException::class.java) { client.newCall(request).execute().close() }
            assertSame(address, endpoint!!.address)
            assertEquals(7, (endpoint!!.address as Inet6Address).scopeId)
            assertEquals("[fe80:0:0:0:0:0:0:1]:10234", request.header("Host"))
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
    @Test fun nullAndEmptyBodiesRemainGetAndPostRespectively() {
        val get = DctHttpRequests.build(base, "/notifications?ack", null, emptyList())
        val post = DctHttpRequests.build(base, "/communications", byteArrayOf().toRequestBody(), emptyList())
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
        val request = DctHttpRequests.build(base, "/controls", body, emptyList())
        val encoded = Buffer().also { request.body!!.writeTo(it) }.readByteArray()
        assertArrayEquals(bytes, encoded)
        assertEquals("application/vnd.dct.control+proto", request.body!!.contentType().toString())
    }
    @Test fun applicationMetadataIsPreservedWithoutReplacingTransportHeaders() {
        val request = DctHttpRequests.build(base, "/_callback/communications", null,
            listOf("X-OS-Migration-Metadata" to "base64; CAE=", "X-Test" to "a", "X-Test" to "b"))
        assertEquals("base64; CAE=", request.header("X-OS-Migration-Metadata"))
        assertEquals(listOf("a", "b"), request.headers.values("X-Test"))
        assertThrows(IllegalArgumentException::class.java) {
            DctHttpRequests.build(base, "/_callback", null, listOf("Host" to "example.com"))
        }
    }
    @Test fun applicationPathsCannotRedirectToAnotherPeerOrInjectHeaders() {
        listOf("https://example.com/", "//example.com/", "/\\example.com/", "/x\r\nHost: example.com", "/#fragment").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { DctHttpRequests.build(base, path, null, emptyList()) }
        }
    }
}
