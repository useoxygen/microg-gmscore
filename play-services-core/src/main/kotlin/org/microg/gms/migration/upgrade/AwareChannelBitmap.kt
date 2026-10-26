/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.upgrade

/** Negotiated frequencies are candidates; Android still enforces local regulatory availability. */
internal object AwareChannelBitmap {
    fun frequencies(bitmap: ByteArray): Set<Int> {
        require(bitmap.size == 3)
        val result = (1..11).mapTo(linkedSetOf()) { 2407 + 5 * it }
        val fiveGhz = listOf(36..48, 52..64, 100..112, 116..128, 132..144, 149..161, 165..177)
        val sixGhz = listOf(1..13, 17..29, 33..45, 49..61, 65..93, 97..109, 113..125, 129..141,
            145..157, 161..173, 177..189, 193..205, 209..221)
        fiveGhz.forEachIndexed { bit, range ->
            if (bitmap[0].toInt() and (1 shl bit) != 0) range.step(4).forEach { result += 5000 + 5 * it }
        }
        sixGhz.forEachIndexed { bit, range ->
            val byte = 1 + bit / 8
            if (bitmap[byte].toInt() and (1 shl (bit % 8)) != 0) range.step(4).forEach { result += 5950 + 5 * it }
        }
        return result
    }
}
