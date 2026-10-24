/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class VaultRecoveryTest {
    @Test fun recoveredSharedKeysMustDecryptTheActualKeybagBeforeReplacingDurableRecoveryKeys() {
        val shared = ByteArray(32) { (it + 1).toByte() }
        val key = NigoriKey.derive(Base64.getEncoder().encode(shared))
        val metadata = try { Proto.field(1, "users/me/birthdays/fixture") + Proto.field(3,
            Proto.field(1, key.encrypt(Proto.field(2, key.export()))) + Proto.integer(2, 5)) } finally { key.destroy() }
        val session = Session(VaultState(metadata = metadata, trustedVaultKeys = mutableListOf(ByteArray(32) { 9 })))
        val original = session.load().trustedVaultKeys.single()
        assertThrows(VaultLockedException::class.java) { VaultRecovery.acceptTrustedKeys(session, listOf(ByteArray(32))) }
        assertArrayEquals(original, session.load().trustedVaultKeys.single())
        VaultRecovery.acceptTrustedKeys(session, listOf(shared))
        val expected = shared.copyOf(); shared.fill(0)
        assertArrayEquals(expected, session.load().trustedVaultKeys.single())
        VaultKeys.unlock(metadata, trustedVaultKeys = session.load().trustedVaultKeys).destroy()
    }
    private class Session(initial: VaultState) : VaultSession {
        private var bytes = initial.encode()
        override fun load() = VaultState.decode(bytes)
        override fun save(state: VaultState) { bytes = state.encode() }
    }
    private fun <T> withKeys(action: (VaultKeys) -> T): T {
        val key = NigoriKey.derive("fixture recovery key".toByteArray()); val exported = key.export()
        val metadata = Proto.field(1, "users/me/birthdays/fixture") + Proto.field(3,
            Proto.field(1, key.encrypt(Proto.field(2, exported))) + Proto.integer(2, 4))
        val keys = VaultKeys.unlock(metadata, customKey = exported); key.destroy(); exported.fill(0)
        return try { action(keys) } finally { keys.destroy() }
    }
    private fun item() = PasswordData.create("https://fixture.invalid/", "https://fixture.invalid/", "fixture", "original", 1234L)
    private fun resource(data: PasswordData, keys: VaultKeys, version: Byte) = PasswordResourceCodec.encrypt(data, keys,
        Proto.field(1, "users/me/birthdays/fixture/passwords/one") + Proto.field(2, data.tag()) + Proto.field(5, byteArrayOf(version)))

    @Test fun retryOfAnUncertainAttemptRequiresOwnerAcknowledgementAndNewIdentity() = withKeys { keys ->
        val data = item(); val remote = resource(data.withPassword("remote fixture", 2345L), keys, 2)
        val desired = resource(data.withPassword("local fixture", 2345L).withDefaultNote("local note", 2345L), keys, 1)
        val pending = Mutation("old-attempt", MutationKind.UPDATE, data.tag(), "old-name", byteArrayOf(1), desired, MutationPhase.INDETERMINATE)
        val session = Session(VaultState(records = mutableListOf(remote), mutations = mutableListOf(pending), lastSyncMillis = 10))
        assertThrows(PendingMutationException::class.java) { VaultRecovery.retry(session, pending.id, keys, false) }
        assertEquals(pending.id, session.load().mutations.single().id)
        val replacement = VaultRecovery.retry(session, pending.id, keys, true)
        assertNotEquals(pending.id, replacement)
        val next = session.load().mutations.single()
        assertEquals(MutationPhase.QUEUED, next.phase); assertEquals(MutationKind.UPDATE, next.kind)
        assertArrayEquals(byteArrayOf(2), next.baseVersion)
        assertEquals(Proto.decode(remote).string(1), next.name)
        assertEquals("local fixture", PasswordResourceCodec.read(next.desired!!, keys).password)
        assertEquals("local note", PasswordResourceCodec.read(next.desired!!, keys).defaultNote!!.value)
    }
    @Test fun retryOfADisappearedUpdateCreatesOnlyAfterExplicitOwnerChoice() = withKeys { keys ->
        val data = item(); val desired = resource(data, keys, 1)
        val pending = Mutation("attempt", MutationKind.UPDATE, data.tag(), "old", byteArrayOf(1), desired, MutationPhase.CONFLICT)
        val session = Session(VaultState(mutations = mutableListOf(pending), lastSyncMillis = 10))
        VaultRecovery.retry(session, pending.id, keys, false)
        val next = session.load().mutations.single()
        assertEquals(MutationKind.CREATE, next.kind); assertNull(next.name); assertNull(next.baseVersion)
        assertNull(Proto.decode(next.desired!!).optional(1)); assertNull(Proto.decode(next.desired!!).optional(5))
    }
    @Test fun droppingAnUncertainAttemptDoesNotChangeTheAuthenticatedServerSnapshot() = withKeys { keys ->
        val data = item(); val remote = resource(data, keys, 2)
        val pending = Mutation("attempt", MutationKind.DELETE, data.tag(), "one", byteArrayOf(1), null, MutationPhase.INDETERMINATE)
        val session = Session(VaultState(records = mutableListOf(remote), mutations = mutableListOf(pending)))
        VaultRecovery.drop(session, pending.id)
        assertTrue(session.load().mutations.isEmpty()); assertArrayEquals(remote, session.load().records.single())
    }
    @Test fun replacementVaultRecoveryDiscardsOldOutboxesAndCachesWithoutTouchingTheServer() {
        val pending = Mutation("attempt", MutationKind.DELETE, "fixture", "one", byteArrayOf(1), null)
        val session = Session(VaultState(metadata = Proto.field(1, "users/me/birthdays/old"),
            records = mutableListOf(byteArrayOf(1)), mutations = mutableListOf(pending),
            pendingMetadata = Proto.field(1, "users/me/birthdays/new"), preferenceMutations = mutableListOf(pending),
            affiliationCache = byteArrayOf(2), directAffiliationProofs = byteArrayOf(3)))
        VaultRecovery.acceptRemoteReset(session)
        val result = session.load()
        assertEquals("users/me/birthdays/new", Proto.decode(result.metadata!!).string(1))
        assertTrue(result.records.isEmpty()); assertTrue(result.mutations.isEmpty()); assertTrue(result.preferenceMutations.isEmpty())
        assertNull(result.pendingMetadata); assertNull(result.affiliationCache); assertNull(result.directAffiliationProofs)
    }
    @Test fun retryRefusesPendingMetadataAndAnUnauthenticatedSnapshot(): Unit = withKeys { keys ->
        val pending = Mutation("attempt", MutationKind.DELETE, "fixture", "one", byteArrayOf(1), null)
        val session = Session(VaultState(mutations = mutableListOf(pending)))
        assertThrows(VaultLockedException::class.java) { VaultRecovery.retry(session, pending.id, keys, true) }
        session.save(session.load().also { it.lastSyncMillis = 10; it.pendingMetadata = byteArrayOf(1) })
        assertThrows(VaultLockedException::class.java) { VaultRecovery.retry(session, pending.id, keys, true) }
    }
}
