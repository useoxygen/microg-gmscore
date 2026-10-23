/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.folsom.DomainData
import org.microg.gms.auth.folsom.Keys
import org.microg.gms.auth.folsom.utils.FolsomKeyRefresh
import org.microg.gms.auth.folsom.utils.FolsomKeySnapshot
import org.microg.gms.auth.folsom.utils.FolsomStaleKeyPolicy
import org.microg.gms.auth.folsom.utils.TinkEncryptionException
import java.io.IOException

/** Real consumer coordinator against a serialized store adapter; no Android, account or server access. */
class FolsomKeyRefreshTest {
    private val keyA = Keys(keyVersion = 1, keyMaterial = ByteArray(32) { 1 }.toByteString())
    private val keyB = Keys(keyVersion = 2, keyMaterial = ByteArray(32) { 2 }.toByteString())
    private val keyC = Keys(keyVersion = 3, keyMaterial = ByteArray(32) { 3 }.toByteString())

    private class Disk(initial: DomainData) {
        var bytes = initial.encode()
        var prepares = 0
        var commits = 0
        fun read(): DomainData = DomainData.ADAPTER.decode(bytes)
        fun save(domain: DomainData) { bytes = domain.encode() }
        fun reopen(): Disk = Disk(read())
        fun mark() = save(FolsomStaleKeyPolicy.mark(read()))
        fun owner(keys: List<Keys>) = save(FolsomStaleKeyPolicy.fresh(read()).copy(keys = keys))
        fun reset() = save(FolsomStaleKeyPolicy.reset(read(), "synthetic-id"))
        fun client(fetch: suspend () -> List<Keys>) = FolsomKeyRefresh(
            read = { val domain = read(); FolsomKeySnapshot(FolsomStaleKeyPolicy.generation(domain), domain.keys) },
            prepare = { snapshot, force ->
                val current = read()
                val generation = FolsomStaleKeyPolicy.generation(current)
                if (generation != snapshot.generation) throw TinkEncryptionException("Fixture generation changed")
                val marked = if (force || !FolsomStaleKeyPolicy.stale(generation)) FolsomStaleKeyPolicy.mark(current) else current
                save(marked); prepares++
                FolsomKeySnapshot(FolsomStaleKeyPolicy.generation(marked), emptyList())
            },
            fetch = fetch,
            commit = { snapshot, keys ->
                val current = read()
                if (FolsomStaleKeyPolicy.generation(current) != snapshot.generation || !FolsomStaleKeyPolicy.stale(snapshot.generation))
                    throw TinkEncryptionException("Fixture generation changed")
                save(FolsomStaleKeyPolicy.fresh(current).copy(keys = keys)); commits++
            }
        )
    }

    @Test fun cachedKeysRemainUsableWithoutTreatingTimestampZeroAsStale() = runBlocking {
        val disk = Disk(DomainData(keys = listOf(keyA), lastFetchTimestamp = 0))
        var fetches = 0
        val result = disk.client { fetches++; listOf(keyB) }.load(force = false)
        assertEquals(listOf(keyA), result); assertEquals(0, fetches); assertEquals(0, disk.prepares); assertEquals(0, disk.commits)
    }

    @Test fun staleMarkSurvivesReopenAndForcesCachedAToFreshBExactlyOnce() = runBlocking {
        val original = Disk(DomainData(keys = listOf(keyA), googleUserId = "synthetic-id"))
        original.mark()
        val disk = original.reopen()
        assertTrue(FolsomStaleKeyPolicy.stale(FolsomStaleKeyPolicy.generation(disk.read())))
        var fetches = 0
        assertEquals(listOf(keyB), disk.client { fetches++; listOf(keyB) }.load(false))
        assertEquals(1, fetches); assertEquals(listOf(keyB), disk.read().keys)
        assertFalse(FolsomStaleKeyPolicy.stale(FolsomStaleKeyPolicy.generation(disk.read())))
        assertEquals("synthetic-id", disk.read().googleUserId)
        assertEquals(listOf(keyB), disk.reopen().client { throw AssertionError("Fresh cache must avoid member read") }.load(false))
    }

    @Test fun memberReadFailurePreservesCachedAAndTheDurableStaleObligationForTheNextProcess() {
        val original = Disk(DomainData(keys = listOf(keyA)))
        original.mark(); val disk = original.reopen(); val before = disk.bytes.copyOf()
        assertThrows(IOException::class.java) { runBlocking { disk.client { throw IOException("Synthetic outage") }.load(false) } }
        assertArrayEquals(before, disk.bytes); assertEquals(0, disk.commits); assertEquals(listOf(keyA), disk.read().keys)
        val reopened = disk.reopen(); var fetches = 0
        assertEquals(listOf(keyB), runBlocking { reopened.client { fetches++; listOf(keyB) }.load(false) })
        assertEquals(1, fetches); assertEquals(listOf(keyB), reopened.read().keys)
    }

