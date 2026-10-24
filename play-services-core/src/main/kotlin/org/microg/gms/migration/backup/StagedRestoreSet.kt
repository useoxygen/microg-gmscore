/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.backup

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Reader for completed Switch migration artifacts. Never enumerates arbitrary files,
 * deletes source data, extracts archive paths, or restores outside BackupManager. */
internal class StagedRestoreSet(private val directory: File) : Closeable {
    data class Package(val name: String, val full: Boolean)
    private var packages = emptyList<Package>()
    private var index = -1
    private var stream: InputStream? = null
    private var active = false
    private var consumed = false
    private var bytesRead = 0L
    private var sessionToken = 0L
    val current: Package? get() = packages.getOrNull(index)

    fun token(): Long? = runCatching {
        require(file("@pm@").isFile)
        val token = file("restore_token_file")
        require(token.length() in 1..32)
        token.readText(Charsets.UTF_8).trim().toLong().also { require(it > 0) }
    }.getOrNull()

    @Synchronized fun start(token: Long, requested: List<Package>): Boolean {
        if (active || token != token() || requested.size !in 1..MAX_PACKAGES) return false
        if (requested.map { it.name }.distinct().size != requested.size || !file("@pm@").isFile) return false
        packages = requested.filter { runCatching { file(it.name).isFile }.getOrDefault(false) }
            .sortedBy { if (it.name == "@pm@") 0 else 1 }
        if (packages.isEmpty()) return false
        index = -1; active = true; consumed = true; sessionToken = token
        return true
    }

    @Synchronized fun next(): Package? {
        check(active)
        check(consumed) { "Previous restore artifact has not completed" }
        check(token() == sessionToken) { "Migration batch changed during restore" }
        stream?.close(); stream = null
        index++
        val pkg = current ?: return null
        val file = file(pkg.name)
        require(file.isFile && file.length() in 0..MAX_ARTIFACT_BYTES)
        stream = FileInputStream(file)
        consumed = false; bytesRead = 0
        return pkg
    }

    /** -1 is EOF, exactly as IBackupTransport requires; zero never means completion. */
    @Synchronized fun read(buffer: ByteArray): Int {
        check(active && !consumed && current != null)
        require(buffer.isNotEmpty())
        val read = requireNotNull(stream).read(buffer)
        if (read == -1) { consumed = true; stream?.close(); stream = null }
        else { bytesRead += read; require(bytesRead <= MAX_ARTIFACT_BYTES) }
        return read
    }
    @Synchronized fun abortPackage() { stream?.close(); stream = null; consumed = true }
    @Synchronized override fun close() {
        abortPackage(); packages = emptyList(); index = -1; active = false; sessionToken = 0L
    }
    private fun file(name: String): File {
        require(name == "@pm@" || name == "restore_token_file" || name.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")))
        val file = File(directory.canonicalFile, name)
        require(file.canonicalFile == file.absoluteFile) { "Symlink migration artifact" }
        return file
    }
    companion object {
        private const val MAX_PACKAGES = 10000
        private const val MAX_ARTIFACT_BYTES = 512L * 1024 * 1024
    }
}
