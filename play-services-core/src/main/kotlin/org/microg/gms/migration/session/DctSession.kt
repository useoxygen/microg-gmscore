/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.session

import java.security.SecureRandom

/** Ephemeral credentials. Avoid a data class: its generated toString would expose the password. */
internal class DctSession private constructor(val sessionId: String, val password: String) {
    val qrUri: String get() = "https://migrate.google/1/d#s=$sessionId&p=$password"
    override fun toString(): String = "DctSession(redacted)"

    companion object {
        private const val ALPHABET = "abcdefghjkmnpqrstvwxyz23456789"
        fun restore(sessionId: String, password: String): DctSession {
            require(sessionId.length == 6 && password.length == 6)
            require((sessionId + password).all { it in ALPHABET })
            return DctSession(sessionId, password)
        }
        fun create(random: SecureRandom = SecureRandom()): DctSession {
            fun credential() = CharArray(6) { ALPHABET[random.nextInt(ALPHABET.length)] }.concatToString()
            return DctSession(credential(), credential())
        }
    }
}
