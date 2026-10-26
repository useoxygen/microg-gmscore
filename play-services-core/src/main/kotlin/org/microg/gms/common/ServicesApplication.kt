/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.common

import androidx.multidex.MultiDexApplication
import androidx.work.Configuration
import android.os.Build
import okhttp3.OkHttp
import org.microg.gms.fido.core.AccountPasskeyBackend
import org.microg.gms.fido.core.AccountPasskeyBackendProvider
import org.microg.gms.passwords.LegacySyncedPasskeyBackend

/** WorkManager's provider runs in one process; services and owner UI also need on-demand access. */
class ServicesApplication : MultiDexApplication(), Configuration.Provider, AccountPasskeyBackendProvider {
    override fun onCreate() {
        super.onCreate()
        // The default-process Startup provider does not run in :ui or other
        // secondary app processes. PSL and cookie handling need this context.
        OkHttp.initialize(this)
    }

    override val accountPasskeyBackend: AccountPasskeyBackend?
        get() = if (Build.VERSION.SDK_INT >= 28) LegacySyncedPasskeyBackend else null
    override fun getWorkManagerConfiguration(): Configuration = Configuration.Builder()
        .setDefaultProcessName(applicationInfo.processName)
        .build()
}
