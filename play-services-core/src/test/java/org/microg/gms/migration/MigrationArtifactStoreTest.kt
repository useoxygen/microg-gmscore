/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.os.ParcelFileDescriptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationArtifactStoreTest {
    @Test fun startingANewBatchPreservesPreviousBytesForVerification() {
        val context = RuntimeEnvironment.getApplication()
        val store = MigrationArtifactStore(context)
        store.file("previous-fixture").writeBytes(byteArrayOf(42))
        store.startBatch()
        assertFalse(store.contains("previous-fixture"))
        val history = File(context.noBackupFilesDir, "migration-history").walkTopDown()
            .first { it.name == "previous-fixture" }
        assertArrayEquals(byteArrayOf(42), history.readBytes())
    }
    @Test fun packageMetadataUsesActualVersionAndSha256SignatureArray() {
        val hash = ByteArray(32) { it.toByte() }
        val bytes = MigrationArtifactStore.packageMetadata(0x1_0000_0001L, listOf(hash))
        java.io.DataInputStream(bytes.inputStream()).use { data ->
            assertEquals(Int.MIN_VALUE, data.readInt()); assertEquals(0x1_0000_0001L, data.readLong())
            assertEquals(1, data.readInt()); assertEquals(32, data.readInt())
            val decoded = ByteArray(32); data.readFully(decoded); assertArrayEquals(hash, decoded)
            assertEquals(-1, data.read())
        }
    }
    @Test fun stagedBytesSurviveAFreshStoreAndPeerCannotEscapeTheDirectory() {
        val context = RuntimeEnvironment.getApplication()
        val store = MigrationArtifactStore(context)
        for (name in listOf("../secret", "/absolute", "a/b", "a\\b", "..", ""))
            assertThrows(IllegalArgumentException::class.java) { store.file(name) }
        val input = File(context.cacheDir, "synthetic-migration").apply { writeBytes(byteArrayOf(0, 1, 2, 3, -1)) }
        ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { store.stage("synthetic-package", it) }
        assertFalse(store.contains("synthetic-package"))
        store.publish("synthetic-package")
        assertArrayEquals(input.readBytes(), MigrationArtifactStore(context).file("synthetic-package").readBytes())
    }
}
