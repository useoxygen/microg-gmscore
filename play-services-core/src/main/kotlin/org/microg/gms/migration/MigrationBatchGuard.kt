/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import java.io.Closeable
import java.io.File

/** Tokens are protocol values (iOS always uses1), not immutable batch identities.
 * All finalized staging mutations honor this process-wide restore lease. */
internal object MigrationBatchGuard {
    private val lock=Any()
    private val readers=mutableMapOf<File,Int>()
    fun acquire(directory: File): Closeable = synchronized(lock) {
        val key=directory.canonicalFile
        readers[key]=(readers[key] ?: 0)+1
        object : Closeable {
            private var closed=false
            override fun close() = synchronized(lock) {
                if(!closed) {
                    closed=true
                    val remaining=requireNotNull(readers[key])-1
                    if(remaining==0)readers.remove(key) else readers[key]=remaining
                }
            }
        }
    }
    fun <T> mutate(directory: File, action: () -> T): T = synchronized(lock) {
        check(directory.canonicalFile !in readers) { "Migration batch is being restored" }
        action()
    }
}
