/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.assertThrows
import org.junit.Test

class PasskeyDomainAuthorityTest {
    private fun registration(ciphertext: ByteArray, field: Int = 12, version: Int = 7) = SyncedPasskey.decode(
        Proto.field(1, ByteArray(16) { 1 }) + Proto.field(2, byteArrayOf(2)) + Proto.field(3, "example.com") +
            Proto.field(4, byteArrayOf(3)) + Proto.integer(14, version.toLong()) +
            (if (field == 19) Proto.integer(18, 9) else byteArrayOf()) + Proto.field(field, ciphertext))

    @Test fun resetAndRecreatedSameEpochCannotCommitPreparedRegistration() {
        PasskeyDomainKey(7, ByteArray(32) { 4 }).use { original ->
            PasskeyDomainKey(7, ByteArray(32) { 5 }).use { recreated ->
                val pending = registration(PasskeyCrypto.encrypt(Proto.field(1, byteArrayOf(6)), original))
                PasskeyCrypto.requireDomainAuthority(pending, listOf(original))
                assertThrows(VaultLockedException::class.java) { PasskeyCrypto.requireDomainAuthority(pending, emptyList()) }
                assertThrows(VaultLockedException::class.java) { PasskeyCrypto.requireDomainAuthority(pending, listOf(recreated)) }
            }
        }
    }
    @Test fun unspecifiedEpochTrialsActualRetainedKeyAndDeviceEnvelopeDoesNotNeedPrivateDeviceKey() {
        PasskeyDomainKey(7, ByteArray(32) { 4 }).use { original ->
            PasskeyDomainKey(8, ByteArray(32) { 5 }).use { newer ->
                PasskeyDomainKey(9, ByteArray(32) { 6 }).use { device ->
                    val encrypted = PasskeyCrypto.encryptWithDevice(Proto.field(1, byteArrayOf(7)), original, device)
                    val pending = registration(encrypted, 19, 0)
                    PasskeyCrypto.requireDomainAuthority(pending, listOf(original, newer))
                    assertThrows(VaultLockedException::class.java) { PasskeyCrypto.requireDomainAuthority(pending, listOf(newer)) }
                    assertThrows(VaultLockedException::class.java) {
                        PasskeyCrypto.requireDomainAuthority(registration(encrypted.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }, 19, 0), listOf(original))
                    }
                }
            }
        }
    }
}
