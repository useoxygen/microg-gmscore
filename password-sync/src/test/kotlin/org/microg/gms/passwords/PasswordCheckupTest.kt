/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class PasswordCheckupTest {
    private fun item() = PasswordData.create("https://fixture.invalid/", "https://fixture.invalid/login", "fixture", "fixture secret", 1234L)
    private fun issue(muted: Boolean = false) = Proto.integer(1, 123456L) + Proto.integer(2, if (muted) 1 else 0) +
        Proto.integer(3, 1) + Proto.field(500, byteArrayOf(7))
    private fun withIssues(issues: ByteArray) = PasswordData.decode(Proto.decode(item().encode()).replace(19, issues).replace(600, byteArrayOf(8)).encode())
    @Test fun warningsComeOnlyFromActualFlagsAndNoSavedFlagsNeverClaimACleanPassword() {
        assertTrue(PasswordCheckup.risks(item()).isEmpty())
        val data = withIssues(Proto.field(1, issue(true)) + Proto.field(2, issue()) + Proto.field(3, issue()) + Proto.field(4, issue()))
        val risks = PasswordCheckup.risks(data)
        assertEquals(PasswordRiskKind.values().toSet(), risks.map { it.kind }.toSet())
        assertEquals(1, risks.count { it.dismissed })
        assertEquals(PasswordRiskKind.LEAKED, risks.single { it.dismissed }.kind)
        assertTrue(PasswordCheckup.risks(data.withBlocked(true)).isEmpty())
        assertFalse(risks.toString().contains("fixture secret"))
    }
    @Test fun dismissalTogglesOnlyLeakedMuteAndPreservesOtherRisksDatesNotificationsNotesAndIdentity() {
        val notes = item().withDefaultNote("fixture note", 1234L).encodedNotes!!
        val issues = Proto.field(1, issue()) + Proto.field(2, issue()) + Proto.field(3, issue()) + Proto.field(4, issue()) + Proto.field(501, byteArrayOf(9))
        val data = PasswordData.decode(Proto.decode(withIssues(issues).encode()).replace(22, notes).encode())
        val dismissed = PasswordCheckup.withLeakedDismissed(data, true)
        assertEquals(data.tag(), dismissed.tag()); assertEquals(data.password, dismissed.password)
        val before = Proto.decode(data.encode()); val after = Proto.decode(dismissed.encode())
        assertArrayEquals(before.replace(19, null).encode(), after.replace(19, null).encode())
        val updatedIssues = Proto.decode(after.one(19)); val leaked = Proto.decode(updatedIssues.one(1))
        assertEquals(123456L, leaked.integer(1)); assertEquals(1L, leaked.integer(2)); assertEquals(1L, leaked.integer(3))
        assertArrayEquals(byteArrayOf(7), leaked.one(500))
        for (field in listOf(2, 3, 4, 501)) assertArrayEquals(Proto.decode(issues).one(field), updatedIssues.one(field))
        assertArrayEquals(dismissed.encode(), PasswordCheckup.withLeakedDismissed(dismissed, true).encode())
        val restored = PasswordCheckup.withLeakedDismissed(dismissed, false)
        assertFalse(PasswordCheckup.risks(restored).single { it.kind == PasswordRiskKind.LEAKED }.isMuted)
        assertArrayEquals(before.replace(19, null).encode(), Proto.decode(restored.encode()).replace(19, null).encode())
        assertArrayEquals(Proto.decode(Proto.decode(issues).one(1)).replace(2, null).encode(),
            Proto.decode(Proto.decode(Proto.decode(restored.encode()).one(19)).one(1)).replace(2, null).encode())
    }
    @Test fun reusedWeakAndPhishedWarningsCannotBeDismissedOrInvented() {
        val other = withIssues(Proto.field(2, issue(true)) + Proto.field(3, issue(true)) + Proto.field(4, issue(true)))
        assertFalse(PasswordCheckup.risks(other).any { it.dismissed })
        assertThrows(ConflictException::class.java) { PasswordCheckup.withLeakedDismissed(other, true) }
        assertThrows(ConflictException::class.java) { PasswordCheckup.withLeakedDismissed(item(), true) }
        assertTrue(PasswordCheckup.risks(withIssues(Proto.field(99, issue()))).isEmpty())
    }
    @Test fun actionLinksUseOnlyTheCredentialHttpsRealmAndNeverCarryItsPathOrSecret() {
        assertEquals("https://fixture.invalid/", PasswordCheckup.website(item()))
        assertNull(PasswordCheckup.website(PasswordData.create("javascript:fixture", "", "fixture", "fixture secret", 1234L)))
        assertNull(PasswordCheckup.website(PasswordData.create("https://user@fixture.invalid/", "", "fixture", "fixture secret", 1234L)))
    }
}
