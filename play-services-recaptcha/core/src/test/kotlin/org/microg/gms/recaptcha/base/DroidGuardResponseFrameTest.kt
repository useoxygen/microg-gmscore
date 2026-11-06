/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.recaptcha.base

import android.content.Context
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.droidguard.DroidGuardClientTrace
import org.microg.gms.droidguard.DroidGuardResponseFrame
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Random
import java.util.UUID

/** One decoded trace event: its type and, when present, its elapsed milliseconds. */
internal data class TraceEvent(val type: Long, val elapsedMillis: Long?)

/** A client response frame decoded with the independent [parseProto], checking the frame's fixed structure. */
internal class DecodedFrame(val header: ByteArray, val response: ByteArray, val trace: List<TraceEvent>, val clientUuid: ByteArray?)

internal fun decodeFrame(bytes: ByteArray): DecodedFrame {
    // The header is first: tag 1/length-delimited, length 6, and the XOR of all eight encoded bytes is 3.
    assertEquals(0x0a, bytes[0].toInt() and 0xff)
    assertEquals(6, bytes[1].toInt())
    val checksum: Int = (0 until 8).fold(0) { acc, i -> acc xor (bytes[i].toInt() and 0xff) }
    assertEquals(3, checksum)
    val fields = parseProto(bytes)
    val numbers = fields.numbers()
    assertTrue("fields were $numbers", numbers == listOf(1, 2, 3) || numbers == listOf(1, 2, 3, 5))
    fields.forEach { assertEquals("field ${it.number} wire type", 2, it.wireType) }
    val trace = parseProto(fields.single(3).bytes!!).map { entry ->
        assertEquals(1, entry.number)
        val event = parseProto(entry.bytes!!)
        assertTrue("event fields were ${event.numbers()}", event.numbers() == listOf(1) || event.numbers() == listOf(1, 2))
        event.forEach { assertEquals(0, it.wireType) }
        TraceEvent(event.single(1).varint!!, event.firstOrNull { it.number == 2 }?.varint)
    }
    return DecodedFrame(bytes.copyOfRange(0, 8), fields.single(2).bytes!!, trace, fields.firstOrNull { it.number == 5 }?.bytes)
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DroidGuardResponseFrameTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val raw = "synthetic-native-dg-response".toByteArray() + byteArrayOf(0, 0x0a, 0x06, -1)

    @Before
    fun clearPreferences() {
        context.getSharedPreferences("dg_shared_preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** A clock the test moves by whole milliseconds, plus a sub-millisecond remainder that must be truncated. */
    private class FakeClock {
        var nanos = 7_000_000_123L
        fun advanceMillis(millis: Long) {
            nanos += millis * 1_000_000 + 400_000
        }
    }

    private fun initTrace(clock: FakeClock): DroidGuardClientTrace = DroidGuardClientTrace { clock.nanos }.apply {
        record(DroidGuardClientTrace.INIT_START)
        clock.advanceMillis(41)
        record(DroidGuardClientTrace.INIT_END)
    }

    @Test
    fun frameFieldsDecodeIndependently() {
        val clock = FakeClock()
        val init = initTrace(clock)
        // The handle is held for 2 s between init and snapshot; the snapshot itself takes 9 ms.
        clock.advanceMillis(2_000)
        val snapshot = init.copy().apply { record(DroidGuardClientTrace.SNAPSHOT_START) }
        clock.advanceMillis(9)
        snapshot.record(DroidGuardClientTrace.SNAPSHOT_END)
        val uuid = ByteArray(16) { (it + 1).toByte() }

        val frame = decodeFrame(DroidGuardResponseFrame.frame(raw, snapshot, uuid))

        assertArrayEquals(raw, frame.response)
        // Google's coarse events 2, 13, 14, 15 are serialized as 1, 12, 13, 14; the first event has no duration.
        assertEquals(
            listOf(TraceEvent(1, null), TraceEvent(12, 41), TraceEvent(13, 2_000), TraceEvent(14, 9)),
            frame.trace,
        )
        assertArrayEquals(uuid, frame.clientUuid)
        // The init trace the snapshot copied is unchanged, so a later snapshot again measures from init end.
        assertEquals(listOf(TraceEvent(1, null), TraceEvent(12, 41)), decodeFrame(DroidGuardResponseFrame.frame(raw, init, null)).trace)
    }

    @Test
    fun firstEventCarriesNoDurationFieldEvenAtZeroElapsed() {
        val trace = DroidGuardClientTrace { 5L }.apply {
            record(DroidGuardClientTrace.INIT_START)
            record(DroidGuardClientTrace.INIT_END)
        }
        // The second event's zero is present (proto presence matters), the first's is absent.
        assertEquals(listOf(TraceEvent(1, null), TraceEvent(12, 0)), decodeFrame(DroidGuardResponseFrame.frame(raw, trace, null)).trace)
    }

    @Test
    fun headerIsRandomWithChecksumThree() {
        val trace = DroidGuardClientTrace.start()
        val headers = (1..64).map { decodeFrame(DroidGuardResponseFrame.frame(raw, trace, null)).header.toList() }.toSet()
        assertTrue("headers repeat: ${headers.size}", headers.size > 60)
        // Every random draw is adjusted, whatever its bytes are.
        for (seed in 0L until 256L) {
            val header = decodeFrame(DroidGuardResponseFrame.frame(raw, trace, null, Random(seed))).header
            val drawn = ByteArray(6).also { Random(seed).nextBytes(it) }
            assertArrayEquals(drawn.copyOfRange(0, 5), header.copyOfRange(2, 7))
        }
    }

    @Test
    fun uuidIsOmittedWhenUnavailable() {
        val frame = decodeFrame(DroidGuardResponseFrame.frame(raw, DroidGuardClientTrace.start(), null))
        assertNull(frame.clientUuid)
        assertArrayEquals(raw, frame.response)
    }

    @Test
    fun clientUuidIsGeneratedOnceAndStoredAsBigEndianBits() {
        val first = DroidGuardResponseFrame.clientUuid(context)!!
        val stored = context.getSharedPreferences("dg_shared_preferences", Context.MODE_PRIVATE).getString("client_uuid", null)!!
        val uuid = UUID.fromString(stored)
        assertArrayEquals(bigEndian(uuid), first)
        assertArrayEquals(first, DroidGuardResponseFrame.clientUuid(context))
    }

    @Test
    fun storedClientUuidIsMostThenLeastSignificantBitsBigEndian() {
        context.getSharedPreferences("dg_shared_preferences", Context.MODE_PRIVATE).edit()
            .putString("client_uuid", "00112233-4455-6677-8899-aabbccddeeff").commit()
        assertEquals(
            "00112233445566778899aabbccddeeff",
            DroidGuardResponseFrame.clientUuid(context)!!.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun unreadableStoredClientUuidIsOmitted() {
        context.getSharedPreferences("dg_shared_preferences", Context.MODE_PRIVATE).edit().putInt("client_uuid", 7).commit()
        assertNull(DroidGuardResponseFrame.clientUuid(context))
    }

    @Test
    fun onlyUsableUnframedResponsesAreFramed() {
        val framed = DroidGuardResponseFrame.frame(raw, DroidGuardClientTrace.start(), ByteArray(16))
        assertTrue(DroidGuardResponseFrame.isFramed(framed))
        assertFalse(DroidGuardResponseFrame.shouldFrame(framed))
        assertTrue(DroidGuardResponseFrame.shouldFrame(raw))
        assertFalse(DroidGuardResponseFrame.shouldFrame(null))
        assertFalse(DroidGuardResponseFrame.shouldFrame(ByteArray(0)))
        assertFalse(DroidGuardResponseFrame.shouldFrame("ERROR : synthetic failure".toByteArray()))
    }

    @Test
    fun onlyAStructurallyValidFrameCountsAsFramed() {
        val framed = DroidGuardResponseFrame.frame(raw, DroidGuardClientTrace.start(), null)
        val header = framed.copyOfRange(0, 8)
        val response = byteArrayOf(0x12, 3, 1, 2, 3)
        // A valid header alone, or followed by a response, a trace and an unknown field.
        assertFalse(DroidGuardResponseFrame.isFramed(header))
        assertTrue(DroidGuardResponseFrame.isFramed(header + response))
        assertTrue(DroidGuardResponseFrame.isFramed(header + response + byteArrayOf(0x1a, 0) + byteArrayOf(0x20, 5)))
        // A broken checksum, a second header, two responses, a non-bytes response, or a truncated field.
        val badChecksum = framed.copyOf().also { it[7] = (it[7].toInt() xor 1).toByte() }
        assertFalse(DroidGuardResponseFrame.isFramed(badChecksum))
        assertFalse(DroidGuardResponseFrame.isFramed(header + header + response))
        assertFalse(DroidGuardResponseFrame.isFramed(header + response + response))
        assertFalse(DroidGuardResponseFrame.isFramed(header + byteArrayOf(0x10, 3)))
        assertFalse(DroidGuardResponseFrame.isFramed(framed.copyOf(framed.size - 1)))
        // The raw response is not mistaken for a frame, even though it contains 0a 06.
        assertFalse(DroidGuardResponseFrame.isFramed(raw))
    }

    private fun bigEndian(uuid: UUID): ByteArray =
        java.nio.ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
}
