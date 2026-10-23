/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class LeakCheckEngineTest {
    private val parent = "users/me/birthdays/fixture"
    private fun password(realm: String = "https://fixture.invalid/", username: String = "user") = PasswordData.create(realm, realm, username, "password123", 1000)
    private inner class Vault(initial: List<PasswordData>) : LeakCheckVault {
        var records = initial.toList(); var currentParent = parent; var commits = 0
        var beforeCommit: () -> Unit = {}; var failCommit = false
        override fun snapshot() = LeakCheckSnapshot(currentParent, records.toList())
        override fun commit(snapshot: LeakCheckSnapshot, changes: List<LeakCheckChange>) {
            beforeCommit(); snapshot.verify(currentParent, records)
            if (failCommit) throw PasswordRpcException(14, true)
            val replacements = changes.associate { it.original.tag() to it.replacement }
            records = records.map { replacements[it.tag()] ?: it }; commits++
        }
    }

    @Test fun successfulCompleteAssessmentAddsOnlyNewPositiveMetadataAndPreservesFutureFields() {
        val original = PasswordData.decode(password().encode() + Proto.field(99, "future-password-field") + Proto.field(19, Proto.field(99, "future-issue")))
        val vault = Vault(listOf(original)); val updates = ArrayList<LeakCheckProgress>()
        val server = LeakCheckTestServer()
        val result = LeakCheckEngine(LeakCheckProtocol(server), vault, progress = updates::add, now = { 2000 }).assess()
        assertEquals(1, result.checked); assertEquals(1, result.leaked); assertEquals(0, result.knownLeaked); assertEquals(1, vault.commits)
        val saved = Proto.decode(vault.records.single().encode()); val issues = Proto.decode(saved.one(19))
        assertEquals("future-password-field", saved.string(99)); assertEquals("future-issue", issues.string(99))
        val issue = Proto.decode(issues.one(1))
        assertEquals((2000L + 11_644_473_600_000L) * 1000, issue.integer(1)); assertNull(issue.integer(2)); assertNull(issue.integer(3))
        assertEquals(LeakCheckProgress(1, 1, saving = true), updates.last())
    }

    @Test fun alreadyLeakedGroupsNeverQueryAndRetainMuteDateAndUnknownMetadata() {
        val issue = Proto.integer(1, 123) + Proto.integer(2, 1) + Proto.integer(3, 1) + Proto.field(99, "future")
        val original = PasswordData.decode(password().encode() + Proto.field(19, Proto.field(1, issue) + Proto.field(4, Proto.integer(1, 456))))
        val sibling = password("https://other.invalid/")
        val vault = Vault(listOf(original, sibling)); var calls = 0
        val protocol = LeakCheckProtocol(LeakCheckTransport { _, _ -> calls++; error("Known compromised group must not be sent") })
        val result = LeakCheckEngine(protocol, vault, now = { 2000 }).assess()
        assertEquals(0, calls); assertEquals(0, result.checked); assertEquals(0, result.leaked); assertEquals(2, result.knownLeaked)
        assertArrayEquals(original.encode(), vault.records.first().encode())
        assertTrue(LeakCheckIssues.isKnownLeaked(vault.records.last()))
    }

    @Test fun identicalPairsAcrossSitesShareOneQueryWithoutChangingTheirSeparateIdentities() {
        val vault = Vault(listOf(password(), password("https://other.invalid/")))
        val server = LeakCheckTestServer()
        val result = LeakCheckEngine(LeakCheckProtocol(server), vault).assess()
        assertEquals(2, result.checked); assertEquals(2, result.leaked); assertEquals(2, server.calls)
        assertEquals(2, vault.records.map { it.tag() }.toSet().size)
    }

    @Test fun canonicalEmptyUsernameIsReportedUnassessedRatherThanAllClear() {
        val original = password(username = "..."); val vault = Vault(listOf(original))
        val result = LeakCheckEngine(LeakCheckProtocol(LeakCheckTransport { _, _ -> error("No usable username") }), vault).assess()
        assertEquals(0, result.checked); assertEquals(1, result.unassessed); assertEquals(0, result.leaked)
        assertArrayEquals(original.encode(), vault.records.single().encode())
    }

    @Test fun aLaterRpcFailureCannotPublishEarlierPartialPositivesOrAnAssessment() {
        val vault = Vault(listOf(password(), password("https://other.invalid/", "other-user")))
        val before = vault.records.map { it.encode() }; val server = LeakCheckTestServer()
        server.before = { _, _ -> if (server.calls == 3) throw PasswordRpcException(7, false) }
        assertThrows(PasswordRpcException::class.java) { LeakCheckEngine(LeakCheckProtocol(server), vault).assess() }
        assertEquals(0, vault.commits); before.zip(vault.records).forEach { (old, current) -> assertArrayEquals(old, current.encode()) }
    }

    @Test fun grantFailureAndUnknownSaveOutcomeNeverReturnFreshSuccess() {
        val denied = Vault(listOf(password()))
        assertThrows(PasswordRpcException::class.java) { LeakCheckEngine(LeakCheckProtocol(LeakCheckTransport { _, _ -> throw PasswordRpcException(7, false) }), denied).assess() }
        assertEquals(0, denied.commits)
        val uncertain = Vault(listOf(password())).apply { failCommit = true }
        assertThrows(PasswordRpcException::class.java) { LeakCheckEngine(LeakCheckProtocol(LeakCheckTestServer()), uncertain).assess() }
        assertEquals(0, uncertain.commits)
    }

    @Test fun cancellationAfterRealQueryBeforeMetadataCommitPreventsAnyChange() {
        val vault = Vault(listOf(password())); var stopped = false
        assertThrows(LeakCheckCancelledException::class.java) {
            LeakCheckEngine(LeakCheckProtocol(LeakCheckTestServer()), vault, check = { if (stopped) throw LeakCheckCancelledException() },
                progress = { if (it.checked > 0) stopped = true }).assess()
        }
        assertEquals(0, vault.commits); assertFalse(LeakCheckIssues.isKnownLeaked(vault.records.single()))
    }

    @Test fun fullBaselineChangesAndBirthdayResetFailBeforeAnyMetadataMutation() {
        for (reset in listOf(false, true)) {
            val vault = Vault(listOf(password()))
            vault.beforeCommit = {
                if (reset) vault.currentParent = "users/me/birthdays/reset" else vault.records = listOf(password().withDefaultNote("owner edit", 2000))
            }
            assertThrows(Exception::class.java) { LeakCheckEngine(LeakCheckProtocol(LeakCheckTestServer()), vault).assess() }
            assertEquals(0, vault.commits)
        }
    }

    @Test fun negativeResultPreservesSavedWeakPhishedAndFutureIssueFieldsExactly() {
        val issues = Proto.field(3, Proto.integer(1, 123)) + Proto.field(4, Proto.integer(1, 456)) + Proto.field(99, "future")
        val original = PasswordData.decode(password().encode() + Proto.field(19, issues))
        val vault = Vault(listOf(original))
        val result = LeakCheckEngine(LeakCheckProtocol(LeakCheckTestServer(false)), vault).assess()
        assertEquals(1, result.checked); assertEquals(0, result.leaked)
        assertArrayEquals(original.encode(), vault.records.single().encode())
    }
}
