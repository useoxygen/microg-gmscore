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
        println("PASS: real Android hidden transport Binder/API, key-value/full-stream bytes, FD ownership and restore evidence preserved")
        println("Not tested: BackupManager registration/restore, app agents/policy, stock-GMS transfer")
    }
}
