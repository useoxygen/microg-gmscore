/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class SyncedPasskeyTest {
    private val parent = "users/me/birthdays/fixture"
    private fun data(id: Int = 1, credential: Int = id, created: Long = 1, extra: ByteArray = byteArrayOf()): SyncedPasskey =
        SyncedPasskey.decode(Proto.field(1, ByteArray(16) { id.toByte() }) + Proto.field(2, ByteArray(16) { credential.toByte() }) +
            Proto.field(3, "example.invalid") + Proto.field(4, byteArrayOf(7)) + Proto.integer(6, created) +
            Proto.field(7, "fixture-user") + Proto.field(12, ByteArray(28)) + extra)
    private fun resource(item: SyncedPasskey, name: String = "$parent/opaque/${item.tag}"): ByteArray =
        Proto.field(1, name) + Proto.field(2, item.tag) + Proto.field(7, item.encode())
    private fun keys(): VaultKeys {
        val wrapping = NigoriKey(ByteArray(16) { 1 }, ByteArray(16) { 2 })
        return try { VaultKeys.unlock(Proto.field(1, parent) + Proto.field(3,
            Proto.field(1, wrapping.encrypt(Proto.field(2, wrapping.export()))) + Proto.integer(2, 4)), customKey = wrapping.export()) }
        finally { wrapping.destroy() }
    }
    @Test fun independentHkdfAndNistAes256GcmVectors() {
        val raw = ByteArray(32) { it.toByte() }
        val info = "KeychainApplicationKey:gmscore_module:com.google.android.gms.fido"
        val expected = ByteArray(32)
        HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(raw, byteArrayOf(), info.toByteArray(Charsets.US_ASCII)))
            generateBytes(expected, 0, expected.size) }
        assertArrayEquals(expected, PasskeyCrypto.hkdf(raw, info))
        val tag = "530f8afbc74536b9a963b4f1c4cb738b".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(byteArrayOf(), PasskeyCrypto.open(ByteArray(32), ByteArray(12) + tag, ""))
        assertThrows(ProtocolException::class.java) { PasskeyCrypto.open(ByteArray(32), ByteArray(12) + tag, "different AAD") }
    }
    @Test fun nestedDomainAndDeviceKeysProduceARealNativeSignatureAndRejectWrongVersions() {
        val pair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
        val plain = Proto.field(1, pair.private.encoded) + Proto.field(2, ByteArray(32) { 8 }) + Proto.field(90, "future")
        val domain = PasskeyDomainKey(3, ByteArray(32) { 5 })
        val device = PasskeyDomainKey(9, ByteArray(32) { 6 })
        val ciphertext = PasskeyCrypto.encryptWithDevice(plain, domain, device)
        val proto = Proto.decode(data().encode()).replace(12, null).replace(19, ciphertext).replaceInteger(14, 3).replaceInteger(18, 9)
        val item = SyncedPasskey.decode(proto.encode())
        try {
            PasskeyCrypto.decrypt(item, listOf(domain), listOf(device)).use { secret ->
                assertArrayEquals(plain, secret.encode())
                val message = "native synthetic assertion".toByteArray()
                val signed = Signature.getInstance("SHA256withECDSA").run { initSign(secret.privateKey()); update(message); sign() }
                assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(pair.public); update(message); verify(signed) })
                assertEquals("PasskeySecret[redacted]", secret.toString())
            }
            assertThrows(VaultLockedException::class.java) { PasskeyCrypto.decrypt(item, listOf(domain)) }
            assertThrows(VaultLockedException::class.java) { PasskeyCrypto.decrypt(SyncedPasskey.decode(proto.replaceInteger(14, 4).encode()), listOf(domain), listOf(device)) }
            assertThrows(VaultLockedException::class.java) { PasskeyCrypto.decrypt(SyncedPasskey.decode(proto.replaceInteger(18, 10).encode()), listOf(domain), listOf(device)) }
            val corrupt = ciphertext.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            assertThrows(VaultLockedException::class.java) { PasskeyCrypto.decrypt(SyncedPasskey.decode(proto.replace(19, corrupt).encode()), listOf(domain), listOf(device)) }
        } finally { domain.close(); device.close(); plain.fill(0) }
    }
    @Test fun legacyEncryptedEnvelopeAndUnknownFieldsRemainCompatible() {
        val domain = PasskeyDomainKey(1, ByteArray(32) { 4 })
        val pair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
        val plain = Proto.field(1, pair.private.encoded) + Proto.field(99, byteArrayOf(9))
        val item = SyncedPasskey.decode(Proto.decode(data(extra = Proto.field(88, byteArrayOf(8))).encode())
            .replace(12, PasskeyCrypto.encrypt(plain, domain)).replaceInteger(14, 1).encode())
        try { PasskeyCrypto.decrypt(item, listOf(domain)).use { assertArrayEquals(plain, it.encode()) }
            assertArrayEquals(byteArrayOf(8), Proto.decode(item.withLastUsed(123).encode()).one(88))
        } finally { domain.close() }
    }
    @Test fun resourceCiphertextWrapsEntitySpecificsAndNamesAreOpaque() {
        val keys = keys()
        try {
            val source = resource(data()) + Proto.field(200, byteArrayOf(1))
            val encrypted = PasskeyResourceCodec.write(data(), keys, source)
            val fields = Proto.decode(encrypted)
            assertNull(fields.optional(7)); assertNotNull(fields.optional(4))
            assertArrayEquals(byteArrayOf(1), fields.one(200))
            val unwrapped = keys.decrypt(fields.one(4))
            assertArrayEquals(data().encode(), Proto.decode(unwrapped).one(895275))
            assertEquals(data().tag, PasskeyResourceCodec.read(encrypted, keys).tag)
            assertThrows(ProtocolException::class.java) { PasskeyResourceCodec.read(encrypted + Proto.field(7, data().encode()), keys) }
            assertThrows(ProtocolException::class.java) { PasskeyResourceCodec.read(Proto.decode(source).replace(2, "wrong".toByteArray()).encode(), keys) }
            assertEquals("opaque-server-id", PasskeyApi.checkedName("opaque-server-id", parent))
        } finally { keys.destroy() }
    }
    @Test fun shadowingOverridesClockSkewAndNewestWinsWithoutDeletingOldResources() {
        val old = data(1, created = 300)
        val replacement = data(2, created = 200).withShadows(listOf(old.credentialId))
        assertEquals(replacement.tag, SyncedPasskey.visible(listOf(old, replacement)).single().tag)
        assertEquals(old.tag, SyncedPasskey.visible(listOf(old, data(2, created = 200))).single().tag)
        assertTrue(SyncedPasskey.visible(listOf(old, data(2, created = 300))).isEmpty())
        assertTrue(SyncedPasskey.visible(listOf(old, data(2, credential = 1, created = 400))).isEmpty())
        assertEquals("01010101010101010101010101010101", old.tag)
        val reordered = SyncedPasskey.decode(Proto.decode(old.encode()).replace(1, old.syncId).encode())
        assertFalse(old.encode().contentEquals(reordered.encode())); assertTrue(old.sameRegistration(reordered))
    }
    @Test fun actualListAndCreateBytesMatchPinnedFieldsAndPaginationCannotLoop() {
        var calls = 0
        val api = PasskeyApi(PasswordTransport { method, raw ->
            val request = Proto.decode(raw)
            when (method) {
                PasswordMethod.LIST_WEBAUTHN -> {
                    assertEquals(parent, request.string(1))
                    if (calls++ == 0) { assertNull(request.optional(3)); Proto.field(1, resource(data())) + Proto.field(2, byteArrayOf(1)) }
                    else { assertArrayEquals(byteArrayOf(1), request.one(3)); Proto.field(1, resource(data(2))) }
                }
                PasswordMethod.CREATE_WEBAUTHN -> {
                    assertEquals(parent, request.string(1)); assertArrayEquals(resource(data()), request.one(2))
                    assertArrayEquals(byteArrayOf(9), request.one(4)); resource(data())
                }
                else -> throw AssertionError()
            }
        })
        assertEquals(2, api.snapshot(parent).size)
        api.create(parent, resource(data()), byteArrayOf(9))
        assertThrows(ProtocolException::class.java) { PasskeyApi(PasswordTransport { _, _ -> Proto.field(2, byteArrayOf(1)) }).snapshot(parent) }
    }
    @Test fun snapshotAuthenticatesAllRecordsBeforeReplacingAndRetainsUnresolvedAttemptHistory() {
        val keys = keys()
        try {
            val raw = resource(data())
            val pending = resource(data(2))
            val old = PasskeyState(parent, listOf(raw), 1, pending, true, listOf(byteArrayOf(4)))
            val missing = PasskeySnapshots.reconcile(old, parent, listOf(raw), keys, 2)
            assertNotNull(missing.pending); assertTrue(missing.pendingLaunched); assertEquals(1, missing.abandoned.size)
            val confirmed = PasskeySnapshots.reconcile(old, parent, listOf(raw, pending), keys, 3)
            assertNull(confirmed.pending); assertFalse(confirmed.pendingLaunched)
            assertThrows(VaultResetException::class.java) { PasskeySnapshots.reconcile(old, "users/me/birthdays/reset", emptyList(), keys, 4) }
            assertThrows(ProtocolException::class.java) { PasskeySnapshots.reconcile(old, parent, listOf(raw, byteArrayOf(0)), keys, 4) }
            assertArrayEquals(old.encode(), PasskeyState.decode(old.encode()).encode())
        } finally { keys.destroy() }
    }
    @Test fun nativeCandidatePathFetchesOnFirstUseResetAndPopulatedUnrelatedRpCache() {
        var calls = 0
        val remote = listOf(data())
        assertEquals(remote, PasskeySnapshots.candidates({ emptyList() }, { calls++; remote }))
        assertEquals(1, calls)
        assertEquals(remote, PasskeySnapshots.candidates({ throw VaultResetException() }, { calls++; remote }))
        assertEquals(2, calls)
        val otherRp = SyncedPasskey.decode(Proto.decode(data(2).encode()).replace(3, "other.invalid".toByteArray()).encode())
        assertEquals(listOf(otherRp), PasskeySnapshots.candidates({ remote }, { calls++; listOf(otherRp) }))
        assertEquals(3, calls)
        assertTrue(PasskeySnapshots.candidates({ remote }, { emptyList() }).isEmpty())
        assertThrows(VaultResetException::class.java) { PasskeySnapshots.candidates({ throw VaultResetException() }, { throw VaultResetException() }) }
    }
    @Test fun deviceAuthorizationUsesSaltedSignalsActualBytesAndStrictPositiveVersions() {
        val salt = ByteArray(16) { it.toByte() }
        val request = Proto.decode(PasskeyDeviceAuthorization.request(salt, true, byteArrayOf(5), "synthetic-rapt"))
        assertEquals("synthetic-rapt", request.string(1))
        assertEquals("hw_protected", Proto.decode(request.one(2)).string(1))
        assertEquals(1L, Proto.decode(request.one(2)).integer(2))
        assertArrayEquals(byteArrayOf(5), Proto.decode(request.one(3)).one(1))
        assertArrayEquals(salt, Proto.decode(request.one(3)).one(2))
        val first = PasskeyDeviceAuthorization.signals(salt, true, "synthetic-rapt")
        assertEquals(setOf("salt", "rapt_token", "trusted_vault_key_availability_0"), first.keys)
        assertNotEquals(first, PasskeyDeviceAuthorization.signals(ByteArray(16) { 7 }, true, "synthetic-rapt"))
        val response = Proto.field(1, Proto.field(1, Proto.integer(1, 7) + Proto.field(2, ByteArray(32) { 3 })))
        PasskeyDeviceAuthorization.response(response, setOf(7)).forEach(PasskeyDomainKey::close)
        assertThrows(ProtocolException::class.java) { PasskeyDeviceAuthorization.response(response, setOf(8)) }
        assertThrows(ProtocolException::class.java) { PasskeyDeviceAuthorization.response(response, setOf(0)) }
        assertThrows(PasskeyReauthRequiredException::class.java) { PasskeyDeviceAuthorization.response(Proto.field(2, Proto.field(1, "synthetic")), setOf(7)) }
    }
    @Test fun advertisedNativeCapabilitiesMatchFinalRedemptionAndDoNotClaimUnsupportedOptions() {
        assertTrue(PasskeyRequestSupport.create(listOf(-7), null, null, emptySet()))
        assertTrue(PasskeyRequestSupport.create(listOf(-257, -7), "none", "platform", setOf("credProps")))
        assertFalse(PasskeyRequestSupport.create(listOf(-257), null, null, emptySet()))
        for (attestation in listOf("direct", "indirect", "enterprise", "invalid"))
            assertFalse(PasskeyRequestSupport.create(listOf(-7), attestation, "platform", emptySet()))
        assertFalse(PasskeyRequestSupport.create(listOf(-7), "none", "cross-platform", emptySet()))
        for (extension in listOf("prf", "largeBlob", "credBlob", "payment", "appid", "uvm", "unknown")) {
            assertFalse(PasskeyRequestSupport.create(listOf(-7), null, null, setOf(extension)))
            assertFalse(PasskeyRequestSupport.get(setOf(extension)))
        }
        assertTrue(PasskeyRequestSupport.get(emptySet()))
        assertFalse(PasskeyRequestSupport.get(setOf("credProps")))
    }
}
