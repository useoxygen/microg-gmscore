/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Binder
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 10-27 run 5: Switch's later setupDocuments chunks timed out behind a running install job. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationLockTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() { context.getSharedPreferences("migration-installs", 0).edit().clear().commit() }

    @Test fun switchCanOpenTheQueueWhileAnInstallHoldsTheLock() {
        val held = CountDownLatch(1); val release = CountDownLatch(1)
        val job = Executors.newSingleThreadExecutor()
        job.execute { synchronized(MigrationInstallQueue.LOCK) { held.countDown(); release.await(10, TimeUnit.SECONDS) } }
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS))
            val open = Executors.newSingleThreadExecutor().submit { MigrationInstallQueue(context) }
            open.get(2, TimeUnit.SECONDS)
        } finally { release.countDown(); job.shutdown() }
    }

    @Test fun switchBindsDuringALegacyUpgradeWhileAnInstallHoldsTheLock() {
        context.getSharedPreferences("migration-installs", 0).edit().putStringSet("packages", setOf("org.example.app")).commit()
        val held = CountDownLatch(1); val release = CountDownLatch(1)
        val job = Executors.newSingleThreadExecutor()
        job.execute { synchronized(MigrationInstallQueue.LOCK) { held.countDown(); release.await(10, TimeUnit.SECONDS) } }
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS))
            val service = Robolectric.buildService(PlaySetupService::class.java).create().get()
            val started = System.nanoTime()
            assertNotNull(service.onBind(Intent(PlaySetupService.DESCRIPTOR + ".BIND")))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000)
        } finally { release.countDown(); job.shutdown() }
    }

    @Test fun anAppPlayKeepsRefusingGoesBehindTheOthersAcrossSessions() {
        val queue = MigrationInstallQueue(context) { operation, name ->
            if (operation == 3) Bundle().apply { putString("package_name", name); putString("status", "none") }
            else MigrationInstallQueue.unavailable(name, "rate_limited")
        }
        val first = queue.beginTransfer()
        queue.select(listOf("a.example.app", "b.example.app").map { Bundle().apply { putString("package_name", it) } })
        val second = queue.beginTransfer()
        queue.select(listOf(Bundle().apply { putString("package_name", "c.example.app") }))
        assertEquals("a.example.app", queue.dispatchOrder().first { it.first == first }.second)
        assertFalse(queue.dispatch("a.example.app", first))
        assertTrue(queue.throttled)
        assertEquals(first to "a.example.app", queue.dispatchOrder().last())
    }

    @Test fun aSessionsRefusalSurvivesAnOlderSessionQueueingTheSameApp() {
        var installs = 0
        val queue = MigrationInstallQueue(context) { operation, name ->
            fun queued() = Bundle().apply {
                putString("package_name", name); putString("status", "queued"); putLong("version_code", 12)
            }
            when {
                operation == 3 -> if (installs >= 2) queued() else Bundle().apply { putString("package_name", name); putString("status", "none") }
                ++installs == 1 -> MigrationInstallQueue.unavailable(name, "selected_version_unavailable")
                else -> queued()
            }
        }
        fun doc(version: Long) = listOf(Bundle().apply { putString("package_name", "org.example.app"); putLong("version_code", version) })
        val older = queue.beginTransfer(); queue.select(doc(12))
        val newer = queue.beginTransfer(); queue.select(doc(13))
        assertEquals(listOf(newer, older), queue.dispatchOrder().map { it.first })
        queue.dispatchRound()
        val receipt = queue.summary(newer).getParcelableArray("cyclon_app_results")!!.map { it as Bundle }.single()
        assertEquals("selected_version_unavailable", receipt.getString("error"))
        assertTrue(receipt.getBoolean("terminal"))
        assertTrue(queue.pending(newer).isEmpty())
    }

    @Test fun aRefusalSurvivesWhenARateLimitEndsTheRoundMidSession() {
        var aInstalls = 0
        val queue = MigrationInstallQueue(context) { operation, name ->
            fun state(status: String) = Bundle().apply {
                putString("package_name", name); putString("status", status); putLong("version_code", 12)
            }
            when {
                operation == 3 -> state(if (name == "a.example.app" && aInstalls >= 2) "queued" else "none")
                name == "z.example.app" -> MigrationInstallQueue.unavailable(name, "rate_limited")
                ++aInstalls == 1 -> MigrationInstallQueue.unavailable(name, "selected_version_unavailable")
                else -> state("queued")
            }
        }
        fun doc(name: String, version: Long) = Bundle().apply { putString("package_name", name); putLong("version_code", version) }
        val older = queue.beginTransfer(); queue.select(listOf(doc("a.example.app", 12)))
        val newer = queue.beginTransfer(); queue.select(listOf(doc("a.example.app", 13), doc("z.example.app", 1)))
        assertFalse(queue.dispatchRound()) // Refusal for a, then 429 for z ends the round inside the newer session.
        assertFalse(queue.dispatchRound()) // The older session's a now queues before z is tried again.
        val receipt = queue.summary(newer).getParcelableArray("cyclon_app_results")!!.map { it as Bundle }
            .single { it.getString("package_name") == "a.example.app" }
        assertEquals("selected_version_unavailable", receipt.getString("error"))
        assertFalse("a.example.app" in queue.pending(newer))
        assertTrue(queue.pending(older).isEmpty())
    }

    @Test fun auroraBridgeConnectsWithoutTheMainThread() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.aurora.store"
            applicationInfo = ApplicationInfo().apply { packageName = "com.aurora.store"; flags = ApplicationInfo.FLAG_SYSTEM }
        })
        val store = Binder().apply { attachInterface(null, AuroraMigrationClient.DESCRIPTOR) }
        shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindServiceForIntent(
            Intent("com.aurora.store.migration.BIND").setPackage("com.aurora.store"),
            ComponentName("com.aurora.store", "com.aurora.store.migration.MigrationService"), store)
        // The test runs on the main looper and never idles it, like a main thread blocked on the queue lock.
        val started = System.nanoTime()
        AuroraMigrationClient(context).close()
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 5)
    }
}
