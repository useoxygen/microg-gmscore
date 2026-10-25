/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.io.ByteArrayOutputStream

/** Bounded deterministic CBOR used by the enclave's signed command and COSE contracts. */
object EnclaveCbor {
    const val MAX = 1024 * 1024
    fun encode(value: Any): ByteArray {
        val out = ByteArrayOutputStream()
        fun head(major: Int, n: Long) {
            if (n < 0) throw ProtocolException()
            if (n < 24) out.write((major shl 5) or n.toInt()) else {
                val width = when { n <= 255 -> 1; n <= 65535 -> 2; n <= 0xffffffffL -> 4; else -> 8 }
                out.write((major shl 5) or when (width) { 1 -> 24; 2 -> 25; 4 -> 26; else -> 27 })
                for (i in width - 1 downTo 0) out.write((n ushr (8 * i)).toInt() and 255)
            }
        }
        fun write(v: Any, depth: Int) {
            if (depth > 16 || out.size() > MAX) throw ProtocolException()
            when (v) {
                is Int -> write(v.toLong(), depth)
                is Long -> if (v >= 0) head(0, v) else head(1, -(v + 1))
                is ByteArray -> { head(2, v.size.toLong()); out.write(v) }
                is String -> { val bytes = v.toByteArray(Charsets.UTF_8); head(3, bytes.size.toLong()); out.write(bytes) }
                is List<*> -> { if (v.size > 4096) throw ProtocolException(); head(4, v.size.toLong()); v.forEach { write(it ?: throw ProtocolException(), depth + 1) } }
                is Map<*, *> -> {
                    if (v.size > 4096) throw ProtocolException()
                    val entries = v.entries.map { encode(it.key ?: throw ProtocolException()) to (it.value ?: throw ProtocolException()) }
                        .sortedWith { a, b -> if (a.first.size != b.first.size) a.first.size - b.first.size else compare(a.first, b.first) }
                    head(5, entries.size.toLong())
                    entries.forEach { (key, item) -> out.write(key); write(item, depth + 1) }
                }
                is Boolean -> out.write(if (v) 245 else 244)
                else -> throw ProtocolException()
            }
            if (out.size() > MAX) throw ProtocolException()
        }
        write(value, 0); return out.toByteArray()
    }
    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) { val delta = (a[i].toInt() and 255) - (b[i].toInt() and 255); if (delta != 0) return delta }
        return 0
    }
    fun decode(raw: ByteArray): Any {
        if (raw.size !in 1..MAX) throw ProtocolException()
        var at = 0; var nodes = 0
        fun byte(): Int = if (at < raw.size) raw[at++].toInt() and 255 else throw ProtocolException()
        fun size(ai: Int): Long {
            if (ai < 24) return ai.toLong()
            if (ai !in 24..27) throw ProtocolException()
            var n = 0L
            repeat(1 shl (ai - 24)) { if (n > (Long.MAX_VALUE ushr 8)) throw ProtocolException(); n = (n shl 8) or byte().toLong() }
            if (n < when (ai) { 24 -> 24L; 25 -> 256L; 26 -> 65536L; else -> 0x100000000L }) throw ProtocolException()
            return n
        }
        fun read(depth: Int): Any {
            if (depth > 16 || ++nodes > 16384) throw ProtocolException()
            val tag = byte(); val major = tag ushr 5
            if (major == 7) return when (tag) { 244 -> false; 245 -> true; else -> throw ProtocolException() }
            val n = size(tag and 31)
            return when (major) {
                0 -> n
                1 -> -1L - n
                2, 3 -> {
                    if (n > raw.size - at) throw ProtocolException()
                    val data = raw.copyOfRange(at, at + n.toInt()); at += n.toInt()
                    if (major == 2) data else Proto.utf8(data)
                }
                4 -> { if (n > 4096) throw ProtocolException(); List(n.toInt()) { read(depth + 1) } }
                5 -> {
                    if (n > 4096) throw ProtocolException()
                    val out = LinkedHashMap<Any, Any>(); val keys = HashSet<String>()
                    repeat(n.toInt()) {
                        val key = read(depth + 1)
                        if (key !is String && key !is Long) throw ProtocolException()
                        if (!keys.add((if (key is String) "s:" else "i:") + key)) throw ProtocolException()
                        out[key] = read(depth + 1)
                    }; out
                }
                else -> throw ProtocolException()
            }
        }
        return read(0).also { if (at != raw.size) throw ProtocolException() }
    }
    @Suppress("UNCHECKED_CAST") fun map(value: Any?): Map<Any, Any> = value as? Map<Any, Any> ?: throw ProtocolException()
    fun bytes(value: Any?, size: IntRange = 1..MAX): ByteArray = (value as? ByteArray)?.takeIf { it.size in size } ?: throw ProtocolException()
    fun destroy(value: Any?) { when (value) { is ByteArray -> value.fill(0); is List<*> -> value.forEach(::destroy); is Map<*, *> -> value.values.forEach(::destroy) } }
}
