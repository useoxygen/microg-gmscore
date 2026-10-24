/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.controller

import okhttp3.RequestBody.Companion.toRequestBody
import org.microg.gms.migration.dct.*
import org.microg.gms.migration.payload.*
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Server-side notifications retain a message until the next consumed-message ACK. */
internal class DctServerControl(private val model: String, private val manufacturer: String,
    private val application: (DctHttpRequest) -> DctHttpReply, private val failure: (Int) -> Unit) : Closeable {
    private data class Message(val type: String, val bytes: ByteArray, val consumed: () -> Unit, val id: Long)
    private val queue = ArrayBlockingQueue<Message>(8)
    private val polling = Any()
    private var retained: Message? = null
    @Volatile private var closed = false
    fun oneWay(type: String, bytes: ByteArray, id: Long = 0, consumed: () -> Unit = {}): Boolean = !closed && bytes.size <= 16 * 1024 * 1024 && queue.offer(Message(type, bytes.copyOf(), consumed, id))
    fun cancel(id: Long): Boolean {
        if (queue.removeIf { it.id == id }) return true
        synchronized(polling) { if (retained?.id == id) { retained = null; return true } }
        return false
    }
    private fun send(frame: DctControlFrame): DctHttpReply = reply(DctOfflineFrame(control = frame).encode())
    private fun reply(bytes: ByteArray = byteArrayOf()) = DctHttpReply(DctControlChannel.CONTROL_TYPE, bytes.toRequestBody())
    fun handle(request: DctHttpRequest): DctHttpReply {
        check(!closed)
        return when (request.path.substringBefore('?')) {
            "/notifications" -> synchronized(polling) {
                require(request.method == "GET" && request.path in listOf("/notifications", "/notifications?ack"))
                if (request.path.endsWith("?ack")) { retained?.consumed?.invoke(); retained = null }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
                while (retained == null && !closed && System.nanoTime() < deadline) retained = queue.poll(100, TimeUnit.MILLISECONDS)
                val message = retained
                if (message == null) reply() else DctHttpReply(message.type, message.bytes.toRequestBody())
            }
            "/capabilities" -> {
                require(request.method == "POST" && request.headers["content-type"]?.substringBefore(';') == DctControlChannel.CONTROL_TYPE); DeviceCapabilityRequest.ADAPTER.decode(requireNotNull(request.bytes))
                reply(DeviceCapabilityResponse(field1 = NcmCapabilities()).encode())
            }
            "/controls" -> {
                require(request.method == "POST" && request.headers["content-type"]?.substringBefore(';') == DctControlChannel.CONTROL_TYPE)
                val control = DctOfflineFrame.ADAPTER.decode(requireNotNull(request.bytes)).control ?: throw IOException("Missing control")
                when {
                    control.device_info_request != null -> send(DctControlFrame(device_info_response = DeviceInfoResponse(device_model = model, manufacturer = manufacturer, platform = 1)))
                    control.medium_closure_request != null -> send(DctControlFrame(medium_closure_response = MediumClosureResponse()))
                    control.disconnection_request != null -> { val result = send(DctControlFrame(disconnection_response = DisconnectionResponse())); result.copy(dispose = { failure(8005) }) }
                    // Integrity was completed on the initial authenticated link; never fabricate a second verdict.
                    control.integrity_start_request != null -> throw DctFailure(8018)
                    control.medium_negotiation_request != null -> send(DctControlFrame(medium_negotiation_response = WirelessMediumNegotiationResponse()))
                    else -> { DctControlChannel.field(control); reply() }
                }
            }
            else -> application(request)
        }
    }
    override fun close() { closed = true; queue.clear(); synchronized(polling) { retained = null } }
}
