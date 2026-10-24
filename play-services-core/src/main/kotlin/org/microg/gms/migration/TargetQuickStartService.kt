/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import org.microg.gms.migration.controller.DctController
import org.microg.gms.migration.dct.DeviceInfoResponse
import org.microg.gms.migration.session.DctSessions
import java.io.Closeable

internal const val QUICKSTART_DESCRIPTOR = "com.google.android.gms.smartdevice.quickstart.ITargetQuickStartService"
internal const val OS_CALLBACK = "com.google.android.gms.smartdevice.quickstart.ITargetOsMigrationCallbacks"

/** Direct internal API. Manifest is non-exported; the permission-protected activity owns integration. */
class TargetQuickStartService : Service() {
    private lateinit var binder: TargetQuickStartBinder
    override fun onCreate() { super.onCreate(); binder = TargetQuickStartBinder(this) }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() { binder.close(); super.onDestroy() }
}

internal class TargetQuickStartBinder(private val context: android.content.Context) :
    MigrationBinder(QUICKSTART_DESCRIPTOR, android.os.Process.myUid()), Closeable {
    private var controller: DctController? = null
    private var callback: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    private val listener = object : DctController.Listener {
        override fun advertising(status: Int) {
            if (status == 0) emit(1) { writeString(requireNotNull(controller).session.qrUri) } else emit(3) { writeInt(status) }
        }
        override fun connected(peer: DeviceInfoResponse) { emit(3) { writeInt(0) } }
        override fun bandwidth(medium: Int, status: Int) { emit(4) { writeInt(medium); writeInt(3); writeInt(status) } }
        override fun closed(status: Int) { emit(6) { writeInt(status) } }
    }
    private fun emit(code: Int, write: Parcel.() -> Unit) {
        val target = callback ?: return
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(OS_CALLBACK); data.write()
            check(target.transact(code, data, reply, 0)); reply.readException()
        } catch (e: Exception) { close() }
        finally { data.recycle(); reply.recycle() }
    }
    private fun register(next: IBinder?) {
        death?.let { old -> callback?.let { runCatching { it.unlinkToDeath(old, 0) } } }
        callback = next
        death = next?.let { binder -> IBinder.DeathRecipient { close() }.also { binder.linkToDeath(it, 0) } }
    }
    override fun dispatch(code: Int, data: Parcel, reply: Parcel?): Boolean {
        when (code) {
            15 -> {
                data.readLong() // SUW analytics session identity; never replaces the DCT session.
                val next = data.readStrongBinder()
                if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data)
                data.requireEnd()
                check(controller == null)
                register(next)
                controller = DctSessions.retained() ?: DctSessions.create(context)
                requireNotNull(controller).also { active -> active.add(listener); if (!active.advertised) active.start() }
            }
            17 -> { val next = data.readStrongBinder(); data.requireEnd(); register(next)
                controller?.let { active -> if (active.advertised) listener.advertising(0); active.peer?.let(listener::connected)
                    if (active.upgraded) listener.bandwidth(6, 1) } }
            16 -> { data.requireEnd(); controller?.stopAdvertising() }
            2 -> { data.requireEnd(); controller?.close(); close() }
            18 -> { val next = data.readStrongBinder(); data.requireEnd(); register(next)
                // Account transfer is unsupported, including on a connected transport.
                emit(5) { writeInt(13); writeInt(0) } }
            20 -> { val reason = data.readInt(); data.requireEnd(); controller?.fail(reason); close() }
            else -> return false
        }
        reply?.writeNoException(); return true
    }
    override fun close() {
        register(null)
        controller?.remove(listener)
        // Retain for Switch's authenticated broker registration; the controller owns a bounded expiry.
        controller = null
    }
}
