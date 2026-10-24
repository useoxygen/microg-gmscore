/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.controller

import okhttp3.Call
import okhttp3.Response
import org.microg.gms.migration.dct.*
import okio.ByteString.Companion.encodeUtf8
import org.microg.gms.migration.payload.DctHttpChannel
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Polling is independent of request/response waits, including the slow Integrity provider. */
internal class DctControlChannel(private val http: org.microg.gms.migration.payload.DctHttpPeer, private val model: String,
                                 private val manufacturer: String, private val salt: String,
                                 private val oneWay: (String, ByteArray) -> Unit,
                                 private val failure: (Int) -> Unit) : DctHandshake.Transport, Closeable {
    private val pending = ConcurrentHashMap<Int, ArrayBlockingQueue<DctControlFrame>>()
    private val poller = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    fun start() { poller.execute {
        var ack = false
        try {
            while (!closed) {
                http.notifications(ack).execute().use { response ->
                    val bytes = bounded(response)
                    ack = false
                    if (bytes.isNotEmpty()) {
                        val type = response.header("Content-Type")?.substringBefore(';') ?: throw IOException("Missing notification type")
                        if (type == CONTROL_TYPE) receive(DctOfflineFrame.ADAPTER.decode(bytes).control ?: throw IOException("Missing DCT control"))
                        else oneWay(type, bytes)
                        ack = true // only acknowledge a completely consumed/delivered message
                    }
                }
            }
        } catch (e: Exception) { if (!closed) failure(if (e is javax.net.ssl.SSLHandshakeException) 8016 else 8012) }
    } }
    private fun receive(frame: DctControlFrame) {
        when {
            frame.device_info_request != null -> send(DctControlFrame(device_info_response = DeviceInfoResponse(
                device_model = model, platform = 1, manufacturer = manufacturer)))
            frame.integrity_start_request != null -> send(DctControlFrame(integrity_start_response = DeviceIntegrityStartResponse(
                challenge = IntegrityChallenge(salt = salt.encodeUtf8()))))
            frame.disconnection_request != null -> {
                send(DctControlFrame(disconnection_response = DisconnectionResponse())); failure(8005)
            }
            frame.medium_negotiation_request != null -> {
                // This endpoint currently subscribes; an empty response honestly declines hosting/other media.
                send(DctControlFrame(medium_negotiation_response = WirelessMediumNegotiationResponse()))
            }
            frame.medium_closure_request != null -> send(DctControlFrame(medium_closure_response = MediumClosureResponse()))
            frame.wifi_credential_request != null -> throw DctFailure(8017) // no fabricated Wi-Fi credential/status
            else -> {
                val id = field(frame)
                if (!pending.computeIfAbsent(id) { ArrayBlockingQueue(8) }.offer(frame)) throw IOException("DCT control queue full")
            }
        }
    }
    override fun send(frame: DctControlFrame) {
        if (closed) throw IOException("DCT channel closed")
        http.control(DctOfflineFrame(control = frame).encode()).execute().use { response ->
            val bytes = bounded(response)
            if (bytes.isNotEmpty()) receive(DctOfflineFrame.ADAPTER.decode(bytes).control ?: throw IOException("Missing DCT control"))
        }
    }
    override fun exchange(frame: DctControlFrame, expectedField: Int): DctControlFrame { send(frame); return await(expectedField) }
    override fun await(expectedField: Int): DctControlFrame {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        val queue = pending.computeIfAbsent(expectedField) { ArrayBlockingQueue(8) }
        while (!closed && System.nanoTime() < deadline) queue.poll(100, TimeUnit.MILLISECONDS)?.let { return it }
        throw DctFailure(if (expectedField in 11..14) 8018 else 8012)
    }
    override fun capabilities(request: DeviceCapabilityRequest): DeviceCapabilityResponse =
        http.capabilities(request.encode()).execute().use { DeviceCapabilityResponse.ADAPTER.decode(bounded(it)) }
    override fun close() { closed = true; (http as? Closeable)?.close(); poller.shutdownNow(); pending.clear() }
    companion object {
        const val CONTROL_TYPE = "application/vnd.dct.control+proto"
        fun bounded(response: Response, max: Int = 1024 * 1024): ByteArray {
            if (!response.isSuccessful) throw IOException("DCT HTTP ${response.code}")
            val body = response.body ?: return byteArrayOf()
            if (body.contentLength() > max) throw IOException("DCT response too large")
            val source = body.source()
            if (source.request(max.toLong() + 1)) throw IOException("DCT response too large")
            return source.readByteArray()
        }
        fun field(frame: DctControlFrame): Int = when {
            frame.device_info_response != null -> 2
            frame.medium_negotiation_response != null -> 4
            frame.medium_closure_response != null -> 6
            frame.disconnection_response != null -> 8
            frame.wifi_credential_response != null -> 10
            frame.integrity_start_response != null -> 12
            frame.integrity_attestation != null -> 13
            frame.integrity_attestation_ack != null -> 14
            else -> throw IOException("Unsupported DCT control")
        }
    }
}
