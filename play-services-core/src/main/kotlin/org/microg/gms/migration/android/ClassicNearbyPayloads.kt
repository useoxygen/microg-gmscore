/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame
import java.io.Closeable
import java.io.OutputStream

/** Bounded, sequential chunk delivery. Sinks are selected by a trusted owner, never by
 * peer-provided file names. Completion is emitted only after final size and sink close. */
internal class ClassicNearbyPayloads(private val sink: Sink, private val maxBytes: Long) : Closeable {
    interface Sink {
        fun open(header: PayloadTransferFrame.PayloadHeader): OutputStream
        fun progress(id: Long, bytes: Long)
        fun complete(id: Long, bytes: Long)
        fun cancelled(id: Long) { failed(id) }
        fun failed(id: Long)
    }
    private class Pending(val header: PayloadTransferFrame.PayloadHeader, val stream: OutputStream, var size: Long = 0)
    private val pending = linkedMapOf<Long, Pending>()
    // A completed/cancelled ID cannot reopen a sink and overwrite an earlier artifact.
    private val seen = hashSetOf<Long>()
    private var delivered = 0L
    private var closed = false

    @Synchronized fun receive(frame: PayloadTransferFrame) {
        check(!closed)
        require(frame.hasPayloadHeader() && frame.payloadHeader.hasId())
        val header = frame.payloadHeader
        val id = header.id
        if (frame.packetType == PayloadTransferFrame.PacketType.CONTROL) {
            require(frame.hasControlMessage())
            pending.remove(id)?.let { item ->
                try { item.stream.close() } finally { if(frame.controlMessage.event == PayloadTransferFrame.ControlMessage.EventType.PAYLOAD_CANCELED) sink.cancelled(id) else sink.failed(id) }
            }
            return
        }
        if (frame.packetType == PayloadTransferFrame.PacketType.PAYLOAD_ACK) return
        require(frame.packetType == PayloadTransferFrame.PacketType.DATA && frame.hasPayloadChunk())
        require(header.type in listOf(PayloadTransferFrame.PayloadHeader.PayloadType.BYTES,
            PayloadTransferFrame.PayloadHeader.PayloadType.FILE, PayloadTransferFrame.PayloadHeader.PayloadType.STREAM))
        require(header.totalSize in -1..maxBytes)
        if (header.type == PayloadTransferFrame.PayloadHeader.PayloadType.BYTES) require(header.totalSize in 0..ClassicParcelBytes.MAX_BYTES)
        val chunk = frame.payloadChunk
        try {
            val item = pending[id] ?: run {
                require(chunk.offset == 0L && pending.size < MAX_PENDING)
                require(seen.size < MAX_IDS && seen.add(id)) { "Nearby payload ID reused" }
                Pending(header, sink.open(header)).also { pending[id] = it }
            }
            require(header == item.header && chunk.offset == item.size) { "Nearby payload is out of order or changed" }
            require((chunk.flags and 1.inv()) == 0 && chunk.body.size() <= 1048576)
            val length = chunk.body.size().toLong()
            require(length <= maxBytes - delivered)
            require(header.totalSize == -1L || length <= header.totalSize - item.size)
            chunk.body.writeTo(item.stream)
            item.size += length; delivered += length
            if ((chunk.flags and 1) != 0) {
                require(header.totalSize == -1L || item.size == header.totalSize)
                item.stream.close()
                pending.remove(id)
                sink.complete(id, item.size)
            } else sink.progress(id, item.size)
        } catch (e: Exception) {
            pending.remove(id)?.let { runCatching { it.stream.close() } }
            sink.failed(id)
            throw e
        }
    }
    @Synchronized fun cancel(id: Long): Boolean {
        val item=pending.remove(id) ?: return false
        try { item.stream.close() } finally { sink.failed(id) }
        return true
    }
    @Synchronized override fun close() {
        closed = true
        val old = pending.toMap(); pending.clear()
        old.forEach { (id, item) -> runCatching { item.stream.close() }; sink.failed(id) }
    }
    companion object {
        private const val MAX_PENDING = 16
        private const val MAX_IDS = 65536
    }
}
