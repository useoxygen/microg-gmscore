/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

/** Production owner coordinator over a serialized store and source-shaped RPCs; no account/server/device access. */
class PasskeyOwnerManagementTest {
    private val parent = "users/me/birthdays/owner-fixture"
    private val owner = "synthetic-stable-owner"
    private fun data(id: Int, user: Int = 7, rp: String = "example.invalid", extra: ByteArray = byteArrayOf()): SyncedPasskey =
        SyncedPasskey.decode(Proto.field(1, ByteArray(16) { id.toByte() }) + Proto.field(2, byteArrayOf(id.toByte())) +
            Proto.field(3, rp) + Proto.field(4, byteArrayOf(user.toByte())) + Proto.integer(6, id.toLong()) +
            Proto.field(7, "synthetic-user") + Proto.field(8, "Synthetic name") + Proto.field(12, ByteArray(28) { id.toByte() }) + extra)
    private fun resource(value: SyncedPasskey): ByteArray = Proto.field(1, "$parent/opaque/${value.tag}") +
        Proto.field(2, value.tag) + Proto.integer(3, 41) + Proto.field(7, value.encode()) +
        Proto.field(8, Proto.integer(1, 123) + Proto.integer(2, 456)) + Proto.field(93, "outer-future")
    private fun keys(): VaultKeys {
        val wrapping = NigoriKey(ByteArray(16) { 1 }, ByteArray(16) { 2 })
        return try { VaultKeys.unlock(Proto.field(1, parent) + Proto.field(3,
            Proto.field(1, wrapping.encrypt(Proto.field(2, wrapping.export()))) + Proto.integer(2, 4)), customKey = wrapping.export()) }
        finally { wrapping.destroy() }
    }
    private class ProcessDied : Error()
    private inner class Fixture(values: List<SyncedPasskey>) : AutoCloseable {
        val keys: VaultKeys = this@PasskeyOwnerManagementTest.keys()
        val remote = LinkedHashMap<String, ByteArray>()
        var bytes: ByteArray
        var activeAccount = owner
        var activeParent = parent
        var ownerAllowed = true
        val deletes = ArrayList<String>()
        val updates = ArrayList<ByteArray>()
        var snapshots = 0
        var afterSave: (PasskeyState) -> Unit = {}
        var deleting: (String) -> Unit = { name -> remote.remove(name); Unit }
        var updating: (ByteArray) -> ByteArray = { raw -> remote[Proto.decode(raw).string(1)] = raw.copyOf(); raw }
        init {
            values.map(::resource).forEach { remote[Proto.decode(it).string(1)] = it }
            bytes = PasskeyState(parent, remote.values.toList()).encode()
        }
        fun state() = PasskeyState.decode(bytes)
        fun save(state: PasskeyState) { bytes = state.encode(); afterSave(PasskeyState.decode(bytes)) }
        fun verify() {
            if (!ownerAllowed || activeAccount != owner) throw VaultLockedException()
            if (activeParent != parent) throw VaultResetException()
        }
        fun controller(boundOwner: String = owner, boundParent: String = parent): PasskeyOwnerManagement {
            val api = PasskeyApi(PasswordTransport { method, raw ->
                val request = Proto.decode(raw)
                when (method) {
                    PasswordMethod.LIST_WEBAUTHN -> {
                        assertEquals(boundParent, request.string(1)); snapshots++
                        remote.values.fold(byteArrayOf()) { all, record -> all + Proto.field(1, record) }
                    }
                    PasswordMethod.DELETE_WEBAUTHN -> {
                        val name = request.string(1)
                        assertArrayEquals(byteArrayOf(9), request.one(4))
                        assertEquals(PasskeyOwnerPhase.LAUNCHED, state().ownerMutation!!.steps.single { it.name == name }.phase)
                        deletes.add(name); deleting(name); byteArrayOf()
                    }
                    PasswordMethod.UPDATE_WEBAUTHN -> {
                        val record = request.one(2)
                        assertEquals(request.string(1), Proto.decode(record).string(1))
                        assertArrayEquals(byteArrayOf(9), request.one(4))
                        assertEquals(PasskeyOwnerPhase.LAUNCHED, state().ownerMutation!!.steps.single().phase)
                        updates.add(record.copyOf()); updating(record)
                    }
                    else -> error("Unexpected owner RPC")
                }
            })
            return PasskeyOwnerManagement(boundOwner, boundParent, keys, api, byteArrayOf(9), ::state, ::save, ::verify) { 2000 }
        }
        fun baseline(value: SyncedPasskey) = remote.values.single { Proto.decode(it).string(2) == value.tag }.copyOf()
        override fun close() { keys.destroy() }
    }

