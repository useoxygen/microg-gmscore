/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.app.Service
import android.accounts.Account
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.util.Log
import org.microg.gms.common.GooglePackagePermission
import org.microg.gms.common.PackageUtils
import org.microg.gms.utils.ExtendedPackageInfo

private const val HELPER = "com.google.android.gms.backup.migrate.helper.ID2dMigrateHelper"
private const val BROKER = "com.google.android.gms.backup.migrate.helper.ID2dMigrateHelperBroker"
private const val BROKER_CALLBACK = "com.google.android.gms.backup.migrate.helper.ID2dMigrateHelperBrokerCallbacks"
private const val BOOLEAN_CALLBACK = "com.google.android.gms.backup.migrate.helper.IBooleanCallbacks"
private const val VOID_CALLBACK = "com.google.android.gms.backup.migrate.helper.IVoidCallbacks"

/** Signature-bound Switch staging and iOS metadata; actual system restore is a separate gate. */
class MigrateHelperService : Service() {
    override fun onBind(intent: Intent): IBinder = object : MigrationBinder(BROKER, null) {
        override fun dispatch(code: Int, data: Parcel, reply: Parcel?): Boolean {
            requireSwitchCaller()
            if (code != 1) return false
            require(data.readInt() == 1) { "Missing migrate-helper version request" }
            require(data.createByteArray()?.contentEquals(byteArrayOf(8, 1)) == true) { "Unsupported migrate-helper version" }
            val callback = requireNotNull(data.readStrongBinder())
            data.requireEnd()
            val helper = EmptyMigrateHelper(Binder.getCallingUid(), this@MigrateHelperService)
            sendCallback(callback, BROKER_CALLBACK, 1) {
                writeInt(1) // nullable Parcelable containing a protobuf byte array
                writeByteArray(byteArrayOf(8, 1)) // field 1: broker protocol version 1
                writeStrongBinder(helper)
            }
            reply?.writeNoException()
            Log.d("GmsSwitchSupport", "Migrate-helper v1 connected; no Google restore staging")
            return true
        }
    }

    private fun requireSwitchCaller() {
        val caller = PackageUtils.getAndCheckCallingPackage(this, SWITCH_PACKAGE)
        check(caller == SWITCH_PACKAGE && ExtendedPackageInfo(this, caller)
            .hasGooglePackagePermission(GooglePackagePermission.ACCOUNT)) { "Untrusted migration caller" }
    }
}

