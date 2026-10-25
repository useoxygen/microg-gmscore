/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PasswordEncryptionKeyProofTest {
    private val public = hex("046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")
    private val raw = ByteArray(32) { (it + 1).toByte() }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun domain(epoch: Int = 7) = Proto.field(1, EnrollmentWire.DOMAIN) + Proto.integer(2, epoch.toLong())
    private fun proof(material: ByteArray, input: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(material, "HmacSHA256")); doFinal(input)
    }
    private fun member(epoch: Int = 7, material: ByteArray = raw, proofField: Int = 3, duplicate: Boolean = false): ByteArray {
        val entry = Proto.integer(1, epoch.toLong()) + Proto.field(2, ByteArray(100) { 0x11 }) + Proto.field(proofField, proof(material, public))
        val membership = Proto.field(1, EnrollmentWire.DOMAIN) + Proto.field(3, entry) + if (duplicate) Proto.field(3, entry) else byteArrayOf()
        return Proto.field(1, EnrollmentWire.memberName(public)) + Proto.field(2, public) + Proto.field(3, membership)
    }
    @Test fun freshServerMemberHmacBindsEachActualEpochAndPublicKey() {
        val key = VersionedTrustedVaultKey(7, raw)
        try {
            PasswordEncryptionKeyProof.verify(domain(), listOf(member()), listOf(key), emptyList())
            val changed = Proto.decode(member()).replace(2, public.copyOf().apply { this[64] = (this[64].toInt() xor 1).toByte() }).encode()
            assertThrows(VaultLockedException::class.java) { PasswordEncryptionKeyProof.verify(domain(), listOf(changed), listOf(key), emptyList()) }
            assertFalse(key.toString().contains(SyncedPasskey.hex(raw)))
        } finally { key.destroy() }
    }
    @Test fun epochLabelsAndOtherKeyProofFieldsCannotGrantAuthority() {
        val key = VersionedTrustedVaultKey(7, raw)
        try {
            assertThrows(ProtocolException::class.java) { PasswordEncryptionKeyProof.verify(domain(8), listOf(member()), listOf(key), emptyList()) }
            for (record in listOf(member(6), member(material = ByteArray(32)), member(proofField = 4), member(duplicate = true)))
                assertThrows(VaultLockedException::class.java) { PasswordEncryptionKeyProof.verify(domain(), listOf(record), listOf(key), emptyList()) }
            val foreign = Proto.field(1, "users/me/securitydomains/hw_protected") + Proto.integer(2, 7)
            assertThrows(VaultLockedException::class.java) { PasswordEncryptionKeyProof.verify(foreign, listOf(member()), listOf(key), emptyList()) }
        } finally { key.destroy() }
    }
    @Test fun retainedEpochCannotBeReboundEvenWithAValidNewServerMemberProof() {
        val key = VersionedTrustedVaultKey(7, raw)
        val old = VersionedTrustedVaultKey(7, ByteArray(32) { 0x33 })
        try {
            assertThrows(ConflictException::class.java) { PasswordEncryptionKeyProof.verify(domain(), listOf(member()), listOf(key), listOf(old)) }
        } finally { key.destroy(); old.destroy() }
    }
    @Test fun writerSevenPreservesPendingOwnerFenceAndRejectsOlderSchemaSmuggling() {
        val metadata = Proto.field(1, "users/me/birthdays/birthday") + Proto.field(3, Proto.integer(2, 1))
        val before = PasswordEncryptionSnapshot("1234", "users/me/birthdays/birthday", metadata, emptyList(), emptyList(), emptyList())
        before.use { baseline -> PasswordEncryptionState.prepare("fixture", PasswordEncryptionOperation.CLEAR_PASSWORDS, baseline, 10).use { ready ->
            ready.open().use { opened ->
                val flow = opened.encode()
                val state = VaultState(metadata = metadata, passwordEncryptionFlow = flow)
                val encoded = state.encode()
                assertEquals(7L, Proto.decode(encoded).integer(1))
                val reopened = VaultState.decode(encoded)
                assertArrayEquals(flow, reopened.passwordEncryptionFlow)
                assertTrue(PasswordEncryptionState.fences(reopened.passwordEncryptionFlow))
                assertThrows(ProtocolException::class.java) { VaultState.decode(Proto.decode(encoded).replaceInteger(1, 6).encode()) }
                opened.closeView().use { unknown ->
                    reopened.passwordEncryptionFlow = unknown.encode()
                    assertTrue(PasswordEncryptionState.fences(VaultState.decode(reopened.encode()).passwordEncryptionFlow))
                }
            }
        } }
        val old = Proto.integer(1, 6) + Proto.integer(5, 0)
        assertEquals(7L, Proto.decode(VaultState.decode(old).encode()).integer(1))
    }
}
