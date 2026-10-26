/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.content.Context
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationInstallQueueTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun selected() = listOf(Bundle().apply { putString("package_name", "org.example.app"); putLong("version_code", 12) })
    private fun state(status: String, installed: Boolean = false, version: Long = 0) = Bundle().apply {
        putString("package_name", "org.example.app"); putString("status", status)
        putBoolean("installed", installed); putLong("installed_version_code", version)
    }
    @Before fun clear() { context.getSharedPreferences("migration-installs", 0).edit().clear().commit() }
    @Test fun unavailableDocumentsNeverEnterTheSelectableCatalog() {
        val available = Bundle().apply { putString("package_name", "org.example.available"); putInt("availability", 1) }
        val missing = MigrationInstallQueue.unavailable("org.example.missing", "not_found")
        val reply = PlaySetupService.catalog(listOf(available, missing))
        fun names(key: String): List<String?> = reply.getParcelableArray(key)!!.map { it as Bundle }
            .flatMap { it.getParcelableArray("documents")!!.toList() }.map { (it as Bundle).getString("package_name") }
        assertEquals(listOf("org.example.available"), names("document_groups"))
        assertEquals(listOf("org.example.missing"), names("unrestorable_document_groups"))
    }
    @Test fun uncertainEnqueueIsReconciledAfterRestartWithoutAnotherInstall() {
        val calls = mutableListOf<Int>()
        val first = MigrationInstallQueue(context) { operation, name ->
            calls += operation
            if (operation == 3) state("none") else MigrationInstallQueue.unavailable(name, "store_unavailable")
        }
        first.select(selected()); assertFalse(first.dispatch("org.example.app"))
        assertEquals(setOf("org.example.app"), MigrationInstallQueue(context).pending())
        val restarted = MigrationInstallQueue(context) { operation, _ -> calls += operation; state("queued") }
        assertTrue(restarted.dispatch("org.example.app")); assertTrue(restarted.pending().isEmpty())
        assertEquals(listOf(3, 2, 3), calls)
    }
    @Test fun queueNeverReportsDownloadingOrOldInstalledVersionAsComplete() {
        val queue = MigrationInstallQueue(context) { _, _ -> state("downloading", false, 11) }
        queue.select(selected()); assertFalse(queue.summary().getBoolean("all_installed"))
        val restarted = MigrationInstallQueue(context) { _, _ -> state("none", true, 11) }
        assertTrue(restarted.needsEnqueue("org.example.app", state("none", true, 11)))
        assertFalse(restarted.summary().getBoolean("all_installed"))
    }
    @Test fun unavailableAppKeepsItsReasonAcrossFreshPreferencesAndStatusQueries() {
        val first = MigrationInstallQueue(context) { operation, name ->
            if (operation == 3) state("none") else MigrationInstallQueue.unavailable(name, "not_found")
        }
        first.select(selected()); assertTrue(first.dispatch("org.example.app"))
        val restarted = MigrationInstallQueue(context) { _, _ -> state("none") }
        val result = restarted.summary().getParcelableArray("cyclon_app_results")!!.single() as Bundle
        assertEquals("not_found", result.getString("error")); assertFalse(result.getBoolean("installed"))
    }
}