    @Test fun renameChangesOnlyNamesAndEditedFlagWhilePreservingOpaqueSecretVersionAndUnknownFields() {
        val original = data(1, extra = Proto.integer(13, 999) + Proto.integer(14, 3) + Proto.integer(18, 7) +
            Proto.field(89, "future-specifics") + Proto.integer(90, 12345))
        Fixture(listOf(original)).use { f ->
            val before = f.baseline(original)
            f.controller().rename(before, "updated-user", "Updated name")
            assertEquals(1, f.updates.size); assertTrue(f.deletes.isEmpty())
            val wire = Proto.decode(f.updates.single())
            val actual = PasskeyResourceCodec.read(f.updates.single(), f.keys)
            assertEquals("updated-user", actual.username); assertEquals("Updated name", actual.displayName); assertTrue(actual.editedByUser)
            fun unedited(value: SyncedPasskey) = Proto.decode(value.encode()).replace(7, null).replace(8, null).replace(15, null).encode()
            assertArrayEquals(unedited(original), unedited(actual))
            assertArrayEquals(original.ciphertext, actual.ciphertext)
            assertEquals(41L, wire.integer(3)); assertArrayEquals(Proto.decode(before).one(8), wire.one(8))
            assertEquals("outer-future", wire.string(93)); assertNotNull(wire.optional(4)); assertNull(wire.optional(7))
            assertNull(f.state().ownerMutation)
            val receipt = PasskeyOwnerMutation.decode(f.state().ownerHistory.single())
            assertEquals(PasskeyOwnerKind.RENAME, receipt.kind); assertEquals(PasskeyOwnerPhase.COMMITTED, receipt.steps.single().phase)
        }
    }

    @Test fun activeDeleteCoversReviewedHiddenAndOlderSiblingsButShadowedDeleteCoversOnlySelectedResource() {
        val old = data(1)
        val active = data(2, extra = Proto.field(5, old.credentialId))
        val hidden = data(3, extra = Proto.integer(16, 1))
        val otherUser = data(4, user = 8); val otherRp = data(5, rp = "other.invalid")
        Fixture(listOf(old, active, hidden, otherUser, otherRp)).use { f ->
            val reviewed = listOf(old, active, hidden).map(f::baseline)
            f.controller().delete(f.baseline(active), reviewed)
            assertEquals(reviewed.map { Proto.decode(it).string(1) }.sorted(), f.deletes)
            assertEquals(setOf(otherUser.tag, otherRp.tag), f.remote.values.map { Proto.decode(it).string(2) }.toSet())
        }
        Fixture(listOf(old, active, hidden)).use { f ->
            val selected = f.baseline(old)
            f.controller().delete(selected, listOf(selected))
            assertEquals(listOf(Proto.decode(selected).string(1)), f.deletes)
            assertEquals(setOf(active.tag, hidden.tag), f.remote.values.map { Proto.decode(it).string(2) }.toSet())
        }
    }

