/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.backup

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import java.io.File

/** Debug-only app_process probe for real Android FD ownership/API compatibility.
 * Takes one fresh shell-owned directory. Does not bind BackupManager, install an app,
 * change the selected transport, or access the running GMS package's data. */
object MigrationRestoreProbe {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1)
        val root = File(args[0]).canonicalFile
        require(root.path.startsWith("/data/local/tmp/cyc712-restore-probe-"))
        require(!root.exists()) { "Probe directory must be fresh; preserve earlier evidence" }
        check(root.mkdir())
        val context = object : ContextWrapper(null) {
            override fun getNoBackupFilesDir(): File = root
        }
        val stage = File(root, "migration-staging").apply { check(mkdir()) }
        val metadata = byteArrayOf(0, 9, 1, -1)
        val data = ByteArray(70001) { (it % 251).toByte() }
        File(stage, "restore_token_file").writeText("42")
        File(stage, "@pm@").writeBytes(metadata)
        File(stage, "com.example.synthetic").writeBytes(data)
        val pm = PackageInfo().apply { packageName = "@pm@" }
        val app = PackageInfo().apply {
            packageName = "com.example.synthetic"
            applicationInfo = ApplicationInfo().apply { backupAgentName = null }
        }
        MigrationBackupTransport(context).use { transport ->
            check(transport.binder != null)
            check(transport.transportFlags == 2)
            check(transport.availableRestoreSets.single().token == 42L)
            check(transport.initializeDevice() == -1000)
            check(transport.startRestore(42, arrayOf(pm, app)) == 0)
            check(transport.nextRestorePackage().packageName == "@pm@")
            val kvOutput = File(root, "kv-output")
            ParcelFileDescriptor.open(kvOutput, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use {
                check(transport.getRestoreData(it) == 0)
                check(it.fileDescriptor.valid()) { "Transport closed caller's FD" }
                check(transport.performBackup(app, it, 0) == -1000)
            }
            check(kvOutput.readBytes().contentEquals(metadata)) { "Key/value bytes changed" }
            val next = transport.nextRestorePackage()
            check(next.packageName == app.packageName && next.dataType == 2)
            val fullOutput = File(root, "full-output")
            ParcelFileDescriptor.open(fullOutput, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use {
                var total = 0
                while (true) {
                    val count = transport.getNextFullRestoreDataChunk(it)
                    if (count == -1) break
                    check(count > 0); total += count
                    check(it.fileDescriptor.valid()) { "Chunk closed caller's FD" }
                }
                check(total == data.size)
            }
            check(fullOutput.readBytes().contentEquals(data)) { "Full-stream bytes changed or overwrote prior chunks" }
            transport.finishRestore()
        }
        check(File(stage, app.packageName).readBytes().contentEquals(data))
        helperIntegration(root, metadata, data)
        println("PASS: real Android hidden transport Binder/API, key-value/full-stream bytes, FD ownership and restore evidence preserved")
        println("Not tested: BackupManager registration/restore, app agents/policy, stock-GMS transfer")
    }
    private fun helperIntegration(root: File, metadata: ByteArray, data: ByteArray) {
        val folder = File(root,"helper").apply { check(mkdir()) }
        val context = object : ContextWrapper(null) { override fun getNoBackupFilesDir(): File = folder }
        val helper = org.microg.gms.migration.EmptyMigrateHelper(android.os.Process.myUid(),context)
        class Result : android.os.Binder() {
            val latch = java.util.concurrent.CountDownLatch(1); var result = 0
            override fun onTransact(code: Int, parcel: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
                parcel.enforceInterface("com.google.android.gms.backup.migrate.helper.IVoidCallbacks")
                check(parcel.dataAvail()==0); result=code; latch.countDown(); return true
            }
        }
        fun call(code: Int, result: Result, write: android.os.Parcel.() -> Unit) {
            val request=android.os.Parcel.obtain(); val reply=android.os.Parcel.obtain()
            try { request.writeInterfaceToken("com.google.android.gms.backup.migrate.helper.ID2dMigrateHelper"); request.write(); request.writeStrongBinder(result); check(helper.transact(code,request,reply,0)); reply.readException() }
            finally { request.recycle(); reply.recycle() }
        }
        val pipe=ParcelFileDescriptor.createPipe(); val started=Result()
        call(3,started) { writeString("com.example.synthetic"); writeInt(1); pipe[0].writeToParcel(this,0) }
        check(started.latch.await(1,java.util.concurrent.TimeUnit.SECONDS) && started.result==1) { "Staging registration waited for EOF or failed" }
        val stage=File(folder,"migration-staging"); check(!File(stage,"com.example.synthetic").exists())
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(data) }
        val completed=Result(); call(4,completed) { writeString("com.example.synthetic") }
        check(completed.latch.await(6,java.util.concurrent.TimeUnit.SECONDS) && completed.result==1) { "Helper did not publish confirmed EOF" }
        pipe[0].close()
        check(File(stage,"com.example.synthetic").readBytes().contentEquals(data))
        File(stage,"@pm@").writeBytes(metadata); File(stage,"restore_token_file").writeText("42")
        val app=PackageInfo().apply { packageName="com.example.synthetic"; applicationInfo=ApplicationInfo().apply { backupAgentName=null } }
        MigrationBackupTransport(context).use { transport ->
            check(transport.startRestore(42,arrayOf(app))==0)
            check(transport.nextRestorePackage().packageName==app.packageName)
            val output=File(folder,"restored")
            ParcelFileDescriptor.open(output,ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use { fd ->
                var count=0
                while(true) { val chunk=transport.getNextFullRestoreDataChunk(fd); if(chunk == -1)break; check(chunk>0); count+=chunk }
                check(count==data.size)
            }
            check(output.readBytes().contentEquals(data))
        }
        println("PASS: C helper tx3 registration before EOF, tx4 two-phase publication -> A local restore exact bytes")
    }
}
