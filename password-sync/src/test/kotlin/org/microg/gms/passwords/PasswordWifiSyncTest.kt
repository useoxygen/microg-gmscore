/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class PasswordWifiSyncTest {
    private val parent = "users/me/birthdays/wifi-fixture"
    private fun network(key: String = "synthetic-passphrase", metered: Int = 3) = PasswordWifiNetwork.create("46697874757265", PasswordWifiNetwork.PSK, key, metered)
    private fun keys(): VaultKeys {
        val password = "synthetic-vault-passphrase".toByteArray()
        val key = NigoriKey.derive(password)
        val metadata = Proto.field(1, parent) + Proto.field(3, Proto.field(1, key.encrypt(Proto.field(2, key.export()))) + Proto.integer(2, 3))
        return try { VaultKeys.unlock(metadata, customPassphrase = password) } finally { key.destroy(); password.fill(0) }
    }
    private class Store : VaultStore {
        var bytes = VaultState().encode()
        var afterSave: ((VaultState) -> Unit)? = null
        override fun <T> locked(action: (VaultSession) -> T): T = action(object : VaultSession {
            override fun load(): VaultState = VaultState.decode(bytes)
            override fun save(state: VaultState) { bytes = state.encode(); afterSave?.invoke(VaultState.decode(bytes)) }
        })
        fun enable() = locked { session -> val state = session.load(); val wifi = PasswordWifiState.decode(null); wifi.enabled = true; state.wifiSync = wifi.encode(); session.save(state) }
        fun wifi(): PasswordWifiState = PasswordWifiState.decode(VaultState.decode(bytes).wifiSync)
    }
    private inner class Server(private val keys: VaultKeys) : PasswordTransport {
        val records = HashMap<String, ByteArray>()
        var lists = 0; var writes = 0; var serial = 0L; var loseResponse = false; var commit = true; var duplicate = false
        fun put(network: PasswordWifiNetwork): ByteArray {
            val raw = Proto.decode(PasswordWifiResource.write(network, keys, records[network.tag()]))
                .replace(1, "$parent/networks/fixture".toByteArray()).replace(5, Proto.integer(1, ++serial)).encode()
            records[network.tag()] = raw; return raw
        }
        override fun invoke(method: PasswordMethod, request: ByteArray): ByteArray {
            val p = Proto.decode(request)
            return when (method) {
                PasswordMethod.LIST_WIFI -> {
                    lists++; assertEquals(parent, p.string(1)); assertNull(p.optional(4))
                    records.values.fold(byteArrayOf()) { a, raw -> a + Proto.field(1, raw) + if (duplicate) Proto.field(1, raw) else byteArrayOf() }
                }
                PasswordMethod.CREATE_WIFI, PasswordMethod.UPDATE_WIFI -> {
                    assertArrayEquals(byteArrayOf(4, 2), p.one(4)); assertNull(p.optional(3))
                    val desired = PasswordWifiResource.read(p.one(2), keys)
                    if (method == PasswordMethod.CREATE_WIFI) { assertEquals(parent, p.string(1)); assertFalse(records.containsKey(desired.tag())) }
                    else { val previous = Proto.decode(records[desired.tag()]!!); assertEquals(previous.string(1), p.string(1)); assertArrayEquals(previous.one(5), Proto.decode(p.one(2)).one(5)) }
                    writes++
                    val result = if (commit) put(desired) else p.one(2)
                    if (loseResponse) throw PasswordRpcException(14, true)
                    result
                }
                PasswordMethod.DELETE_WIFI -> {
                    assertArrayEquals(byteArrayOf(4, 2), p.one(4)); assertNull(p.optional(2)); assertNull(p.optional(5))
                    val old = records.entries.single { Proto.decode(it.value).string(1) == p.string(1) }
                    records.remove(old.key); writes++; byteArrayOf()
                }
                else -> throw AssertionError("Unexpected Wi-Fi RPC")
            }
        }
    }
    private class Device : PasswordWifiPlatform {
        override val uid = 1000
        val records = HashMap<String, PasswordWifiDeviceNetwork>()
        var reads = 0; var adds = 0; var updates = 0; var removals = 0; var loseAdd = false
        override fun snapshot(): List<PasswordWifiDeviceNetwork> { reads++; return records.values.toList() }
        override fun add(network: PasswordWifiNetwork): Int {
            adds++; val id = 20 + adds
            records[network.tag()] = PasswordWifiDeviceNetwork(id, uid, true, network)
            if (loseAdd) throw PasswordRpcException(14, true)
            return id
        }
        override fun update(before: PasswordWifiDeviceNetwork, network: PasswordWifiNetwork): Int {
            assertEquals(uid, before.creatorUid); updates++
            records[network.tag()] = PasswordWifiDeviceNetwork(before.networkId, uid, true, network); return before.networkId
        }
        override fun remove(before: PasswordWifiDeviceNetwork): Boolean {
            assertEquals(uid, before.creatorUid); removals++; records.remove(before.network.tag()); return true
        }
    }
    private fun sync(store: Store, server: Server, device: Device, keys: VaultKeys, check: () -> Unit = {}, currentParent: String = parent,
        ownerRetry: Boolean = false): PasswordWifiState = store.locked { session ->
        PasswordWifiMerge(PasswordWifiApi(server), device, check).synchronize(session, session.load(), currentParent, keys, byteArrayOf(4, 2), ownerRetry)
    }

    @Test fun optInIsRequiredBeforeAnyNativeCredentialReadOrCloudRequest() {
        val keys = keys()
        try { val store = Store(); val server = Server(keys); val device = Device()
            assertFalse(sync(store, server, device, keys).enabled)
            assertEquals(0, server.lists); assertEquals(0, device.reads); assertEquals(0, device.adds)
        } finally { keys.destroy() }
    }
    @Test fun exactWireEncryptionRawHexAndMeteredValuesRoundTripWithUnknownFields() {
        val keys = keys()
        try {
            for ((security, key) in listOf(PasswordWifiNetwork.PSK to "aB".repeat(32), PasswordWifiNetwork.WEP to "0a".repeat(5), PasswordWifiNetwork.WEP to "MiXeD word")) {
                val data = PasswordWifiNetwork.create("46697874757265", security, key, 2)
                assertEquals(if (key.matches(Regex("[0-9a-fA-F]+"))) key else "\"$key\"", PasswordWifiNetwork.frameworkKey(data))
                assertEquals(1, PasswordWifiNetwork.frameworkMetered(data.metered))
                assertEquals(2, PasswordWifiNetwork.syncedMetered(1)); assertEquals(1, PasswordWifiNetwork.syncedMetered(2)); assertEquals(3, PasswordWifiNetwork.syncedMetered(0))
                assertEquals("MiXeD", PasswordWifiNetwork.frameworkValue("\"MiXeD\""))
                val raw = PasswordWifiResource.write(data, keys, Proto.field(2, data.tag()) + Proto.field(500, byteArrayOf(9)))
                assertArrayEquals(byteArrayOf(9), Proto.decode(raw).one(500))
                val plain = keys.decrypt(Proto.decode(raw).one(6))
                try { assertArrayEquals(data.encode(), Proto.decode(plain).one(662827)) } finally { plain.fill(0) }
                assertTrue(data.sameConfiguration(PasswordWifiResource.read(raw, keys)))
            }
            assertThrows(ProtocolException::class.java) { PasswordWifiNetwork.create("A", PasswordWifiNetwork.PSK, "synthetic") }
            assertThrows(ProtocolException::class.java) { PasswordWifiNetwork.create("46", 1, "synthetic") }
            assertThrows(ProtocolException::class.java) { PasswordWifiNetwork.create("46", PasswordWifiNetwork.PSK, "*") }
        } finally { keys.destroy() }
    }
    @Test fun accountNetworksReachTheNativeSinkAndOnlyOwnedNetworksFollowRemoteEditsAndDeletion() {
        val keys = keys()
        try { val store = Store(); store.enable(); val server = Server(keys); val device = Device(); server.put(network())
            sync(store, server, device, keys); assertEquals(1, device.adds); assertTrue(network().sameConfiguration(device.records.values.single().network))
            server.put(network("changed-synthetic", 2)); sync(store, server, device, keys)
            assertEquals(1, device.updates); assertEquals(2, device.records.values.single().network.metered)
            server.records.clear(); sync(store, server, device, keys)
            assertEquals(1, device.removals); assertTrue(device.records.isEmpty())
        } finally { keys.destroy() }
    }
    @Test fun preexistingUnownedAndExcludedNetworksAreNeverOverwrittenOrDeleted() {
        val keys = keys()
        try { for (eligible in listOf(true, false)) {
            val store = Store(); store.enable(); val server = Server(keys); val device = Device()
            server.put(network()); device.records[network().tag()] = PasswordWifiDeviceNetwork(7, 2000, eligible, network())
            sync(store, server, device, keys)
            server.put(network("changed-synthetic")); val result = sync(store, server, device, keys)
            assertTrue(result.conflicts > 0); assertEquals(0, device.updates); assertEquals(0, device.adds)
            server.records.clear(); sync(store, server, device, keys)
            assertEquals(0, device.removals); assertEquals(1, device.records.size)
        } } finally { keys.destroy() }
    }
    @Test fun nativeLostOutcomeReconcilesExistenceWithoutInventingAccountOwnership() {
        val keys = keys()
        try { val store = Store(); store.enable(); val server = Server(keys); val device = Device(); server.put(network()); device.loseAdd = true
            assertThrows(PasswordRpcException::class.java) { sync(store, server, device, keys) }
            assertEquals(1, store.wifi().indeterminate)
            device.loseAdd = false; sync(store, server, device, keys)
            assertEquals(1, device.adds); assertEquals(0, store.wifi().pending)
            server.put(network("changed-synthetic")); assertTrue(sync(store, server, device, keys).conflicts > 0)
            assertEquals(0, device.updates)
            server.records.clear(); sync(store, server, device, keys)
            assertEquals(0, device.removals); assertEquals(1, device.records.size)
        } finally { keys.destroy() }
    }

    @Test fun queuedCreateForAnotherAccountCannotClaimSameUidNetworkOrChangeAndDeleteIt() {
        val keys = keys()
        try {
            val accountA = Store(); accountA.enable(); val accountB = Store(); accountB.enable()
            val serverA = Server(keys); serverA.put(network()); val serverB = Server(keys); serverB.put(network())
            val device = Device()
            accountB.afterSave = { state ->
                if (PasswordWifiState.decode(state.wifiSync).deviceMutations.any { it.phase == MutationPhase.QUEUED })
                    throw VaultLockedException()
            }
            assertThrows(VaultLockedException::class.java) { sync(accountB, serverB, device, keys) }
            assertEquals(0, device.adds); accountB.afterSave = null
            sync(accountA, serverA, device, keys); assertEquals(1, device.adds)
            sync(accountB, serverB, device, keys)
            assertEquals(0, accountB.wifi().pending); assertEquals(1, device.adds)
            serverB.put(network("account-b-edit"))
            assertTrue(sync(accountB, serverB, device, keys).conflicts > 0); assertEquals(0, device.updates)
            serverB.records.clear(); sync(accountB, serverB, device, keys)
            assertEquals(0, device.removals); assertEquals(1, device.records.size)
            serverA.put(network("account-a-edit")); sync(accountA, serverA, device, keys)
            assertEquals(1, device.updates)
        } finally { keys.destroy() }
    }

    @Test fun aDurablyRecordedNativeAddResultRetainsOwnershipAfterReadbackInterruption() {
        val keys = keys()
        try {
            val store = Store(); store.enable(); val server = Server(keys); server.put(network()); val device = Device()
            var interrupt = true
            store.afterSave = { state ->
                if (interrupt && PasswordWifiState.decode(state.wifiSync).deviceMutations.any { it.phase == MutationPhase.LAUNCHED && it.name != null }) {
                    interrupt = false; throw ProtocolException()
                }
            }
            assertThrows(ProtocolException::class.java) { sync(store, server, device, keys) }
            store.afterSave = null
            sync(store, server, device, keys); assertEquals(1, device.adds); assertEquals(0, store.wifi().pending)
            server.put(network("changed-synthetic")); sync(store, server, device, keys)
            assertEquals(1, device.updates)
            server.records.clear(); sync(store, server, device, keys); assertEquals(1, device.removals)
        } finally { keys.destroy() }
    }
    @Test fun cloudLostOutcomeDoesNotReplayUnlessOwnerExplicitlyAuthorizesCompatibleNewAttempt() {
        val keys = keys()
        try { for (committed in listOf(true, false)) {
            val store = Store(); store.enable(); val server = Server(keys); val device = Device()
            device.records[network().tag()] = PasswordWifiDeviceNetwork(1, 2000, true, network())
            server.loseResponse = true; server.commit = committed
            assertThrows(PasswordRpcException::class.java) { sync(store, server, device, keys) }
            server.loseResponse = false; sync(store, server, device, keys)
            assertEquals(1, server.writes); assertEquals(if (committed) 0 else 1, store.wifi().indeterminate)
            if (!committed) { server.commit = true; sync(store, server, device, keys, ownerRetry = true); assertEquals(2, server.writes) }
            assertEquals(0, store.wifi().pending)
        } } finally { keys.destroy() }
    }
    @Test fun accountRevocationAtDurableLaunchFencePreventsTheNativeCallAndPreservesRecovery() {
        val keys = keys()
        try { val store = Store(); store.enable(); val server = Server(keys); val device = Device(); server.put(network()); var admitted = true
            store.afterSave = { if (PasswordWifiState.decode(it.wifiSync).deviceMutations.any { m -> m.phase == MutationPhase.LAUNCHED }) admitted = false }
            assertThrows(VaultLockedException::class.java) { sync(store, server, device, keys, { if (!admitted) throw VaultLockedException() }) }
            assertEquals(0, device.adds); assertEquals(1, store.wifi().pending)
        } finally { keys.destroy() }
    }
    @Test fun removalOfAnUploadedLocalNetworkDeletesOnlyItsAccountResource() {
        val keys = keys()
        try { val store = Store(); store.enable(); val server = Server(keys); val device = Device()
            device.records[network().tag()] = PasswordWifiDeviceNetwork(1, 2000, true, network())
            sync(store, server, device, keys); assertEquals(1, server.writes)
            device.records.clear(); sync(store, server, device, keys)
            assertEquals(2, server.writes); assertTrue(server.records.isEmpty()); assertEquals(0, device.removals)
        } finally { keys.destroy() }
    }
    @Test fun resetAndMalformedSnapshotCannotInferNativeDeletionOrRetargetOldJournal() {
        val keys = keys()
        try { val store = Store(); store.enable(); val server = Server(keys); val device = Device(); server.put(network())
            sync(store, server, device, keys); val saved = store.bytes.copyOf(); val lists = server.lists
            assertThrows(VaultResetException::class.java) { sync(store, server, device, keys, currentParent = "users/me/birthdays/replacement") }
            assertArrayEquals(saved, store.bytes); assertEquals(lists, server.lists); assertEquals(0, device.removals)
            server.duplicate = true
            assertThrows(ProtocolException::class.java) { sync(store, server, device, keys) }
            assertEquals(0, device.removals); assertArrayEquals(saved, store.bytes)
        } finally { keys.destroy() }
    }
    @Test fun wifiJournalSurvivesVaultSerializationAndFutureWriterFence() {
        val state = VaultState(); val wifi = PasswordWifiState.decode(null); wifi.enabled = true
        state.wifiSync = wifi.encode(); state.sharingJournal = byteArrayOf(9)
        val raw = state.encode(); assertEquals(5L, Proto.decode(raw).integer(1))
        assertTrue(PasswordWifiState.decode(VaultState.decode(raw).wifiSync).enabled)
        assertArrayEquals(byteArrayOf(9), VaultState.decode(raw).sharingJournal)
        assertThrows(ProtocolException::class.java) { VaultState.decode(Proto.decode(raw).replaceInteger(1, 6).encode()) }
    }
}
