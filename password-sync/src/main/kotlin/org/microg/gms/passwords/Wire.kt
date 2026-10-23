/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** A bounded, lossless protobuf view. Unknown fields survive password updates. */
class Proto private constructor(private val parts: List<Part>) {
    private class Part(val number: Int, val wire: Int, val value: ByteArray, val encoded: ByteArray)

    fun bytes(number: Int): List<ByteArray> = parts.filter { it.number == number && it.wire == 2 }.map { it.value.copyOf() }
    fun one(number: Int): ByteArray = bytes(number).singleOrNull() ?: throw ProtocolException()
    fun optional(number: Int): ByteArray? {
        val values = bytes(number)
        if (values.size > 1) throw ProtocolException()
        return values.singleOrNull()
    }
    fun string(number: Int): String = utf8(one(number))
    fun optionalString(number: Int): String? = optional(number)?.let(::utf8)
    fun integer(number: Int): Long? {
        val values = parts.filter { it.number == number && it.wire == 0 }
        if (values.size > 1) throw ProtocolException()
        return values.singleOrNull()?.value?.let { readVarint(it, 0).first }
    }
    fun encode(): ByteArray = parts.fold(ByteArrayOutputStream()) { out, part -> out.apply { write(part.encoded) } }.toByteArray()
    fun replace(number: Int, value: ByteArray?): Proto = decode(
        parts.filter { it.number != number }.fold(ByteArrayOutputStream()) { out, part -> out.apply { write(part.encoded) } }.toByteArray() +
            (value?.let { field(number, it) } ?: byteArrayOf()))
    fun replaceInteger(number: Int, value: Long): Proto = decode(replace(number, null).encode() + integer(number, value))
    override fun toString(): String = "Proto[redacted]"

    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
        const val MAX_FIELDS = 100_000

        fun utf8(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { throw ProtocolException() }

        fun decode(data: ByteArray): Proto {
            if (data.size > MAX_BYTES) throw ProtocolException()
            val parts = ArrayList<Part>()
            var offset = 0
            while (offset < data.size) {
                if (parts.size >= MAX_FIELDS) throw ProtocolException()
                val start = offset
                val (tag, next) = readVarint(data, offset)
                offset = next
                val number = tag ushr 3
                val wire = (tag and 7).toInt()
                if (number !in 1 until (1L shl 29)) throw ProtocolException()
                val value: ByteArray
                if (wire == 0) {
                    val (_, end) = readVarint(data, offset)
                    value = data.copyOfRange(offset, end)
                    offset = end
                } else {
                    val length: Long
                    when (wire) {
                        1 -> length = 8
                        5 -> length = 4
                        2 -> {
                            val pair = readVarint(data, offset)
                            length = pair.first
                            offset = pair.second
                        }
                        else -> throw ProtocolException()
                    }
                    if (length < 0 || length > data.size - offset) throw ProtocolException()
                    value = data.copyOfRange(offset, offset + length.toInt())
                    offset += length.toInt()
                }
                parts.add(Part(number.toInt(), wire, value, data.copyOfRange(start, offset)))
            }
            return Proto(parts)
        }

        private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
            var result = 0L
            var offset = start
            for (shift in 0..63 step 7) {
                if (offset >= data.size) throw ProtocolException()
                val byte = data[offset++].toInt() and 255
                if (shift == 63 && byte > 1) throw ProtocolException()
                result = result or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return result to offset
            }
            throw ProtocolException()
        }

        fun varint(value: Long): ByteArray {
            var current = value
            val out = ByteArrayOutputStream()
            while (current and -128L != 0L) {
                out.write(((current and 127) or 128).toInt())
                current = current ushr 7
            }
            out.write(current.toInt())
            return out.toByteArray()
        }
        fun field(number: Int, value: ByteArray): ByteArray {
            require(number in 1 until (1 shl 29))
            return varint((number.toLong() shl 3) or 2) + varint(value.size.toLong()) + value
        }
        fun field(number: Int, value: String): ByteArray = field(number, value.toByteArray(Charsets.UTF_8))
        fun integer(number: Int, value: Long): ByteArray = varint(number.toLong() shl 3) + varint(value)
    }
}

class ProtocolException : Exception("Invalid password protocol data")
class VaultLockedException : Exception("Password vault requires owner authentication")
