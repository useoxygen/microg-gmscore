/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.payload

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Response
import org.microg.gms.migration.controller.DctControlChannel
import org.microg.gms.migration.controller.DctFailure
import org.microg.gms.migration.nearby.DctRecord
import org.microg.gms.migration.nearby.DctSchemas
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** requestId correlates HTTP responses; payloadId belongs only to transfer accounting. */
internal class DctPayloadBridge(private val context: Context, private val channel: () -> DctHttpPeer?,
                                private val received: (DctRecord) -> Unit,
                                private val update: (Long, Int, Long, Long) -> Unit,
                                private val notifyPeer: ((Long, String, ByteArray, () -> Unit) -> Boolean)? = null,
                                private val cancelNotice: ((Long) -> Boolean)? = null) : Closeable {
    private val server = DctServerPayloads(context, received, update)
    fun incoming(request: DctHttpRequest): DctHttpReply = server.incoming(request)
    private val work = Executors.newFixedThreadPool(2)
    private val calls = ConcurrentHashMap<Long, Call>()
    private val requestIds = ConcurrentHashMap<Long, Long>()
    private val owned = ConcurrentHashMap<Long, DctRecord>()
    private val notices = ConcurrentHashMap.newKeySet<Long>()
    private val nextId = AtomicLong(System.nanoTime())
    @Volatile private var closed = false
    @Synchronized fun send(payload: DctRecord): Int {
        if (closed) { payload.close(); return 8005 }
        if (payload.int(17) in 2..3) return server.send(payload)
        if (channel() == null && payload.int(17) == 4 && notifyPeer != null) {
            try { val message = requireNotNull(payload.obj(18)); val body = bytes(message, 2, -1) ?: byteArrayOf()
                val id = payload.long(1)
                if (!notices.add(id)) return 8009
                val ok = notifyPeer.invoke(id, requireNotNull(message.text(1)), body) { if (notices.remove(id) && !closed) update(id, 1, body.size.toLong(), body.size.toLong()) }
                if (!ok) notices.remove(id)
                return if (ok) 0 else 8013
            } finally { payload.close() }
        }
        if (channel() == null) { payload.close(); return 8013 }
        if (owned.size >= 16) { payload.close(); return 8013 }
        val id = payload.long(1)
        if (owned.putIfAbsent(id, payload) != null) { payload.close(); return 8009 }
        work.execute {
            var length = 0L
            try {
                if (closed || owned[id] !== payload) return@execute
                val http = channel() ?: throw DctFailure(8005)
                val call = when (payload.int(17)) {
                    1 -> {
                        val request = requireNotNull(payload.obj(15))
                        val requestId = request.long(1)
                        check(requestIds.putIfAbsent(requestId, id) == null) { "Duplicate requestId" }
                        val path = requireNotNull(request.fields[2] as? Uri)
                        require(path.scheme == null && path.authority == null && path.fragment == null)
                        val body = bytes(payload, 11, 3); length = body?.size?.toLong() ?: 0L
                        val headers = request.list(4).map { requireNotNull(it.text(1)) to requireNotNull(it.text(2)) }
                        // DCT (legacyType=0) uses the application URI verbatim. /_callback is legacy DATA_GENERAL.
                        http.request(path.toString(), request.text(3), body, headers)
                    }
                    4 -> {
                        val message = requireNotNull(payload.obj(18))
                        val body = bytes(message, 2, -1) ?: byteArrayOf(); length = body.size.toLong()
                        http.request("/_callback", requireNotNull(message.text(1)), body)
                    }
                    // A response needs a retained incoming HTTP request; no such request exists in client role.
                    2, 3 -> throw DctFailure(8009)
                    else -> throw DctFailure(8013)
                }
                calls[id] = call
                if (closed || !owned.containsKey(id)) { call.cancel(); return@execute }
                update(id, 3, length, 0)
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("DCT application HTTP ${response.code}")
                    if (payload.int(17) == 1) {
                        val requestId = requireNotNull(payload.obj(15)).long(1)
                        fun deliver(result: DctRecord) { result.use {
                            if (owned.containsKey(id) && !closed) {
                                received(result)
                                val data = requireNotNull(result.obj(16))
                                val size = if (result.int(17) == 3) data.list(5).sumOf { it.long(3) }
                                    else data.list(7).sumOf { ((it.fields[1] as ParcelFileDescriptor).statSize - 4).coerceAtLeast(0) }
                                update(result.long(1), 1, size, size)
                            }
                        } }
                        val type = response.header("Content-Type") ?: "application/octet-stream"
                        if (type.startsWith("multipart/mixed", true)) multipart(response, requestId, type, ::deliver)
                        else deliver(responsePayload(response, requestId))
                    } else DctControlChannel.bounded(response)
                }
                if (owned.remove(id, payload)) update(id, 1, length, length)
            } catch (e: Exception) {
                if (owned.remove(id, payload) && !closed) update(id, 2, length, 0)
            } finally {
                calls.remove(id); requestIds.entries.removeAll { it.value == id }; owned.remove(id, payload); payload.close()
            }
        }
        return 0
    }
    fun cancel(id: Long): Int {
        if (notices.remove(id)) { cancelNotice?.invoke(id); update(id, 4, 0, 0); return 0 }
        val payload = owned.remove(id) ?: return server.cancel(id)
        calls.remove(id)?.cancel()
        update(id, 4, 0, 0)
        // The worker owns descriptor cleanup, including jobs queued before cancellation.
        return 0
    }
    private fun responsePayload(response: Response, requestId: Long): DctRecord {
        val type = response.header("Content-Type") ?: "application/octet-stream"
        if (response.header("X-OS-Migration-Metadata") != null) {
            val value = requireNotNull(response.header("X-OS-Migration-Metadata")); require(value.startsWith("base64; "))
            val metadata = android.util.Base64.decode(value.substring(8), android.util.Base64.NO_WRAP)
            val body = requireNotNull(response.body); val length = body.contentLength()
            require(length in 0..(4L * 1024 * 1024 * 1024) && length + 64 * 1024 * 1024 < context.cacheDir.usableSpace)
            val file = File.createTempFile("dct-", ".part", context.cacheDir)
            try {
                body.byteStream().use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var remaining = length
                    while (remaining > 0) { val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt()); if (n <= 0) throw IOException("Truncated DCT file"); output.write(buffer, 0, n); remaining -= n }
                    if (input.read() != -1) throw IOException("DCT file exceeds declared length")
                } }
                val part = DctRecord(DctSchemas.file, mapOf(1 to metadata, 2 to ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 3 to length))
                return DctRecord(DctSchemas.payload, mapOf(1 to nextId.incrementAndGet(), 2 to 0, 17 to 3,
                    16 to DctRecord(DctSchemas.response, mapOf(1 to true, 2 to requestId, 3 to type, 5 to listOf(part)))))
            } finally { file.delete() }
        }
        val data = DctControlChannel.bounded(response, DctRecord.MAX_BYTES)
        return DctRecord(DctSchemas.payload, mapOf(1 to nextId.incrementAndGet(), 2 to 0, 17 to 2,
            16 to DctRecord(DctSchemas.response, mapOf(1 to true, 2 to requestId, 3 to type,
                7 to listOf(fdBytes(data))))))
    }
    private fun multipart(response: Response, requestId: Long, contentType: String, deliver: (DctRecord) -> Unit) {
        val boundary = contentType.toMediaType().parameter("boundary") ?: throw IOException("Missing multipart boundary")
        require(boundary.matches(Regex("[A-Za-z0-9_-]{1,70}")))
        val source = requireNotNull(response.body).source()
        val opening = source.readUtf8LineStrict(256)
        if (opening == "--$boundary--") {
            deliver(DctRecord(DctSchemas.payload, mapOf(1 to nextId.incrementAndGet(), 17 to 2,
                16 to DctRecord(DctSchemas.response, mapOf(1 to true, 2 to requestId, 3 to "application/octet-stream", 6 to true)))))
            return
        }
        require(opening == "--$boundary")
        var total = 0L
        while (true) {
            val headers = linkedMapOf<String, String>(); var headerBytes = 0
            while (true) {
                val line = source.readUtf8LineStrict(8192); headerBytes += line.length + 2; require(headerBytes <= 32768)
                if (line.isEmpty()) break
                val split = line.indexOf(':'); require(split > 0)
                val key = line.substring(0, split).lowercase(); require(key !in headers)
                headers[key] = line.substring(split + 1).trim()
            }
            val length = headers["content-length"]?.toLongOrNull() ?: throw IOException("Missing part length")
            val filePart = headers.containsKey("x-os-migration-metadata")
            require(length in 0..(if (filePart) 4L * 1024 * 1024 * 1024 else DctRecord.MAX_BYTES.toLong()))
            total = Math.addExact(total, length); require(total <= 32L * 1024 * 1024 * 1024)
            require(length + 64 * 1024 * 1024 < context.cacheDir.usableSpace)
            val metadata = headers["x-os-migration-metadata"]?.let {
                require(it.startsWith("base64; ")); android.util.Base64.decode(it.substring(8), android.util.Base64.NO_WRAP)
            } ?: byteArrayOf()
            val file = File.createTempFile("dct-", ".part", context.cacheDir)
            try {
                DataOutputStream(file.outputStream()).use { out ->
                    if (!filePart) out.writeInt(length.toInt())
                    var remaining = length; val buffer = ByteArray(64 * 1024)
                    while (remaining > 0) {
                        val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count <= 0) throw IOException("Truncated DCT part")
                        out.write(buffer, 0, count); remaining -= count
                    }
                }
                require(source.readUtf8LineStrict(2).isEmpty())
                val last = when (source.readUtf8LineStrict(256)) {
                    "--$boundary--" -> true
                    "--$boundary" -> false
                    else -> throw IOException("Invalid DCT part boundary")
                }
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val part = if (filePart) DctRecord(DctSchemas.file, mapOf(1 to metadata, 2 to fd, 3 to length))
                    else DctRecord(DctSchemas.parcelBytes, mapOf(1 to fd))
                val type = headers["content-type"] ?: "application/octet-stream"
                deliver(DctRecord(DctSchemas.payload, mapOf(1 to nextId.incrementAndGet(), 2 to 0, 17 to (if (filePart) 3 else 2),
                    16 to DctRecord(DctSchemas.response, mapOf(1 to last, 2 to requestId, 3 to type, 6 to true,
                        (if (filePart) 5 else 7) to listOf(part))))))
                if (last) return
            } finally { file.delete() }
        }
    }
    fun oneWay(type: String, data: ByteArray) {
        if (closed) return
        DctRecord(DctSchemas.payload, mapOf(1 to nextId.incrementAndGet(), 2 to 0, 17 to 4,
            18 to DctRecord(DctSchemas.oneWay, mapOf(1 to type, 2 to fdBytes(data))))).use {
            received(it); update(it.long(1), 1, data.size.toLong(), data.size.toLong())
        }
    }
    private fun fdBytes(data: ByteArray): DctRecord {
        val file = File.createTempFile("dct-", ".bytes", context.cacheDir)
        try {
            DataOutputStream(file.outputStream()).use { it.writeInt(data.size); it.write(data) }
            return DctRecord(DctSchemas.parcelBytes, mapOf(1 to ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)))
        } finally { file.delete() }
    }
    private fun bytes(record: DctRecord, fdField: Int, inline: Int): ByteArray? {
        val wrapped = record.obj(fdField) ?: return record.bytes(inline)
        val descriptor = requireNotNull(wrapped.fields[1] as? ParcelFileDescriptor)
        return DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor))).use {
            val length = it.readInt(); require(length in 0..DctRecord.MAX_BYTES)
            ByteArray(length).also(it::readFully)
        }
    }
    override fun close() {
        closed = true; notices.forEach { cancelNotice?.invoke(it) }; notices.clear(); server.close(); calls.values.forEach { it.cancel() }; calls.clear()
        work.shutdown() // queued workers still run descriptor cleanup
    }
}
