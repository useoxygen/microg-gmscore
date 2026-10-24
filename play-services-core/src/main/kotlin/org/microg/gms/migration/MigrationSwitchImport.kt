/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.content.ComponentName
import android.content.Intent

/** Switch 1140078 ResumeWifiD2dRestore contract (hxs/kuq). Session credentials come from ConnectionHint. */
internal object MigrationSwitchImport {
    fun intent(setup: Intent): Intent = Intent("com.google.android.apps.pixelmigrate.ACTION_LAUNCH_WIFI_D2D")
        .setComponent(ComponentName(SWITCH_PACKAGE, "com.google.android.apps.pixelmigrate.migrate.component.MultiD2dMigrateFlowActivity"))
        .apply {
            // Carry SUW presentation/lifecycle arguments; never send QR credentials as Intent extras.
            listOf("firstRun", "deferredSetup", "preDeferredSetup", "isSetupFlow", "useImmersiveMode").forEach {
                if (setup.hasExtra(it)) putExtra(it, setup.getBooleanExtra(it, false))
            }
            setup.getStringExtra("theme")?.let { putExtra("theme", it) }
            if (setup.hasExtra("suw_session_id")) putExtra("suw_session_id", setup.getLongExtra("suw_session_id", -1))
            putExtra("is_quick_start", true)
            putExtra("after_account", true)
            putExtra("suw_supports_ios_wifi_migration_state", true)
            putExtra("is_ios_wifi_d2d", true)
            putExtra("is_os_migration_import", true)
            putExtra("has_fallback_flow", false)
            putExtra("has_d2d_fallback", false)
        }
}
