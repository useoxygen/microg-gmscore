/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentResolver
import org.microg.gms.migration.backup.MigrationBackupTransport
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrateFileLifecycleTest {
    private val descriptor = "com.google.android.gms.backup.migrate.helper.ID2dMigrateHelper"
    private class Callback : Binder() {
        val done = CountDownLatch(1)
        var result = 0
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface("com.google.android.gms.backup.migrate.helper.IVoidCallbacks")
            assertEquals(0, data.dataAvail()); result = code; done.countDown(); return true
        }
    }
    private fun call(binder: IBinder, code: Int, callback: Callback, write: Parcel.() -> Unit) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor); data.write(); data.writeStrongBinder(callback)
            assertTrue(binder.transact(code, data, reply, 0)); reply.readException()
        } finally { data.recycle(); reply.recycle() }
    }
    // Robolectric models a pipe as a regular file and requires sender-close before descriptor handoff.
    // Real streaming / acknowledgement-before-EOF is tested in androidTest, not claimed by this fixture.
    private fun source(bytes: ByteArray): ParcelFileDescriptor {
        val file = File.createTempFile("migration", ".fixture", RuntimeEnvironment.getApplication().cacheDir)
        file.writeBytes(bytes)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    @Test fun registrationDoesNotPublishUntilRemoteSuccess() {
        val context = RuntimeEnvironment.getApplication()
        val store = MigrationArtifactStore(context)
        val helper = EmptyMigrateHelper(Binder.getCallingUid(), context)
        val input = source(byteArrayOf(1, 2, 3))
        val started = Callback()
        call(helper, 3, started) { writeString("org.example.fixture"); writeInt(1); input.writeToParcel(this, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE) }
        assertTrue(started.done.await(1, TimeUnit.SECONDS))
        assertEquals(1, started.result); assertFalse(store.contains("org.example.fixture"))
        val completed = Callback()
        call(helper, 4, completed) { writeString("org.example.fixture") }
        assertTrue(completed.done.await(6, TimeUnit.SECONDS)); assertEquals(1, completed.result)
        assertArrayEquals(byteArrayOf(1, 2, 3), store.file("org.example.fixture").readBytes())
    }
    @Test fun remoteFailureNeverPublishesLocallyCopiedBytes() {
        val context = RuntimeEnvironment.getApplication()
        val store = MigrationArtifactStore(context)
        val helper = EmptyMigrateHelper(Binder.getCallingUid(), context)
        val input = source(byteArrayOf(1))
        val started = Callback()
        call(helper, 3, started) { writeString("org.example.failed"); writeInt(1); input.writeToParcel(this, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE) }
        assertEquals(1, started.result)
        val failed = Callback()
        call(helper, 5, failed) { writeString("org.example.failed") }
        assertTrue(failed.done.await(6, TimeUnit.SECONDS)); assertEquals(2, failed.result)
        assertFalse(store.contains("org.example.failed"))
    }

    @Test fun iosMetadataAcknowledgementWaitsForTheTransportLeaseAndRejectsItsFailure() {
        val context = RuntimeEnvironment.getApplication()
        val provider = RestoreTransportProvider()
        ShadowContentResolver.registerProviderInternal("media", provider)
        ShadowContentResolver.registerProviderInternal("ai.cyclon.migration.restore", provider)
        val telephony = PackageInfo().apply {
            packageName = "com.android.providers.telephony"
            setLongVersionCode(37)
            signingInfo = Shadow.newInstanceOf(SigningInfo::class.java).apply {
                shadowOf(this).setSignatures(arrayOf(Signature(byteArrayOf(1, 2, 3))))
            }
        }
        shadowOf(context.packageManager).installPackage(telephony)
        val helper = EmptyMigrateHelper(Binder.getCallingUid(), context)
        val input = source(byteArrayOf(1, 2, 3))
        call(helper, 3, Callback()) { writeString(telephony.packageName); writeInt(1); input.writeToParcel(this, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE) }
        val staged = Callback()
        call(helper, 4, staged) { writeString(telephony.packageName) }
        assertTrue(staged.done.await(6, TimeUnit.SECONDS)); assertEquals(1, staged.result)
        val ready = Callback()
        call(helper, 7, ready) {}
        assertTrue(ready.done.await(6, TimeUnit.SECONDS)); assertEquals(1, ready.result)
        assertEquals(listOf("scan_volume" to null, "acquire" to null), provider.calls)
        MigrationBackupTransport(context).use { transport ->
            assertEquals(0, transport.startRestore(1, arrayOf(PackageInfo().apply { packageName = "@pm@" })))
            // A failed attempt also calls finishRestore before Switch schedules its retry.
            transport.finishRestore()
        }
        assertEquals(listOf("scan_volume" to null, "acquire" to null), provider.calls)
        provider.success = false
        val denied = Callback()
        call(helper, 7, denied) {}
        assertTrue(denied.done.await(6, TimeUnit.SECONDS)); assertEquals(2, denied.result)
    }

    @Test fun iosMetadataWithoutTelephonyDoesNotChangeTheBackupTransport() {
        val context = RuntimeEnvironment.getApplication()
        val provider = RestoreTransportProvider().apply { success = false }
        ShadowContentResolver.registerProviderInternal("media", provider)
        ShadowContentResolver.registerProviderInternal("ai.cyclon.migration.restore", provider)
        val completed = Callback()
        call(EmptyMigrateHelper(Binder.getCallingUid(), context), 7, completed) {}
        assertTrue(completed.done.await(6, TimeUnit.SECONDS)); assertEquals(1, completed.result)
        assertEquals(listOf("scan_volume" to null), provider.calls)
    }
}
