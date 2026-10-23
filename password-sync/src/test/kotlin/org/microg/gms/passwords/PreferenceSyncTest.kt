/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class PreferenceSyncTest {
    private class Store : VaultStore {
        var bytes = VaultState().encode()
        override fun <T> locked(action: (VaultSession) -> T): T = action(object : VaultSession {
            override fun load() = VaultState.decode(bytes)
            override fun save(state: VaultState) { bytes = state.encode() }
        })
        fun state() = VaultState.decode(bytes)
    }
    private class Server : PasswordTransport {
        val parent = "users/me/birthdays/preference-fixture"
        val records = ArrayList<ByteArray>()
        var writes = 0
        var loseResponse = false
        var repeatToken = false
        var malformedPage = false
        var version = 0L
        var requiredClientToken: ByteArray? = null
        private fun resourceId(name: String) = "$parent/priority_preferences/" + PriorityPreference.tagFor(name).replace('/', '_').replace('+', '-')
        fun entity(name: String, value: String): ByteArray = Proto.decode(PriorityPreference.create(name, value).encode())
            .replace(1, resourceId(name).toByteArray())
            .replace(3, Proto.integer(1, ++version)).encode()
        override fun invoke(method: PasswordMethod, request: ByteArray): ByteArray {
            val body = Proto.decode(request)
            return when (method) {
                PasswordMethod.LIST_PREFERENCES -> {
                    assertEquals(parent, body.string(1)); assertNull(body.optional(3))
                    if (body.optional(4) != null) assertArrayEquals("page".toByteArray(), body.one(4))
                    if (malformedPage) Proto.field(1, Proto.field(4, "broken")) else
                        records.fold(byteArrayOf()) { all, record -> all + Proto.field(1, record) } +
                            (if (repeatToken) Proto.field(2, "page") else byteArrayOf())
                }
                PasswordMethod.CREATE_PREFERENCE, PasswordMethod.UPDATE_PREFERENCE -> {
                    requiredClientToken?.let { assertArrayEquals(it, body.one(4)) }
                    val incoming = PriorityPreference.decode(body.one(2))
                    assertNull(body.optional(3)); assertNotNull(body.optional(4))
                    val old = records.indexOfFirst { PriorityPreference.decode(it).tag == incoming.tag }
                    if (method == PasswordMethod.CREATE_PREFERENCE) {
                        assertEquals(parent, body.string(1))
                        if (old >= 0) throw PasswordRpcException(6, false)
                    } else {
                        assertEquals(incoming.id, body.string(1))
                        if (old < 0 || !PriorityPreference.decode(records[old]).version!!.contentEquals(incoming.version!!))
                            throw PasswordRpcException(10, false)
                    }
                    val result = Proto.decode(incoming.encode()).replace(1,
                        resourceId(incoming.name).toByteArray()).replace(3, Proto.integer(1, ++version)).encode()
                    if (old < 0) records.add(result) else records[old] = result
                    writes++
                    if (loseResponse) throw PasswordRpcException(14, true)
                    result
                }
                else -> throw AssertionError("Unexpected preference RPC")
            }
        }
    }
    private fun engine(server: Server, store: Store) = PreferenceSync(PasswordApi(server), store)

    @Test fun preferenceStaleMetadataRefreshesTheParentAndRetriesTheSameOperationOnce() {
        val server = Server(); val store = Store(); var attempts = 0; var metadataReads = 0
        val transport = PasswordTransport { method, request ->
            when {
                method == PasswordMethod.GET_METADATA -> { metadataReads++; Proto.field(1, server.parent) }
                method == PasswordMethod.CREATE_PREFERENCE && attempts++ == 0 -> throw PasswordRpcException(9, false)
                else -> server.invoke(method, request)
            }
        }
        val sync = PreferenceSync(PasswordApi(transport), store)
        sync.synchronize(server.parent)
        sync.enqueue("fixture", "true")
        val receipt = sync.synchronize(server.parent)
        assertEquals(2, attempts); assertEquals(1, metadataReads); assertEquals(1, server.writes)
        assertEquals(1, receipt.uploaded); assertTrue(store.state().preferenceMutations.isEmpty())
    }

    @Test fun preferenceReceiptCountsCommitsFromBeforeAndAfterTheMetadataRefresh() {
        val server = Server(); val store = Store(); var rejected = false
        val transport = PasswordTransport { method, request ->
            if (method == PasswordMethod.GET_METADATA) Proto.field(1, server.parent) else {
                if (method == PasswordMethod.CREATE_PREFERENCE && PriorityPreference.decode(Proto.decode(request).one(2)).name == "second" && !rejected) {
                    rejected = true; throw PasswordRpcException(9, false)
                }
                server.invoke(method, request)
            }
        }
        val sync = PreferenceSync(PasswordApi(transport), store)
        sync.synchronize(server.parent); sync.enqueue("first", "true"); sync.enqueue("second", "false")
        val receipt = sync.synchronize(server.parent)
        assertEquals(2, receipt.uploaded); assertEquals(2, server.writes)
        assertTrue(store.state().preferenceMutations.isEmpty())
    }

    @Test fun preferenceStaleMetadataCannotRebaseAcrossABirthdayReset() {
        val server = Server(); val store = Store()
        val transport = PasswordTransport { method, request ->
            when (method) {
                PasswordMethod.GET_METADATA -> Proto.field(1, "users/me/birthdays/replacement")
                PasswordMethod.CREATE_PREFERENCE -> throw PasswordRpcException(9, false)
                else -> server.invoke(method, request)
            }
        }
        val sync = PreferenceSync(PasswordApi(transport), store)
        sync.synchronize(server.parent)
        val operation = sync.enqueue("fixture", "true")
        assertThrows(VaultResetException::class.java) { sync.synchronize(server.parent) }
        assertEquals(0, server.writes); assertEquals(operation, store.state().preferenceMutations.single().id)
        assertEquals(MutationPhase.QUEUED, store.state().preferenceMutations.single().phase)
        assertEquals(server.parent, store.state().preferenceParent)
    }

    @Test fun incompletePreferenceSnapshotCannotAcknowledgePushOrClearThePasswordObligation() {
        val server = Server(); val store = Store()
        store.locked { session -> val state = session.load()
            state.invalidations = SyncInvalidations().receive("fixture", setOf(SyncDeviceInfo.NIGORI), ByteArray(32), 0)
            session.save(state)
        }
        server.repeatToken = true
        assertThrows(ProtocolException::class.java) { engine(server, store).synchronize(server.parent) }
        assertEquals(0L, store.state().invalidations.preferencesHandled)
        server.repeatToken = false
        engine(server, store).synchronize(server.parent)
        assertEquals(1L, store.state().invalidations.preferencesHandled)
        assertEquals(0L, store.state().invalidations.passwordsHandled)
        assertTrue(store.state().invalidations.pending)
    }

    @Test fun preferenceCreateAndUpdateUseAndPreserveRegisteredClientToken() {
        val server = Server(); val store = Store(); val token = byteArrayOf(9, 8, 7)
        server.requiredClientToken = token
        var registrations = 0
        val registration = DeviceRegistrationSync(PasswordTransport { method, _ ->
            assertEquals(PasswordMethod.CREATE_DEVICE, method); registrations++
            Proto.field(1, token) + Proto.field(2, "fixture-guid")
        }, { SyncDeviceInfo("Phone", "Maker", "123") })
        val sync = PreferenceSync(PasswordApi(server), store, registration)
        sync.synchronize(server.parent)
        sync.enqueue("fixture", "true"); sync.synchronize(server.parent)
        sync.enqueue("fixture", "false"); sync.synchronize(server.parent)
        assertEquals(1, registrations)
        assertEquals(2, server.writes)
        assertArrayEquals(token, store.state().deviceRegistration!!.token)
        assertTrue(store.state().preferenceMutations.isEmpty())
    }

    @Test fun createUpdateAndOfflineCoalescingUseThePriorityPreferenceWireLayout() {
        val server = Server(); val store = Store(); val sync = engine(server, store)
        sync.synchronize(server.parent)
        val first = sync.enqueue("credentials_enable_service", "true")
        assertEquals(first, sync.enqueue("credentials_enable_service", "false"))
        assertEquals("false", sync.value("credentials_enable_service"))
        assertEquals(1, sync.synchronize(server.parent).uploaded)
        sync.enqueue("credentials_enable_service", "true")
        assertEquals(1, sync.synchronize(server.parent).uploaded)
        assertEquals("true", engine(server, store).value("credentials_enable_service"))
        assertEquals(2, server.writes)
        assertTrue(store.state().mutations.isEmpty())
    }

    @Test fun lostCreateAndUpdateResponsesReconcileWithoutReplayingEitherEffect() {
        val server = Server(); val store = Store(); var sync = engine(server, store)
        sync.synchronize(server.parent)
        for (value in listOf("true", "false")) {
            sync.enqueue("credentials_enable_service", value)
            server.loseResponse = true
            assertThrows(PasswordRpcException::class.java) { sync.synchronize(server.parent) }
            assertEquals(MutationPhase.INDETERMINATE, store.state().preferenceMutations.single().phase)
            val writes = server.writes
            sync = engine(server, store)
            sync.synchronize(server.parent)
            assertEquals(writes, server.writes)
            assertTrue(store.state().preferenceMutations.isEmpty())
            assertEquals(value, sync.value("credentials_enable_service"))
        }
    }

    @Test fun concurrentRemoteEditRetainsTheLocalIntentAsAConflict() {
        val server = Server(); val store = Store(); val sync = engine(server, store)
        server.records.add(server.entity("credentials_enable_service", "true"))
        sync.synchronize(server.parent)
        sync.enqueue("credentials_enable_service", "false")
        server.records[0] = server.entity("credentials_enable_service", "null")
        assertEquals(1, sync.synchronize(server.parent).conflicts)
        assertEquals(0, server.writes)
        assertEquals("null", sync.value("credentials_enable_service"))
        assertEquals("false", PriorityPreference.decode(store.state().preferenceMutations.single().desired!!).value)
    }

    @Test fun incompleteSnapshotNeverReplacesPreviouslyAuthenticatedSettings() {
        val server = Server(); val store = Store(); val sync = engine(server, store)
        server.records.add(server.entity("credentials_enable_service", "true"))
        sync.synchronize(server.parent)
        val before = store.bytes.copyOf()
        server.repeatToken = true
        assertThrows(ProtocolException::class.java) { sync.synchronize(server.parent) }
        assertArrayEquals(before, store.bytes)
        server.repeatToken = false; server.malformedPage = true
        assertThrows(ProtocolException::class.java) { sync.synchronize(server.parent) }
        assertArrayEquals(before, store.bytes)
    }

    @Test fun oldVaultsMigrateAndNewJournalStateCarriesAnOldWriterFence() {
        val old = Proto.integer(1, 1) + Proto.integer(5, 123L)
        assertEquals(123L, VaultState.decode(old).lastSyncMillis)
        val server = Server(); val store = Store(); val sync = engine(server, store)
        sync.synchronize(server.parent)
        sync.enqueue("credentials_enable_service", "true")
        assertEquals(6L, Proto.decode(store.bytes).integer(1))
        val reopened = VaultState.decode(store.bytes)
        assertEquals(1, reopened.preferenceMutations.size)
        assertEquals("true", engine(server, store).value("credentials_enable_service"))
        assertThrows(ProtocolException::class.java) { VaultState.decode(Proto.integer(1, 7)) }
    }

    @Test fun unknownFieldsSurviveValueEditsAndAChangedVaultCannotReceiveOldQueuedSettings() {
        val source = Proto.decode(PriorityPreference.create("fixture", "true").encode()).replace(500, byteArrayOf(4, 5)).encode()
        assertArrayEquals(byteArrayOf(4, 5), Proto.decode(PriorityPreference.decode(source).withValue("false").encode()).one(500))
        val server = Server(); val store = Store(); val sync = engine(server, store)
        sync.synchronize(server.parent)
        sync.enqueue("fixture", "true")
        val before = store.bytes.copyOf()
        assertThrows(VaultResetException::class.java) { sync.synchronize("users/me/birthdays/replacement") }
        assertArrayEquals(before, store.bytes)
        assertEquals(0, server.writes)
    }
}
