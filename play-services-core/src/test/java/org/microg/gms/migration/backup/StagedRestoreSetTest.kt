/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.backup

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class StagedRestoreSetTest {
    @get:Rule val temp = TemporaryFolder()
    private fun files(): File = temp.newFolder().apply {
        File(this, "restore_token_file").writeText("42")
        File(this, "@pm@").writeBytes(byteArrayOf(0, 1, 2, -1))
        File(this, "com.example.fixture").writeBytes(byteArrayOf(5, 0, 8, 9, -1))
    }
    private val pm = StagedRestoreSet.Package("@pm@", false)
    private val app = StagedRestoreSet.Package("com.example.fixture", true)
    private fun readAll(reader: StagedRestoreSet): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(2)
        while (true) { val count = reader.read(chunk); if (count == -1) break; out.write(chunk, 0, count) }
        return out.toByteArray()
    }
    @Test fun preservesArchiveBytesAndRetainsEvidenceAcrossFreshReader() {
        val root = files()
        repeat(2) {
            StagedRestoreSet(root).use { reader ->
                assertEquals(42L, reader.token())
                assertTrue(reader.start(42, listOf(app, pm)))
                assertEquals(pm, reader.next())
                assertArrayEquals(File(root, "@pm@").readBytes(), readAll(reader))
                assertEquals(app, reader.next())
                assertArrayEquals(File(root, app.name).readBytes(), readAll(reader))
                assertNull(reader.next())
            }
        }
        assertTrue(File(root, app.name).isFile)
    }
    @Test fun unknownTokenOrMissingMetadataDoesNotCreateRestoreSet() {
        val root = files()
        StagedRestoreSet(root).use { assertFalse(it.start(43, listOf(pm, app))) }
        File(root, "@pm@").delete()
        StagedRestoreSet(root).use { assertNull(it.token()); assertFalse(it.start(42, listOf(app))) }
    }
    @Test fun onlyRequestedCompleteFilesAreExposed() {
        val root = files()
        File(root, "com.example.other").writeText("not requested")
        File(root, "com.example.partial.new").writeText("incomplete")
        StagedRestoreSet(root).use { reader ->
            assertTrue(reader.start(42, listOf(pm, StagedRestoreSet.Package("com.example.absent", true))))
            assertEquals(pm, reader.next()); readAll(reader); assertNull(reader.next())
        }
    }
    @Test fun cannotAdvancePastPartiallyReadArchiveOrStartConcurrentSession() {
        StagedRestoreSet(files()).use { reader ->
            assertTrue(reader.start(42, listOf(pm, app))); assertFalse(reader.start(42, listOf(pm)))
            reader.next(); reader.read(ByteArray(1))
            assertThrows(IllegalStateException::class.java) { reader.next() }
            reader.abortPackage(); assertEquals(app, reader.next())
        }
    }
    @Test fun symlinkAndTraversalCannotBecomeAnAppArtifact() {
        val root = files(); val outside = temp.newFile().apply { writeText("private") }
        Files.createSymbolicLink(File(root, "com.example.link").toPath(), outside.toPath())
        StagedRestoreSet(root).use { reader ->
            assertTrue(reader.start(42, listOf(pm, StagedRestoreSet.Package("com.example.link", true),
                StagedRestoreSet.Package("../outside", true))))
            assertEquals(pm, reader.next()); readAll(reader); assertNull(reader.next())
        }
    }
    @Test fun aNewBatchCannotSupplyBytesToThePreviousRestore() {
        val root = files()
        StagedRestoreSet(root).use { reader ->
            assertTrue(reader.start(42, listOf(pm, app)))
            assertEquals(pm, reader.next()); readAll(reader)
            File(root, "restore_token_file").writeText("43")
            File(root, app.name).writeText("another batch")
            assertThrows(IllegalStateException::class.java) { reader.next() }
        }
    }
    @Test fun largeSparseArchiveStreamsWithoutAllocatingItsFullSize() {
        val root=files(); val size=700L*1024*1024
        java.io.RandomAccessFile(File(root,app.name),"rw").use {
            it.setLength(size); it.seek(size-2); it.write(byteArrayOf(71,12))
        }
        StagedRestoreSet(root).use { reader ->
            assertTrue(reader.start(42,listOf(app))); assertEquals(app,reader.next())
            val buffer=ByteArray(64*1024); var total=0L; var last=byteArrayOf()
            while(true) { val count=reader.read(buffer); if(count<0)break; total+=count; last=buffer.copyOfRange(count-2,count) }
            assertEquals(size,total); assertArrayEquals(byteArrayOf(71,12),last); assertNull(reader.next())
        }
    }
    @Test fun anEmptyKeyValueArtifactCompletesAtEof() {
        val root = files()
        File(root, app.name).writeBytes(byteArrayOf())
        val emptyApp = app.copy(full = false)
        StagedRestoreSet(root).use { reader ->
            assertTrue(reader.start(42, listOf(emptyApp)))
            assertEquals(emptyApp, reader.next())
            assertArrayEquals(byteArrayOf(), readAll(reader)); assertNull(reader.next())
        }
    }
}
