/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.authzen

import org.junit.Assert.*
import org.junit.Test

class AuthZenKeyRegistryTest {
    private val storage = InMemoryStorage()
    private val registry = AuthZenKeyRegistry(storage, TestLock)
    private val publicKeyHandle = ByteArray(32) { 9 }

    private fun key(seed: Int) = ByteArray(32) { (seed + it).toByte() }
    private fun handle(seed: Int) = ByteArray(32) { (seed * 3).toByte() }

    private fun enroll(seed: Int) {
        registry.stage("a", AuthZenKeyType.RAW256, key(seed), handle(seed))
        registry.markSent("a", handle(seed))
        registry.commit("a", handle(seed), publicKeyHandle)
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail()
        } catch (e: IllegalStateException) {
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun aKeyCountsOnlyAfterTheServerConfirmed() {
        registry.stage("a", AuthZenKeyType.RAW256, key(1), handle(1))
        assertNull(registry.serverKey("a", handle(1)))
        expectFailure { registry.commit("a", handle(1), publicKeyHandle) }
        registry.markSent("a", handle(1))
        assertNull(registry.serverKey("a", handle(1)))
        // A request that may have reached the server is neither replaced nor silently dropped.
        expectFailure { registry.stage("a", AuthZenKeyType.RAW256, key(2), handle(2)) }
        assertFalse(registry.abort("a"))
        registry.commit("a", handle(1), publicKeyHandle)
        val keys = registry.keys("a")
        assertEquals(1L, keys.generation)
        assertArrayEquals(key(1), keys.active!!.material)
        assertArrayEquals(publicKeyHandle, keys.publicKeyHandle)
        assertNull(keys.pending)
        assertArrayEquals(key(1), registry.serverKey("a", handle(1))!!.encoded)
    }

    @Test
    fun aStagedKeyCanBeAbortedAfterACrashBeforeSending() {
        registry.stage("a", AuthZenKeyType.RAW128, ByteArray(16), handle(1))
        val restarted = AuthZenKeyRegistry(storage, TestLock)
        assertNotNull(restarted.keys("a").pending)
        assertTrue(restarted.abort("a"))
        assertNull(restarted.keys("a").pending)
        assertEquals(0L, restarted.keys("a").generation)
    }

    @Test
    fun rotationKeepsRetiredKeysForDecryptionOnly() {
        enroll(1)
        enroll(2)
        val keys = registry.keys("a")
        assertEquals(2L, keys.generation)
        assertArrayEquals(key(2), keys.active!!.material)
        assertEquals(AuthZenKeyStatus.INACTIVE, keys.keys.single { it.handle.contentEquals(handle(1)) }.status)
        assertNotNull(registry.serverKey("a", handle(1)))
        enroll(3)
        enroll(4)
        assertNull(registry.serverKey("a", handle(1)))
        assertEquals(3, registry.keys("a").keys.size)
    }

    @Test
    fun serverDirectivesAndClearing() {
        enroll(1)
        registry.deactivate("a", handle(1))
        assertNull(registry.keys("a").active)
        assertNotNull(registry.serverKey("a", handle(1)))
        registry.delete("a", handle(1))
        assertNull(registry.serverKey("a", handle(1)))
        assertEquals(3L, registry.keys("a").generation)
        enroll(2)
        registry.clear("a")
        assertNull(registry.serverKey("a", handle(2)))
    }

    @Test
    fun keysAreAccountScopedAndValidated() {
        enroll(1)
        assertNull(registry.serverKey("b", handle(1)))
        expectFailure { registry.stage("b", AuthZenKeyType.RAW256, ByteArray(16), handle(1)) }
        assertEquals(setOf("RAW128", "RAW256"), AuthZenKeyType.values().map { it.name }.toSet())
    }

    @Test
    fun anUnreadableFileMeansNoKeys() {
        enroll(1)
        storage.files.keys.forEach { storage.files[it] = "{".toByteArray() }
        assertNull(registry.keys("a").active)
    }

    @Test
    fun theRegistryOnlyWritesItsOwnFiles() {
        enroll(1)
        assertTrue(storage.files.keys.all { it.startsWith("keys-") && !it.contains("a@") })
    }
}
