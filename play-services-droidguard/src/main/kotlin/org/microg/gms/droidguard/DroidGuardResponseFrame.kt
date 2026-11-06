/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard

import android.content.Context
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Random
import java.util.UUID

/**
 * The operations a DroidGuard client measured for one handle, in the order they happened (CYC-1544). Each event after
 * the first carries the whole milliseconds since the event before it, on a monotonic clock; the first carries none.
 * A snapshot works on a [copy] of its handle's init trace, so every snapshot reports the time since the handle opened.
 */
class DroidGuardClientTrace(private val clockNanos: () -> Long) {
    private class Event(val type: Int, val elapsedMillis: Long?)

    private val events = ArrayList<Event>()
    private var lastNanos: Long? = null

    @Synchronized
    fun record(type: Int) {
        val now = clockNanos()
        events += Event(type, lastNanos?.let { (now - it) / 1_000_000 })
        lastNanos = now
    }

    @Synchronized
    fun copy(): DroidGuardClientTrace {
        val copy = DroidGuardClientTrace(clockNanos)
        copy.events += events
        copy.lastNanos = lastNanos
        return copy
    }

    /** The trace message: repeated field 1 events, each with event type field 1 and elapsed milliseconds field 2. */
    @Synchronized
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        for (event in events) {
            val encoded = ByteArrayOutputStream()
            encoded.writeVarintField(1, event.type.toLong())
            event.elapsedMillis?.let { encoded.writeVarintField(2, it) }
            out.writeBytesField(1, encoded.toByteArray())
        }
        return out.toByteArray()
    }

    companion object {
        /** Event types as Play services' DroidGuard client serializes them. */
        const val INIT_START = 1
        const val INIT_END = 12
        const val SNAPSHOT_START = 13
        const val SNAPSHOT_END = 14

        /** A trace whose first event, [INIT_START], is recorded now. */
        @JvmStatic
        fun start(): DroidGuardClientTrace = DroidGuardClientTrace { System.nanoTime() }.apply { record(INIT_START) }
    }
}

/**
 * The frame Play services' DroidGuard client puts around the raw bytes its service's snapshot returns, before base64
 * (CYC-1544): an eight-byte header field 1 (six random bytes, one adjusted so the XOR of all eight encoded bytes is 3),
 * the raw response unchanged in field 2, the client's [DroidGuardClientTrace] in field 3 and, when available, the
 * client's own locally generated UUID in field 5. Only [FRAMED_FLOW] is framed; every other flow keeps the raw bytes.
 * Error markers and empty responses are never framed, so callers still recognize them.
 */
object DroidGuardResponseFrame {
    const val FRAMED_FLOW = "recaptchabase-android"

    private const val HEADER_SIZE = 8
    private const val HEADER_CHECKSUM = 3
    private const val UUID_SIZE = 16
    private const val PREFERENCES = "dg_shared_preferences"
    private const val KEY_CLIENT_UUID = "client_uuid"
    private val ERROR_MARKER = "ERROR : ".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()
    private val uuidLock = Any()

    /** A trace started now for a [flow] whose responses are framed, or null. */
    @JvmStatic
    fun startTrace(flow: String?): DroidGuardClientTrace? = if (flow == FRAMED_FLOW) DroidGuardClientTrace.start() else null

    /** Whether [raw], a service's snapshot response, is a usable response this client frames. */
    @JvmStatic
    fun shouldFrame(raw: ByteArray?): Boolean =
        raw != null && raw.isNotEmpty() && !startsWith(raw, ERROR_MARKER) && !isFramed(raw)

    @JvmStatic
    fun frame(raw: ByteArray, trace: DroidGuardClientTrace, clientUuid: ByteArray?): ByteArray =
        frame(raw, trace, clientUuid, random)

    @JvmStatic
    fun frame(raw: ByteArray, trace: DroidGuardClientTrace, clientUuid: ByteArray?, random: Random): ByteArray {
        val out = ByteArrayOutputStream()
        val header = ByteArray(HEADER_SIZE)
        header[0] = 0x0a
        header[1] = 6
        val body = ByteArray(6).also { random.nextBytes(it) }
        System.arraycopy(body, 0, header, 2, body.size)
        header[HEADER_SIZE - 1] = (header[HEADER_SIZE - 1].toInt() xor xorOf(header, HEADER_SIZE) xor HEADER_CHECKSUM).toByte()
        out.write(header)
        out.writeBytesField(2, raw)
        out.writeBytesField(3, trace.encode())
        if (clientUuid != null && clientUuid.size == UUID_SIZE) out.writeBytesField(5, clientUuid)
        return out.toByteArray()
    }

    /**
     * Whether [bytes] already carry this frame: a valid header, then only well-formed fields after it, with no second
     * field 1 and exactly one length-delimited field 2.
     */
    @JvmStatic
    fun isFramed(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER_SIZE || bytes[0] != 0x0a.toByte() || bytes[1] != 6.toByte()) return false
        if (xorOf(bytes, HEADER_SIZE) != HEADER_CHECKSUM) return false
        var pos = HEADER_SIZE
        var responses = 0
        fun readVarint(): Long? {
            var result = 0L
            var shift = 0
            while (pos < bytes.size && shift < 64) {
                val b = bytes[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return null
        }
        while (pos < bytes.size) {
            val tag = readVarint() ?: return false
            val number = tag ushr 3
            val wireType = (tag and 7).toInt()
            if (number <= 1 || number > Int.MAX_VALUE) return false
            when (wireType) {
                0 -> readVarint() ?: return false
                1 -> pos += 8
                2 -> {
                    val length = readVarint() ?: return false
                    if (length < 0 || length > bytes.size - pos) return false
                    pos += length.toInt()
                    if (number == 2L) responses++
                }
                5 -> pos += 4
                else -> return false
            }
            if (number == 2L && wireType != 2) return false
            if (pos > bytes.size) return false
        }
        return responses == 1
    }

    /**
     * This client's own UUID, as its most and then least significant 64 bits, big-endian. It is generated here once,
     * kept in this app's private preferences, and null if it cannot be read or kept.
     */
    @JvmStatic
    fun clientUuid(context: Context): ByteArray? = try {
        synchronized(uuidLock) {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val stored = preferences.getString(KEY_CLIENT_UUID, null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val uuid = stored ?: UUID.randomUUID().takeIf { preferences.edit().putString(KEY_CLIENT_UUID, it.toString()).commit() }
            uuid?.let { ByteBuffer.allocate(UUID_SIZE).putLong(it.mostSignificantBits).putLong(it.leastSignificantBits).array() }
        }
    } catch (e: RuntimeException) {
        null
    }

    private fun xorOf(bytes: ByteArray, count: Int): Int {
        var xor = 0
        for (i in 0 until count) xor = xor xor (bytes[i].toInt() and 0xff)
        return xor
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
        bytes.size >= prefix.size && (prefix.indices).all { bytes[it] == prefix[it] }
}

private fun ByteArrayOutputStream.writeVarint(value: Long) {
    var v = value
    while (v and 0x7fL.inv() != 0L) {
        write(((v and 0x7f) or 0x80).toInt())
        v = v ushr 7
    }
    write(v.toInt())
}

private fun ByteArrayOutputStream.writeVarintField(number: Int, value: Long) {
    writeVarint((number shl 3).toLong())
    writeVarint(value)
}

private fun ByteArrayOutputStream.writeBytesField(number: Int, value: ByteArray) {
    writeVarint((number shl 3 or 2).toLong())
    writeVarint(value.size.toLong())
    write(value)
}
