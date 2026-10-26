/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.backup

import android.app.backup.BackupTransport
import android.app.backup.RestoreDescription
import android.app.backup.RestoreSet
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/** Local-cache restore only. The framework verifies @pm@ metadata, app signatures,
 * allowBackup/data-extraction rules, versions and restore-agent results.
 * This is not a Google cloud backup transport and never accepts backup writes. */
internal class MigrationBackupTransport(context: Context) : BackupTransport(), Closeable {
    private val restore = StagedRestoreSet(File(context.noBackupFilesDir, "migration-staging"))
    override fun name() = "com.google.android.gms/.backup.BackupTransportService"
    override fun transportDirName() = "cyclonMigrationRestore"
    override fun currentDestinationString() = "Phone transfer"
    override fun configurationIntent(): Intent? = null
    override fun dataManagementIntent(): Intent? = null
    override fun dataManagementIntentLabel(): CharSequence? = null
    override fun getTransportFlags() = 2 // FLAG_DEVICE_TO_DEVICE_TRANSFER, not cloud/encrypted backup.
    override fun getCurrentRestoreSet() = restore.token() ?: 0L
    override fun getAvailableRestoreSets(): Array<RestoreSet> = restore.token()?.let {
        arrayOf(RestoreSet("Phone transfer", "D2D", it, 2))
    } ?: emptyArray()
    @Synchronized override fun startRestore(token: Long, packages: Array<PackageInfo>): Int = runCatching {
        val requested = packages.map { pkg ->
            val app = pkg.applicationInfo
            val full = pkg.packageName != "@pm@" && app != null &&
                (app.backupAgentName == null || (app.flags and ApplicationInfo.FLAG_FULL_BACKUP_ONLY) != 0)
            StagedRestoreSet.Package(pkg.packageName, full)
        }
        if (restore.start(token, requested)) OK else ERROR
    }.getOrDefault(ERROR)
    @Synchronized override fun nextRestorePackage(): RestoreDescription = try {
        restore.next()?.let { RestoreDescription(it.name,
            if (it.full) RestoreDescription.TYPE_FULL_STREAM else RestoreDescription.TYPE_KEY_VALUE) }
            ?: RestoreDescription.NO_MORE_PACKAGES
    } catch (e: Exception) { restore.close(); throw IllegalStateException("Migration archive unavailable", e) }
    @Synchronized override fun getRestoreData(data: ParcelFileDescriptor): Int {
        return try {
            require(restore.current?.full == false)
            ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(data.fileDescriptor)).use { output ->
                val buffer = ByteArray(CHUNK)
                while (true) {
                    val size = restore.read(buffer)
                    if (size == -1) break
                    output.write(buffer, 0, size)
                }
            }
            OK
        } catch (e: Exception) { restore.abortPackage(); ERROR }
    }
    @Synchronized override fun getNextFullRestoreDataChunk(socket: ParcelFileDescriptor): Int = try {
        require(restore.current?.full == true)
        val buffer = ByteArray(CHUNK)
        val size = restore.read(buffer)
        if (size > 0) ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(socket.fileDescriptor)).use {
            it.write(buffer, 0, size)
        }
        size
    } catch (e: Exception) { restore.abortPackage(); ERROR }
    override fun abortFullRestore(): Int { restore.abortPackage(); return OK }
    override fun finishRestore() = restore.close()
    // Reject writes without touching the current restore set, transport selection or other apps.
    override fun initializeDevice() = ERROR
    override fun performBackup(pkg: PackageInfo, data: ParcelFileDescriptor, flags: Int) = ERROR
    override fun performFullBackup(pkg: PackageInfo, data: ParcelFileDescriptor, flags: Int) = ERROR
    override fun clearBackupData(pkg: PackageInfo) = ERROR
    override fun finishBackup() = ERROR
    override fun sendBackupData(bytes: Int) = ERROR
    override fun close() = restore.close()
    companion object { private const val CHUNK = 32 * 1024; private const val OK = 0; private const val ERROR = -1000 }
}
