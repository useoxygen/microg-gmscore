/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.authzen

import android.util.Log
import okio.Buffer
import java.io.EOFException
import java.util.Locale
import kotlin.random.Random

private const val TAG = "GmsAuthZen"

/** Stages of the native AuthZen route (useoxygen/cyclon#842). */
internal enum class AuthZenEvent {
    /** A SecureGcm `P` message reached GmsCore. */
    INGRESS,
    /** The envelope was refused before any cryptography (sender, size, encoding). */
    ENVELOPE_REJECTED,
    /** Why the route cannot handle prompts yet; logged instead of processing. */
    READINESS,
    /** Lab capture only: field numbers, wire types and size buckets of a message, never values. */
    SHAPE,
    KEY_LOOKUP,
    CRYPTO_VERIFIED,
    CRYPTO_FAILED,
    UNSUPPORTED,
    STORE,
    UI_POSTED,
    NOTIFICATIONS_BLOCKED,
    OWNER_AUTH,
    DECISION,
    SEND,
    RESULT,
}

/**
 * Redacted event log, `event=key_lookup session=1a2b3c4d found=true`. Details may only be booleans, enum constants
 * and counts the caller chose; anything else is written as `?`. Never pass account names, ids, keys, handles,
 * transaction ids, challenge numbers, text or payloads, nor their lengths to the byte (use [sizeBucket]).
 */
internal class AuthZenDiagnostics(private val random: Random = Random.Default, private val sink: (String) -> Unit = { Log.i(TAG, it) }) {
    fun newSession(): String = String.format(Locale.ROOT, "%08x", random.nextInt())

    fun event(event: AuthZenEvent, session: String?, vararg details: Pair<String, Any?>) {
        val line = StringBuilder("event=").append(event.name.lowercase(Locale.ROOT))
        if (session != null) line.append(" session=").append(session)
        for ((key, value) in details) line.append(' ').append(key).append('=').append(render(value))
        sink(line.toString())
    }

    private fun render(value: Any?): String = when (value) {
        null -> "none"
        is Boolean, is Int, is Long -> value.toString()
        is Enum<*> -> value.name.lowercase(Locale.ROOT)
        is ProtoShape -> value.toString()
        else -> "?"
    }
}

/** Rough size, so logs show a payload's shape without its exact length. */
internal fun sizeBucket(length: Int): Int = when {
    length <= 0 -> 0
    length <= 256 -> 256
    length <= 1024 -> 1024
    length <= 4096 -> 4096
    length <= 16384 -> 16384
    else -> 65536
}

/**
 * Top-level protobuf layout of a message: `tag:wiretype:bucket` per field, at most [MAX_FIELDS]. For protocol
 * discovery in lab capture mode. Varint values and field contents are never included, only that a field exists,
 * its wire type and its size bucket.
 */
internal class ProtoShape private constructor(private val fields: List<String>, private val complete: Boolean) {
    override fun toString() = fields.joinToString(",", "[", if (complete) "]" else ",...]")

    companion object {
        private const val MAX_FIELDS = 32

        fun of(bytes: ByteArray): ProtoShape {
            val buffer = Buffer().write(bytes)
            val fields = mutableListOf<String>()
            try {
                while (!buffer.exhausted()) {
                    if (fields.size == MAX_FIELDS) return ProtoShape(fields, false)
                    val key = buffer.readVarint()
                    val tag = key ushr 3
                    val size = when (val wireType = (key and 7).toInt()) {
                        0 -> { buffer.readVarint(); 0 }
                        1 -> { buffer.skip(8); 8 }
                        2 -> buffer.readVarint().toInt().also { if (it < 0) return ProtoShape(fields + "malformed", true); buffer.skip(it.toLong()) }
                        5 -> { buffer.skip(4); 4 }
                        else -> return ProtoShape(fields + "wiretype$wireType", true)
                    }
                    fields += "$tag:${(key and 7)}:${sizeBucket(size)}"
                }
            } catch (e: EOFException) {
                return ProtoShape(fields + "truncated", true)
            }
            return ProtoShape(fields, true)
        }

        private fun Buffer.readVarint(): Long {
            var result = 0L
            for (shift in 0 until 64 step 7) {
                val byte = readByte().toInt()
                result = result or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return result
            }
            throw EOFException()
        }
    }
}
