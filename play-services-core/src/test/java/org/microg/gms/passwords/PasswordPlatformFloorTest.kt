/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Unsupported platforms must refuse before storage, account authority or network preparation. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PasswordPlatformFloorTest {
    // Exercise the actual entry guards without requiring unsupported/uncached old Robolectric SDKs.
    private fun onSdk(sdk: Int, action: () -> Unit) {
        val previous = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
        try { action() } finally { ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", previous) }
    }
    private class StorageTripwire : ContextWrapper(RuntimeEnvironment.getApplication()) {
        var storageReads = 0
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File {
            storageReads++
            throw AssertionError("Unsupported platform reached storage")
        }
    }

    @Test fun encryptedStoresRefuseBeforeOpeningStorageOrInvokingAuthority() {
        val context = StorageTripwire()
        var authorityCalls = 0
        for (sdk in listOf(19, 22)) onSdk(sdk) {
            assertThrows(VaultLockedException::class.java) { AndroidVaultStore(context, "platform-floor") { authorityCalls++ } }
            assertThrows(VaultLockedException::class.java) { AndroidPasskeyStore(context, "platform-floor") { authorityCalls++ } }
            AndroidPasskeyStore.purge(context) { authorityCalls++; emptySet() }
        }
        assertEquals(0, authorityCalls)
        assertEquals(0, context.storageReads)
    }

    @Test fun enclaveIdentityRefusesBeforeOwnerAuthorityOrKeystoreGeneration() {
        val context = StorageTripwire()
        var authorityCalls = 0
        for (sdk in listOf(19, 22)) onSdk(sdk) {
            val identity = EnclavePinIdentity(context, "synthetic-platform-floor",
                EnclavePinIdentity.newAlias("synthetic-platform-floor"), null) { authorityCalls++ }
            assertThrows(VaultLockedException::class.java) { identity.readStable(allowCreate = true) }
        }
        assertEquals(0, authorityCalls)
        assertEquals(0, context.storageReads)
    }

    @Test fun recoveryClientsRefuseBeforeCreatingAccountOrNetworkAuthority() {
        val context = StorageTripwire()
        val account = Account("synthetic-platform-floor", "com.google")
        var authorityCalls = 0
        val authorize = { authorityCalls++; Unit }
        onSdk(27) {
            assertThrows(VaultLockedException::class.java) { EnclavePinClient(context, account, "synthetic-id", authorize) }
            assertThrows(VaultLockedException::class.java) { GpmPinRenewalClient(context, account, "synthetic-id", authorize) }
            assertThrows(VaultLockedException::class.java) {
                RecoverableVaultClient(context, account, "synthetic-id", "chromesync", null, authorize)
            }
            assertThrows(VaultLockedException::class.java) {
                RecoveryRestoreClient(context, account, "synthetic-id", "chromesync", authorize = authorize)
            }
            assertThrows(VaultLockedException::class.java) { RecoverableVaultTransport(context, account, "synthetic-id", authorize) }
        }
        assertEquals(0, authorityCalls)
        assertEquals(0, context.storageReads)
    }
}
