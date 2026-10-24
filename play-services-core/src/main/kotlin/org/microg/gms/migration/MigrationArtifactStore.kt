/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.app.backup.BackupDataOutput
import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/** Private D2D staging only. No path supplied by a peer can leave this directory. */
internal class MigrationArtifactStore(private val context: Context) {
    val directory = File(context.noBackupFilesDir, "migration-staging").apply { check(mkdirs() || isDirectory) }
    fun file(name: String): File {
        require(name.isNotBlank() && name.length <= 255 && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it == '\u0000' }) { "Unsafe migration artifact name" }
        return File(directory, name)
    }
    fun contains(name: String) = file(name).isFile
    @Synchronized fun startBatch() {
        // Keep the previous transfer for verification/recovery. Never delete completed imported data as cleanup.
        if (directory.listFiles().orEmpty().isNotEmpty()) {
            val history = File(context.noBackupFilesDir, "migration-history").apply { check(mkdirs() || isDirectory) }
            check(directory.renameTo(File(history, java.util.UUID.randomUUID().toString()))) {
                "Could not preserve previous migration staging"
            }
            check(directory.mkdirs())
        }
    }
    private fun pending(name: String): File {
        file(name) // validate before constructing a private temporary path
        return File(directory, ".pending").apply { check(mkdirs() || isDirectory) }.resolve(name)
    }
    fun stage(name: String, source: ParcelFileDescriptor, writeId: String = name) {
        file(name)
        // Large MMS archives are legitimate. Bound by this device's available storage,
        // reserving room for metadata/OS use rather than imposing a 512 MiB data-loss ceiling.
        val byteBudget = (directory.usableSpace - 256L * 1024 * 1024).coerceAtLeast(0)
        val target = AtomicFile(pending(writeId))
        val output = target.startWrite()
        try {
            ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val length = input.read(buffer)
                    if (length < 0) break
                    total += length
                    require(total <= byteBudget) { "Insufficient storage for migration artifact" }
                    output.write(buffer, 0, length)
                }
            }
            target.finishWrite(output)
        } catch (e: Throwable) { target.failWrite(output); throw e }
    }
    @Synchronized fun publish(name: String, writeId: String = name) {
        val source = pending(writeId)
        check(source.isFile && source.renameTo(file(name))) { "Missing or incomplete migration artifact" }
    }
    @Synchronized fun discard(writeId: String) { AtomicFile(pending(writeId)).delete() }
    @Synchronized fun generateIosMetadata() {
        // Android's package-manager backup metadata format: API level, incremental build.
        // No package/telephony payload or account recovery is invented by generating the envelope.
        val oldToken = file("restore_token_file")
        check(!oldToken.exists() || oldToken.delete())
        val target = AtomicFile(file("@pm@"))
        val output = target.startWrite()
        try {
            val bytes = ByteArrayOutputStream().also { stream ->
                DataOutputStream(stream).use { it.writeInt(Build.VERSION.SDK_INT); it.writeUTF(Build.VERSION.INCREMENTAL) }
            }.toByteArray()
            // The platform constructor is hidden from the public SDK; the privileged image must authorize it.
            BackupDataOutput::class.java.getConstructor(java.io.FileDescriptor::class.java).newInstance(output.fd).apply {
                writeEntityHeader("@meta@", bytes.size)
                writeEntityData(bytes, bytes.size)
                // The iOS SMS converter targets the platform telephony provider. Do not manufacture
                // destination-signature metadata for arbitrary Android app-data archives.
                val telephony = "com.android.providers.telephony"
                if (contains(telephony)) {
                    @Suppress("DEPRECATION")
                    val info = context.packageManager.getPackageInfo(telephony,
                        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
                    @Suppress("DEPRECATION")
                    val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
                    val hashes = requireNotNull(signatures).map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()) }
                    require(hashes.isNotEmpty())
                    @Suppress("DEPRECATION")
                    val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                    val packageBytes = packageMetadata(version, hashes)
                    writeEntityHeader(telephony, packageBytes.size); writeEntityData(packageBytes, packageBytes.size)
                }
            }
            target.finishWrite(output)
        } catch (e: Throwable) { target.failWrite(output); throw e }
        val token = AtomicFile(file("restore_token_file"))
        val tokenOutput = token.startWrite()
        try { tokenOutput.write("1".toByteArray(Charsets.UTF_8)); token.finishWrite(tokenOutput) }
        catch (e: Throwable) { token.failWrite(tokenOutput); throw e }
    }
    companion object {
        internal fun packageMetadata(version: Long, signatureHashes: List<ByteArray>): ByteArray =
            ByteArrayOutputStream().also { stream ->
                DataOutputStream(stream).use { data ->
                    if (version > Int.MAX_VALUE) { data.writeInt(Int.MIN_VALUE); data.writeLong(version) }
                    else data.writeInt(version.toInt())
                    data.writeInt(signatureHashes.size)
                    signatureHashes.forEach { hash -> require(hash.size == 32); data.writeInt(hash.size); data.write(hash) }
                }
            }.toByteArray()
    }
}
