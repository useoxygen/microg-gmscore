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
    private val uid: Int,
    private var state: AndroidPostSetupState = AndroidPostSetupState(),
    private val direct: Boolean = false,
    private val reload: (() -> AndroidPostSetupState)? = null,
    private val bootstrapFactory: ((DctRecord, IBinder, (Int) -> Unit) -> LegacyTargetBootstrap)? = null,
    private val advertisingName: () -> String = { "" }
) : MigrationBinder(if (direct) DIRECT_TARGET_DESCRIPTOR else TARGET_DESCRIPTOR, uid), Closeable {
    private var bootstrap: LegacyTargetBootstrap? = null
    override fun dispatch(transaction: Int, data: Parcel, reply: Parcel?): Boolean {
        val code = if (direct) when (transaction) { 1 -> 10; 2 -> 11; else -> return false } else transaction
        if (code !in setOf(1, 2, 3, 4, 5, 10, 11, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23)) return false
        if(reload != null && (code==13 || (code in listOf(14,15,17) && state.hint()==null))) {
            val latest=reload.invoke()
            val old=state.hint(); val fresh=latest.hint()
            val same=old?.text(2)==fresh?.text(2) && java.security.MessageDigest.isEqual(old?.bytes(3),fresh?.bytes(3))
            old?.bytes(3)?.fill(0); fresh?.bytes(3)?.fill(0)
            if(same)latest.close() else { state.close(); state=latest }
        }
        if (code == 4) {
            data.skipSafeParcelable(); data.skipApiMetadata(); data.requireEnd()
            reply?.writeNoException(); reply?.writeString(bootstrap?.name ?: advertisingName())
            return true
        }
        val callback = requireNotNull(data.readStrongBinder())
        // Only the broker's authenticated Switch reaches here; these calls use its retained pairing.
        if (code in setOf(13, 14, 15, 17, 22)) RetainedSessionUse.called(uid)
        when (code) {
            1 -> {
                val options = requireNotNull(DctRecord.nullable(data,LegacyBootstrapExchange.schema))
                val listener = requireNotNull(data.readStrongBinder()); data.skipApiMetadata(); data.requireEnd()
                try {
                    if(bootstrap?.isActive == true) status(callback,1,10551)
                    else if(bootstrapFactory == null) status(callback,1,10557)
                    else bootstrap = bootstrapFactory.invoke(options,listener) { code -> status(callback,1,code) }.also { it.start() }
                } finally { options.close() }
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
            16 -> {
                data.skipApiMetadata(); data.requireEnd()
                // GMS gesr.c reads persisted *source* WorkProfilePayload, defaulting to
                // (null, 0, null). No source management metadata is imported here yet.
                // Switch ejn dereferences successful results, so the parcel must be present.
                sendCallback(callback, TARGET_CALLBACK, 7) {
                    writeNullable(Status.SUCCESS)
                    writeNullable(DctRecord(mapOf(2 to DctRecord.Kind.IntValue), mapOf(2 to 0)))
                }
            }
            22 -> {
                data.skipApiMetadata(); data.requireEnd()
                // startRestore. GMS foig asks the old phone over its quick-start session to start copying, and answers
                // 13 without such a session. A legacy Android source starts its copy by itself once the bootstrap
                // completes, so a verified retained session is that request already made; Switch fails the whole
                // connection unless this succeeds.
                val hint = state.hint()
                pair(callback, 7, if (hint == null) 13 else 0)
                hint?.bytes(3)?.fill(0)
            }
            23 -> {
                data.skipApiMetadata(); data.requireEnd()
                state.clearRetained(); pair(callback, 9, 0)
            }
            else -> {
                data.skipApiMetadata(); data.requireEnd()
                when (code) {
                    3 -> { bootstrap?.close(); bootstrap = null; status(callback,3,0) }
                    5 -> status(callback,4,if(bootstrap?.pin==null)10552 else 0,textResult=true,textValue=bootstrap?.pin)
                    11 -> status(callback, 6, 10565)
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
                       textResult: Boolean = false, nullResult: Boolean = false, emptyList: Boolean = false, textValue: String? = null) {
        sendCallback(callback, if (direct) DIRECT_CALLBACK else TARGET_CALLBACK, if (direct) 1 else operation) {
            writeNullable(Status(code, message))
            if (textResult) writeString(textValue)
            if (nullResult) writeInt(0)
            if (emptyList) writeInt(0)
        }
    }
    override fun close() { bootstrap?.close(); bootstrap = null; state.close() }
    companion object {
        const val DIRECT_TARGET_DESCRIPTOR = "com.google.android.gms.smartdevice.directtransfer.internal.ITargetDirectTransferService"
        const val DIRECT_CALLBACK = "com.google.android.gms.smartdevice.directtransfer.internal.IDirectTransferCallback"
        const val TARGET_CALLBACK = "com.google.android.gms.smartdevice.d2d.internal.ITargetDeviceCallbacks"
    }
}
