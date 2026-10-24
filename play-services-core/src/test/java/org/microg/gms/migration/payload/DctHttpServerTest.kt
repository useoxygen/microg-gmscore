/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.payload

import android.os.ParcelFileDescriptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.microg.gms.migration.nearby.DctRecord
import org.microg.gms.migration.nearby.DctSchemas
import java.io.*
import java.net.*
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DctHttpServerTest {
    private fun bytes(value: String): DctRecord {
        val file = File.createTempFile("dct-test", ".bytes", RuntimeEnvironment.getApplication().cacheDir)
        try { DataOutputStream(file.outputStream()).use { it.writeInt(value.length); it.writeBytes(value) }
            return DctRecord(DctSchemas.parcelBytes, mapOf(1 to ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)))
        } finally { file.delete() }
    }
    private fun response(id: Long, request: Long, value: String, multipart: Boolean, last: Boolean) = DctRecord(DctSchemas.payload,
        mapOf(1 to id, 17 to 2, 16 to DctRecord(DctSchemas.response,
            mapOf(1 to last, 2 to request, 3 to "application/vnd.os-migration.control+proto", 6 to multipart, 7 to listOf(bytes(value))))))
    @Test fun publisherCorrelatesDifferentIdsAndStreamsSeveralBatchesOverRealHttp() {
        val events = CopyOnWriteArrayList<Pair<Long,Int>>()
        val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
        lateinit var bridge: DctPayloadBridge
        bridge = DctPayloadBridge(RuntimeEnvironment.getApplication(), { null }, { incoming ->
            try {
                assertEquals(1, incoming.int(17)); val request = incoming.obj(15)!!
                assertEquals("/communications?part=2", request.fields[2].toString())
                val id = request.long(1); assertNotEquals(id, incoming.long(1))
                assertEquals(8009, bridge.send(response(899, id+500, "wrong", false, true)))
                assertEquals(0, bridge.send(response(900, id, "one", true, false)))
                assertEquals(0, bridge.send(response(901, id, "two", true, true)))
                assertEquals(8009, bridge.send(response(902, id, "late", true, true)))
            } catch (t: Throwable) { error.set(t) }
        }, { id,status,_,_ -> events.add(id to status) })
        val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        DctHttpServer.loopback(socket, bridge::incoming).use { server ->
            server.start()
            val client = OkHttpClient()
            try {
                client.newCall(Request.Builder().url("http://localhost:${server.port}/communications?part=2")
                    .header("x-dct-version", "v1").post(byteArrayOf().toRequestBody()).build()).execute().use { reply ->
                    assertEquals(200, reply.code); assertEquals("chunked", reply.header("Transfer-Encoding"))
                    val body = reply.body!!.string(); val boundary = reply.header("Content-Type")!!.substringAfter("boundary=")
                    assertEquals("--$boundary\r\nContent-Type: application/vnd.os-migration.control+proto\r\nContent-Length: 3\r\n\r\none\r\n" +
                        "--$boundary\r\nContent-Type: application/vnd.os-migration.control+proto\r\nContent-Length: 3\r\n\r\ntwo\r\n--$boundary--\r\n", body)
                }
                assertTrue(events.contains(900L to 1)); assertTrue(events.contains(901L to 1))
            } finally { bridge.close(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        }
        error.get()?.let { throw it }
    }
    @Test fun nonMultipartFileKeepsMetadataAndExactBytes() {
        lateinit var bridge: DctPayloadBridge
        val file = File.createTempFile("dct-file", ".test", RuntimeEnvironment.getApplication().cacheDir).apply { writeBytes(ByteArray(70000) { (it%251).toByte() }) }
        val expected = file.readBytes()
        bridge = DctPayloadBridge(RuntimeEnvironment.getApplication(), { null }, { incoming ->
            bridge.send(DctRecord(DctSchemas.payload, mapOf(1 to 100L, 17 to 3, 16 to DctRecord(DctSchemas.response,
                mapOf(1 to true, 2 to incoming.obj(15)!!.long(1), 3 to "application/octet-stream", 5 to listOf(DctRecord(DctSchemas.file,
                    mapOf(1 to byteArrayOf(1,2), 2 to ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 3 to file.length()))))))))
        }, { _,_,_,_ -> })
        val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        DctHttpServer.loopback(socket, bridge::incoming).use { server ->
            server.start(); val client = OkHttpClient()
            try { client.newCall(Request.Builder().url("http://localhost:${server.port}/file").header("x-dct-version", "v1").build()).execute().use {
                assertEquals("base64; AQI=", it.header("X-OS-Migration-Metadata")); assertArrayEquals(expected, it.body!!.bytes())
            } } finally { bridge.close(); file.delete(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        }
    }
    @Test fun callbackPostsAreOneWayAndDoNotWaitForApplicationResponse() {
        var seen = false
        DctPayloadBridge(RuntimeEnvironment.getApplication(), { null }, { payload ->
            seen = true; assertEquals(4, payload.int(17)); assertEquals("application/test", payload.obj(18)!!.text(1))
        }, { _,_,_,_ -> }).use { bridge ->
            val reply = bridge.incoming(DctHttpRequest("POST", "/_callback", mapOf("content-type" to "application/test"), byteArrayOf(4,2)))
            assertTrue(seen); assertEquals(200, reply.status); assertEquals(0L, reply.body.contentLength())
        }
    }
    @Test fun cancellationUnblocksWaitingServerAndDeathClosesQueuedDescriptors() {
        val entered = CountDownLatch(1); val requestId = java.util.concurrent.atomic.AtomicLong()
        val server = DctServerPayloads(RuntimeEnvironment.getApplication(), { requestId.set(it.long(1)); entered.countDown() }, { _,_,_,_ -> })
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<Boolean> { try { server.incoming(DctHttpRequest("GET", "/communications", emptyMap(), null)); false } catch (e: IOException) { true } }
        try { assertTrue(entered.await(3, TimeUnit.SECONDS)); assertEquals(0, server.cancel(requestId.get())); assertTrue(future.get(2, TimeUnit.SECONDS)) }
        finally { server.close(); executor.shutdownNow() }
    }
    @Test fun parserPreservesNullGetVersusEmptyPostAndRejectsSmuggling() {
        fun read(value: String) = DctHttpServer.read(ByteArrayInputStream(value.toByteArray()))
        assertNull(read("GET /x HTTP/1.1\r\nx-dct-version: v1\r\n\r\n").bytes)
        assertArrayEquals(byteArrayOf(), read("POST /x HTTP/1.1\r\nx-dct-version: v1\r\nContent-Length: 0\r\n\r\n").bytes)
        assertArrayEquals("abc".toByteArray(), read("POST /x HTTP/1.1\r\nx-dct-version: v1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n").bytes)
        listOf("Content-Length: 0\r\nContent-Length: 0", "Content-Length: 0\r\nTransfer-Encoding: chunked", "Content-Length: -1").forEach {
            assertThrows(IllegalArgumentException::class.java) { read("POST /x HTTP/1.1\r\nx-dct-version: v1\r\n$it\r\n\r\n") }
        }
    }
}
