/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package com.google.android.gms.credential.manager

import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.passwords.AgentSignInAccess

/** Sign-ins for Cyclon (CYC-1594): what the page says in each state. */
class SignInsTest {
    private val none = SignIns.Setup(account = false, autofill = false, passwords = false)
    private val login = SignIns.Login("a", "Amazon", "owner@example.com", null, AgentSignInAccess.Level.ALWAYS, null)

    @Test fun setupShowsOnlyWhileAStepIsUndone() {
        assertFalse(none.any)
        assertTrue(none.copy(account = true).any)
        assertTrue(none.copy(autofill = true).any)
        assertTrue(none.copy(passwords = true).any)
    }

    @Test fun aLoginSaysItsUsernameChoiceAndLastUse() {
        assertEquals("owner@example.com · Always allow · used Oct 26, 2026", SignIns.details("owner@example.com", "Always allow", "used Oct 26, 2026"))
        assertEquals("owner · Ask each time", SignIns.details("owner", "Ask each time", null))
        // A login saved without a username shows only Cyclon's choice.
        assertEquals("Never", SignIns.details("", "Never", null))
    }

    @Test fun noLoginsIsSaidOnceAndNotWhileReading() {
        val empty = SignIns.Logins.Shown(emptyList())
        assertTrue(SignIns.saysNone(none, empty, busy = false))
        assertFalse("the setup step already says so", SignIns.saysNone(none.copy(passwords = true), empty, busy = false))
        assertFalse(SignIns.saysNone(none, empty, busy = true))
        assertFalse(SignIns.saysNone(none, SignIns.Logins.Shown(listOf(login)), busy = false))
        assertFalse(SignIns.saysNone(none, SignIns.Logins.Locked, busy = false))
    }

    @Test fun theLoginsSectionIsNeverAnEmptyHeading() {
        val empty = SignIns.Logins.Shown(emptyList())
        assertFalse(SignIns.showsLogins(none.copy(passwords = true), empty, busy = false))
        assertTrue(SignIns.showsLogins(none, empty, busy = false))
        assertTrue(SignIns.showsLogins(none, SignIns.Logins.Locked, busy = false))
        assertTrue(SignIns.showsLogins(none, SignIns.Logins.Choosing, busy = false))
        assertTrue(SignIns.showsLogins(none.copy(passwords = true), SignIns.Logins.Shown(listOf(login)), busy = false))
    }

    @Test fun thisPhoneNoteOnlyWhereThereAreChoices() {
        assertTrue(SignIns.saysThisPhone(SignIns.Logins.Locked))
        assertTrue(SignIns.saysThisPhone(SignIns.Logins.Choosing))
        assertTrue(SignIns.saysThisPhone(SignIns.Logins.Shown(listOf(login))))
        assertFalse(SignIns.saysThisPhone(SignIns.Logins.Shown(emptyList())))
    }

    @Test fun eachChoiceHasItsWords() {
        assertEquals(AgentSignInAccess.Level.values().size, AgentSignInAccess.Level.values().map(SignIns::levelLabel).toSet().size)
    }
}
