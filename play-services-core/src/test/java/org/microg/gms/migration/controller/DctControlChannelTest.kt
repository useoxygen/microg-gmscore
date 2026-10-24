/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.controller

import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.migration.dct.*
import org.microg.gms.migration.payload.LoopbackDctPeer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DctControlChannelTest {
    @Test fun realLoopbackUsesExactControlBodyAndSeparateCapabilities() {
        val error = AtomicReference<Throwable>()
        LoopbackDctPeer { exchange ->
            try {
                assertEquals("v1", exchange.requestHeaders.getFirst("x-dct-version"))
                assertEquals(DctControlChannel.CONTROL_TYPE, exchange.requestHeaders.getFirst("Content-Type"))
                assertEquals("POST", exchange.requestMethod)
                val body = exchange.requestBody.readBytes()
                val response = if (exchange.requestURI.path == "/controls") {
                    assertNotNull(DctOfflineFrame.ADAPTER.decode(body).control!!.device_info_request)
                    DctOfflineFrame(control = DctControlFrame(device_info_response = DeviceInfoResponse(platform = 2))).encode()
                } else {
                    assertNotNull(DeviceCapabilityRequest.ADAPTER.decode(body).field1)
                    DeviceCapabilityResponse(field1 = NcmCapabilities()).encode()
                }
                LoopbackDctPeer.respond(exchange, DctControlChannel.CONTROL_TYPE, response)
            } catch (t: Throwable) { error.set(t); exchange.close() }
        }.use { peer ->
            DctControlChannel(peer, "model", "maker", "salt", { _, _ -> error("Unexpected app data") }, { error("Unexpected failure") }).use {
                assertEquals(2, it.exchange(DctControlFrame(device_info_request = DeviceInfoRequest(platform = 1)), 2).device_info_response!!.platform)
                assertNotNull(it.capabilities(DeviceCapabilityRequest(field1 = NcmCapabilities())).field1)
            }
        }
        error.get()?.let { throw it }
    }
    @Test fun pollingAcknowledgesOnlyAfterDeliveryAndStopsOnClose() {
        val delivered = CountDownLatch(1); val acknowledged = CountDownLatch(1)
        val queries = ArrayBlockingQueue<Boolean>(4)
        val failed = AtomicReference<Int>()
        LoopbackDctPeer { exchange ->
            val ack = exchange.requestURI.rawQuery == "ack"
            queries.offer(ack)
            if (ack) {
                if (delivered.count == 0L) acknowledged.countDown()
                Thread.sleep(1000)
                exchange.sendResponseHeaders(204, -1); exchange.close()
            } else LoopbackDctPeer.respond(exchange, "application/vnd.os-migration.control+proto", byteArrayOf(7, 9))
        }.use { peer ->
            val controls = DctControlChannel(peer, "model", "maker", "salt", { type, bytes ->
                assertEquals("application/vnd.os-migration.control+proto", type); assertArrayEquals(byteArrayOf(7, 9), bytes); delivered.countDown()
            }, { failed.set(it) })
            controls.start()
            assertTrue(delivered.await(3, TimeUnit.SECONDS)); assertTrue(acknowledged.await(3, TimeUnit.SECONDS))
            controls.close()
            assertEquals(false, queries.poll()); assertEquals(true, queries.poll()); assertNull(failed.get())
        }
    }
}
