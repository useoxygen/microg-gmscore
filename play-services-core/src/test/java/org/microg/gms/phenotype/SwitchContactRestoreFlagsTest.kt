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
class SwitchContactRestoreFlagsTest {
    private val provider get() = Robolectric.buildContentProvider(ConfigurationProvider::class.java).create().get()
    private val namespace = "com.google.android.apps.pixelmigrate"
    private val flagName = "D2d__enable_contacts_restore_to_google_account"
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

    @Test fun directProviderReadEnablesOnlyTheExistingGoogleDestinationPath() {
        assertEquals(mapOf(flagName to "true"), rows(provider))
    }

    @Test fun foreignAndMalformedNamespacesDoNotReceiveContactFlags() {
        val provider = provider
        listOf("foreign.package", "$namespace#com.google.android.apps.restore", "$namespace.other").forEach {
            assertNull(SwitchContactRestoreFlags.rows(it))
            assertNull(SwitchContactRestoreFlags.flags(it))
            assertTrue(rows(provider, Uri.parse("content://com.google.android.gms.phenotype/$it")).isEmpty())
        }
        listOf("$uri/extra", "$uri?flag=$flagName", "$uri#fragment",
            "content://foreign.authority/$namespace").forEach {
            assertTrue(rows(provider, Uri.parse(it)).isEmpty())
        }
    }

    @Test fun snapshotAndCommittedServiceReadsAgreeWithTheProviderOnTypedBoolean() {
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
            val flag = configuration.flags.single()
            assertEquals(Flag.DATA_TYPE_BOOL, flag.dataType)
            assertEquals(0, flag.flagType)
            assertTrue(flag.bool)
            assertEquals(expected, mapOf(flag.name to flag.bool.toString()))
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
        assertEquals(mapOf(flagName to "true"), rows(provider))
    }
}
