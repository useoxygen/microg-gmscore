/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

/** Desired-state proof from complete authenticated reads, never evidence of which UI action caused it. */
object PasswordEncryptionReadback {
    fun canStart(snapshot: PasswordEncryptionSnapshot, operation: PasswordEncryptionOperation): Boolean {
        val type = Proto.decode(Proto.decode(snapshot.metadata).one(3)).integer(2)
        return when (operation) {
            PasswordEncryptionOperation.ENROLL -> type == 2L
            PasswordEncryptionOperation.CLEAR_PASSWORDS -> type == 5L
            PasswordEncryptionOperation.RESET_PASSKEYS -> true
        }
    }
    fun deletion(snapshot: PasswordEncryptionSnapshot, operation: PasswordEncryptionOperation): Boolean = when (operation) {
        PasswordEncryptionOperation.CLEAR_PASSWORDS ->
            Proto.decode(Proto.decode(snapshot.metadata).one(3)).integer(2) in setOf(1L, 2L, 3L, 4L) &&
                snapshot.passwords.isEmpty() && snapshot.wifi.isEmpty()
        PasswordEncryptionOperation.RESET_PASSKEYS -> snapshot.passkeys.isEmpty()
        PasswordEncryptionOperation.ENROLL -> false
    }
}
