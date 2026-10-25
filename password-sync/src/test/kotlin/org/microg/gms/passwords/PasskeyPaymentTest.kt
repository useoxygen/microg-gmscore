/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class PasskeyPaymentTest {
    private val id = byteArrayOf(1, 2, 3)
    private val hash = ByteArray(32) { 7 }
    private val origin = "https://merchant.example"
    private val rp = "bank.example"
    private fun item(payment: Boolean = false): SyncedPasskey = SyncedPasskey.decode(Proto.field(1, ByteArray(16) { 1 }) +
        Proto.field(2, id) + Proto.field(3, rp) + Proto.field(4, byteArrayOf(2)) + Proto.field(12, ByteArray(32)) +
        (if (payment) Proto.integer(11, 1) else byteArrayOf()) + Proto.field(97, "future-metadata"))

    @Test fun creationAliasesAreExplicitAndCannotShadowEachOther() {
        assertFalse(PasskeyPayment.enrollment(null, null)); assertFalse(PasskeyPayment.enrollment(false, null))
        assertFalse(PasskeyPayment.enrollment(null, false)); assertTrue(PasskeyPayment.enrollment(true, null))
        assertTrue(PasskeyPayment.enrollment(null, true))
        for (first in listOf(false, true)) for (second in listOf(false, true))
            assertThrows(ProtocolException::class.java) { PasskeyPayment.enrollment(first, second) }
    }
    @Test fun enrollmentPreservesActualCredentialAndUnknownFieldsButChangesRegistrationAuthority() {
        val original = item(); val enrolled = PasskeyPayment.withPaymentSupport(original)
        assertFalse(original.paymentSupport); assertTrue(enrolled.paymentSupport)
        assertFalse(original.sameRegistration(enrolled))
        assertArrayEquals(original.encode(), Proto.decode(enrolled.encode()).replace(11, null).encode())
        assertArrayEquals(enrolled.encode(), PasskeyPayment.withPaymentSupport(enrolled).encode())
        assertThrows(ProtocolException::class.java) { SyncedPasskey.decode(original.encode() + Proto.integer(11, 2)) }
    }
    @Test fun crossOriginAuthorizationRequiresBrowserHashFiniteAllowlistAndActualStoredOptIn() {
        val enrolled = item(true)
        PasskeyPayment.authorize(enrolled, true, origin, rp, hash, listOf(id))
        fun refused(value: SyncedPasskey = enrolled, verified: Boolean = true, originValue: String = origin,
            rpValue: String = rp, digest: ByteArray? = hash, allowed: List<ByteArray> = listOf(id)) =
            assertThrows(ProtocolException::class.java) { PasskeyPayment.authorize(value, verified, originValue, rpValue, digest, allowed) }
        refused(value = item(false)); refused(verified = false); refused(digest = null); refused(digest = ByteArray(31))
        refused(digest = ByteArray(33)); refused(allowed = emptyList()); refused(allowed = listOf(byteArrayOf(9)))
        refused(allowed = listOf(id, byteArrayOf())); refused(allowed = List(1001) { id }); refused(rpValue = "other.example")
        for (bad in listOf("http://merchant.example", "https://user@merchant.example", "https://merchant.example/path",
                "https://merchant.example?x=y", "https://merchant.example#fragment", "https://merchant.example:99999")) refused(originValue = bad)
        assertFalse(PasskeyPayment.crossOrigin("https://bank.example", rp))
        assertFalse(PasskeyPayment.crossOrigin("https://login.bank.example:8443", rp))
        assertTrue(PasskeyPayment.crossOrigin("https://bank.example.evil", rp))
        assertTrue(PasskeyPayment.crossOrigin("https://notbank.example", rp))
        // Ordinary same-RP requests retain ordinary WebAuthn behavior; no new payment authority is inferred.
        PasskeyPayment.authorize(item(false), false, "https://bank.example", rp, null, emptyList())
    }
    @Test fun actualSignatureCommitsToBrowserTransactionHashAndExactRequestBindingChangesWithIt() {
        val pair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
        val auth = MessageDigest.getInstance("SHA-256").digest(rp.toByteArray()) + byteArrayOf(5, 0, 0, 0, 0)
        try {
            val signature = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(auth + hash); sign() }
            fun valid(digest: ByteArray) = Signature.getInstance("SHA256withECDSA").run { initVerify(pair.public); update(auth + digest); verify(signature) }
            assertTrue(valid(hash)); assertFalse(valid(hash.copyOf().apply { this[0] = 8 }))
            val signer = ByteArray(32) { 1 }
            fun binding(digest: ByteArray) = LegacyPasskeyBridgePolicy.requestDigest("com.android.chrome", signer, "browser", origin,
                false, Proto.field(1, digest) + Proto.field(2, rp) + Proto.field(3, id))
            assertFalse(binding(hash).contentEquals(binding(hash.copyOf().apply { this[0] = 8 })))
        } finally { runCatching { pair.private.destroy() } }
    }
}
