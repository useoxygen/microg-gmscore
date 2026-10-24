/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.controller

import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.migration.dct.*
import org.microg.gms.migration.payload.*

class DctServerControlTest {
    private fun request(path: String, bytes: ByteArray? = null) = DctHttpRequest(if (bytes == null) "GET" else "POST", path,
        mapOf("content-type" to DctControlChannel.CONTROL_TYPE), bytes)
    private fun DctHttpReply.bytes() = Buffer().also { body.writeTo(it) }.readByteArray()
    @Test fun publisherRetainsNotificationUntilAcknowledgedAndDoesNotAcknowledgeOnQueueing() {
        var consumed = 0
        DctServerControl("target", "Cyclon", { error("Unexpected application request") }, {}).use { peer ->
            assertTrue(peer.oneWay("application/test", byteArrayOf(7)) { consumed++ })
            assertTrue(peer.oneWay("application/test", byteArrayOf(8)))
            assertArrayEquals(byteArrayOf(7), peer.handle(request("/notifications")).bytes()); assertEquals(0, consumed)
            assertArrayEquals(byteArrayOf(7), peer.handle(request("/notifications")).bytes()); assertEquals(0, consumed)
            assertArrayEquals(byteArrayOf(8), peer.handle(request("/notifications?ack")).bytes()); assertEquals(1, consumed)
        }
    }
    @Test fun publisherAnswersInfoAndCapabilitiesButNeverFakesASecondAttestation() {
        DctServerControl("target", "Cyclon", { error("Unexpected application request") }, {}).use { peer ->
            val info = DctOfflineFrame.ADAPTER.decode(peer.handle(request("/controls", DctOfflineFrame(control = DctControlFrame(device_info_request = DeviceInfoRequest())).encode())).bytes())
            assertEquals("target", info.control!!.device_info_response!!.device_model)
            val capabilities = DeviceCapabilityResponse.ADAPTER.decode(peer.handle(request("/capabilities", DeviceCapabilityRequest().encode())).bytes())
            assertNotNull(capabilities.field1); assertFalse(capabilities.field1!!.host_support)
            val failure = assertThrows(DctFailure::class.java) { peer.handle(request("/controls", DctOfflineFrame(control = DctControlFrame(integrity_start_request = DeviceIntegrityStartRequest(skip = IntegritySkip()))).encode())) }
            assertEquals(8018, failure.status)
        }
    }
}
