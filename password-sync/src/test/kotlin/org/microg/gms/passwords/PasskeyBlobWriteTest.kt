/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

class PasskeyBlobWriteTest {
    private fun rawSecret() = Proto.field(1, "private-key-placeholder") + Proto.field(2, ByteArray(32) { 4 }) +
        Proto.field(99, "future-secret")
    @Test fun creationAdvertisesOnlyOurConservativeCapacityAndStoresExactlyTheAcceptedCredentialBlob() {
        for (size in listOf(0, 1, 32, 33, 100)) PasskeyBlobCreateRequest(ByteArray(size) { 7 }, true).use { request ->
            val encoded = request.apply(rawSecret())
            assertTrue(request.credBlobRequested); assertEquals(size <= 32, request.credBlobStored)
            assertTrue(request.largeBlobSupport)
            val parsed = Proto.decode(encoded)
            if (size <= 32) assertArrayEquals(ByteArray(size) { 7 }, parsed.one(3)) else assertNull(parsed.optional(3))
            assertEquals("future-secret", parsed.string(99)); assertArrayEquals(Proto.decode(rawSecret()).one(2), parsed.one(2))
        }
    }
    @Test fun writePersistsRawDeflateAndOriginalSizeWhilePreservingEveryOtherSecretField() {
        for (payload in listOf(byteArrayOf(), "hello".toByteArray(), ByteArray(2048) { (it * 73).toByte() })) {
            PasskeyBlobWriteRequest(payload).use { request ->
                val original = rawSecret(); val written = request.apply(original)!!
                val parsed = Proto.decode(written)
                assertEquals(payload.size.toLong(), parsed.integer(5))
                assertArrayEquals(original, parsed.replace(4, null).replace(5, null).encode())
                PasskeyBlobReadRequest(false, true).read(written).use { assertArrayEquals(payload, it.largeBlob) }
                assertNull(request.apply(written)) // A proof retry must not launch another encryption/update.
            }
        }
        assertThrows(ProtocolException::class.java) { PasskeyBlobWriteRequest(ByteArray(2049)) }
    }
    @Test fun semanticallyEqualBlobDoesNotRecompressOrRewrapADifferentValidDeflateEncoding() {
        val stored = byteArrayOf(1, 5, 0, 0xfa.toByte(), 0xff.toByte()) + "hello".toByteArray()
        val secret = rawSecret() + Proto.field(4, stored) + Proto.integer(5, 5)
        PasskeyBlobWriteRequest("hello".toByteArray()).use { assertNull(it.apply(secret)) }
    }
    @Test fun authenticatedSecretUpdatesPreserveDomainDeviceEpochsOpaqueFieldsAndActualPrivateKey() {
        val pair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
        val pkcs8 = pair.private.encoded
        val domain = PasskeyDomainKey(7, ByteArray(32) { 2 }); val device = PasskeyDomainKey(9, ByteArray(32) { 3 })
        try {
            for (field in listOf(9, 12, 19)) {
                val original = Proto.field(1, pkcs8) + Proto.field(2, ByteArray(32) { 4 }) + Proto.field(3, byteArrayOf(5)) + Proto.field(99, "future-secret")
                val ciphertext = when (field) {
                    9 -> domain.derive("KeychainApplicationKey:gmscore_module:com.google.android.gms.fido").let { key ->
                        try { PasskeyCrypto.seal(key, pkcs8, "", SecureRandom()) } finally { key.fill(0) }
                    }
                    12 -> PasskeyCrypto.encrypt(original, domain)
                    else -> {
                        val dk = device.derive("DeviceAuthorizationKey:WebauthnCredentialSpecifics")
                        val nested = try { PasskeyCrypto.seal(dk, original, "WebauthnCredentialSpecifics.DeviceAuthorizationEncrypted", SecureRandom()) }
                            finally { dk.fill(0) }
                        val wrapper = Proto.field(1, nested) + Proto.field(99, "future-wrapper")
                        val key = domain.derive("KeychainApplicationKey:gmscore_module:com.google.android.gms.fido")
                        try { PasskeyCrypto.seal(key, wrapper, "WebauthnCredentialSpecifics.SecurityDomainEncrypted", SecureRandom()) }
                        finally { key.fill(0); wrapper.fill(0); nested.fill(0) }
                    }
                }
                val data = SyncedPasskey.decode(Proto.field(1, ByteArray(16)) + Proto.field(2, byteArrayOf(1)) +
                    Proto.field(3, "example.invalid") + Proto.field(4, byteArrayOf(2)) + Proto.integer(14, 7) +
                    Proto.integer(18, if (field == 19) 9 else 0) + Proto.field(field, ciphertext) + Proto.field(98, "future-outer"))
                PasskeyCrypto.decrypt(data, listOf(domain), listOf(device)).use { old ->
                    val before = old.encode()
                    val migrated = PasskeyPrfMigration.upgrade(before) ?: before.copyOf()
                    val after = PasskeyBlobWriteRequest("persisted".toByteArray()).use { it.apply(migrated)!! }
                    try {
                        val changed = PasskeyCrypto.updateSecret(data, before, after, listOf(domain), listOf(device))
                        assertEquals(if (field == 9) 12 else field, changed.encryptedField)
                        assertEquals(7, changed.keyVersion); assertEquals(data.deviceKeyVersion, changed.deviceKeyVersion)
                        assertEquals("future-outer", Proto.decode(changed.encode()).string(98))
                        PasskeyCrypto.decrypt(changed, listOf(domain), listOf(device)).use { opened ->
                            val encoded = opened.encode()
                            try {
                                assertArrayEquals(pkcs8, Proto.decode(encoded).one(1))
                                PasskeyBlobReadRequest(false, true).read(encoded).use { assertArrayEquals("persisted".toByteArray(), it.largeBlob) }
                                if (field != 9) assertEquals("future-secret", Proto.decode(encoded).string(99))
                                val private = opened.privateKey(); assertArrayEquals(pkcs8, private.encoded); runCatching { private.destroy() }
                            } finally { encoded.fill(0) }
                        }
                        if (field == 19) {
                            val key = domain.derive("KeychainApplicationKey:gmscore_module:com.google.android.gms.fido")
                            val wrapper = try { PasskeyCrypto.open(key, changed.ciphertext, "WebauthnCredentialSpecifics.SecurityDomainEncrypted") }
                                finally { key.fill(0) }
                            try { assertEquals("future-wrapper", Proto.decode(wrapper).string(99)) } finally { wrapper.fill(0) }
                        }
                    } finally { before.fill(0); migrated.fill(0); after.fill(0) }
                }
                original.fill(0); ciphertext.fill(0)
            }
        } finally { pkcs8.fill(0); domain.close(); device.close(); runCatching { pair.private.destroy() } }
    }
    @Test fun secretMutationCannotReplacePrivateKeyCredentialBlobExistingPrfOrOpaqueMetadata() {
        val domain = PasskeyDomainKey(7, ByteArray(32) { 2 })
        try {
            val original = Proto.field(1, ByteArray(64) { 1 }) + Proto.field(2, ByteArray(32) { 4 }) +
                Proto.field(99, "future-secret") + Proto.field(3, byteArrayOf(5))
            val data = SyncedPasskey.decode(Proto.field(1, ByteArray(16)) + Proto.field(2, byteArrayOf(1)) +
                Proto.field(3, "example.invalid") + Proto.field(4, byteArrayOf(2)) + Proto.integer(14, 7) +
                Proto.field(12, PasskeyCrypto.encrypt(original, domain)))
            for (field in listOf(1, 2, 3, 99)) assertThrows(ProtocolException::class.java) {
                PasskeyCrypto.updateSecret(data, original, Proto.decode(original).replace(field, ByteArray(32) { 8 }).encode(), listOf(domain))
            }
        } finally { domain.close() }
    }
    @Test fun unspecifiedEpochUsesTheActuallyAuthenticatedOlderDomainInsteadOfTheNewestRetainedKey() {
        val older = PasskeyDomainKey(7, ByteArray(32) { 2 }); val newer = PasskeyDomainKey(8, ByteArray(32) { 3 })
        val device = PasskeyDomainKey(9, ByteArray(32) { 4 })
        val before = Proto.field(1, ByteArray(64) { 1 }) + Proto.field(2, ByteArray(32) { 4 })
        try {
            for (field in listOf(12, 19)) {
                val ciphertext = if (field == 12) PasskeyCrypto.encrypt(before, older) else PasskeyCrypto.encryptWithDevice(before, older, device)
                val data = SyncedPasskey.decode(Proto.field(1, ByteArray(16)) + Proto.field(2, byteArrayOf(1)) +
                    Proto.field(3, "example.invalid") + Proto.field(4, byteArrayOf(2)) + Proto.integer(18, 9) + Proto.field(field, ciphertext))
                val after = PasskeyBlobWriteRequest(byteArrayOf(7)).use { it.apply(before)!! }
                val updated = PasskeyCrypto.updateSecret(data, before, after, listOf(older, newer), listOf(device))
                assertEquals(7, updated.keyVersion)
                PasskeyCrypto.decrypt(updated, listOf(older, newer), listOf(device)).use {
                    assertArrayEquals(after, it.encode())
                }
                val wrong = Proto.decode(before).replace(1, ByteArray(64) { 9 }).encode()
                assertThrows(ConflictException::class.java) { PasskeyCrypto.updateSecret(data, wrong, wrong, listOf(older, newer), listOf(device)) }
            }
        } finally { older.close(); newer.close(); device.close(); before.fill(0) }
    }
}
