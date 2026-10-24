/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class PasswordEncryptionReadbackTest {
    private fun snapshot(type: Long?, wifi: List<ByteArray> = emptyList(), passkeys: List<ByteArray> = emptyList()) =
        PasswordEncryptionSnapshot("1234", "users/me/birthdays/fixture", Proto.field(1, "users/me/birthdays/fixture") +
            Proto.field(3, type?.let { Proto.integer(2, it) } ?: byteArrayOf()), emptyList(), wifi, passkeys)
    @Test fun emptyUnchangedTrustedVaultAfterCancelIsNotAConfirmedEncryptionReset() {
        for (type in listOf(null, 0L, 5L, 6L, 999L)) snapshot(type).use {
            assertFalse(PasswordEncryptionReadback.deletion(it, PasswordEncryptionOperation.CLEAR_PASSWORDS))
        }
        for (type in listOf(1L, 2L, 3L, 4L)) snapshot(type).use {
            assertTrue(PasswordEncryptionReadback.deletion(it, PasswordEncryptionOperation.CLEAR_PASSWORDS))
        }
        snapshot(2L, wifi = listOf(Proto.field(1, "still-present"))).use {
            assertFalse(PasswordEncryptionReadback.deletion(it, PasswordEncryptionOperation.CLEAR_PASSWORDS))
        }
    }
    @Test fun passkeyResetIsASeparateDesiredStateAndNeverConfirmsEnrollment() {
        snapshot(5L, wifi = listOf(Proto.field(1, "unrelated"))).use {
            assertTrue(PasswordEncryptionReadback.deletion(it, PasswordEncryptionOperation.RESET_PASSKEYS))
            assertFalse(PasswordEncryptionReadback.deletion(it, PasswordEncryptionOperation.ENROLL))
        }
        snapshot(2L, passkeys = listOf(Proto.field(1, "still-present"))).use {
            assertFalse(PasswordEncryptionReadback.deletion(it, PasswordEncryptionOperation.RESET_PASSKEYS))
        }
    }
    @Test fun sourceEnrollmentAndClearPreconditionsDoNotUpgradeOtherPassphraseModes() {
        for (type in listOf(null, 0L, 1L, 2L, 3L, 4L, 5L, 6L)) snapshot(type).use {
            assertEquals(type == 2L, PasswordEncryptionReadback.canStart(it, PasswordEncryptionOperation.ENROLL))
            assertEquals(type == 5L, PasswordEncryptionReadback.canStart(it, PasswordEncryptionOperation.CLEAR_PASSWORDS))
        }
    }
}
