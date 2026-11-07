/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** CYC-1606: a false EDEADLK from the kernel is waited out; every other lock failure still fails at once. */
class ProcessFileLockTest {
    private val deadlock = IOException("Resource deadlock would occur")

    @Test fun waitsOutDeadlockReportsUntilTheLockIsFree() {
        val waits = ArrayList<Long>(); var attempts = 0
        val result = ProcessFileLock.retrying({ waits.add(it) }) { if (++attempts < 4) throw deadlock else "locked" }
        assertEquals("locked", result)
        assertEquals(listOf(25L, 50L, 100L), waits)
    }

    @Test fun otherFailuresAreNotRetried() {
        val failure = IOException("No space left on device"); var attempts = 0
        val thrown = assertThrows(IOException::class.java) {
            ProcessFileLock.retrying({ throw AssertionError("must not wait") }) { attempts++; throw failure }
        }
        assertSame(failure, thrown)
        assertEquals(1, attempts)
    }

    @Test fun aLastingDeadlockStillFailsAfterAboutTwentyFiveSeconds() {
        var waited = 0L; var attempts = 0
        val thrown = assertThrows(IOException::class.java) {
            ProcessFileLock.retrying({ waited += it }) { attempts++; throw deadlock }
        }
        assertTrue(ProcessFileLock.deadlockReported(thrown))
        assertEquals(ProcessFileLock.WAITS_MILLIS.size + 1, attempts)
        assertTrue(waited in 20_000L..30_000L)
    }

    @Test fun locksARealFile() {
        val file = File.createTempFile("process-file-lock", ".lock")
        try {
            RandomAccessFile(file, "rw").use { access ->
                ProcessFileLock.lock(access.channel).use { assertTrue(it.isValid && !it.isShared) }
            }
        } finally { file.delete() }
    }
}
