/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.session

import org.junit.Assert.*
import org.junit.Test

class DctSessionTest {
    @Test fun credentialsUseTheCurrentAlphabetAndVersionedFragmentUri() {
        repeat(100) {
            val session = DctSession.create()
            assertTrue(session.sessionId.matches(Regex("[abcdefghjkmnpqrstvwxyz23456789]{6}")))
            assertTrue(session.password.matches(Regex("[abcdefghjkmnpqrstvwxyz23456789]{6}")))
            assertEquals("https://migrate.google/1/d#s=${session.sessionId}&p=${session.password}", session.qrUri)
            assertFalse(session.toString().contains(session.password))
            assertFalse(session.toString().contains(session.sessionId))
        }
    }
}