    @Test fun refreshedGroupCannotSilentlyExpandConsentOrDeleteChangedSiblingBytes() {
        val a = data(1); val b = data(2)
        for (newSibling in listOf(false, true)) Fixture(listOf(a, b)).use { f ->
            val selected = f.baseline(b); val reviewed = f.remote.values.map { it.copyOf() }
            if (newSibling) {
                val added = resource(data(3)); f.remote[Proto.decode(added).string(1)] = added
            } else {
                val name = Proto.decode(f.baseline(a)).string(1)
                f.remote[name] = Proto.decode(f.remote[name]!!).replaceInteger(3, 42).encode()
            }
            assertThrows(ConflictException::class.java) { f.controller().delete(selected, reviewed) }
            assertTrue(f.deletes.isEmpty()); assertNull(f.state().ownerMutation)
        }
    }

    @Test fun unknownPartialGroupSurvivesReopenAndOnlyExplicitRetryArchivesThenLaunchesRemainingFiniteSteps() {
        val a = data(1); val b = data(2); val c = data(3)
        Fixture(listOf(a, b, c)).use { f ->
            val reviewed = f.remote.values.map { it.copyOf() }
            val ordered = reviewed.sortedBy { Proto.decode(it).string(1) }
            val unknownName = Proto.decode(ordered[1]).string(1)
            f.deleting = { name -> if (name == unknownName) throw PasswordRpcException(14, true) else { f.remote.remove(name); Unit } }
            assertThrows(PasswordRpcException::class.java) { f.controller().delete(f.baseline(c), reviewed) }
            val old = f.state().ownerMutation!!
            assertEquals(listOf(PasskeyOwnerPhase.COMMITTED, PasskeyOwnerPhase.INDETERMINATE, PasskeyOwnerPhase.QUEUED), old.steps.map { it.phase })
            f.controller().refresh() // Reopened controller, serialized journal: no automatic dispatch.
            assertEquals(2, f.deletes.size); assertEquals(old.id, f.state().ownerMutation!!.id)
            val later = resource(data(4)); f.remote[Proto.decode(later).string(1)] = later
            f.deleting = { name -> f.remote.remove(name); Unit }
            f.controller().retry(old.id)
            assertEquals(4, f.deletes.size)
            assertEquals(setOf(Proto.decode(later).string(1)), f.remote.keys)
            assertNull(f.state().ownerMutation)
            val receipts = f.state().ownerHistory.map(PasskeyOwnerMutation::decode)
            assertEquals(2, receipts.size); assertEquals(old.id, receipts[0].id); assertNotEquals(old.id, receipts[1].id)
            assertEquals(old.steps.map { it.phase }, receipts[0].steps.map { it.phase })
            old.steps.indices.forEach { assertArrayEquals(old.steps[it].before, receipts[0].steps[it].before) }
            assertEquals(old.steps.drop(1).map { it.name }, receipts[1].steps.map { it.name })
        }
    }

    @Test fun processDeathAfterDeleteOrRenameIsConfirmedByFullReadbackWithoutReplay() {
        for (rename in listOf(false, true)) Fixture(listOf(data(1))).use { f ->
            val baseline = f.remote.values.single().copyOf()
            if (rename) f.updating = { raw -> f.remote[Proto.decode(raw).string(1)] = raw.copyOf(); throw ProcessDied() }
            else f.deleting = { name -> f.remote.remove(name); throw ProcessDied() }
            assertThrows(ProcessDied::class.java) {
                if (rename) f.controller().rename(baseline, "new-user", "New name") else f.controller().delete(baseline, listOf(baseline))
            }
            assertEquals(PasskeyOwnerPhase.LAUNCHED, f.state().ownerMutation!!.steps.single().phase)
            f.controller().refresh()
            assertEquals(1, f.deletes.size + f.updates.size)
            assertNull(f.state().ownerMutation)
            assertEquals(PasskeyOwnerPhase.COMMITTED, PasskeyOwnerMutation.decode(f.state().ownerHistory.single()).steps.single().phase)
        }
    }

