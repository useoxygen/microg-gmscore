/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class TrustedVaultLifecycleCacheTest {
    private val parent = "users/me/birthdays/lifecycle-fixture"
    private class Session(initial: VaultState) : VaultSession {
        var bytes = initial.encode()
        override fun load() = VaultState.decode(bytes)
        override fun save(state: VaultState) { bytes = state.encode() }
    }
    private fun state(): VaultState {
        val key = VersionedTrustedVaultKey(7, ByteArray(32) { 3 })
        return try { VaultState().apply {
            metadata = Proto.field(1, parent) + Proto.field(3, Proto.integer(2, 5))
            records.add(Proto.field(1, "$parent/passwords/retained") + Proto.field(2, byteArrayOf(9, 8, 7)))
            mutations.add(Mutation("queued-delete", MutationKind.DELETE, "existing-tag", "$parent/passwords/retained", null, null))
            trustedVaultKeys.add(ByteArray(32) { 3 })
            versionedTrustedVaultKeys = VersionedTrustedVaultKeys(parent, listOf(key))
            lastSyncMillis = 1234
        } } finally { key.destroy() }
    }
    @Test fun confirmedResetOnlyRemovesRecoveryAuthorityAndPreservesEveryEncryptedRecordAndOutboxByte() {
        val session = Session(state())
        val expected = Proto.decode(session.bytes).replace(17, null).replace(20, null).encode()
        VaultRecovery.clearTrustedKeys(session, parent)
        assertArrayEquals(expected, session.bytes)
        val reopened = session.load()
        assertTrue(reopened.trustedVaultKeys.isEmpty()); assertNull(reopened.versionedTrustedVaultKeys)
        assertEquals(1, reopened.records.size); assertEquals("queued-delete", reopened.mutations.single().id)
        VaultRecovery.clearTrustedKeys(session, parent)
        assertArrayEquals(expected, session.bytes)
    }
    @Test fun staleResetCannotRemoveReplacementVaultKeysOrRaceAChangedPendingBirthday() {
        for (pending in listOf(false, true)) {
            val state = state()
            val replacement = Proto.field(1, "users/me/birthdays/replacement") + Proto.field(3, Proto.integer(2, 5))
            if (pending) state.pendingMetadata = replacement else {
                state.versionedTrustedVaultKeys?.destroy(); state.versionedTrustedVaultKeys = null
                state.metadata = replacement
            }
            val session = Session(state); val before = session.bytes.copyOf()
            assertThrows(VaultResetException::class.java) { VaultRecovery.clearTrustedKeys(session, parent) }
            assertArrayEquals(before, session.bytes)
        }
    }
    @Test fun revokedOwnerOrAccountAtEitherBoundaryCannotPersistAuthorityRemoval() {
        for (revokeAt in 1..2) {
            val session = Session(state()); val before = session.bytes.copyOf(); var checks = 0
            assertThrows(VaultLockedException::class.java) { VaultRecovery.clearTrustedKeys(session, parent) {
                if (++checks == revokeAt) throw VaultLockedException()
            } }
            assertArrayEquals(before, session.bytes)
        }
    }
    @Test fun accountDomainResetCanClearCurrentAuthorityWithoutAnOptedInOrInitializedPasswordVault() {
        val empty = Session(VaultState()); val before = empty.bytes.copyOf()
        VaultRecovery.clearTrustedKeys(empty, null)
        assertArrayEquals(before, empty.bytes)
        val current = Session(state())
        VaultRecovery.clearTrustedKeys(current, null)
        assertTrue(current.load().trustedVaultKeys.isEmpty())
        assertNull(current.load().versionedTrustedVaultKeys)
        assertEquals("queued-delete", current.load().mutations.single().id)
    }
}
