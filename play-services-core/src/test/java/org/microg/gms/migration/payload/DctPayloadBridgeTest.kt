/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.payload

import android.net.Uri
import android.os.ParcelFileDescriptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.microg.gms.migration.nearby.DctRecord
import org.microg.gms.migration.nearby.DctSchemas
import java.io.DataInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DctPayloadBridgeTest {
    private fun request(id: Long, requestId: Long, bytes: ByteArray? = null) = DctRecord(DctSchemas.payload, buildMap {
        put(1, id); put(2, 0); put(17, 1); if (bytes != null) put(3, bytes)
        put(15, DctRecord(DctSchemas.request, mapOf(1 to requestId, 2 to Uri.parse("/communications"),
            3 to "application/vnd.os-migration.control+proto")))
    })
    @Test fun emptyBodyRemainsPostAndResponseKeepsRequestIdNotPayloadId() {
        val received = CountDownLatch(1); val done = CountDownLatch(1); val error = AtomicReference<Throwable>()
        LoopbackDctPeer { exchange ->
            try {
                assertEquals("/communications", exchange.requestURI.path)
                assertEquals("POST", exchange.requestMethod); assertArrayEquals(byteArrayOf(), exchange.requestBody.readBytes())
                LoopbackDctPeer.respond(exchange, "application/vnd.os-migration.control+proto", byteArrayOf(4, 2))
            } catch (t: Throwable) { error.set(t); exchange.close() }
        }.use { peer ->
            DctPayloadBridge(RuntimeEnvironment.getApplication(), { peer }, { payload ->
                try {
                    assertEquals(2, payload.int(17)); assertEquals(999L, payload.obj(16)!!.long(2)); assertNotEquals(17L, payload.long(1))
                    val descriptor = payload.obj(16)!!.list(7).single().fields[1] as ParcelFileDescriptor
                    DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor))).use {
                        assertEquals(2, it.readInt()); assertEquals(4, it.read()); assertEquals(2, it.read())
                    }
                } catch (t: Throwable) { error.set(t) }
                received.countDown()
            }, { id, status, _, _ -> if (status == 1 && id == 17L) { done.countDown() } }).use {
                assertEquals(0, it.send(request(17L, 999L, byteArrayOf())))
                assertTrue(received.await(5, TimeUnit.SECONDS)); assertTrue(done.await(5, TimeUnit.SECONDS))
            }
        }
        error.get()?.let { throw it }
    }
    @Test fun multipartFileResponsesPreserveExactBytesAndMetadataInOwnedDescriptors() {
        val first = "bytes\r\n--synthetic\r\nstill part one".toByteArray()
        val second = ByteArray(70000) { (it % 251).toByte() }
        val parts = listOf(first, second)
        val body = java.io.ByteArrayOutputStream().apply {
            parts.forEachIndexed { index, data ->
                write(("--synthetic\r\nContent-Type: application/octet-stream\r\n" +
                    "Content-Length: ${data.size}\r\nX-OS-Migration-Metadata: base64; ${if (index == 0) "AQI=" else "AwQ="}\r\n\r\n").toByteArray())
                write(data); write("\r\n".toByteArray())
            }
            write("--synthetic--\r\n".toByteArray())
        }.toByteArray()
        val delivered = CountDownLatch(1); val error = AtomicReference<Throwable>(); val actual = java.util.concurrent.atomic.AtomicBoolean(false)
        LoopbackDctPeer { LoopbackDctPeer.respond(it, "multipart/mixed; boundary=synthetic", body) }.use { peer ->
            DctPayloadBridge(RuntimeEnvironment.getApplication(), { peer }, { payload ->
                try {
                    actual.set(true); assertEquals(3, payload.int(17)); assertEquals(999L, payload.obj(16)!!.long(2))
                    val files = payload.obj(16)!!.list(5); assertEquals(2, files.size)
                    files.forEachIndexed { index, file ->
                        assertEquals(parts[index].size.toLong(), file.long(3))
                        assertArrayEquals(if (index == 0) byteArrayOf(1, 2) else byteArrayOf(3, 4), file.bytes(1))
                        val fd = file.fields[2] as ParcelFileDescriptor
                        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd.fileDescriptor)).use { input ->
                            assertArrayEquals(parts[index], input.readBytes())
                        }
                    }
                } catch (t: Throwable) { error.set(t) }
                delivered.countDown()
            }, { _, status, _, _ -> if (status == 2) delivered.countDown() }).use {
                it.send(request(17, 999)); assertTrue(delivered.await(5, TimeUnit.SECONDS)); assertTrue(actual.get())
            }
        }
        error.get()?.let { throw it }
    }

    @Test fun cancelStopsRealInFlightHttpAndReportsOriginalPayloadOnlyOnce() {
        val arrived = CountDownLatch(1); val release = CountDownLatch(1); val cancelled = CountDownLatch(1)
        val events = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Int>>()
        LoopbackDctPeer { exchange -> arrived.countDown(); release.await(3, TimeUnit.SECONDS); exchange.close() }.use { peer ->
            DctPayloadBridge(RuntimeEnvironment.getApplication(), { peer }, { error("Cancelled response delivered") }, { id, status, _, _ ->
                events.add(id to status); if (status == 4) cancelled.countDown()
            }).use {
                it.send(request(71, 999)); assertTrue(arrived.await(3, TimeUnit.SECONDS))
                assertEquals(0, it.cancel(71)); assertTrue(cancelled.await(1, TimeUnit.SECONDS)); release.countDown()
                assertEquals(8009, it.cancel(71)); assertEquals(listOf(71L to 3, 71L to 4), events.toList())
            }
        }
    }
}
