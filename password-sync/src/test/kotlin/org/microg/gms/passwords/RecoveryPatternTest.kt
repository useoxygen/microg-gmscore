/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class RecoveryPatternTest {
    private fun draw(vararg cells: Int): CharArray {
        var pattern = intArrayOf()
        cells.forEach { cell -> val next = RecoveryPattern.append(pattern, cell); pattern.fill(0); pattern = next }
        return try { RecoveryPattern.encode(pattern) } finally { pattern.fill(0) }
    }
    @Test fun literalProducerEncodingIsAsciiOneThroughNine() {
        assertArrayEquals("123456789".toCharArray(), RecoveryPattern.encode(intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8)))
    }
    @Test fun crossedUnvisitedHorizontalVerticalAndDiagonalNodesAreInserted() {
        assertArrayEquals("123".toCharArray(), draw(0, 2))
        assertArrayEquals("147".toCharArray(), draw(0, 6))
        assertArrayEquals("159".toCharArray(), draw(0, 8))
        assertArrayEquals("357".toCharArray(), draw(2, 6))
    }
    @Test fun knightMovesDoNotInventAnIntermediateNode() {
        assertArrayEquals("16".toCharArray(), draw(0, 5))
        assertArrayEquals("18".toCharArray(), draw(0, 7))
    }
    @Test fun revisitedNodesAndAlreadyVisitedMidpointsAreNotAddedAgain() {
        assertArrayEquals("213".toCharArray(), draw(1, 0, 2, 1))
        assertArrayEquals("1".toCharArray(), draw(0, 0))
    }
    @Test fun malformedSequencesCannotReachTheRecoveryHash() {
        for (cells in listOf(intArrayOf(-1), intArrayOf(9), intArrayOf(0, 0)))
            assertThrows(ProtocolException::class.java) { RecoveryPattern.encode(cells) }
        assertThrows(ProtocolException::class.java) { RecoveryPattern.append(intArrayOf(0), 10) }
    }
}
