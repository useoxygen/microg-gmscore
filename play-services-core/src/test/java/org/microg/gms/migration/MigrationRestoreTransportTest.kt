/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationRestoreTransportTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun provider() = RestoreTransportProvider().also {
        ShadowContentResolver.registerProviderInternal("ai.cyclon.migration.restore", it)
    }

    @Test fun acquireUsesTheCompanionContractAndAcceptsAnAlreadySelectedTransport() {
        val provider = provider()
        MigrationRestoreTransport(context).acquire()
        provider.leaseId = null
        MigrationRestoreTransport(context).acquire()
        assertEquals(listOf("acquire" to null, "acquire" to null), provider.calls)
    }

    @Test fun deniedOrMissingAcquireResponseNeverClaimsALease() {
        val provider = provider()
        val selection = MigrationRestoreTransport(context)
        provider.success = false
        assertThrows(IllegalStateException::class.java) { selection.acquire() }
        provider.nullResponse = true
        assertThrows(IllegalStateException::class.java) { selection.acquire() }
        provider.failure = SecurityException("Denied")
        assertThrows(SecurityException::class.java) { selection.acquire() }
    }
}

internal class RestoreTransportProvider : ContentProvider() {
    var leaseId: String? = "first"
    var success = true
    var nullResponse = false
    var failure: RuntimeException? = null
    val calls = mutableListOf<Pair<String, String?>>()
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        calls += method to extras?.getString("lease_id")
        if (method == "scan_volume") assertEquals("external_primary", arg) else assertNull(arg)
        failure?.let { throw it }
        return if (nullResponse) null else Bundle().apply {
            putBoolean("success", success)
            if (method == "acquire") putString("lease_id", leaseId)
        }
    }
    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
