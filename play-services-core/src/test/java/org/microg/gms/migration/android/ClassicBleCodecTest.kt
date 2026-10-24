/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class ClassicBleCodecTest {
    @Test fun regularAdvertisementHasDistinctMediumAndConnectionHeadersAndRealMac() {
        val advertisement=ClassicBleCodec.regular("service","ABCD","Synthetic target".toByteArray(),3,"AA:BB:CC:DD:EE:F0")
        assertEquals(0x48,advertisement[0].toInt())
        assertEquals(0x23,advertisement[8].toInt())
        val (endpoint,address)=ClassicBleCodec.parse("service",advertisement)
        assertEquals("ABCD",endpoint.id); assertEquals(3,endpoint.pcp); assertEquals("Synthetic target",endpoint.info.toString(Charsets.UTF_8)); assertEquals("AA:BB:CC:DD:EE:F0",address)
        val header=ClassicBleCodec.header("service",advertisement)
        assertEquals(17,header.size); assertEquals(1,ClassicBleCodec.slots("service",header))
        assertThrows(IllegalArgumentException::class.java) { ClassicBleCodec.parse("wrong service",advertisement) }
        listOf("00:00:00:00:00:00","02:00:00:00:00:00","FF:FF:FF:FF:FF:FF","wrong").forEach { value -> assertThrows(IllegalArgumentException::class.java) { ClassicBleCodec.mac(value) } }
    }
    @Test fun fastMediumWrapperDoesNotInventAnRfcommAddress() {
        val advertisement=ClassicBleCodec.fast("service","ABCD",byteArrayOf(1,2),2)
        assertEquals(0x4a,advertisement[0].toInt())
        val (endpoint,address)=ClassicBleCodec.parse("service",advertisement)
        assertEquals("ABCD",endpoint.id); assertArrayEquals(byteArrayOf(1,2),endpoint.info); assertNull(address)
        assertThrows(IllegalArgumentException::class.java) { ClassicBleCodec.parse("service",byteArrayOf(0x48,0)) }
        assertThrows(IllegalArgumentException::class.java) { ClassicBleCodec.fast("service","ABCD",ByteArray(18),1) }
    }
}
