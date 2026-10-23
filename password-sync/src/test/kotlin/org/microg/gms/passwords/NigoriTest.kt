/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class NigoriTest {
    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)
    private fun hex(data: ByteArray) = data.joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun chromiumPbkdf2Reference() {
        val proto = Proto.decode(NigoriKey.derive(bytes("hunter2")).export())
        assertEquals("4596bf346572497d92b2a0e2146d93c1", hex(proto.one(3)))
        assertEquals("2292ad9db96fe590b22a58db50f6f545", hex(proto.one(4)))
        assertEquals("ibGL7ymU0Si+eYCXGS6SBHPFT+JCYiB6GDOYqj6vIwEiWJ7RENSHxmIQ8Q3rXd/UnZUmFHYB+jSIbthQADXvrQ==",
            Proto.utf8(NigoriKey.derive(bytes("password")).name()))
    }

    @Test fun chromiumScryptReference() {
        val proto = Proto.decode(NigoriKey.deriveScrypt(bytes("hunter2"), bytes("alpensalz")).export())
        assertEquals("8aa735e0091339a5e51da3b3dd1b328a", hex(proto.one(3)))
        assertEquals("a7e73611968dfd2bca5b3382aed451ba", hex(proto.one(4)))
    }

    @Test fun readModernAndLegacyBlobsAndRejectMutations() {
        val key = NigoriKey.derive(bytes("synthetic"))
        for (modern in listOf(false, true)) {
            val encrypted = key.encrypt(bytes("private synthetic plaintext"), modern)
            assertArrayEquals(bytes("private synthetic plaintext"), key.decrypt(encrypted))
            val fields = Proto.decode(encrypted)
            val blob = fields.one(2)
            val firstAuthenticatedByte = if (modern) 0 else 16
            for (i in firstAuthenticatedByte until blob.size) {
                val mutated = blob.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
                assertThrows(ProtocolException::class.java) { key.decrypt(fields.replace(2, mutated).encode()) }
            }
        }
    }

    @Test fun realKeystoreWrappingLayoutAndUnknownFields() {
        val raw = bytes("synthetic server key material")
        val wrapping = NigoriKey.derive(Base64.getEncoder().encode(raw))
        val dataKey = NigoriKey.derive(bytes("synthetic password encryption key"))
        val encryptedBag = dataKey.encrypt(Proto.field(2, dataKey.export()) + Proto.field(100, "unknown future metadata"))
        val token = wrapping.encrypt(dataKey.export())
        val metadata = Proto.field(1, "users/me/birthdays/synthetic") + Proto.field(2, raw) +
            Proto.field(3, Proto.field(1, encryptedBag) + Proto.integer(2, 2) + Proto.field(3, token))
        val unlocked = VaultKeys.unlock(metadata)
        assertArrayEquals(bytes("value"), unlocked.decrypt(dataKey.encrypt(bytes("value"))))
        assertFalse(unlocked.toString().contains("synthetic"))
        assertThrows(VaultLockedException::class.java) {
            VaultKeys.unlock(Proto.decode(metadata).replace(2, bytes("wrong keystore key")).encode())
        }
    }

    @Test fun malformedWireInputsAndLosslessUnknownFields() {
        for (input in listOf(byteArrayOf(0), byteArrayOf(10, 5, 1), byteArrayOf(11), ByteArray(11) { 255.toByte() })) {
            assertThrows(ProtocolException::class.java) { Proto.decode(input) }
        }
        val unknown = Proto.field(456, byteArrayOf(0, 255.toByte()))
        val proto = Proto.decode(Proto.field(8, "old secret") + unknown)
        val updated = Proto.decode(proto.replace(8, bytes("new secret")).encode())
        assertArrayEquals(byteArrayOf(0, 255.toByte()), updated.one(456))
        assertEquals("new secret", updated.string(8))
        assertEquals("Proto[redacted]", updated.toString())
    }

    @Test fun trustedVaultAndCustomPassphraseModesUseExplicitDerivation() {
        val shared = bytes("synthetic trusted vault shared key")
        val trusted = NigoriKey.derive(Base64.getEncoder().encode(shared))
        val trustedMeta = Proto.field(3, Proto.field(1, trusted.encrypt(Proto.field(2, trusted.export()))) + Proto.integer(2, 5))
        assertThrows(VaultLockedException::class.java) { VaultKeys.unlock(trustedMeta) }
        VaultKeys.unlock(trustedMeta, listOf(shared)).destroy()
        val password = bytes("synthetic custom sync passphrase")
        for (scrypt in listOf(false, true)) {
            val key = if (scrypt) NigoriKey.deriveScrypt(password, bytes("synthetic salt")) else NigoriKey.derive(password)
            val specifics = Proto.field(1, key.encrypt(Proto.field(2, key.export()))) + Proto.integer(2, 4) +
                Proto.integer(5, if (scrypt) 2 else 1) +
                if (scrypt) Proto.field(6, Base64.getEncoder().encode(bytes("synthetic salt"))) else byteArrayOf()
            val metadata = Proto.field(3, specifics)
            VaultKeys.unlock(metadata, customPassphrase = password).destroy()
            val imported = VaultKeys.keyForPassphrase(metadata, password)
            VaultKeys.unlock(metadata, customKey = imported.export()).destroy()
            assertThrows(VaultLockedException::class.java) { VaultKeys.unlock(metadata, customPassphrase = bytes("wrong")) }
            imported.destroy(); key.destroy()
        }
    }

    @Test fun rotatedKeyBagCanReadAnOlderEntryAndWritesWithCurrentKey() {
        val shared = bytes("synthetic trusted key")
        val current = NigoriKey.derive(Base64.getEncoder().encode(shared))
        val old = NigoriKey.derive(bytes("old data key"))
        val bag = Proto.field(2, old.export()) + Proto.field(2, current.export())
        val metadata = Proto.field(3, Proto.field(1, current.encrypt(bag)) + Proto.integer(2, 5))
        val keys = VaultKeys.unlock(metadata, listOf(shared))
        assertArrayEquals(bytes("old encrypted value"), keys.decrypt(old.encrypt(bytes("old encrypted value"))))
        assertArrayEquals(current.name(), Proto.decode(keys.encrypt(bytes("new value"))).one(1))
        keys.destroy(); old.destroy(); current.destroy()
    }

    @Test fun staleTrustedVaultKeysRefreshOnceWithoutChangingEncryptionMetadata() {
        val shared = bytes("new shared key")
        val current = NigoriKey.derive(Base64.getEncoder().encode(shared))
        val metadata = Proto.field(3, Proto.field(1, current.encrypt(Proto.field(2, current.export()))) + Proto.integer(2, 5))
        val attempts = ArrayList<Boolean>(); val copies = ArrayList<ByteArray>()
        val keys = VaultKeys.unlockTrustedVault(metadata) { force ->
            attempts.add(force)
            listOf((if (force) shared.copyOf() else bytes("old shared key")).also(copies::add))
        }
        assertEquals(listOf(false, true), attempts)
        assertTrue(copies.all { key -> key.all { it == 0.toByte() } })
        assertArrayEquals(bytes("private"), keys.decrypt(current.encrypt(bytes("private"))))
        keys.destroy(); current.destroy(); shared.fill(0)
    }

    @Test fun unavailableTrustedKeysRemainLockedAndCurrentKeysAvoidNetworkRefresh() {
        val shared = bytes("current shared key")
        val current = NigoriKey.derive(Base64.getEncoder().encode(shared))
        val metadata = Proto.field(3, Proto.field(1, current.encrypt(Proto.field(2, current.export()))) + Proto.integer(2, 5))
        val attempts = ArrayList<Boolean>()
        VaultKeys.unlockTrustedVault(metadata) { force -> attempts.add(force); listOf(shared.copyOf()) }.destroy()
        assertEquals(listOf(false), attempts)
        attempts.clear()
        assertThrows(VaultLockedException::class.java) {
            VaultKeys.unlockTrustedVault(metadata) { force -> attempts.add(force); emptyList() }
        }
        assertEquals(listOf(false, true), attempts)
        var reads = 0
        assertThrows(ProtocolException::class.java) {
            VaultKeys.unlockTrustedVault(Proto.field(3, Proto.integer(2, 4))) { reads++; emptyList() }
        }
        assertEquals(0, reads)
        current.destroy(); shared.fill(0)
    }
}
