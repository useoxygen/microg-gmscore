/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import kotlin.math.abs
import kotlin.math.sign

/** Pinned fsqh.a and LockPatternView: row-major cells become ASCII1..9, including unvisited crossed midpoints. */
object RecoveryPattern {
    private fun checked(cells: IntArray) {
        if (cells.size > 9 || cells.any { it !in 0..8 } || cells.toSet().size != cells.size) throw ProtocolException()
    }
    fun append(cells: IntArray, cell: Int): IntArray {
        checked(cells)
        if (cell !in 0..8) throw ProtocolException()
        if (cell in cells) return cells.copyOf()
        if (cells.isEmpty()) return intArrayOf(cell)
        val previous = cells.last()
        val row = previous / 3; val column = previous % 3
        val rows = cell / 3 - row; val columns = cell % 3 - column
        val middleRow = row + if (abs(rows) == 2 && abs(columns) != 1) rows.sign else 0
        val middleColumn = column + if (abs(columns) == 2 && abs(rows) != 1) columns.sign else 0
        val middle = middleRow * 3 + middleColumn
        return cells + if (middle != previous && middle !in cells) intArrayOf(middle, cell) else intArrayOf(cell)
    }
    fun encode(cells: IntArray): CharArray {
        checked(cells)
        return CharArray(cells.size) { ('1'.code + cells[it]).toChar() }
    }
}
