/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.payload

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import org.microg.gms.migration.nearby.DctRecord
import org.microg.gms.migration.nearby.DctSchemas
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

/** Each incoming HTTP request owns one bounded response queue until its terminal multipart batch. */
internal class DctServerPayloads(private val context: Context, private val received: (DctRecord) -> Unit,
    private val update: (Long, Int, Long, Long) -> Unit) : Closeable {
    private class Pending(val payloadId: Long) { val batches = ArrayBlockingQueue<DctRecord>(4); var last = false; var closed = false }
    private val ids = AtomicLong(System.nanoTime())
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val sending = ConcurrentHashMap<Long, Pending>()
    private val payloads = ConcurrentHashMap<Long, DctRecord>()
    @Volatile private var closed = false
    fun incoming(request: DctHttpRequest): DctHttpReply {
        check(!closed && pending.size < 16)
        val requestId = ids.incrementAndGet(); val payloadId = ids.incrementAndGet(); val state = Pending(payloadId)
        pending[requestId] = state
        try {
            val payload = DctRecord(DctSchemas.payload, buildMap {
                put(1, payloadId); put(2, 0); put(17, 1)
                request.bytes?.let { put(11, fdBytes(it)) }
                put(15, DctRecord(DctSchemas.request, buildMap {
                    put(1, requestId); put(2, Uri.parse(request.path))
                    request.headers["content-type"]?.let { put(3, it) }
                    put(4, request.headers.filterKeys { it !in setOf("host", "content-length", "transfer-encoding", "connection", "x-dct-version", "content-type") }
                        .map { DctRecord(DctSchemas.header, mapOf(1 to it.key, 2 to it.value)) })
                }))
            })
            payload.use { received(it) }; update(payloadId, 1, request.bytes?.size?.toLong() ?: 0, request.bytes?.size?.toLong() ?: 0)
            val first = take(state)
            val response = requireNotNull(first.obj(16)); val type = requireNotNull(response.text(3))
            val multipart = response.bool(6)
            val boundary = "dct${java.util.UUID.randomUUID().toString().replace("-", "")}"
            val replyType = if (multipart) "multipart/mixed; boundary=$boundary" else type
            val firstParts = parts(first)
            if (!multipart) require(firstParts.size <= 1)
            val headers = if (!multipart && first.int(17) == 3 && firstParts.isNotEmpty())
                mapOf("X-OS-Migration-Metadata" to "base64; ${android.util.Base64.encodeToString(firstParts[0].metadata, android.util.Base64.NO_WRAP)}") else emptyMap()
            return DctHttpReply(replyType, object : RequestBody() {
                override fun contentType() = replyType.toMediaType()
                override fun contentLength() = if (multipart) -1L else firstParts.sumOf { it.length }
                override fun writeTo(sink: BufferedSink) {
                    var batch = first
                    try {
                        var total = 0L
                        while (true) {
                            if (closed || state.closed) throw IOException("DCT response cancelled")
                            val value = requireNotNull(batch.obj(16)); require(value.bool(6) == multipart)
                            val pieces = if (batch === first) firstParts else parts(batch)
                            var transferred = 0L
                            try {
                                for (part in pieces) {
                                    total = Math.addExact(total, part.length); require(total <= 32L * 1024 * 1024 * 1024)
                                    if (multipart) sink.writeUtf8("--$boundary\r\nContent-Type: ${part.contentType}\r\nContent-Length: ${part.length}\r\n" +
                                        (if (batch.int(17) == 3) "X-OS-Migration-Metadata: base64; ${android.util.Base64.encodeToString(part.metadata, android.util.Base64.NO_WRAP)}\r\n" else "") + "\r\n")
                                    part.open().use { input -> val bytes = ByteArray(65536); var remaining = part.length
                                        while (remaining > 0) { check(!closed && !state.closed); val n = input.read(bytes, 0, minOf(bytes.size.toLong(), remaining).toInt()); if (n <= 0) throw EOFException(); sink.write(bytes, 0, n); remaining -= n; transferred += n }
                                    }
                                    if (multipart) sink.writeUtf8("\r\n")
                                }
                                sink.flush(); update(batch.long(1), 1, transferred, transferred)
                            } catch (e: Exception) { update(batch.long(1), if (state.closed) 4 else 2, pieces.sumOf { it.length }, transferred); throw e }
                            finally { sending.remove(batch.long(1)); payloads.remove(batch.long(1)); batch.close() }
                            if (!multipart || value.bool(1)) break
                            batch = take(state)
                        }
                        if (multipart) sink.writeUtf8("--$boundary--\r\n")
                    } finally { pending.remove(requestId, state); dispose(state) }
                }
            }, headers, dispose = { pending.remove(requestId, state); dispose(state) })
        } catch (e: Exception) { pending.remove(requestId, state); dispose(state); throw e }
    }
    fun send(payload: DctRecord): Int {
        val state = payload.obj(16)?.long(2)?.let(pending::get)
        if (state == null || closed) { payload.close(); return 8009 }
        synchronized(state) {
            if (state.closed || state.last || sending.putIfAbsent(payload.long(1), state) != null) { payload.close(); return 8009 }
            if (state.batches.remainingCapacity() == 0) { sending.remove(payload.long(1)); payload.close(); return 8013 }
            payloads[payload.long(1)] = payload
            update(payload.long(1), 3, 0, 0)
            if (!state.batches.offer(payload)) { sending.remove(payload.long(1)); payloads.remove(payload.long(1)); payload.close(); return 8013 }
            val response = requireNotNull(payload.obj(16)); state.last = !response.bool(6) || response.bool(1)
        }
        return 0
    }
    fun cancel(id: Long): Int {
        val state = sending[id] ?: pending.values.firstOrNull { it.payloadId == id } ?: return 8009
        dispose(state); return 0
    }
    private fun take(state: Pending): DctRecord {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!closed && !state.closed && System.nanoTime() < deadline) state.batches.poll(100, TimeUnit.MILLISECONDS)?.let { return it }
        throw IOException("DCT response cancelled or timed out")
    }
    private fun dispose(state: Pending) { synchronized(state) { state.closed = true
        state.batches.clear()
        sending.entries.filter { it.value === state }.forEach { (id, _) -> sending.remove(id); payloads.remove(id)?.close(); update(id, 4, 0, 0) }
    } }
    private fun parts(payload: DctRecord): List<DctFilePart> {
        val value = requireNotNull(payload.obj(16)); val type = requireNotNull(value.text(3))
        return if (payload.int(17) == 3) value.list(5).map { file ->
            require(file.long(3) in 0..(4L * 1024 * 1024 * 1024))
            DctFilePart(type, file.long(3), file.bytes(1) ?: byteArrayOf()) {
                val descriptor = file.fields[2] as? ParcelFileDescriptor
                val uri = file.fields[4] as? Uri
                when {
                    descriptor != null -> ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor))
                    uri != null -> context.contentResolver.openInputStream(uri) ?: throw IOException("DCT file unavailable")
                    file.long(3) == 0L -> ByteArrayInputStream(byteArrayOf()) // metadata-only/empty file is valid
                    else -> throw IOException("DCT file unavailable")
                }
            }
        } else {
            @Suppress("UNCHECKED_CAST")
            val inline = value.fields[4] as? List<ByteArray> ?: emptyList()
            val data = if (value.list(7).isNotEmpty()) value.list(7).map { item ->
                val fd = item.fields[1] as ParcelFileDescriptor
                DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd.fileDescriptor))).use {
                    val size = it.readInt(); require(size in 0..DctRecord.MAX_BYTES); ByteArray(size).also(it::readFully)
                }
            } else inline
            data.map { bytes -> DctFilePart(type, bytes.size.toLong(), byteArrayOf()) { ByteArrayInputStream(bytes) } }
        }
    }
    private fun fdBytes(bytes: ByteArray): DctRecord {
        val file = File.createTempFile("dct-", ".request", context.cacheDir)
        try { DataOutputStream(file.outputStream()).use { it.writeInt(bytes.size); it.write(bytes) }
            return DctRecord(DctSchemas.parcelBytes, mapOf(1 to ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)))
        } finally { file.delete() }
    }
    override fun close() { closed = true; pending.values.forEach(::dispose); pending.clear() }
}
