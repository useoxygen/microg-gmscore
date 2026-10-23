/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.upgrade

import org.junit.Assert.*
import org.junit.Test

class AwareChannelBitmapTest {
    @Test fun baseChannelsAreAlwaysIncluded() {
        assertEquals((1..11).map { 2407 + 5 * it }.toSet(), AwareChannelBitmap.frequencies(byteArrayOf(0, 0, 0)))
    }
    @Test fun recoveredSixGhzRangeIsNotNarrowedToFourChannels() {
        val result = AwareChannelBitmap.frequencies(byteArrayOf(0, 16, 0))
        assertTrue(result.containsAll(listOf(6275, 6295, 6315, 6335, 6355, 6375, 6395, 6415)))
        assertEquals(19, result.size)
    }
    @Test fun fiveGhzAndFinalSixGhzBitUseTheSpecifiedBands() {
        val result = AwareChannelBitmap.frequencies(byteArrayOf(1, 0, 16))
        assertTrue(result.containsAll(listOf(5180, 5200, 5220, 5240, 6995, 7015, 7035, 7055)))
        assertEquals(19, result.size)
    }
    @Test fun malformedBitmapCannotSelectChannels() {
        assertThrows(IllegalArgumentException::class.java) { AwareChannelBitmap.frequencies(byteArrayOf(0, 0)) }
    }
}
