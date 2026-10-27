/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AuroraMigrationWireTest {
    private class Store(private val modern: Boolean) : Binder() {
        val calls = mutableListOf<Int>()
        val installOptions = mutableListOf<Bundle>()
        var authorized = true
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            calls += code
            if (code !in 1..3 && !(modern && code in 4..7)) return false
            check(authorized) { "Unauthorized bridge caller" }
            data.enforceInterface(AuroraMigrationClient.DESCRIPTOR)
            val result = Bundle()
            when (code) {
                7 -> {
                    result.putInt("bridge_version", 2)
                    for (key in listOf("install_options", "automatic_update_hold", "anonymous_prepare", "owner_approved_retry"))
                        result.putBoolean(key, true)
                }
                in 1..3 -> {
                    assertEquals("org.example.app", data.readString())
                    if (code == 2) {
                        installOptions += if (modern && data.dataAvail() > 0) {
                            assertEquals(1, data.readInt()); Bundle.CREATOR.createFromParcel(data)
                        } else Bundle()
                    }
                    result.putString("status", "queued")
                }
                else -> {
                    assertEquals(1, data.readInt())
                    val options = Bundle.CREATOR.createFromParcel(data)
                    assertEquals("transfer-one", options.getString("session_id"))
                    result.putBoolean("success", true)
                }
            }
            assertEquals("Trailing fields on tx$code", 0, data.dataAvail())
            requireNotNull(reply).writeNoException(); reply.writeInt(1); result.writeToParcel(reply, 0)
            return true
        }
    }
    private fun options() = Bundle().apply {
        putString("session_id", "transfer-one"); putInt("documents_type", 3); putBoolean("owner_approved_retry", true)
    }
    @Test fun oldAuroraGetsStringOnlyInstallEvenWhenCallerSuppliesOptions() {
        val store = Store(false)
        val client = AuroraMigrationWire(store)
        assertEquals("queued", client.call(2, "org.example.app", options()).getString("status"))
        assertEquals(listOf(7, 2), store.calls)
        assertTrue(store.installOptions.single().isEmpty)
    }
    @Test fun oldAuroraNeverReceivesUnsupportedPauseResumeOrAnonymousOperations() {
        val store = Store(false)
        val client = AuroraMigrationWire(store)
        assertEquals(1, client.control(7, Bundle()).getInt("bridge_version"))
        for (operation in 4..6) {
            val result = client.control(operation, options())
            assertFalse(result.getBoolean("success")); assertTrue(result.getBoolean("unsupported"))
            assertEquals("bridge_update_required", result.getString("error"))
        }
        assertEquals(listOf(7), store.calls)
    }
    @Test fun newAuroraReceivesOnlyNegotiatedExtensionsAndCapabilityQueryHasNoPayload() {
        val store = Store(true)
        val client = AuroraMigrationWire(store)
        assertTrue(client.control(7, Bundle()).getBoolean("automatic_update_hold"))
        assertEquals(2, client.control(7, Bundle()).getInt("bridge_version"))
        client.call(2, "org.example.app", options())
        for (operation in 4..6) assertTrue(client.control(operation, options()).getBoolean("success"))
        assertEquals(listOf(7, 2, 4, 5, 6), store.calls)
        assertTrue(store.installOptions.single().getBoolean("owner_approved_retry"))
        assertEquals(3, store.installOptions.single().getInt("documents_type"))
    }
    @Test fun catalogAndStatusKeepOriginalStringOnlyWire() {
        val store = Store(false)
        val client = AuroraMigrationWire(store)
        client.call(1, "org.example.app"); client.call(3, "org.example.app")
        assertEquals(listOf(1, 3), store.calls)
    }
    @Test fun rejectedCapabilityQueryIsNotSilentlyDowngradedToLegacy() {
        val store = Store(true).apply { authorized = false }
        try { AuroraMigrationWire(store).control(7, Bundle()); fail("Authorization failure was swallowed") }
        catch (_: IllegalStateException) { }
    }
}
