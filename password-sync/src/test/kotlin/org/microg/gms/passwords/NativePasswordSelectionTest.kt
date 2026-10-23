package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class NativePasswordSelectionTest {
    @Test fun removedAndReaddedAccountNameCannotRedeemOldSelection() {
        val selection = NativePasswordSelection("synthetic@example.invalid", "original-google-id", "credential-id")
        assertTrue(selection.matches("synthetic@example.invalid", "original-google-id"))
        assertFalse(selection.matches("synthetic@example.invalid", "replacement-google-id"))
        assertFalse(selection.matches("synthetic@example.invalid", null))
        assertFalse(selection.matches(null, null))
        assertFalse(selection.toString().contains("original-google-id"))
    }
    @Test fun deviceSelectionCannotBecomeAnAccountSelection() {
        val local = NativePasswordSelection(null, null, "local-id")
        assertTrue(local.matches(null, null))
        assertFalse(local.matches("synthetic@example.invalid", "google-id"))
    }
}
