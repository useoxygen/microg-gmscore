/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.ble

import org.junit.Assert.*
import org.junit.Test

class DctAdvertisementTest {
    @Test fun targetMatchesTheMigrationWireContract() {
        val expected = byteArrayOf(0x20, 0x61, 97, 98, 99, 100, 101, 102, 0x12, 1, 0x13, -2,
            0x24, 0x81.toByte(), 0, 0x16, 2)
        assertArrayEquals(expected, DctAdvertisement.target("abcdef", 129))
        val decoded = DctAdvertisement.decode(expected)
        assertEquals(setOf(1, 2, 3, 4, 6), decoded.keys)
        assertArrayEquals(byteArrayOf(-127, 0), decoded[4])
    }
    @Test fun everyTruncatedElementIsRejected() {
        val advertisement = DctAdvertisement.target("abcdef", 129)
        listOf(2, 3, 4, 5, 6, 7, 9, 11, 13, 14, 16).forEach { size ->
            assertThrows(IllegalArgumentException::class.java) { DctAdvertisement.decode(advertisement.copyOf(size)) }
        }
    }
    @Test fun extendedElementsRoundTripWithoutLosingTheirType() {
        val value = ByteArray(100) { it.toByte() }
        val encoded = DctAdvertisement.encode(mapOf(42 to value))
        assertEquals(0xe4, encoded[1].toInt() and 255)
        assertEquals(42, encoded[2].toInt())
        assertArrayEquals(value, DctAdvertisement.decode(encoded)[42])
    }
    @Test fun duplicateSessionAndWrongVersionAreRejected() {
        val valid = DctAdvertisement.target("abcdef", 129)
        assertThrows(IllegalArgumentException::class.java) {
            DctAdvertisement.decode(valid + byteArrayOf(0x11, 97))
        }
        assertThrows(IllegalArgumentException::class.java) { DctAdvertisement.decode(byteArrayOf(0)) }
    }
}
