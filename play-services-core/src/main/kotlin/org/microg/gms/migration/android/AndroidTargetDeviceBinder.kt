/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.google.android.gms.common.api.Status
import org.microg.gms.migration.*
import org.microg.gms.migration.nearby.DctRecord
import java.io.Closeable

/** Confirmed TargetDevice transactions, with no invented paired state or transferred accounts.
 * The pipe transport is separately testable; account bootstrap remains unavailable. */
internal class AndroidTargetDeviceBinder(
    uid: Int,
    private val state: AndroidPostSetupState = AndroidPostSetupState(),
    private val direct: Boolean = false
) : MigrationBinder(if (direct) DIRECT_TARGET_DESCRIPTOR else TARGET_DESCRIPTOR, uid), Closeable {
    override fun dispatch(transaction: Int, data: Parcel, reply: Parcel?): Boolean {
        val code = if (direct) when (transaction) { 1 -> 10; 2 -> 11; else -> return false } else transaction
        if (code !in setOf(1, 2, 3, 4, 5, 10, 11, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23)) return false
        if (code == 4) {
            data.skipSafeParcelable(); data.skipApiMetadata(); data.requireEnd()
            reply?.writeNoException(); reply?.writeString("") // No active advertising name.
            return true
        }
        val callback = requireNotNull(data.readStrongBinder())
        when (code) {
            1 -> {
                data.skipSafeParcelable(); data.readStrongBinder(); data.skipApiMetadata(); data.requireEnd()
                status(callback, 1, 10557) // NEARBY_BOOTSTRAP_ERROR; no radio/bootstrap is running.
            }
            2 -> {
                data.readStrongBinder(); data.skipApiMetadata(); data.requireEnd()
                status(callback, 2, 10552)
            }
            10, 20 -> {
                data.skipSafeParcelable()
                // Bound the FD array before unmarshalling it. Close every received descriptor,
                // including malformed requests; these are account pipes, not bulk-data handles.
                val count = data.readInt()
                require(count in -1..2) { "Invalid SmartDevice pipe count" }
                val pipes = ArrayList<ParcelFileDescriptor>()
                try {
                    repeat(maxOf(0, count)) {
                        require(data.readInt() == 1)
                        pipes.add(ParcelFileDescriptor.CREATOR.createFromParcel(data))
                    }
                    requireNotNull(data.readStrongBinder())
                    data.skipApiMetadata(); data.requireEnd()
                    require(pipes.size == 2) { "SmartDevice needs exactly two pipes" }
                    // Do not report started/completed if Google account bootstrap is unavailable.
                    status(callback, 5, 10573, "Account transfer unavailable; sign in on this phone")
                } finally { pipes.forEach { runCatching { it.close() } } }
            }
            13 -> {
                data.skipApiMetadata(); data.requireEnd()
                val hint = state.hint()
                pair(callback, 1, if (hint == null) 10590 else 0, hint ?: DctRecord(AndroidSetupSchemas.hint))
            }
            14 -> {
                data.skipApiMetadata(); data.requireEnd()
                val (status, result) = attempt {
                    state.start()?.let { DctRecord(AndroidSetupSchemas.handshake, mapOf(2 to it)) }
                }
                pair(callback, 2, status, result ?: DctRecord(AndroidSetupSchemas.handshake))
            }
            15, 17 -> {
                val input = DctRecord.nullable(data, AndroidSetupSchemas.handshake)
                data.skipApiMetadata(); data.requireEnd()
                input.use {
                    val (status, result) = attempt {
                        // No session is an absent-state error even before a handshake exists.
                        if (state.hint() == null) null else {
                            val message = requireNotNull(input?.bytes(2))
                            if (code == 15) state.finish(message)?.let { (body, hmac) ->
                                DctRecord(AndroidSetupSchemas.handshake, mapOf(2 to body, 3 to hmac))
                            } else state.authenticate(message, requireNotNull(input?.bytes(3)))?.let { key ->
                                DctRecord(AndroidSetupSchemas.auth, mapOf(2 to key))
                            }
                        }
                    }
                    pair(callback, if (code == 15) 4 else 5, status, result ?: DctRecord(
                        if (code == 15) AndroidSetupSchemas.handshake else AndroidSetupSchemas.auth))
                }
            }
            21 -> {
                data.skipApiMetadata(); data.requireEnd()
                sendCallback(callback, PAIR_CALLBACK, 6) { writeNullable(Status.SUCCESS); writeNullable(Bundle()) }
            }
            22 -> {
                data.skipApiMetadata(); data.requireEnd()
                pair(callback, 7, 10590)
            }
            23 -> {
                data.skipApiMetadata(); data.requireEnd()
                state.close(); pair(callback, 9, 0)
            }
            else -> {
                data.skipApiMetadata(); data.requireEnd()
                when (code) {
                    3 -> status(callback, 3, 10552)
                    5 -> status(callback, 4, 10552, textResult = true)
                    11 -> status(callback, 6, 10565)
                    16 -> status(callback, 7, 10581, nullResult = true)
                    18 -> status(callback, 8, 10581, emptyList = true)
                    19 -> status(callback, 9, 10552, nullResult = true)
                }
            }
        }
        reply?.writeNoException()
        return true
    }

    private fun attempt(action: () -> DctRecord?): Pair<Int, DctRecord?> = try {
        action().let { (if (it == null) 10590 else 0) to it }
    } catch (e: SecurityException) { 10591 to null }
      catch (e: IllegalStateException) { 10593 to null }
      catch (e: IllegalArgumentException) { 13 to null }
      catch (e: Exception) { 10592 to null }

    private fun pair(callback: IBinder, operation: Int, code: Int, value: DctRecord? = null) {
        sendCallback(callback, PAIR_CALLBACK, operation) {
            writeNullable(Status(code))
            if (value != null) writeNullable(value)
        }
    }
    private fun status(callback: IBinder, operation: Int, code: Int, message: String? = null,
                       textResult: Boolean = false, nullResult: Boolean = false, emptyList: Boolean = false) {
        sendCallback(callback, if (direct) DIRECT_CALLBACK else TARGET_CALLBACK, if (direct) 1 else operation) {
            writeNullable(Status(code, message))
            if (textResult) writeString(null)
            if (nullResult) writeInt(0)
            if (emptyList) writeInt(0)
        }
    }
    override fun close() = state.close()
    companion object {
        const val DIRECT_TARGET_DESCRIPTOR = "com.google.android.gms.smartdevice.directtransfer.internal.ITargetDirectTransferService"
        const val DIRECT_CALLBACK = "com.google.android.gms.smartdevice.directtransfer.internal.IDirectTransferCallback"
        const val TARGET_CALLBACK = "com.google.android.gms.smartdevice.d2d.internal.ITargetDeviceCallbacks"
    }
}
