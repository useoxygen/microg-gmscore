/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.folsom.vault.VaultListRequest
import org.microg.gms.auth.folsom.vault.VaultUpdateRequest

class NativeRecoverableVaultTest {
    @Test fun generatedListRequestPreservesSourcePageSizeContextAndTokenWithoutGuessingAnotherView() {
        val raw = RecoverableVaultWire.list("source-shaped-session", 123, "opaque-token")
        val decoded = VaultListRequest.ADAPTER.decode(raw)
        assertEquals(50, decoded.pageSize); assertEquals("opaque-token", decoded.pageToken)
        assertEquals("source-shaped-session", decoded.context!!.session); assertEquals(123, decoded.context!!.version)
        assertArrayEquals(raw, decoded.encode())
    }
    @Test fun generatedUpdateAdapterKeepsTheExactCanonicalSourceBodyAndTwoDifferentMemberEntries() {
        // Published P256 generator and twice-generator coordinates; no production key-generation helper as oracle.
        fun pair(n: Int): ByteArray {
            val scalar = if (n == 2) 2 else 1
            val public = if (scalar == 1) "04" +
                "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
                "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5" else "04" +
                "7cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978" +
                "07775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1"
            return ByteArray(31) + byteArrayOf(scalar.toByte()) + public.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        val snapshot = RecoverableVaultSnapshot(1, pair(3).copyOfRange(32, 97), 4, 10, RecoverableVaultWire.handle(5, 6),
            byteArrayOf(7), byteArrayOf(8), byteArrayOf(9))
        val raw = RecoverableVaultWire.update(snapshot, mapOf("chromesync" to (pair(1) to ByteArray(125) { 1 }),
            "hw_protected" to (pair(2) to ByteArray(125) { 2 })), "session", 123)
        val decoded = VaultUpdateRequest.ADAPTER.decode(raw)
        assertEquals(123, decoded.version); assertEquals("session", decoded.context!!.session)
        assertEquals(2, decoded.vault!!.entries.size)
        assertArrayEquals(raw, decoded.encode())
        assertTrue(RecoverableVaultWire.vaultMatches(decoded.vault!!.encode(), Proto.decode(raw).one(3)))
    }
}
