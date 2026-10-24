/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

/** Public Chrome145 production fixture is an independent positive oracle, rather than a self-signed replacement root. */
class EnclaveAttestationTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/enclave/$name")!!.use { it.readBytes() }
    private fun raw() = fixture("chrome145-public-attestation.cbor")
    private fun hash() = fixture("chrome145-public-handshake.bin")
    @Test fun actualProductionAmdDiceAndHandshakeChainVerifies() { EnclaveAttestation.verify(raw(), hash()) }
    @Test fun independentlyChangedHandshakeAndEachSignedLinkAreRejected() {
        assertThrows(Exception::class.java) { EnclaveAttestation.verify(raw(), hash().also { it[0] = (it[0].toInt() xor 1).toByte() }) }
        for (field in 0L..4L) {
            val map = EnclaveCbor.map(EnclaveCbor.decode(raw())).toMutableMap()
            val value = (map[field] as ByteArray).copyOf(); value[value.size / 2] = (value[value.size / 2].toInt() xor 1).toByte()
            map[field] = value
            assertThrows("tampered attestation link $field", Exception::class.java) { EnclaveAttestation.verify(EnclaveCbor.encode(map), hash()) }
        }
    }
    @Test fun reportPlatformLayerCountTcbAndRootDigestCannotBeSubstituted() {
        for (change in 0..4) {
            val map = EnclaveCbor.map(EnclaveCbor.decode(raw())).toMutableMap()
            var evidence = Proto.decode(map[3L] as ByteArray); var root = Proto.decode(evidence.one(1))
            when (change) {
                0 -> root = root.replaceInteger(1, 2)
                1 -> evidence = evidence.replaceRepeated(2, evidence.bytes(2) + evidence.bytes(2))
                else -> { val report = root.one(2); val offset = when (change) { 2 -> 0x180; 3 -> 0x50; else -> 0x1a0 }
                    report[offset] = (report[offset].toInt() xor 1).toByte(); root = root.replace(2, report) }
            }
            evidence = evidence.replace(1, root.encode()); map[3L] = evidence.encode()
            assertThrows(Exception::class.java) { EnclaveAttestation.verify(EnclaveCbor.encode(map), hash()) }
        }
    }
    @Test fun cborRejectsAmbiguousDuplicateTrailingIndefiniteAndOversizedRepresentations() {
        for (raw in listOf(byteArrayOf(0xa2.toByte(), 0, 1, 0, 2), byteArrayOf(0x9f.toByte(), 0xff.toByte()), byteArrayOf(0x18, 1),
            byteArrayOf(1, 0), byteArrayOf(0x5a, 0x7f, -1, -1, -1))) assertThrows(Exception::class.java) { EnclaveCbor.decode(raw) }
        assertArrayEquals(byteArrayOf(0xa2.toByte(), 1, 0x41, 9, 0x61, 0x61, 0xf5.toByte()), EnclaveCbor.encode(mapOf("a" to true, 1 to byteArrayOf(9))))
    }
}
