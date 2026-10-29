/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.session

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.microg.gms.migration.MigrationSwitchImport

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationSwitchImportTest {
    @Test fun resumeImportContractPreservesSuwContextAndOmitsSecrets() {
        val intent = MigrationSwitchImport.intent(Intent().putExtra("theme", "glif_v4").putExtra("firstRun", true)
            .putExtra("suw_session_id", 1234L).putExtra("password", "secret"))
        assertEquals("com.google.android.apps.restore", intent.component!!.packageName)
        assertTrue(intent.component!!.className.endsWith("MultiD2dMigrateFlowActivity"))
        listOf("is_quick_start", "after_account", "is_from_d2d_wizard_manager", "suw_supports_ios_wifi_migration_state", "is_ios_wifi_d2d", "is_os_migration_import", "firstRun").forEach { assertTrue(it, intent.getBooleanExtra(it, false)) }
        assertEquals("glif_v4", intent.getStringExtra("theme")); assertEquals(1234L, intent.getLongExtra("suw_session_id", -1))
        assertFalse(intent.hasExtra("password")); assertFalse(intent.hasExtra("final_hold"))
    }
    // Both copy steps stay open until the copy ends, so their result never arrives mid-copy (CYC-925).
    @Test fun androidCopyStepOpensAsTheWizardDoes() {
        val intent = org.microg.gms.migration.android.copyStep()
        assertEquals("com.google.android.apps.restore", intent.`package`)
        listOf("is_quick_start", "after_account", "is_from_d2d_wizard_manager").forEach { assertTrue(it, intent.getBooleanExtra(it, false)) }
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_FORWARD_RESULT != 0)
    }
}
