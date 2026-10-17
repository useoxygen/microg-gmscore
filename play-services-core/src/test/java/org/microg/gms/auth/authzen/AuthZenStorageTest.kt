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
