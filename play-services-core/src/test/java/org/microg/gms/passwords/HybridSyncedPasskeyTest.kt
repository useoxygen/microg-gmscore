/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import com.google.android.gms.fido.fido2.api.common.*
import com.upokecenter.cbor.CBORObject
import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.fido.core.hybrid.HybridPrf
import org.microg.gms.fido.core.hybrid.HybridAccountSessions
import org.microg.gms.fido.core.ui.AuthenticatorActivity

class HybridSyncedPasskeyTest {
    private fun map(vararg fields: Pair<Any, Any>) = CBORObject.NewMap().apply { fields.forEach { (k, v) -> set(CBORObject.FromObject(k), CBORObject.FromObject(v)) } }
    private fun item(rp: String = "example.invalid") = SyncedPasskey.decode(Proto.field(1, ByteArray(16) { 1 }) +
        Proto.field(2, byteArrayOf(1, 2)) + Proto.field(3, rp) + Proto.field(4, byteArrayOf(3)) + Proto.field(12, ByteArray(32)))
    private fun get() = BrowserPublicKeyCredentialRequestOptions.Builder()
        .setPublicKeyCredentialRequestOptions(PublicKeyCredentialRequestOptions.Builder().setRpId("example.invalid").setChallenge(ByteArray(32)).build())
        .setClientDataHash(ByteArray(32)).build()
    @Test fun sourceCtapParserFeedsRealAccountPrfWithoutSecondDomainHashOrPublicIdSubstitution() {
        val ext = HybridPrf.parse(mapOf("prf" to map("eval" to map("first" to ByteArray(32)))), false)!!
        val parsed = SyncedPasskeyPrf.legacy(ext.prfExtension, false)!!
        val expected = "33ad0a1c607ec03b09e6cd9893680ce210adf300aa1f2660e1b22e10f170f92a".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        parsed.evaluate(ByteArray(32), byteArrayOf(9)).use { result ->
            assertArrayEquals(expected, result.first)
            val cbor = HybridPrf.output(SyncedPasskeyPrf.output(result), false)!!
            assertArrayEquals(expected, cbor["prf"]["results"]["first"].GetByteString())
        }
        PasskeyPrfRequest.normalized(PasskeyPrfValues.raw(ByteArray(32)), create = false).evaluate(ByteArray(32), byteArrayOf(9)).use {
            assertFalse(expected.contentEquals(it.first))
        }
    }
    @Test fun actualSelectedCredentialAuthorityUsesLiveHybridLeaseAndExactRpWithoutWebsitePaymentAdmission() {
        var checked = 0
        LegacySyncedPasskeyBackend.authorizeSelected(item(), AuthenticatorActivity.SOURCE_HYBRID, HybridAccountSessions.CONTEXT, get()) { checked++ }
        assertEquals(1, checked); assertFalse(item().paymentSupport)
        assertThrows(VaultLockedException::class.java) {
            LegacySyncedPasskeyBackend.authorizeSelected(item("other.invalid"), AuthenticatorActivity.SOURCE_HYBRID, HybridAccountSessions.CONTEXT, get()) { checked++ }
        }
        assertThrows(SecurityException::class.java) {
            LegacySyncedPasskeyBackend.authorizeSelected(item(), AuthenticatorActivity.SOURCE_HYBRID, HybridAccountSessions.CONTEXT, get()) { throw SecurityException() }
        }
        assertThrows(ProtocolException::class.java) {
            LegacySyncedPasskeyBackend.authorizeSelected(item(), AuthenticatorActivity.SOURCE_BROWSER, HybridAccountSessions.CONTEXT, get()) { fail() }
        }
    }
    @Test fun actualPrivateRequestDigestBindsHybridTokenAndCannotAcquireBrowserOrAppAuthority() {
        val signer = ByteArray(32) { 2 }; val token = "00000000-0000-0000-0000-000000000001"
        fun digest(source: String = "hybrid", pkg: String = "com.google.android.gms", origin: String = HybridAccountSessions.CONTEXT, value: String? = token) =
            LegacyPasskeyBridgePolicy.requestDigest(pkg, signer, source, origin, false, byteArrayOf(1, 2), value)
        assertFalse(digest().contentEquals(digest(value = "00000000-0000-0000-0000-000000000002")))
        assertThrows(ProtocolException::class.java) { digest(value = null) }
        assertThrows(ProtocolException::class.java) { digest(pkg = "com.android.chrome") }
        assertThrows(ProtocolException::class.java) { digest(origin = "https://example.invalid") }
        assertThrows(ProtocolException::class.java) { digest(source = "browser") }
        assertThrows(ProtocolException::class.java) { digest(source = "app") }
        assertFalse(LegacyPasskeyBridgePolicy.browser("com.google.android.gms", signer, HybridAccountSessions.CONTEXT))
    }
}
