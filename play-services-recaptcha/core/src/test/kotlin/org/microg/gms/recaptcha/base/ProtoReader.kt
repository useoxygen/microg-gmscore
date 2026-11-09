/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.recaptcha.base

/**
 * Minimal protobuf wire decoder for tests, independent of Wire and the generated classes: it only knows tags, wire
 * types, varints and length-delimited values, so a field-number or wire-type mistake in the schema shows up here.
 */
internal class ProtoField(val number: Int, val wireType: Int, val varint: Long?, val bytes: ByteArray?) {
    val string: String get() = String(requireNotNull(bytes) { "field $number is not length-delimited" }, Charsets.UTF_8)
}

internal fun parseProto(data: ByteArray): List<ProtoField> {
    val fields = ArrayList<ProtoField>()
    var pos = 0
    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(pos < data.size) { "truncated varint" }
            val b = data[pos++].toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 64) { "varint too long" }
        }
    }
    while (pos < data.size) {
        val tag = readVarint()
        val number = (tag ushr 3).toInt()
        val wireType = (tag and 7).toInt()
        require(number > 0) { "invalid field number" }
        when (wireType) {
            0 -> fields += ProtoField(number, wireType, readVarint(), null)
            2 -> {
                val length = readVarint().toInt()
                require(length >= 0 && pos + length <= data.size) { "truncated length-delimited field" }
                fields += ProtoField(number, wireType, null, data.copyOfRange(pos, pos + length))
                pos += length
            }
            else -> throw IllegalArgumentException("unexpected wire type $wireType for field $number")
        }
    }
    return fields
}

internal fun List<ProtoField>.single(number: Int): ProtoField = filter { it.number == number }.let {
    require(it.size == 1) { "expected one field $number, found ${it.size}" }
    it[0]
}

internal fun List<ProtoField>.numbers(): List<Int> = map { it.number }
