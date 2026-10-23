/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class VersionedTrustedVaultKeyTest {
    private val parent = "users/me/birthdays/fixture"
    private val shared = ByteArray(32) { (it + 1).toByte() }
    private fun metadata(parent: String = this.parent, raw: ByteArray = shared): ByteArray {
        val key = NigoriKey.derive(Base64.getEncoder().encode(raw))
        return try { Proto.field(1, parent) + Proto.field(3,
            Proto.field(1, key.encrypt(Proto.field(2, key.export()))) + Proto.integer(2, 5)) }
        finally { key.destroy() }
    }
    private fun bundle(epoch: Int = 7, parent: String = this.parent): VersionedTrustedVaultKeys {
        val key = VersionedTrustedVaultKey(epoch, shared)
        return try { VersionedTrustedVaultKeys(parent, listOf(key)) } finally { key.destroy() }
    }
    private class Session(initial: VaultState) : VaultSession {
        var bytes = initial.encode()
        override fun load() = VaultState.decode(bytes)
        override fun save(state: VaultState) { bytes = state.encode() }
    }

    @Test fun codecRetainsExplicitEpochAndBirthdayWithIndependentWipeableCopies() {
        val recovered = bundle()
        val decoded = VersionedTrustedVaultKeys.decode(recovered.encode())
        val keys = decoded.keys
        try {
            assertEquals(parent, decoded.parent); assertEquals(7, keys.single().epoch)
            val material = keys.single().material
            assertArrayEquals(shared, material); material.fill(0)
            assertArrayEquals(shared, keys.single().material)
            assertFalse(keys.single().toString().contains("7"))
            keys.single().destroy()
            assertThrows(VaultLockedException::class.java) { keys.single().material }
            val independent = decoded.keys
            try { assertArrayEquals(shared, independent.single().material) } finally { independent.forEach { it.destroy() } }
        } finally { keys.forEach { it.destroy() }; recovered.destroy(); decoded.destroy() }
    }

    @Test fun invalidAndOverflowEpochsMissingMaterialAndOversizedInputsAreRefused() {
        for (epoch in listOf(0L, -1L, Int.MAX_VALUE.toLong() + 1)) {
            assertThrows(ProtocolException::class.java) { VersionedTrustedVaultKey.decode(Proto.integer(1, epoch) + Proto.field(2, shared)) }
        }
        assertThrows(ProtocolException::class.java) { VersionedTrustedVaultKey.decode(Proto.integer(1, 1)) }
        for (size in listOf(0, 15, 4097)) assertThrows(ProtocolException::class.java) { VersionedTrustedVaultKey(1, ByteArray(size)) }
        assertThrows(ProtocolException::class.java) { VersionedTrustedVaultKey.decode(ByteArray(5000)) }
        assertThrows(ProtocolException::class.java) { VersionedTrustedVaultKeys.decode(ByteArray(421 * 1024)) }
    }

    @Test fun duplicateEpochsAreRefusedEvenWhenTheirMaterialMatches() {
        val first = VersionedTrustedVaultKey(7, shared)
        val conflicting = VersionedTrustedVaultKey(7, ByteArray(32) { 9 })
        try {
            for (second in listOf(first, conflicting)) {
                assertThrows(ProtocolException::class.java) { VersionedTrustedVaultKeys(parent, listOf(first, second)) }
                assertThrows(ProtocolException::class.java) { VersionedTrustedVaultKeys.decode(Proto.integer(1, 1) + Proto.field(2, parent) +
                    Proto.field(3, first.encode()) + Proto.field(3, second.encode())) }
            }
            assertThrows(ProtocolException::class.java) { VersionedTrustedVaultKeys(parent, List(101) { first }) }
        } finally { first.destroy(); conflicting.destroy() }
    }

    @Test fun legacyRawOnlyStateMigratesWithoutInventingAnEpochAndNewWritersAreFenced() {
        val previous = Proto.integer(1, 5) + Proto.field(2, metadata()) + Proto.field(17, shared)
        val migrated = VaultState.decode(previous)
        assertNull(migrated.versionedTrustedVaultKeys)
        assertArrayEquals(shared, migrated.trustedVaultKeys.single())
        VaultKeys.unlock(migrated.metadata!!, trustedVaultKeys = migrated.trustedVaultKeys).destroy()
        val encoded = migrated.encode()
        assertEquals(6L, Proto.decode(encoded).integer(1)); assertNull(Proto.decode(encoded).optional(20))
        assertThrows(ProtocolException::class.java) { VaultState.decode(Proto.integer(1, 7)) }
        val epochs = bundle()
        try {
            assertThrows(ProtocolException::class.java) { VaultState.decode(previous + Proto.field(20, epochs.encode())) }
            // This is the exact schema admission fence in the previous v5 reader.
            assertFalse(Proto.decode(encoded).integer(1) in setOf(1L, 2L, 3L, 4L, 5L))
        } finally { epochs.destroy() }
    }

    @Test fun schemaSixReopensEpochsOnlyUnderTheirExactStoredBirthday() {
        val retained = bundle()
        try {
            val state = VaultState(metadata = metadata(), versionedTrustedVaultKeys = retained)
            val reopened = VaultState.decode(state.encode())
            val keys = reopened.versionedTrustedVaultKeys!!.keys
            try { assertEquals(7, keys.single().epoch); assertArrayEquals(shared, keys.single().material) }
            finally { keys.forEach { it.destroy() }; reopened.versionedTrustedVaultKeys!!.destroy() }
            state.metadata = metadata("users/me/birthdays/replacement")
            assertThrows(VaultResetException::class.java) { state.encode() }
        } finally { retained.destroy() }
    }

    @Test fun verifiedRecoveryPersistsEpochsAndRawUnlockMaterialTogether() {
        val session = Session(VaultState(metadata = metadata()))
        val key = VersionedTrustedVaultKey(7, shared)
        try { VaultRecovery.acceptVersionedTrustedKeys(session, listOf(key), parent) } finally { key.destroy() }
        val reopened = session.load()
        assertArrayEquals(shared, reopened.trustedVaultKeys.single())
        VaultKeys.unlock(reopened.metadata!!, trustedVaultKeys = reopened.trustedVaultKeys).destroy()
        val keys = reopened.versionedTrustedVaultKeys!!.keys
        try { assertEquals(7, keys.single().epoch); assertArrayEquals(shared, keys.single().material) }
        finally { keys.forEach { it.destroy() }; reopened.versionedTrustedVaultKeys!!.destroy() }
    }

    @Test fun wrongKeyBirthdayAndRevokedOwnerCannotReplacePreviouslyVerifiedState() {
        val session = Session(VaultState(metadata = metadata(), trustedVaultKeys = mutableListOf(shared.copyOf()), versionedTrustedVaultKeys = bundle()))
        val before = session.bytes.copyOf()
        val bad = VersionedTrustedVaultKey(8, ByteArray(32))
        val good = VersionedTrustedVaultKey(8, shared)
        try {
            assertThrows(VaultLockedException::class.java) { VaultRecovery.acceptVersionedTrustedKeys(session, listOf(bad), parent) }
            assertArrayEquals(before, session.bytes)
            assertThrows(VaultResetException::class.java) { VaultRecovery.acceptVersionedTrustedKeys(session, listOf(good), "users/me/birthdays/replacement") }
            assertArrayEquals(before, session.bytes)
            var checks = 0
            assertThrows(VaultLockedException::class.java) { VaultRecovery.acceptVersionedTrustedKeys(session, listOf(good), parent) {
                if (++checks == 2) throw VaultLockedException()
            } }
            assertEquals(2, checks); assertArrayEquals(before, session.bytes)
        } finally { bad.destroy(); good.destroy() }
    }

    @Test fun onePreviouslyRetainedEpochCannotBeReboundToDifferentMaterial() {
        val changed = ByteArray(32) { 9 }
        val session = Session(VaultState(metadata = metadata(raw = changed), versionedTrustedVaultKeys = bundle()))
        val before = session.bytes.copyOf(); val key = VersionedTrustedVaultKey(7, changed)
        try {
            assertThrows(ProtocolException::class.java) { VaultRecovery.acceptVersionedTrustedKeys(session, listOf(key), parent) }
            assertArrayEquals(before, session.bytes)
        } finally { key.destroy() }
    }

    @Test fun rawOnlyRecoveryKeepsUnlockCompatibilityAndExplicitlyRemovesEpochAuthority() {
        val session = Session(VaultState(metadata = metadata(), versionedTrustedVaultKeys = bundle()))
        VaultRecovery.acceptTrustedKeys(session, listOf(shared))
        val recovered = session.load()
        assertNull(recovered.versionedTrustedVaultKeys)
        VaultKeys.unlock(recovered.metadata!!, trustedVaultKeys = recovered.trustedVaultKeys).destroy()
    }

    @Test fun pendingResetRetainsNoNewRecoveredEpochsAndOwnerAcceptedResetClearsOldEpochs() {
        val session = Session(VaultState(metadata = metadata(), pendingMetadata = metadata("users/me/birthdays/replacement"),
            trustedVaultKeys = mutableListOf(shared.copyOf()), versionedTrustedVaultKeys = bundle()))
        val before = session.bytes.copyOf(); val key = VersionedTrustedVaultKey(8, shared)
        try {
            assertThrows(VaultResetException::class.java) { VaultRecovery.acceptVersionedTrustedKeys(session, listOf(key), parent) }
            assertArrayEquals(before, session.bytes)
        } finally { key.destroy() }
        VaultRecovery.acceptRemoteReset(session)
        assertNull(session.load().versionedTrustedVaultKeys); assertTrue(session.load().trustedVaultKeys.isEmpty())
    }

    @Test fun observedAutomaticResetClearsEpochsEvenWhenTheReplacementSnapshotFails() {
        for (failList in listOf(false, true)) {
            val session = Session(VaultState(metadata = metadata(), versionedTrustedVaultKeys = bundle()))
            val store = object : VaultStore { override fun <T> locked(action: (VaultSession) -> T): T = action(session) }
            val replacement = metadata("users/me/birthdays/replacement")
            val api = PasswordApi(PasswordTransport { method, _ -> when (method) {
                PasswordMethod.GET_METADATA -> replacement
                PasswordMethod.LIST -> if (failList) throw PasswordRpcException(14, false) else byteArrayOf()
                else -> error("Read-only reset fixture")
            } })
            val sync = VaultSync(api, store)
            if (failList) assertThrows(PasswordRpcException::class.java) { sync.synchronize { VaultKeys.unlock(it, trustedVaultKeys = listOf(shared)) } }
            else sync.synchronize { VaultKeys.unlock(it, trustedVaultKeys = listOf(shared)) }
            assertNull(session.load().versionedTrustedVaultKeys)
        }
    }
}
