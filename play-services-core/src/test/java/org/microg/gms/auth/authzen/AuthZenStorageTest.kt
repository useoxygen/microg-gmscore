/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.authzen

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.cryptauth.CryptAuthUserKey
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The durable store on real files and real file locks (useoxygen/cyclon#842 review round 1): reads hold the lock,
 * a write that did not land can never authorize a send, and concurrent threads and processes send a decision once.
 */
class AuthZenStorageTest {
    private val root: File = Files.createTempDirectory("authzen-storage").toFile()
    private val storage = AtomicFileStorage(File(root, "authzen"))
    private val lock = DirectoryFileLock(File(root, "locks"))
    private val clock = TestClock()
    private val profile = SyntheticProfile()
    private val userKey = CryptAuthUserKey.generate()
    private val posts = AtomicInteger()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val api = AuthZenApi(object : AuthZenTransport {
        override fun post(endpoint: AuthZenEndpoint, bearerToken: String, body: String): AuthZenHttpResult {
            posts.incrementAndGet()
            return AuthZenHttpResult.Response(200, "{\"recorded\":true}", null)
        }
    }, object : AuthZenTokenProvider {
        override fun token(account: String) = "token"
        override fun invalidate(account: String, token: String) = Unit
    })

    private fun sender(storage: AuthZenStorage = this.storage, lock: AuthZenLock = this.lock): AuthZenSender {
        val keys = AuthZenKeyRegistry(storage, lock)
        return AuthZenSender(
            AuthZenRepository(storage, lock), keys, listOf(profile), api, { true }, { true },
            { userKey.keyPair to userKey.keyMaterial }, AuthZenDiagnostics { }, { clock.now() },
        )
    }

    private fun enroll(storage: AuthZenStorage = this.storage) {
        val keys = AuthZenKeyRegistry(storage, lock)
        val handle = ByteArray(32) { it.toByte() }
        keys.stage("a", AuthZenKeyType.RAW256, ByteArray(32) { 7 }, handle)
        keys.markSent("a", handle)
        keys.commit("a", handle, userKey.handle)
    }

    @Test
    fun writesReplaceAtomicallyAndLeaveNoTemporaryFiles() {
        storage.commit("blob", "one".toByteArray())
        storage.commit("blob", "two".toByteArray())
        assertArrayEquals("two".toByteArray(), storage.read("blob"))
        assertEquals(listOf("blob"), File(root, "authzen").list()!!.toList())
        // A write that died before its rename leaves a temporary file, which is never read and is removed on delete.
        File(root, "authzen/blob.123.tmp").writeText("half")
        assertArrayEquals("two".toByteArray(), storage.read("blob"))
        storage.delete("blob")
        assertNull(storage.read("blob"))
        assertEquals(emptyList<String>(), File(root, "authzen").list()!!.toList())
    }

    @Test
    fun aFailedRenameThrowsAndChangesNothing() {
        val repository = AuthZenRepository(storage, lock)
        // Something that cannot be replaced by a rename sits where the store's file belongs.
        File(root, "authzen/transactions").apply { mkdirs() }
        File(root, "authzen/transactions/occupied").writeText("x")
        try {
            repository.apply(AuthZenAction.Reconcile, clock.now())
            fail("a failed rename must throw")
        } catch (e: AuthZenStorageException) {
        }
        assertTrue(File(root, "authzen").listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun everyRenameDeleteAndNewDirectoryIsSyncedInItsParent() {
        val directory = File(root, "a/b/authzen")
        val synced = mutableListOf<String>()
        val storage = AtomicFileStorage(directory) { dir ->
            // The sync of the store's directory comes after the rename or delete it makes durable.
            val blob = File(dir, "blob")
            synced += "${dir.relativeTo(root).path.ifEmpty { "." }}:${if (blob.isFile) blob.readText() else "-"}"
        }
        storage.commit("blob", "one".toByteArray())
        assertEquals(listOf("a/b:-", "a:-", ".:-", "a/b/authzen:one"), synced)
        synced.clear()
        storage.commit("blob", "two".toByteArray())
        storage.delete("blob")
        storage.delete("blob")
        // The second delete finds nothing to unlink and still syncs: the first one's sync might have failed.
        assertEquals(listOf("a/b/authzen:two", "a/b/authzen:-", "a/b/authzen:-"), synced)
    }

    /** Fails the first sync of [failing], then syncs normally; records every sync that succeeded. */
    private class FlakySync(private val failing: File) : (File) -> Unit {
        var failed = false
        val synced = mutableListOf<File>()
        override fun invoke(directory: File) {
            if (!failed && directory == failing) {
                failed = true
                throw AuthZenStorageException("Directory sync failed")
            }
            synced += directory
        }
    }

    @Test
    fun aFailedParentSyncIsRetriedWhenTheDirectoryAlreadyExists() {
        val directory = File(root, "a/authzen")
        val sync = FlakySync(File(root, "a"))
        try {
            AtomicFileStorage(directory, root, sync).commit("blob", "one".toByteArray())
            fail("a failed parent sync must throw")
        } catch (e: AuthZenStorageException) {
        }
        assertTrue("the failed attempt left the directory behind", directory.isDirectory)
        // The same instance and a fresh one (another process, or after a restart) both redo the barrier.
        for (storage in listOf(AtomicFileStorage(directory, root, sync), AtomicFileStorage(directory, root, sync))) {
            sync.synced.clear()
            storage.commit("blob", "two".toByteArray())
            assertEquals(listOf(File(root, "a").absoluteFile, root.absoluteFile, directory), sync.synced.map { it.absoluteFile })
        }
        val retried = FlakySync(File(root, "a"))
        val sameInstance = AtomicFileStorage(directory, root, retried)
        try {
            sameInstance.commit("blob", "three".toByteArray())
            fail("a failed parent sync must throw")
        } catch (e: AuthZenStorageException) {
        }
        sameInstance.commit("blob", "four".toByteArray())
        assertEquals(listOf(File(root, "a").absoluteFile, root.absoluteFile, directory), retried.synced.map { it.absoluteFile })
        // Once established, later writes of that instance sync only the store's directory.
        retried.synced.clear()
        sameInstance.commit("blob", "five".toByteArray())
        assertEquals(listOf(directory), retried.synced)
    }

    @Test
    fun aFailedDeleteSyncIsRetriedWhenTheBlobIsAlreadyGone() {
        val directory = File(root, "authzen")
        AtomicFileStorage(directory).commit("blob", "one".toByteArray())
        val sync = FlakySync(directory)
        try {
            AtomicFileStorage(directory, directorySync = sync).delete("blob")
            fail("a failed delete sync must throw")
        } catch (e: AuthZenStorageException) {
        }
        assertFalse(File(directory, "blob").exists())
        for (storage in listOf(AtomicFileStorage(directory, directorySync = sync), AtomicFileStorage(directory, directorySync = sync))) {
            sync.synced.clear()
            storage.delete("blob")
            assertEquals(listOf(directory), sync.synced)
        }
        // Without a directory nothing was ever stored, so there is nothing to sync.
        sync.synced.clear()
        AtomicFileStorage(File(root, "never"), directorySync = sync).delete("blob")
        assertEquals(emptyList<File>(), sync.synced)
    }

    @Test(expected = IllegalArgumentException::class)
    fun theDurableRootMustContainTheDirectory() {
        AtomicFileStorage(File(root, "a/authzen"), File(root, "b"))
    }

    @Test
    fun aFailedDirectorySyncFailsTheCommitBeforeAnySend() {
        enroll()
        val record = authorizedRecord(storage)
        val failing = AtomicFileStorage(File(root, "authzen")) { throw AuthZenStorageException("Directory sync failed") }
        try {
            failing.commit("blob", "x".toByteArray())
            fail("a failed directory sync must throw")
        } catch (e: AuthZenStorageException) {
        }
        assertTrue(sender(failing).send(record.handle) is SendReport.Stopped)
        assertEquals(0, posts.get())
    }

    @Test
    fun theDefaultDirectorySyncWorksOnThisPlatform() {
        val directory = File(root, "synced").apply { mkdirs() }
        syncDirectory(directory)
        AtomicFileStorage(File(root, "fresh/authzen")).commit("blob", "x".toByteArray())
        assertArrayEquals("x".toByteArray(), AtomicFileStorage(File(root, "fresh/authzen")).read("blob"))
    }

    /** A storage that reports success but keeps the old bytes, like `AtomicFile.finishWrite()` after a lost rename. */
    private class DroppingStorage(private val inner: AuthZenStorage) : AuthZenStorage {
        @Volatile var dropWrites = false
        override fun read(name: String) = inner.read(name)
        override fun write(name: String, bytes: ByteArray) {
            if (!dropWrites) inner.write(name, bytes)
        }

        override fun delete(name: String) = inner.delete(name)
    }

    private fun authorizedRecord(storage: AuthZenStorage, repository: AuthZenRepository = AuthZenRepository(storage, lock, AuthZenReducer(100, 100)), id: String = "tx-1"): AuthZenRecord {
        val request = profile.decodeRequest(syntheticRequestBody(id = id, created = clock.wall, choices = emptyList(), correct = null))
        val digest = SecureMessageCrypto.sha256(request.originalBody).hex()
        val handle = String.format("%032x", id.hashCode().toLong() and 0xffffffffL)
        val record = (repository.apply(AuthZenAction.Receive("a", 1, profile.name, request, digest, handle, emptyList()), clock.now()) as AuthZenResult.Inserted).record
        repository.apply(AuthZenAction.Present(record.handle), clock.now())
        repository.apply(AuthZenAction.Approve(record.handle, null, "attempt"), clock.now())
        val authorized = repository.apply(AuthZenAction.AuthResult(record.handle, "attempt", digest, 1, true), clock.now()) as AuthZenResult.Updated
        assertEquals(AuthZenState.DECISION_AUTHORIZED, authorized.record.state)
        return authorized.record
    }

    @Test
    fun aWriteThatDidNotLandCannotAuthorizeASend() {
        val dropping = DroppingStorage(storage)
        enroll(dropping)
        val record = authorizedRecord(dropping)
        dropping.dropWrites = true
        // SENDING could not be proven stored: no POST, and the durable state is still the stored decision.
        assertTrue(sender(dropping).send(record.handle) is SendReport.Stopped)
        assertEquals(0, posts.get())
        dropping.dropWrites = false
        assertEquals(AuthZenState.DECISION_AUTHORIZED, AuthZenRepository(storage, lock).find(record.handle, clock.now())!!.state)
        assertEquals(AuthZenState.APPROVED_CONFIRMED, (sender(dropping).send(record.handle) as SendReport.Sent).state)
        // Reopening after the answer never sends again.
        assertTrue(sender(dropping).send(record.handle) is SendReport.Stopped)
        assertEquals(1, posts.get())
    }

    @Test
    fun concurrentThreadsOnRealFilesSendOnce() {
        enroll()
        val record = authorizedRecord(storage)
        val start = CountDownLatch(1)
        val workers = (0 until 12).map { index ->
            thread {
                start.await()
                // Separate repositories, like separate screens or processes, mixing sends with rendering reads.
                repeat(5) {
                    if (index % 3 == 0) AuthZenRepository(storage, lock).all(clock.now())
                    else sender().send(record.handle)
                }
            }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals(1, posts.get())
        assertEquals(AuthZenState.APPROVED_CONFIRMED, AuthZenRepository(storage, lock).find(record.handle, clock.now())!!.state)
    }

    /** Separate JVMs take the same file locks, like GmsCore's processes: every decision moves to SENDING once. */
    @Test
    fun concurrentProcessesOnRealFilesBeginEachSendOnce() {
        val repository = AuthZenRepository(storage, lock, AuthZenReducer(100, 100))
        val handles = (0 until 40).map { authorizedRecord(storage, repository, "tx-$it").handle }
        File(root, "handles").writeText(handles.joinToString("\n"))
        val java = File(System.getProperty("java.home"), "bin/java").path
        val classpath = System.getProperty("java.class.path")
        val children = listOf("send", "send-reversed", "read", "read").map { mode ->
            ProcessBuilder(java, "-cp", classpath, AuthZenStorageRaceChild::class.java.name, root.path, mode, clock.wall.toString(), clock.elapsed.toString(), clock.boot)
                .redirectErrorStream(true).start()
        }
        val outputs = children.map { child ->
            val output = child.inputStream.bufferedReader().readText()
            assertTrue("child did not finish", child.waitFor(120, TimeUnit.SECONDS))
            assertEquals(output, 0, child.exitValue())
            output
        }
        val began = outputs.flatMap { it.lines() }.filter { it.startsWith("BEGAN ") }.map { it.removePrefix("BEGAN ") }
        assertEquals("each decision began sending exactly once", handles.sorted(), began.sorted())
        val stored = AuthZenRepository(storage, lock).all(clock.now()).associateBy { it.handle }
        handles.forEach { assertEquals(AuthZenState.SENDING, stored[it]!!.state) }
    }
}

/** A second "process" for [AuthZenStorageTest]: races BeginSend or reconciling reads on the shared files. */
object AuthZenStorageRaceChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args[0])
        val clock = TestClock(args[2].toLong(), args[3].toLong(), args[4])
        val repository = AuthZenRepository(AtomicFileStorage(File(root, "authzen")), DirectoryFileLock(File(root, "locks")), AuthZenReducer(100, 100))
        val handles = File(root, "handles").readLines().filter { it.isNotEmpty() }
        when (args[1]) {
            "send", "send-reversed" -> {
                val order = if (args[1] == "send") handles else handles.reversed()
                for (round in 0 until 3) for (handle in order) {
                    val result = repository.apply(AuthZenAction.BeginSend(handle, "${args[1]}-$round", true, true, 1), clock.now())
                    if (result is AuthZenResult.Updated && result.record.state == AuthZenState.SENDING) println("BEGAN $handle")
                    repository.find(handle, clock.now())
                }
            }
            else -> repeat(150) { repository.all(clock.now()) }
        }
        System.out.flush()
    }
}
