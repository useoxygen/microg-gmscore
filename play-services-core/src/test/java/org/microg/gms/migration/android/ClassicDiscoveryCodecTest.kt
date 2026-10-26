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
class ClassicDiscoveryCodecTest {
    @Test fun lanDiscoveryMatchesPublicNearbyV1AndRejectsAnotherService() {
        val name=ClassicDiscoveryCodec.lanName("fixture","ABCD",byteArrayOf(1,2,3),2)
        val bytes=android.util.Base64.decode(name,android.util.Base64.URL_SAFE)
        assertEquals(0x22,bytes[0].toInt()); assertEquals("ABCD",bytes.copyOfRange(1,5).toString(Charsets.US_ASCII))
        val parsed=ClassicDiscoveryCodec.parseLan("fixture",name,ClassicDiscoveryCodec.lanInfo(byteArrayOf(1,2,3)).toByteArray())
        assertEquals("ABCD",parsed.id); assertEquals(2,parsed.pcp); assertArrayEquals(byteArrayOf(1,2,3),parsed.info)
        assertThrows(IllegalArgumentException::class.java) { ClassicDiscoveryCodec.parseLan("other",name,null) }
        assertEquals("_"+java.security.MessageDigest.getInstance("SHA-256").digest("fixture".toByteArray()).take(6).joinToString("") { "%02X".format(it.toInt() and 255) }+"._tcp.",ClassicDiscoveryCodec.serviceType("fixture"))
    }
    @Test fun explicitFastBleRoundTripAndMalformedBounds() {
        val bytes=ClassicDiscoveryCodec.fastBle("ABCD",byteArrayOf(7,8),1); val peer=ClassicDiscoveryCodec.parseFastBle(bytes)
        assertEquals("ABCD",peer.id); assertArrayEquals(byteArrayOf(7,8),peer.info)
        assertThrows(IllegalArgumentException::class.java) { ClassicDiscoveryCodec.parseFastBle(bytes.copyOf(bytes.size-1)) }
        assertThrows(IllegalArgumentException::class.java) { ClassicDiscoveryCodec.fastBle("ABCD",ByteArray(18),1) }
        assertThrows(IllegalArgumentException::class.java) { ClassicDiscoveryCodec.lanName("fixture","../x",byteArrayOf(),0) }
    }
}
