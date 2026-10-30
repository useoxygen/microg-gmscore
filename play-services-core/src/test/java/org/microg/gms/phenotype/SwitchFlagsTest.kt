/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.phenotype

import android.content.ContentValues
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.google.android.gms.common.api.Status
import com.google.android.gms.phenotype.Configurations
import com.google.android.gms.phenotype.Flag
import com.google.android.gms.phenotype.internal.IPhenotypeCallbacks
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SwitchFlagsTest {
    private val provider get() = Robolectric.buildContentProvider(ConfigurationProvider::class.java).create().get()
    private val namespace = "com.google.android.apps.pixelmigrate"
    private val flagName = "D2d__enable_contacts_restore_to_google_account"
    private val limitName = "backup_ios_app_matching_service_request_apps_limit"
    private val expectedRows = mapOf(flagName to "true", limitName to "1000")
    private val uri get() = Uri.parse("content://com.google.android.gms.phenotype/$namespace")

    private fun rows(provider: ConfigurationProvider, uri: Uri = this.uri): Map<String, String> =
        provider.query(uri, null, null, null, null)!!.use { cursor ->
            val result = linkedMapOf<String, String>()
            while (cursor.moveToNext()) result[cursor.getString(0)] = cursor.getString(1)
            result
        }

    private fun callback(code: Int, read: (Parcel) -> Unit): IPhenotypeCallbacks =
        IPhenotypeCallbacks.Stub.asInterface(object : Binder() {
            override fun onTransact(transaction: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface("com.google.android.gms.phenotype.internal.IPhenotypeCallbacks")
                assertEquals(code, transaction)
                assertEquals(IBinder.FLAG_ONEWAY, flags)
                assertEquals(1, data.readInt())
                assertEquals(0, Status.CREATOR.createFromParcel(data).statusCode)
                read(data)
                assertEquals(0, data.dataAvail())
                return true
            }
        })

    @Test fun directProviderReadEnablesTheGoogleDestinationPathAndRaisesTheAppMatchingLimit() {
        assertEquals(expectedRows, rows(provider))
    }

    @Test fun foreignAndMalformedNamespacesDoNotReceiveContactFlags() {
        val provider = provider
        listOf("foreign.package", "$namespace#com.google.android.apps.restore", "$namespace.other").forEach {
            assertNull(SwitchFlags.rows(it))
            assertNull(SwitchFlags.flags(it))
            assertTrue(rows(provider, Uri.parse("content://com.google.android.gms.phenotype/$it")).isEmpty())
        }
        listOf("$uri/extra", "$uri?flag=$flagName", "$uri#fragment",
            "content://foreign.authority/$namespace").forEach {
            assertTrue(rows(provider, Uri.parse(it)).isEmpty())
        }
    }

    @Test fun snapshotAndCommittedServiceReadsAgreeWithTheProviderOnTypedValues() {
        val service = PhenotypeServiceImpl("com.google.android.apps.restore")
        val expected = rows(provider)
        listOf(false, true).forEach { committed ->
            var actual: Configurations? = null
            val callback = callback(if (committed) 10 else 4) { data ->
                assertEquals(1, data.readInt())
                actual = Configurations.CREATOR.createFromParcel(data)
            }
            if (committed) service.getCommitedConfiguration(callback, namespace)
            else service.getConfigurationSnapshot(callback, namespace, null)
            val configuration = requireNotNull(actual).field4.single()
            assertEquals(0, configuration.id)
            assertTrue(configuration.removeNames.isEmpty())
            val flags = configuration.flags.associateBy { it.name }
            assertEquals(expected.keys, flags.keys)
            flags.values.forEach { assertEquals(0, it.flagType) }
            assertEquals(Flag.DATA_TYPE_BOOL, flags.getValue(flagName).dataType)
            assertTrue(flags.getValue(flagName).bool)
            assertEquals(Flag.DATA_TYPE_LONG, flags.getValue(limitName).dataType)
            assertEquals(1000L, flags.getValue(limitName).long)
            assertEquals(expected, flags.mapValues { (_, flag) ->
                if (flag.dataType == Flag.DATA_TYPE_LONG) flag.long.toString() else flag.bool.toString() })
        }
    }

    @Test fun individualFlagReadDoesNotEnableTheDefaultAccountFlagOrForeignNamespace() {
        val service = PhenotypeServiceImpl("com.google.android.apps.restore")
        listOf(namespace to flagName, namespace to "D2d__enable_contacts_restore_to_default_account",
            "foreign.package" to flagName).forEach { (requestedNamespace, requestedFlag) ->
            var called = false
            service.getFlag(callback(9) { data ->
                called = true
                if (requestedNamespace == namespace && requestedFlag == flagName) {
                    assertEquals(1, data.readInt())
                    val flag = Flag.CREATOR.createFromParcel(data)
                    assertEquals(flagName, flag.name)
                    assertEquals(Flag.DATA_TYPE_BOOL, flag.dataType)
                    assertTrue(flag.bool)
                } else assertEquals(0, data.readInt())
            }, requestedNamespace, requestedFlag, Flag.DATA_TYPE_BOOL)
            assertTrue(called)
        }
    }

    @Test fun destinationConfigurationCannotBeWrittenThroughTheProvider() {
        val provider = provider
        val values = ContentValues().apply { put("name", flagName); put("value", false) }
        assertThrows(UnsupportedOperationException::class.java) { provider.insert(uri, values) }
        assertThrows(IllegalArgumentException::class.java) { provider.update(uri, values, null, null) }
        assertThrows(IllegalArgumentException::class.java) { provider.delete(uri, null, null) }
        assertNull(provider.call("setFlagOverrides", namespace, null))
        assertEquals(expectedRows, rows(provider))
    }

    @Test fun individualFlagReadReturnsTheRaisedAppMatchingLimit() {
        val service = PhenotypeServiceImpl("com.google.android.apps.restore")
        var called = false
        service.getFlag(callback(9) { data ->
            called = true
            assertEquals(1, data.readInt())
            val flag = Flag.CREATOR.createFromParcel(data)
            assertEquals(limitName, flag.name)
            assertEquals(Flag.DATA_TYPE_LONG, flag.dataType)
            assertEquals(1000L, flag.long)
        }, namespace, limitName, Flag.DATA_TYPE_LONG)
        assertTrue(called)
    }
}
