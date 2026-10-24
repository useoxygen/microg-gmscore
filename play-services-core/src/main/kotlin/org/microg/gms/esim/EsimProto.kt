/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.esim

import java.io.ByteArrayOutputStream

/** Pinned eSIM XOS envelope schema: start1/2, profile3/4, notify5, end6, delete7/8. */
internal class EsimProto private constructor(private val fields: Map<Int, List<Value>>) {
    private class Value(val wire: Int, val number: Long = 0, val bytes: ByteArray = byteArrayOf())
    fun number(id: Int, default: Long = 0) = fields[id]?.singleOrNull()?.also { require(it.wire == 0) }?.number ?: default
    fun bytes(id: Int): ByteArray? = fields[id]?.singleOrNull()?.also { require(it.wire == 2) }?.bytes
    fun text(id: Int) = bytes(id)?.toString(Charsets.UTF_8).orEmpty()
    fun messages(id: Int) = fields[id].orEmpty().map { require(it.wire == 2); decode(it.bytes) }
    fun message(id: Int) = bytes(id)?.let(::decode)
    fun keys() = fields.keys
    companion object {
        fun decode(bytes: ByteArray): EsimProto {
            require(bytes.size <= 1024 * 1024)
            var offset = 0
            fun varint(): Long {
                var result = 0L
                for (shift in 0..63 step 7) {
                    require(offset < bytes.size)
                    val next = bytes[offset++].toInt() and 255
                    require(shift < 63 || next < 2)
                    result = result or ((next and 127).toLong() shl shift)
                    if (next < 128) return result
                }
                throw IllegalArgumentException("Malformed eSIM wire integer")
            }
            val fields = linkedMapOf<Int, MutableList<Value>>()
            while (offset < bytes.size) {
                val tag = varint(); val id = (tag ushr 3).toInt(); val wire = (tag and 7).toInt()
                require(id in 1..10000 && fields.values.sumOf { it.size } < 4096)
                val value = when (wire) {
                    0 -> Value(wire, varint())
                    2 -> { val size = varint(); require(size in 0..(bytes.size-offset).toLong()); Value(wire, bytes = bytes.copyOfRange(offset, offset + size.toInt())).also { offset += size.toInt() } }
                    else -> throw IllegalArgumentException("Unsupported eSIM wire type")
                }
                fields.getOrPut(id) { mutableListOf() }.add(value)
            }
            return EsimProto(fields)
        }
        private fun varint(value: Long): ByteArray {
            require(value >= 0); var current = value; val out = ByteArrayOutputStream()
            while (current >= 128) { out.write((current.toInt() and 127) or 128); current = current ushr 7 }
            out.write(current.toInt()); return out.toByteArray()
        }
        fun number(id: Int, value: Long) = varint(id.toLong() shl 3) + varint(value)
        fun bytes(id: Int, value: ByteArray) = varint((id.toLong() shl 3) or 2) + varint(value.size.toLong()) + value
        fun text(id: Int, value: String) = bytes(id, value.toByteArray(Charsets.UTF_8))
        fun envelope(id: Int, value: ByteArray) = bytes(id, value)
    }
}
