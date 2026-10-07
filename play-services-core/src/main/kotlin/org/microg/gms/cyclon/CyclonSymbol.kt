/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.floor
import kotlin.math.min

// Exact cells from Cyclon's cyclon-symbol-cells-32.svg master.
private val symbolRows = arrayOf(
    intArrayOf(16, 0, 7),
    intArrayOf(14, 1, 9),
    intArrayOf(12, 2, 11),
    intArrayOf(11, 3, 9),
    intArrayOf(10, 4, 9),
    intArrayOf(9, 5, 8),
    intArrayOf(8, 6, 8),
    intArrayOf(8, 7, 6),
    intArrayOf(18, 7, 6),
    intArrayOf(7, 8, 6),
    intArrayOf(16, 8, 10),
    intArrayOf(0, 9, 3),
    intArrayOf(7, 9, 6),
    intArrayOf(15, 9, 12),
    intArrayOf(0, 10, 3),
    intArrayOf(7, 10, 21),
    intArrayOf(0, 11, 3),
    intArrayOf(7, 11, 22),
    intArrayOf(0, 12, 4),
    intArrayOf(7, 12, 7),
    intArrayOf(18, 12, 12),
    intArrayOf(0, 13, 5),
    intArrayOf(7, 13, 6),
    intArrayOf(19, 13, 3),
    intArrayOf(24, 13, 6),
    intArrayOf(0, 14, 5),
    intArrayOf(8, 14, 4),
    intArrayOf(20, 14, 2),
    intArrayOf(25, 14, 6),
    intArrayOf(0, 15, 6),
    intArrayOf(8, 15, 4),
    intArrayOf(20, 15, 3),
    intArrayOf(25, 15, 6),
    intArrayOf(1, 16, 6),
    intArrayOf(9, 16, 3),
    intArrayOf(20, 16, 4),
    intArrayOf(26, 16, 6),
    intArrayOf(1, 17, 6),
    intArrayOf(10, 17, 2),
    intArrayOf(20, 17, 4),
    intArrayOf(27, 17, 5),
    intArrayOf(2, 18, 6),
    intArrayOf(10, 18, 3),
    intArrayOf(19, 18, 6),
    intArrayOf(27, 18, 5),
    intArrayOf(2, 19, 12),
    intArrayOf(18, 19, 7),
    intArrayOf(28, 19, 4),
    intArrayOf(3, 20, 22),
    intArrayOf(29, 20, 3),
    intArrayOf(4, 21, 21),
    intArrayOf(29, 21, 3),
    intArrayOf(5, 22, 12),
    intArrayOf(19, 22, 6),
    intArrayOf(29, 22, 3),
    intArrayOf(6, 23, 10),
    intArrayOf(19, 23, 6),
    intArrayOf(8, 24, 6),
    intArrayOf(18, 24, 6),
    intArrayOf(16, 25, 8),
    intArrayOf(15, 26, 8),
    intArrayOf(13, 27, 9),
    intArrayOf(12, 28, 9),
    intArrayOf(9, 29, 11),
    intArrayOf(9, 30, 9),
    intArrayOf(9, 31, 7)
)

@Composable
internal fun CyclonSymbol(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val cell = floor(min(size.width, size.height) / 32f)
        val left = floor((size.width - cell * 32f) / 2f)
        val top = floor((size.height - cell * 32f) / 2f)
        symbolRows.forEach { row ->
            drawRect(color, Offset(left + row[0] * cell, top + row[1] * cell), Size(row[2] * cell, cell))
        }
    }
}
