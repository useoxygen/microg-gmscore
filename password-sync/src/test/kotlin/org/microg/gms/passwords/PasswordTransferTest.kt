/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class PasswordTransferTest {
    @Test fun transferIdentityRetainsTheVaultGenerationAndCannotEqualAReplacementVault() {
        val original = PasswordStoreIdentity("fixture@example.invalid", "fixture-id", "users/me/birthdays/original")
        val replacement = PasswordStoreIdentity("fixture@example.invalid", "fixture-id", "users/me/birthdays/replacement")
        assertFalse(original.same(replacement))
        val reopened = PasswordStoreIdentity.decode(original.encode())
        assertTrue(original.same(reopened)); assertEquals(original.vaultParent, reopened.vaultParent)
    }
    private class Store : VaultStore {
        var bytes = VaultState().encode()
        override fun <T> locked(action: (VaultSession) -> T): T = action(object : VaultSession {
            override fun load() = VaultState.decode(bytes)
            override fun save(state: VaultState) { bytes = state.encode() }
        })
    }
    private class Endpoint : PasswordTransferEndpoint {
        val records = HashMap<String, ByteArray>()
        var writes = 0; var deletes = 0; var loseCopy = false; var loseDelete = false; var unresolved = false
        override fun refresh() = SyncReceipt(records.size, 0, 0, 0)
        override fun committed(tag: String) = records[tag]
        override fun pending(tag: String) = unresolved
        override fun insertIfAbsent(chrome: ByteArray) {
            val tag = tag(chrome)
            if (records[tag] != null) throw ConflictException()
            writes++; records[tag] = chrome
            if (loseCopy) { loseCopy = false; throw PasswordRpcException(14, true) }
        }
        override fun deleteIfUnchanged(chrome: ByteArray) {
            val tag = tag(chrome)
            if (records[tag]?.let { PasswordTransferEngine.samePassword(it, chrome) } != true) throw ConflictException()
            deletes++; records.remove(tag)
            if (loseDelete) { loseDelete = false; throw PasswordRpcException(14, true) }
        }
    }
    private fun chrome(user: String = "fixture", password: String = "fixture-secret") = Proto.field(1,
        PasswordData.create("https://example.invalid/", "https://example.invalid/", user, password, 1234L).withDefaultNote("fixture note", 1234L).encode())
    private val local = PasswordStoreIdentity()
    private val account = PasswordStoreIdentity("fixture@example.invalid", "fixture-id")

    @Test fun queuedGlobalImportBlocksOwnerPageAndFreshAdmissionRechecksTheLaunchFence() {
        val store = Store(); val target = Endpoint(); var fenced = false; var admissions = 0
        val engine = PasswordTransferEngine(store) {
            admissions++
            if (fenced && it.userId == account.userId) throw PendingMutationException()
            target
        }
        val id = engine.start(null, account, null, chrome())
        assertEquals(1, admissions)
        var opened = false
        assertThrows(PendingMutationException::class.java) { engine.withQuiescedAccount(account.userId!!) { opened = true } }
        assertFalse(opened); assertEquals(0, target.writes)
        assertEquals(id, engine.operations().single().id)
        engine.drop(id)
        engine.withQuiescedAccount(account.userId!!) { fenced = true; opened = true }
        assertTrue(opened)
        assertThrows(PendingMutationException::class.java) { engine.start(null, account, null, chrome()) }
        assertTrue(engine.operations().isEmpty()); assertEquals(0, target.writes)
        assertEquals(0, target.deletes)
    }

    @Test fun globalQuiescenceMatchesStableGaiaForEitherSideAndLeavesUnrelatedAccountsAvailable() {
        val store = Store(); val target = Endpoint()
        val other = PasswordStoreIdentity("other@example.invalid", "other-id")
        val engine = PasswordTransferEngine(store) { target }
        engine.start(account, other, chrome(), chrome(user = "other-fixture"))
        assertThrows(PendingMutationException::class.java) { engine.withQuiescedAccount(account.userId!!) {} }
        assertThrows(PendingMutationException::class.java) { engine.withQuiescedAccount(other.userId!!) {} }
        assertEquals("read-only", engine.withQuiescedAccount("unrelated-id") { "read-only" })
        assertEquals(1, engine.operations().size); assertEquals(0, target.writes)
    }

    @Test fun lostDestinationResponseSurvivesReopenAndNeverDuplicatesOrPrematurelyDeletes() {
        val store = Store(); val source = Endpoint(); val destination = Endpoint(); val data = chrome()
        source.records[tag(data)] = data; destination.loseCopy = true
        fun engine() = PasswordTransferEngine(store) { if (it.same(local)) source else destination }
        val id = engine().start(local, account, data, data)
        assertThrows(PasswordRpcException::class.java) { engine().resume(id) }
        assertEquals(0, source.deletes); assertNotNull(source.records[tag(data)])
        assertEquals(1, VaultState.decode(store.bytes).ownerTransfers.size)
        engine().resume(id)
        assertEquals(1, destination.writes); assertEquals(1, source.deletes)
        assertTrue(source.records.isEmpty()); assertTrue(engine().operations().isEmpty())
        assertArrayEquals(data, destination.records[tag(data)])
    }
    @Test fun lostDeleteResponseReconcilesWithoutRepeatingEitherEffect() {
        val store = Store(); val source = Endpoint(); val destination = Endpoint(); val data = chrome()
        source.records[tag(data)] = data; source.loseDelete = true
        val engine = PasswordTransferEngine(store) { if (it.same(local)) source else destination }
        val id = engine.start(local, account, data, data)
        assertThrows(PasswordRpcException::class.java) { engine.resume(id) }
        assertEquals(PasswordTransferPhase.REMOVE_SOURCE, engine.operations().single().phase)
        engine.resume(id)
        assertEquals(1, destination.writes); assertEquals(1, source.deletes); assertTrue(engine.operations().isEmpty())
    }
    @Test fun changedDestinationAndSourceNeverGetSilentlyOverwrittenOrDeleted() {
        val store = Store(); val source = Endpoint(); val destination = Endpoint(); val data = chrome()
        val different = chrome(password = "different fixture")
        source.records[tag(data)] = data; destination.records[tag(data)] = different
        val engine = PasswordTransferEngine(store) { if (it.same(local)) source else destination }
        val id = engine.start(local, account, data, data)
        assertThrows(ConflictException::class.java) { engine.resume(id) }
        assertEquals(0, source.deletes); assertEquals(0, destination.writes)
        destination.records.clear(); source.records[tag(data)] = different
        assertThrows(ConflictException::class.java) { engine.resume(id) }
        assertEquals(0, source.deletes); assertArrayEquals(different, source.records[tag(data)])
        assertArrayEquals(data, engine.operations().single().original)
    }
    @Test fun unresolvedDestinationNeverDeletesSourceAndDroppingNeverMutatesEitherStore() {
        val store = Store(); val source = Endpoint(); val destination = Endpoint(); val data = chrome()
        source.records[tag(data)] = data; destination.unresolved = true
        val engine = PasswordTransferEngine(store) { if (it.same(local)) source else destination }
        val id = engine.start(local, account, data, data)
        assertThrows(PendingMutationException::class.java) { engine.resume(id) }
        engine.drop(id)
        assertEquals(0, source.deletes); assertEquals(0, destination.writes); assertTrue(engine.operations().isEmpty())
    }
    @Test fun identityEditCopiesNewIdentityAndPreservesTheOriginalUntilVerified() {
        val store = Store(); val endpoint = Endpoint(); val before = chrome(); val after = chrome(user = "new fixture")
        endpoint.records[tag(before)] = before; endpoint.loseCopy = true
        val engine = PasswordTransferEngine(store) { endpoint }
        val id = engine.start(local, local, before, after)
        assertThrows(PasswordRpcException::class.java) { engine.resume(id) }
        assertNotNull(endpoint.records[tag(before)]); assertEquals(0, endpoint.deletes)
        engine.resume(id)
        assertNull(endpoint.records[tag(before)]); assertArrayEquals(after, endpoint.records[tag(after)])
    }
    @Test fun importsNeverDeleteAndTheVersionFenceRetainsBothCacheAndTransfer() {
        val store = Store(); val endpoint = Endpoint(); val data = chrome()
        val engine = PasswordTransferEngine(store) { endpoint }
        val id = engine.start(null, account, null, data)
        store.locked { session -> val state = session.load(); state.affiliationCache = byteArrayOf(8); session.save(state) }
        assertEquals(7L, Proto.decode(store.bytes).integer(1))
        assertArrayEquals(byteArrayOf(8), VaultState.decode(store.bytes).affiliationCache)
        engine.resume(id)
        assertEquals(1, endpoint.writes); assertEquals(0, endpoint.deletes)
        assertEquals("PasswordTransferOperation[redacted]", PasswordTransferOperation("fixture", null, account, null, data).toString())
    }
    companion object { private fun tag(chrome: ByteArray) = PasswordData.decode(Proto.decode(chrome).one(1)).tag() }
}
