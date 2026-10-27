/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.os.ResultReceiver
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationSetupReceiversTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var installed = false
    private fun queue() = MigrationInstallQueue(context, storeCall = { _, _ -> Bundle().apply {
        putString("package_name", "org.example.app")
        putString("status", if (installed) "installed" else "installing")
        putBoolean("installed", installed)
        putLong("installed_version_code", if (installed) 12 else 0)
        putLong("version_code", 12)
    } })
    private fun receiver(codes: MutableList<Int>) = object : ResultReceiver(null) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { codes += resultCode }
    }
    private fun alias(receiver: ResultReceiver): ResultReceiver {
        val parcel = Parcel.obtain()
        return try {
            receiver.writeToParcel(parcel, 0); parcel.setDataPosition(0)
            ResultReceiver.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }
    private fun select(queue: MigrationInstallQueue): String {
        val id = queue.beginTransfer()
        queue.select(listOf(Bundle().apply { putString("package_name", "org.example.app"); putLong("version_code", 12) }))
        queue.finishSelection(id)
        queue.dispatch("org.example.app", id)
        return id
    }
    @Before fun clear() {
        MigrationSetupReceivers.clearForTests()
        context.getSharedPreferences("migration-installs", 0).edit().clear().commit()
        installed = false
    }
    @Test fun callbackRegistrySurvivesTheOriginalPublisherAndFinalHoldWaitsForInstallation() {
        val first = queue()
        val id = select(first)
        val progress = mutableListOf<Int>()
        val completions = mutableListOf<Int>()
        val progressReceiver = receiver(progress)
        MigrationSetupReceivers.register(progressReceiver, id)
        MigrationSetupReceivers.hold(receiver(completions), id)
        MigrationSetupReceivers.publish(first)
        assertEquals(listOf(2), progress)
        assertTrue(completions.isEmpty())
        installed = true
        MigrationSetupReceivers.publish(queue())
        MigrationSetupReceivers.publish(queue())
        assertEquals(listOf(2, 1), progress)
        assertEquals(listOf(1), completions)
    }
    @Test fun unregisterUsesTheReceiverBinderAcrossParcelInstances() {
        val first = queue()
        val id = select(first)
        val received = mutableListOf<Int>()
        val callback = receiver(received)
        MigrationSetupReceivers.register(alias(callback), id)
        MigrationSetupReceivers.unregister(alias(callback))
        MigrationSetupReceivers.publish(queue())
        assertTrue(received.isEmpty())
    }
}
