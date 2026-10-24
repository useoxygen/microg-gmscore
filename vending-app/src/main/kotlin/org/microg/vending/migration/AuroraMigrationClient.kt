/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** No APK bytes or owner credentials cross this bridge. */
internal class AuroraMigrationClient(private val context: Context) : Closeable {
    private val connected = ArrayBlockingQueue<IBinder>(1)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { connected.offer(service) }
        override fun onServiceDisconnected(name: ComponentName) { connected.clear() }
    }
    private val bound: Boolean
    private val binder: IBinder
    init {
        val app = context.packageManager.getApplicationInfo("com.aurora.store", 0)
        check(app.flags and ApplicationInfo.FLAG_SYSTEM != 0) { "Aurora must be the image's system app" }
        bound = context.bindService(Intent("com.aurora.store.migration.BIND").setPackage("com.aurora.store"),
            connection, Context.BIND_AUTO_CREATE)
        check(bound) { "Aurora migration bridge unavailable" }
        binder = connected.poll(10, TimeUnit.SECONDS) ?: run {
            context.unbindService(connection)
            error("Aurora migration bridge timed out")
        }
        check(binder.interfaceDescriptor == DESCRIPTOR) { "Wrong Aurora migration bridge" }
    }
    fun call(operation: Int, name: String): Bundle {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR); data.writeString(name)
            check(binder.transact(operation, data, reply, 0)) { "Aurora migration operation unavailable" }
            reply.readException()
            check(reply.readInt() == 1)
            return Bundle.CREATOR.createFromParcel(reply)
        } finally { data.recycle(); reply.recycle() }
    }
    override fun close() { if (bound) context.unbindService(connection) }
    companion object { const val DESCRIPTOR = "com.aurora.store.migration.IMigrationStore" }
}
