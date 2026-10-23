/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.ble

import java.io.ByteArrayOutputStream

/** Migration DCT service data (FC73). It differs from Nearby C++'s service-hash/name format. */
internal object DctAdvertisement {
    const val SERVICE_UUID = "0000fc73-0000-1000-8000-00805f9b34fb"
    private const val VERSION = 0x20

    fun target(sessionId: String, psm: Int): ByteArray {
        require(sessionId.length == 6 && sessionId.all { it in 'a'..'z' || it in '0'..'9' })
        require(psm in 1..0xffff)
        val elements = linkedMapOf(
            1 to sessionId.toByteArray(Charsets.UTF_8),
            2 to byteArrayOf(1), // Android
            3 to byteArrayOf(-2), // migration producer's nominal transmit power
            4 to byteArrayOf(psm.toByte(), (psm ushr 8).toByte()),
            6 to byteArrayOf(2) // data import
        )
        return encode(elements)
    }

    fun encode(elements: Map<Int, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(VERSION)
        elements.forEach { (type, value) ->
            require(type in 1..7 && value.size <= 127) { "Unconfirmed DCT element encoding" }
            if (value.size <= 7 && type <= 15) output.write((value.size shl 4) or type)
            else { output.write(0x80 or value.size); output.write(type) }
            output.write(value)
        }
        return output.toByteArray()
    }

    fun decode(data: ByteArray): Map<Int, ByteArray> {
        require(data.isNotEmpty() && data[0].toInt() and 0xff == VERSION) { "Unsupported DCT advertisement" }
        val elements = linkedMapOf<Int, ByteArray>()
        var position = 1
        while (position < data.size) {
            val header = data[position++].toInt() and 0xff
            val extended = header and 0x80 != 0
            val length = if (extended) header and 0x7f else header ushr 4
            val type = if (extended) {
                require(position < data.size); data[position++].toInt() and 0xff
            } else header and 0x0f
            require(type != 0 && !elements.containsKey(type)) { "Duplicate or invalid DCT element" }
            require(length <= data.size - position) { "Truncated DCT element" }
            elements[type] = data.copyOfRange(position, position + length)
            position += length
        }
        return elements
    }
}
