/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class PasswordAcceptanceSelectionTest {
    private val run = "00000000-0000-0000-0000-000000000123"
    private fun selection() = PasswordAcceptanceSelection("native-only-fixture", "synthetic-stable-id", 100, 4, run, "primary")
    @Test fun actualPersistedReceiptReopensOnlyForExactRunRoleBootAndLiveStableAccount() {
        val receipt = PasswordAcceptanceSelection.decode(selection().encode())
        receipt.verify(run, "primary", 4, 200, "native-only-fixture", "synthetic-stable-id")
        assertEquals("PasswordAcceptanceSelection[redacted]", receipt.toString())
        assertThrows(IllegalStateException::class.java) { receipt.verify(run, "primary", 4, 200, null, null) }
        // Removing/readding the same visible account name must not adopt a different GoogleUserId.
        assertThrows(IllegalStateException::class.java) { receipt.verify(run, "primary", 4, 200, "native-only-fixture", "replacement-id") }
        assertThrows(IllegalStateException::class.java) { receipt.verify(run, "family", 4, 200, "native-only-fixture", "synthetic-stable-id") }
        assertThrows(IllegalStateException::class.java) { receipt.verify("00000000-0000-0000-0000-000000000124", "primary", 4, 200, "native-only-fixture", "synthetic-stable-id") }
    }
    @Test fun rebootElapsedRollbackAndExpiryCannotReuseAnOldSelection() {
        val receipt = selection()
        for ((boot, now) in listOf(5 to 200L, 4 to 99L, 4 to 1_800_100L)) {
            assertThrows(IllegalStateException::class.java) { receipt.verify(run, "primary", boot, now, "native-only-fixture", "synthetic-stable-id") }
        }
        receipt.verify(run, "primary", 4, 1_800_099L, "native-only-fixture", "synthetic-stable-id")
    }
    @Test fun malformedOrUnboundReceiptsCannotBecomeAuthority() {
        val raw = selection().encode(); val parsed = Proto.decode(raw)
        assertThrows(Exception::class.java) { PasswordAcceptanceSelection.decode(parsed.replace(2, null).encode()) }
        assertThrows(Exception::class.java) { PasswordAcceptanceSelection.decode(parsed.replaceInteger(4, -1).encode()) }
        assertThrows(Exception::class.java) { PasswordAcceptanceSelection.decode(parsed.replace(6, "unrestricted".toByteArray()).encode()) }
        assertThrows(Exception::class.java) { PasswordAcceptanceSelection.decode(raw + Proto.field(2, "another-id")) }
    }
}
