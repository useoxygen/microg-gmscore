/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.gcm

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * GmsCore's push self-grant (useoxygen/cyclon#842) on the production [GcmDatabase] and real SQLite: it may only create
 * an absent entry, never change a decision microG's permission dialog or settings committed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class GcmSelfGrantTest {
    private val gms = "com.google.android.gms"
    private lateinit var context: Context
    private val opened = mutableListOf<GcmDatabase>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(GcmDatabase.DB_NAME)
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        context.deleteDatabase(GcmDatabase.DB_NAME)
    }

    private fun database() = GcmDatabase(context).also { opened += it }

    private fun grant(database: GcmDatabase) =
        grantOwnPushPermissionIfAbsent(database, gms, gms, twoStepOn = true, gcmEnabled = true)

    @Test
    fun anAbsentEntryIsGranted() {
        val database = database()
        assertTrue(grant(database))
        assertTrue(database.getApp(gms)!!.allowRegister)
    }

    @Test
    fun aDenialCommittedAfterTheLookupIsKept() {
        val dialog = database()
        // The permission dialog's connection commits a denial right after the service's lookup saw no entry.
        val service = object : GcmDatabase(context) {
            override fun getApp(packageName: String): App? {
                val app = super.getApp(packageName)
                if (app == null) dialog.noteAppKnown(packageName, false)
                return app
            }
        }.also { opened += it }
        assertFalse(grant(service))
        assertFalse(service.getApp(gms)!!.allowRegister)
        assertFalse(dialog.getApp(gms)!!.allowRegister)
    }

    @Test
    fun existingDecisionsAreNeverUpdated() {
        val database = database()
        database.noteAppKnown(gms, false)
        assertFalse(grant(database))
        assertFalse(database.noteAppKnownIfAbsent(gms, true))
        assertFalse(database.getApp(gms)!!.allowRegister)
    }

    @Test
    fun otherPackagesAndTheSettingOffAreNotGranted() {
        val database = database()
        assertFalse(grantOwnPushPermissionIfAbsent(database, "com.example.app", gms, twoStepOn = true, gcmEnabled = true))
        assertFalse(grantOwnPushPermissionIfAbsent(database, gms, gms, twoStepOn = false, gcmEnabled = true))
        assertFalse(grantOwnPushPermissionIfAbsent(database, gms, gms, twoStepOn = true, gcmEnabled = false))
        assertNull(database.getApp(gms))
        assertNull(database.getApp("com.example.app"))
    }
}
