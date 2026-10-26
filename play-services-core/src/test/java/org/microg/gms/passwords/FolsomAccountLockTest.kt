/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.microg.gms.auth.folsom.utils.FolsomAccountLock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual production account FileLock and VaultSync: reset cannot clear authority between capture and its last remote write. */
class FolsomAccountLockTest {
    @get:Rule val temporary = TemporaryFolder()
    private val account = "a".repeat(64)
    private class Store(initial: VaultState) : VaultStore {
        private var bytes = initial.encode()
        override fun <T> locked(action: (VaultSession) -> T): T = synchronized(this) {
            action(object : VaultSession {
                override fun load() = VaultState.decode(bytes)
                override fun save(state: VaultState) { bytes = state.encode() }
            })
        }
    }
    @Test fun realAccountFileLockSpansCapturedKeysThroughActualOutboxMutationBeforeResetCanClear() {
        val directory = temporary.newFolder(); val shared = ByteArray(32) { (it + 1).toByte() }
        val nigori = NigoriKey.derive(Base64.getEncoder().encode(shared)); val parent = "users/me/birthdays/lock-fixture"
        val metadata = Proto.field(1, parent) + Proto.field(3, Proto.field(1, nigori.encrypt(Proto.field(2, nigori.export()))) + Proto.integer(2, 5))
        val store = Store(VaultState(metadata = metadata, trustedVaultKeys = mutableListOf(shared.copyOf())))
        val queued = PasswordData.create("https://lock.invalid/", "https://lock.invalid/", "synthetic", "synthetic-secret", 1)
        val keys = VaultKeys.unlock(metadata, listOf(shared))
        try { VaultSync(PasswordApi(PasswordTransport { _, _ -> throw AssertionError("enqueue is local") }), store)
            .enqueue(MutationKind.CREATE, queued.tag(), PasswordResourceCodec.encrypt(queued, keys)) } finally { keys.destroy() }
        val captured = CountDownLatch(1); val allowSync = CountDownLatch(1); val resetAttempted = CountDownLatch(1)
        val events = Collections.synchronizedList(ArrayList<String>())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val refresh = executor.submit {
                FolsomAccountLock.withLock(directory, account) {
                    val retained = store.locked { it.load().trustedVaultKeys.map { raw -> raw.copyOf() } }
                    try {
                        events.add("captured"); captured.countDown(); assertTrue(allowSync.await(5, TimeUnit.SECONDS))
                        val transport = PasswordTransport { method, request -> when (method) {
                            PasswordMethod.GET_METADATA -> metadata
                            PasswordMethod.LIST -> byteArrayOf()
                            PasswordMethod.CREATE -> {
                                assertFalse(store.locked { it.load().trustedVaultKeys.isEmpty() })
                                events.add("mutation")
                                Proto.decode(Proto.decode(request).one(2)).replace(1, "$parent/passwords/created".toByteArray())
                                    .replace(5, Proto.integer(1, 1)).encode()
                            }
                            else -> throw AssertionError(method)
                        } }
                        VaultSync(PasswordApi(transport), store).synchronize { VaultKeys.unlock(it, retained) }
                    } finally { retained.forEach { it.fill(0) } }
                }
            }
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            // This assertion exercises the OS FileLock, independently of the helper's same-JVM mutex.
            RandomAccessFile(File(directory, "folsom-enroll-$account.lock"), "rw").use { raw ->
                assertThrows(OverlappingFileLockException::class.java) { raw.channel.tryLock()?.close() }
            }
            val reset = executor.submit {
                resetAttempted.countDown()
                FolsomAccountLock.withLock(File(directory, "."), account) {
                    events.add("reset"); store.locked { VaultRecovery.clearTrustedKeys(it, parent) }
                }
            }
            assertTrue(resetAttempted.await(5, TimeUnit.SECONDS)); assertFalse(reset.isDone)
            allowSync.countDown(); refresh.get(5, TimeUnit.SECONDS); reset.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("captured", "mutation", "reset"), events.toList())
            store.locked { session ->
                assertTrue(session.load().trustedVaultKeys.isEmpty()); assertTrue(session.load().mutations.isEmpty())
                assertThrows(VaultLockedException::class.java) { VaultKeys.unlock(metadata, session.load().trustedVaultKeys) }
            }
        } finally { allowSync.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); nigori.destroy(); shared.fill(0) }
    }
    @Test fun differentAccountsDoNotBlockAndFailureReleasesBothNativeLocks() {
        val directory = temporary.newFolder(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { assertThrows(IOException::class.java) {
                FolsomAccountLock.withLock(directory, account) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); throw IOException("synthetic") }
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val other = executor.submit(Callable { FolsomAccountLock.withLock(directory, "b".repeat(64)) { "independent" } })
            assertEquals("independent", other.get(5, TimeUnit.SECONDS))
            release.countDown(); first.get(5, TimeUnit.SECONDS)
            assertEquals("reopened", FolsomAccountLock.withLock(directory, account) { "reopened" })
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }
}