    @Test fun emptyOrInvalidFreshKeysCannotReplaceCacheOrAcknowledgeStaleness() {
        for (response in listOf(emptyList(), listOf(Keys(keyVersion = 0, keyMaterial = keyB.keyMaterial)), listOf(Keys(keyVersion = 2)))) {
            val disk = Disk(DomainData(keys = listOf(keyA))); disk.mark(); val before = disk.bytes.copyOf()
            assertThrows(TinkEncryptionException::class.java) { runBlocking { disk.client { response }.load(false) } }
            assertArrayEquals(before, disk.bytes); assertEquals(listOf(keyA), disk.read().keys); assertEquals(0, disk.commits)
        }
    }

    @Test fun aNewerStaleMarkDuringFetchCannotBeClearedByTheOlderResponse() {
        val disk = Disk(DomainData(keys = listOf(keyA))); disk.mark()
        val oldGeneration = FolsomStaleKeyPolicy.generation(disk.read())
        assertThrows(TinkEncryptionException::class.java) { runBlocking {
            disk.client { disk.mark(); listOf(keyB) }.load(false)
        } }
        assertTrue(FolsomStaleKeyPolicy.generation(disk.read()) > oldGeneration)
        assertTrue(FolsomStaleKeyPolicy.stale(FolsomStaleKeyPolicy.generation(disk.read())))
        assertEquals(listOf(keyA), disk.read().keys); assertEquals(0, disk.commits)
        assertEquals(listOf(keyC), runBlocking { disk.reopen().client { listOf(keyC) }.load(false) })
    }

    @Test fun exactOwnerRecoveryClearsStalenessAndPreventsAnOlderFetchFromOverwritingItsKeys() {
        val disk = Disk(DomainData(keys = listOf(keyA))); disk.mark()
        assertThrows(TinkEncryptionException::class.java) { runBlocking {
            disk.client { disk.owner(listOf(keyC)); listOf(keyB) }.load(false)
        } }
        assertEquals(listOf(keyC), disk.read().keys)
        assertFalse(FolsomStaleKeyPolicy.stale(FolsomStaleKeyPolicy.generation(disk.read())))
        assertEquals(listOf(keyC), runBlocking { disk.reopen().client { throw AssertionError("Owner keys are fresh") }.load(false) })
    }

    @Test fun resetAndNewRefreshCannotReuseTheOldInFlightRefreshGeneration() {
        val disk = Disk(DomainData(keys = listOf(keyA), googleUserId = "synthetic-id"))
        var oldGeneration = -1L
        assertThrows(TinkEncryptionException::class.java) { runBlocking {
            disk.client {
                oldGeneration = FolsomStaleKeyPolicy.generation(disk.read())
                disk.reset()
                assertTrue(disk.read().keys.isEmpty())
                assertTrue(disk.read().legacyKeys.isEmpty())
                disk.mark() // A different post-reset refresh starts while the old response is still in flight.
                assertTrue(FolsomStaleKeyPolicy.generation(disk.read()) > oldGeneration)
                listOf(keyB)
            }.load(true)
        } }
        val reopened = disk.reopen()
        assertTrue(reopened.read().keys.isEmpty()); assertEquals(0, disk.commits)
        assertTrue(FolsomStaleKeyPolicy.generation(reopened.read()) > oldGeneration)
        assertEquals(listOf(keyC), runBlocking { reopened.client { listOf(keyC) }.load(false) })
        assertEquals(listOf(keyC), reopened.read().keys)
    }

    @Test fun explicitForceRefreshPersistsTheObligationBeforeItsMemberRead() {
        val disk = Disk(DomainData(keys = listOf(keyA)))
        assertThrows(IOException::class.java) { runBlocking {
            disk.client {
                assertTrue(FolsomStaleKeyPolicy.stale(FolsomStaleKeyPolicy.generation(disk.read())))
                assertEquals(listOf(keyA), disk.read().keys)
                throw IOException("Synthetic outage")
            }.load(true)
        } }
        assertTrue(FolsomStaleKeyPolicy.stale(FolsomStaleKeyPolicy.generation(disk.reopen().read())))
        assertEquals(listOf(keyA), disk.read().keys)
    }
}
