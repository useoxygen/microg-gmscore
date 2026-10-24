/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MigratePipeInstrumentedTest {
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
    @Test fun senderGetsRegistrationBeforeWritingAndPublicationWaitsForEof() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = MigrationArtifactStore(context)
        val helper = EmptyMigrateHelper(Binder.getCallingUid(), context)
        val pipe = ParcelFileDescriptor.createPipe()
        val started = Callback()
        call(helper, 3, started) { writeString("org.example.fixture"); writeInt(1); pipe[0].writeToParcel(this, 0) }
        pipe[0].close()
        assertTrue("registration cannot wait for sender EOF", started.done.await(1, TimeUnit.SECONDS))
        assertEquals(1, started.result)
        assertFalse(store.contains("org.example.fixture"))
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(byteArrayOf(1, 2, 3)) }
        val completed = Callback()
        call(helper, 4, completed) { writeString("org.example.fixture") }
        assertTrue(completed.done.await(6, TimeUnit.SECONDS)); assertEquals(1, completed.result)
        assertArrayEquals(byteArrayOf(1, 2, 3), store.file("org.example.fixture").readBytes())
    }
}