    @Test fun aPersistedLaunchBeforeDispatchNeverAutomaticallyRetriesAfterProcessDeath() {
        Fixture(listOf(data(1))).use { f ->
            val baseline = f.remote.values.single().copyOf()
            f.afterSave = { state -> if (state.ownerMutation?.steps?.singleOrNull()?.phase == PasskeyOwnerPhase.LAUNCHED) throw ProcessDied() }
            assertThrows(ProcessDied::class.java) { f.controller().delete(baseline, listOf(baseline)) }
            assertTrue(f.deletes.isEmpty())
            f.afterSave = {}
            f.controller().refresh()
            assertEquals(PasskeyOwnerPhase.INDETERMINATE, f.state().ownerMutation!!.steps.single().phase)
            assertTrue(f.deletes.isEmpty())
        }
    }

    @Test fun definiteRefusalOrRevocationBeforeDispatchRemainsExplicitlyRetryableWithoutClaimingCommit() {
        for (refusal in listOf(false, true)) Fixture(listOf(data(1))).use { f ->
            val baseline = f.remote.values.single().copyOf()
            if (refusal) f.deleting = { throw PasswordRpcException(7, false) }
            else f.afterSave = { state -> if (state.ownerMutation?.steps?.singleOrNull()?.phase == PasskeyOwnerPhase.LAUNCHED) f.ownerAllowed = false }
            assertThrows(Exception::class.java) { f.controller().delete(baseline, listOf(baseline)) }
            val old = f.state().ownerMutation!!
            assertEquals(if (refusal) PasskeyOwnerPhase.REFUSED else PasskeyOwnerPhase.QUEUED, old.steps.single().phase)
            assertEquals(if (refusal) 1 else 0, f.deletes.size)
            f.afterSave = {}; f.ownerAllowed = true
            f.controller().refresh()
            assertEquals(if (refusal) 1 else 0, f.deletes.size)
            f.deleting = { name -> f.remote.remove(name); Unit }
            f.controller().retry(old.id)
            assertNull(f.state().ownerMutation)
            val history = f.state().ownerHistory.map(PasskeyOwnerMutation::decode)
            assertEquals(2, history.size); assertEquals(old.id, history[0].id); assertNotEquals(old.id, history[1].id)
            assertEquals(old.steps.single().phase, history[0].steps.single().phase)
        }
    }

    @Test fun renameWithoutServerEditedFlagIsNotACommitAndCannotReplayOnReadback() {
        Fixture(listOf(data(1))).use { f ->
            val baseline = f.remote.values.single().copyOf()
            f.updating = { requested ->
                val desired = PasskeyResourceCodec.read(requested, f.keys)
                val ignoredFlag = SyncedPasskey.decode(Proto.decode(desired.encode()).replace(15, null).encode())
                PasskeyResourceCodec.write(ignoredFlag, f.keys, requested).also { f.remote[Proto.decode(it).string(1)] = it }
            }
            assertThrows(ProtocolException::class.java) { f.controller().rename(baseline, "changed-user", "Changed name") }
            assertEquals(PasskeyOwnerPhase.INDETERMINATE, f.state().ownerMutation!!.steps.single().phase)
            f.controller().refresh()
            assertEquals(PasskeyOwnerPhase.CONFLICT, f.state().ownerMutation!!.steps.single().phase)
            assertEquals(1, f.updates.size)
        }
    }

    @Test fun changedSnapshotAndAccountOrBirthdayRevocationCannotLaunchOrRetry() {
        for (gate in listOf("owner", "account", "birthday")) Fixture(listOf(data(1))).use { f ->
            val baseline = f.remote.values.single().copyOf()
            when (gate) { "owner" -> f.ownerAllowed = false; "account" -> f.activeAccount = "removed-and-readded"; else -> f.activeParent = "users/me/birthdays/reset" }
            assertThrows(Exception::class.java) { f.controller().delete(baseline, listOf(baseline)) }
            assertTrue(f.deletes.isEmpty()); assertTrue(f.updates.isEmpty()); assertEquals(0, f.snapshots)
        }
        Fixture(listOf(data(1))).use { f ->
            val baseline = f.remote.values.single().copyOf()
            f.deleting = { throw PasswordRpcException(14, true) }
            assertThrows(PasswordRpcException::class.java) { f.controller().delete(baseline, listOf(baseline)) }
            val operation = f.state().ownerMutation!!
            assertThrows(VaultLockedException::class.java) { f.controller(boundOwner = "same-name-new-owner").refresh() }
            assertThrows(VaultResetException::class.java) { f.controller(boundParent = "users/me/birthdays/reset").refresh() }
            val changed = Proto.decode(baseline).replaceInteger(3, 42).encode()
            f.remote[Proto.decode(changed).string(1)] = changed
            assertThrows(ConflictException::class.java) { f.controller().retry(operation.id) }
            assertEquals(1, f.deletes.size); assertEquals(PasskeyOwnerPhase.CONFLICT, f.state().ownerMutation!!.steps.single().phase)
        }
    }

