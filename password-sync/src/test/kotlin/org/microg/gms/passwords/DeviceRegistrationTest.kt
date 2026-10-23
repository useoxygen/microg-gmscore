/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class DeviceRegistrationTest {
    private val parent = "users/me/birthdays/fixture"
    private class Store : VaultStore {
        var bytes = VaultState().encode()
        override fun <T> locked(action: (VaultSession) -> T): T = action(object : VaultSession {
            override fun load() = VaultState.decode(bytes)
            override fun save(state: VaultState) { bytes = state.encode() }
        })
        fun read() = VaultState.decode(bytes)
    }
    private fun register(store: Store, sync: DeviceRegistrationSync, parent: String = this.parent): ByteArray =
        store.locked { sync.token(it, it.load(), parent) }

    @Test fun exactDeviceFieldsAndEntityMethodStaySeparateFromPasswordOrigin() {
        val info = Proto.decode(SyncDeviceInfo("Fixture phone", "Fixture maker", "123", pushToken = "fixture-push").encode())
        assertEquals("Fixture phone", info.string(1))
        assertEquals("123", Proto.decode(info.one(2)).string(1))
        assertEquals(1L, info.integer(5))
        assertEquals(604800L, Proto.decode(info.one(6)).integer(1))
        assertEquals("fixture-push", info.string(7))
        assertArrayEquals(listOf(45873L, 47745L, 163425L).fold(byteArrayOf()) { out, n -> out + Proto.varint(n) }, info.one(8))
        assertEquals(1L, info.integer(9))
        assertNull(Proto.decode(SyncDeviceInfo("Phone", "Maker", "123").encode()).optional(7))
        var tokenReads = 0
        val transport = GooglePasswordTransport({ tokenReads++; "fixture-auth" }, "https://chromesyncpasswords-pa.googleapis.com".toHttpUrl())
        assertThrows(ProtocolException::class.java) { transport.invoke(PasswordMethod.CREATE_DEVICE, byteArrayOf()) }
        assertEquals(0, tokenReads)
    }

    @Test fun lostCreateResponseReusesDurableNonceInFreshProcessAndRetainsOtherJournals() {
        val store = Store()
        store.locked { session -> val state = session.load(); state.chromeLocalData["existing"] = byteArrayOf(7); session.save(state) }
        var created = false
        var firstNonce: ByteArray? = null
        var attempts = 0
        val transport = PasswordTransport { method, raw ->
            assertEquals(PasswordMethod.CREATE_DEVICE, method)
            val request = Proto.decode(raw)
            assertEquals(parent, request.string(2))
            assertEquals(16, request.one(4).size)
            assertArrayEquals(request.one(4), store.read().deviceRegistration!!.nonce)
            if (firstNonce == null) firstNonce = request.one(4) else assertArrayEquals(firstNonce, request.one(4))
            attempts++
            if (!created) { created = true; throw PasswordRpcException(14, true) }
            Proto.field(1, byteArrayOf(9, 8)) + Proto.field(2, "fixture-guid")
        }
        val info = { SyncDeviceInfo("Phone", "Maker", "123") }
        assertThrows(PasswordRpcException::class.java) { register(store, DeviceRegistrationSync(transport, info)) }
        assertNull(store.read().deviceRegistration!!.token)
        assertArrayEquals(byteArrayOf(9, 8), register(store, DeviceRegistrationSync(transport, info)))
        assertEquals(2, attempts)
        assertArrayEquals(byteArrayOf(7), store.read().chromeLocalData["existing"])
        assertEquals("fixture-guid", store.read().deviceRegistration!!.cacheGuid)
    }

    @Test fun unchangedRegistrationCachesTokenAndChangedInfoRotatesItAtomically() {
        val store = Store(); var version = "123"; var calls = 0; var time = 10_000L
        val transport = PasswordTransport { method, raw ->
            calls++
            if (calls == 1) assertEquals(PasswordMethod.CREATE_DEVICE, method)
            else {
                assertEquals(PasswordMethod.UPDATE_DEVICE, method)
                assertArrayEquals(byteArrayOf((calls - 1).toByte()), Proto.decode(raw).one(1))
                assertEquals(version, Proto.decode(Proto.decode(Proto.decode(raw).one(2)).one(2)).string(1))
            }
            Proto.field(1, byteArrayOf(calls.toByte())) + Proto.field(2, "fixture-guid")
        }
        val sync = DeviceRegistrationSync(transport, { SyncDeviceInfo("Phone", "Maker", version) }, { time })
        register(store, sync); register(store, sync)
        assertEquals(1, calls)
        version = "124"; register(store, sync)
        assertEquals(2, calls); assertArrayEquals(byteArrayOf(2), store.read().deviceRegistration!!.token)
        time += SyncDeviceInfo.REFRESH_SECONDS * 1000
        register(store, sync); assertEquals(3, calls)
        time = 1; register(store, sync); assertEquals(4, calls)
    }

    @Test fun failedUpdateOrMalformedReceiptCannotReplaceAcknowledgedToken() {
        val store = Store(); var version = "123"; var mode = 0
        val sync = DeviceRegistrationSync(PasswordTransport { method, _ ->
            if (method == PasswordMethod.CREATE_DEVICE) Proto.field(1, byteArrayOf(1)) + Proto.field(2, "fixture-guid")
            else when (mode) { 0 -> throw PasswordRpcException(14, true); else -> Proto.field(1, byteArrayOf()) + Proto.field(2, "") }
        }, { SyncDeviceInfo("Phone", "Maker", version) })
        register(store, sync); val before = store.bytes.copyOf(); version = "124"
        assertThrows(PasswordRpcException::class.java) { register(store, sync) }; assertArrayEquals(before, store.bytes)
        mode = 1
        assertThrows(ProtocolException::class.java) { register(store, sync) }; assertArrayEquals(before, store.bytes)
    }

    @Test fun vaultResetCreatesDifferentRegistrationAndOldPushCannotCrossAccounts() {
        val store = Store(); var count = 0
        val sync = DeviceRegistrationSync(PasswordTransport { method, _ ->
            assertEquals(PasswordMethod.CREATE_DEVICE, method); count++
            Proto.field(1, byteArrayOf(count.toByte())) + Proto.field(2, "guid-$count")
        }, { SyncDeviceInfo("Phone", "Maker", "123") })
        register(store, sync); val old = store.read().deviceRegistration!!
        register(store, sync, "users/me/birthdays/reset")
        assertFalse(old.nonce.contentEquals(store.read().deviceRegistration!!.nonce))
        val hint = PasswordInvalidation.decode(PasswordInvalidation.SENDER, old.cacheGuid, Proto.field(1, Proto.integer(1, 45873)))
        assertTrue(hint.appliesTo(old)); assertFalse(hint.appliesTo(store.read().deviceRegistration))
    }

    @Test fun invalidationRequiresExpectedSenderGuidAndRelevantBoundedTypeIds() {
        val registration = DeviceRegistration(parent, ByteArray(16), byteArrayOf(1), "fixture-guid", byteArrayOf())
        val payload = Proto.field(1, Proto.integer(1, 47745))
        assertTrue(PasswordInvalidation.decode(PasswordInvalidation.SENDER, "fixture-guid", payload).appliesTo(registration))
        assertFalse(PasswordInvalidation.decode(PasswordInvalidation.SENDER, "other-guid", payload).appliesTo(registration))
        assertThrows(ProtocolException::class.java) { PasswordInvalidation.decode("other-sender", "fixture-guid", payload) }
        assertThrows(ProtocolException::class.java) { PasswordInvalidation.decode(PasswordInvalidation.SENDER, "", payload) }
        assertThrows(ProtocolException::class.java) { PasswordInvalidation.decode(PasswordInvalidation.SENDER, "fixture-guid", Proto.field(1, Proto.integer(1, Long.MAX_VALUE))) }
        assertFalse(PasswordInvalidation.decode(PasswordInvalidation.SENDER, "fixture-guid", Proto.field(1, Proto.integer(1, 123))).appliesTo(registration))
    }

    @Test fun legacyStatesMigrateWithoutLosingPendingPreferenceWrites() {
        for (schema in 1L..3L) {
            val pending = Mutation("fixture-operation", MutationKind.CREATE, "fixture-tag", null, null, byteArrayOf(1), MutationPhase.INDETERMINATE)
            val old = Proto.integer(1, schema) + Proto.field(11, parent) + Proto.field(4, pending.encode()) +
                if (schema >= 2L) Proto.field(10, pending.encode()) else byteArrayOf()
            val state = VaultState.decode(old)
            state.deviceRegistration = DeviceRegistration(parent, ByteArray(16))
            val next = state.encode()
            assertEquals(5L, Proto.decode(next).integer(1))
            assertEquals(parent, VaultState.decode(next).preferenceParent)
            assertEquals(16, VaultState.decode(next).deviceRegistration!!.nonce.size)
            assertEquals("fixture-operation", VaultState.decode(next).mutations.single().id)
            assertEquals(MutationPhase.INDETERMINATE, VaultState.decode(next).mutations.single().phase)
            if (schema >= 2L) assertEquals("fixture-operation", VaultState.decode(next).preferenceMutations.single().id)
        }
    }
}
