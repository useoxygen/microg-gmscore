/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.common

import androidx.multidex.MultiDexApplication
import androidx.work.Configuration

/** WorkManager's provider runs in one process; services and owner UI also need on-demand access. */
class ServicesApplication : MultiDexApplication(), Configuration.Provider {
    override fun getWorkManagerConfiguration(): Configuration = Configuration.Builder()
        .setDefaultProcessName(applicationInfo.processName)
        .build()
}