    @Test fun explicitClosureRetainsTheUnknownReceiptWithoutDeletingOrRestoringAnythingRemote() {
        Fixture(listOf(data(1))).use { f ->
            val baseline = f.remote.values.single().copyOf()
            f.deleting = { throw PasswordRpcException(14, true) }
            assertThrows(PasswordRpcException::class.java) { f.controller().delete(baseline, listOf(baseline)) }
            val unknown = f.state().ownerMutation!!
            assertThrows(VaultLockedException::class.java) { f.controller(boundOwner = "replacement-account").close(unknown.id) }
            f.controller().close(unknown.id)
            assertNull(f.state().ownerMutation)
            assertArrayEquals(unknown.encode(), f.state().ownerHistory.single())
            assertArrayEquals(baseline, f.remote.values.single()); assertEquals(1, f.deletes.size)
        }
    }

    @Test fun authorityRevokedWhileRemoteMutationReturnsCannotReleaseSuccessOrPersistACommit() {
        for (reset in listOf(false, true)) Fixture(listOf(data(1))).use { f ->
            val baseline = f.remote.values.single().copyOf()
            f.deleting = { name ->
                f.remote.remove(name)
                if (reset) f.activeParent = "users/me/birthdays/reset" else f.activeAccount = "replacement-account"
            }
            assertThrows(Exception::class.java) { f.controller().delete(baseline, listOf(baseline)) }
            assertEquals(1, f.deletes.size); assertTrue(f.remote.isEmpty())
            assertEquals(PasskeyOwnerPhase.INDETERMINATE, f.state().ownerMutation!!.steps.single().phase)
            assertTrue(f.state().ownerHistory.isEmpty())
        }
    }

    @Test fun stateV2RetainsUncertainCreateAndOwnerObligationsWhileV1CannotSmuggleOwnerFields() {
        val raw = resource(data(1))
        val operation = PasskeyOwnerMutation("synthetic-operation", owner, parent, PasskeyOwnerKind.DELETE,
            listOf(PasskeyOwnerStep(raw, null, PasskeyOwnerPhase.INDETERMINATE)))
        val old = PasskeyState(parent, listOf(raw), 123, raw, true, listOf(Proto.field(1, "old-create")), operation,
            listOf(operation.encode()))
        val encoded = old.encode(); assertEquals(2L, Proto.decode(encoded).integer(1))
        val reopened = PasskeyState.decode(encoded)
        assertArrayEquals(raw, reopened.pending); assertTrue(reopened.pendingLaunched)
        assertEquals(PasskeyOwnerPhase.INDETERMINATE, reopened.ownerMutation!!.steps.single().phase)
        assertArrayEquals(old.abandoned.single(), reopened.abandoned.single())
        assertArrayEquals(operation.encode(), reopened.ownerHistory.single())
        val v1 = Proto.decode(PasskeyState(parent, listOf(raw)).encode()).replaceInteger(1, 1).encode()
        assertEquals(1, PasskeyState.decode(v1).records.size)
        assertThrows(ProtocolException::class.java) { PasskeyState.decode(Proto.decode(encoded).replaceInteger(1, 1).encode()) }
        val keys = keys()
        try {
            assertThrows(VaultResetException::class.java) { PasskeySnapshots.reconcile(reopened, "users/me/birthdays/reset", emptyList(), keys, 2000) }
        } finally { keys.destroy() }
    }
}