internal class EmptyMigrateHelper(uid: Int, private val context: Context? = null) : MigrationBinder(HELPER, uid) {
    private val store by lazy { context?.let { MigrationArtifactStore(it) } }
    @Volatile private var stagingStarted = false
    private data class Write(val file: ParcelFileDescriptor, val completed: CompletableFuture<Unit>, val id: String = java.util.UUID.randomUUID().toString())
    private val writes = mutableMapOf<String, Write>()
    @Synchronized private fun startWrite(name: String, file: ParcelFileDescriptor) {
        beginStaging()
        requireNotNull(store).file(name)
        check(name !in writes && writes.size < 8) { "Migration write already active or limit exceeded" }
        val write = Write(file, CompletableFuture())
        writes[name] = write
        MigrationHelperIo.writes.execute {
            try { requireNotNull(store).stage(name, file, write.id); write.completed.complete(Unit) }
            catch (e: Throwable) { write.completed.completeExceptionally(e) }
            finally { file.close() }
        }
    }
    @Synchronized private fun finishWrite(name: String, remoteSuccess: Boolean) {
        val write = requireNotNull(writes.remove(name)) { "No migration write to complete" }
        try {
            check(remoteSuccess) { "Peer reported migration failure" }
            write.completed.get(5, TimeUnit.SECONDS)
            requireNotNull(store).publish(name, write.id)
        } finally {
            write.file.close()
            if (!write.completed.isDone) write.completed.cancel(true)
            requireNotNull(store).discard(write.id)
        }
    }
    @Synchronized private fun beginStaging() {
        if (!stagingStarted) { requireNotNull(store).startBatch(); stagingStarted = true }
    }
    private val io get() = MigrationHelperIo.executor
    private fun complete(callback: IBinder, action: () -> Unit) {
        io.execute {
            val result = runCatching(action)
            result.exceptionOrNull()?.let { Log.w("GmsSwitchSupport", "Migration operation failed (${it.javaClass.simpleName})") }
            val success = result.isSuccess
            runCatching { sendCallback(callback, VOID_CALLBACK, if (success) 1 else 2) }
        }
    }
    override fun dispatch(code: Int, data: Parcel, reply: Parcel?): Boolean {
        when (code) {
            1 -> {
                val callback = requireNotNull(data.readStrongBinder())
                data.requireEnd()
                sendCallback(callback, VOID_CALLBACK, 1)
            }
            2, 8 -> {
                val name = requireNotNull(data.readString())
                val callback = requireNotNull(data.readStrongBinder())
                data.requireEnd()
                sendCallback(callback, BOOLEAN_CALLBACK, 1) { writeInt(if (stagingStarted && store?.contains(name) == true) 1 else 0) }
            }
            6 -> {
                val callback = requireNotNull(data.readStrongBinder()); data.requireEnd()
                io.execute {
                    runCatching {
                        MigrationSystemRestore(requireNotNull(context)).start { success ->
                            runCatching { sendCallback(callback, VOID_CALLBACK, if (success) 1 else 2) }
                        }
                    }.onFailure { runCatching { sendCallback(callback, VOID_CALLBACK, 2) } }
                }
            }
            7 -> {
                val callback = requireNotNull(data.readStrongBinder()); data.requireEnd()
                complete(callback) {
                    beginStaging()
                    requireNotNull(store).generateIosMetadata()
                    // Switch invokes this after iOS bytes finish, even when its later final-hold scan is skipped.
                    // Use the installed AOSP MediaProvider, not Google's package-name-specific restore path.
                    requireNotNull(context).contentResolver.call(Uri.parse("content://media"),
                        "scan_volume", "external_primary", null)
                }
            }
            3 -> {
                val name = requireNotNull(data.readString())
                val file = if (data.readInt() != 0) ParcelFileDescriptor.CREATOR.createFromParcel(data) else null
                try {
                    val callback = requireNotNull(data.readStrongBinder()); data.requireEnd()
                    val success = runCatching { startWrite(name, requireNotNull(file)) }.isSuccess
                    if (!success) file?.close()
                    // Registration, not EOF: the sender may not write until it gets this callback.
                    sendCallback(callback, VOID_CALLBACK, if (success) 1 else 2)
                } catch (e: Throwable) { file?.close(); throw e }
            }
            4, 5 -> {
                val name = requireNotNull(data.readString())
                val callback = requireNotNull(data.readStrongBinder()); data.requireEnd()
                complete(callback) { finishWrite(name, code == 4) }
            }
            11 -> {
                data.readString(); val callback = requireNotNull(data.readStrongBinder()); data.requireEnd()
                sendCallback(callback, VOID_CALLBACK, 2) // no app-specific restore finalization is implemented
            }
            9, 10 -> {
                if (data.readInt() != 0) Account.CREATOR.createFromParcel(data)
                repeat(if (code == 9) 3 else 5) { data.createByteArray() }
                if (code == 10) { data.readInt(); data.createByteArray() }
                if (data.readInt() != 0) ComponentName.CREATOR.createFromParcel(data)
                val callback = requireNotNull(data.readStrongBinder())
                data.requireEnd()
                sendCallback(callback, VOID_CALLBACK, 2) // credential recovery is unsupported
            }
            else -> return false
        }
        reply?.writeNoException()
        return true
    }
}

private object MigrationHelperIo {
    val executor = Executors.newSingleThreadExecutor()
    val writes = Executors.newFixedThreadPool(8)
}
