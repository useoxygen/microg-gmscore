/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import com.google.android.gms.fido.fido2.api.common.*
import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.fido.core.getProvidedClientData
import org.microg.gms.fido.core.RequestHandlingException

class LegacySyncedPasskeyPaymentTest {
    private fun extensions(third: Boolean? = null, payment: Boolean? = null) = AuthenticationExtensions(null, null, null, null,
        null, null, null, null, third?.let(::GoogleThirdPartyPaymentExtension), null, null, null, payment?.let(::PaymentExtension))
    private fun create(ext: AuthenticationExtensions?) = PublicKeyCredentialCreationOptions.Builder()
        .setRp(PublicKeyCredentialRpEntity("bank.example", "Bank", null))
        .setUser(PublicKeyCredentialUserEntity(byteArrayOf(1), "owner", null, "Owner"))
        .setChallenge(byteArrayOf(1)).setParameters(listOf(PublicKeyCredentialParameters("public-key", -7)))
        .setAuthenticationExtensions(ext).build()
    private fun browser(base: PublicKeyCredentialCreationOptions, hash: ByteArray? = ByteArray(32)) =
        BrowserPublicKeyCredentialCreationOptions.Builder().setPublicKeyCredentialCreationOptions(base).apply {
            if (hash != null) setClientDataHash(hash)
        }.build()

    @Test fun actualLegacyGateAcceptsBothCreationEncodingsOnlyWithBrowserSuppliedHash() {
        for (ext in listOf(extensions(third = true), extensions(payment = true))) {
            val base = create(ext)
            assertTrue(LegacySyncedPasskeyBackend.paymentEnrollment(browser(base)))
            assertTrue(LegacySyncedPasskeyBackend.supported(browser(base)))
            assertFalse(LegacySyncedPasskeyBackend.supported(base))
            assertFalse(LegacySyncedPasskeyBackend.supported(browser(base, null)))
            assertFalse(LegacySyncedPasskeyBackend.supported(browser(base, ByteArray(31))))
        }
        assertFalse(LegacySyncedPasskeyBackend.supported(browser(create(extensions(third = true, payment = true)))))
        assertFalse(LegacySyncedPasskeyBackend.supported(browser(create(extensions(third = false, payment = true)))))
        assertFalse(LegacySyncedPasskeyBackend.paymentEnrollment(browser(create(extensions(third = false)))))
    }
    @Test fun getCannotSmuggleAnEnrollmentExtensionOrTreatItsBooleanAsPaymentAuthorization() {
        for (ext in listOf(extensions(third = true), extensions(payment = true), extensions(payment = false))) {
            val get = PublicKeyCredentialRequestOptions.Builder().setRpId("bank.example").setChallenge(byteArrayOf(1))
                .setAuthenticationExtensions(ext).build()
            assertFalse(LegacySyncedPasskeyBackend.supported(get))
        }
    }
    @Test fun actualBrowserHashPathCopiesExactTransactionDigestAndNeverInventsPaymentJson() {
        val hash = ByteArray(32) { it.toByte() }
        val request = browser(create(null), hash)
        val output = getProvidedClientData(request)!!
        assertArrayEquals(hash, output.second)
        assertEquals("<invalid>", String(output.first, Charsets.UTF_8))
        hash.fill(42)
        assertArrayEquals(ByteArray(32) { it.toByte() }, output.second)
        assertThrows(RequestHandlingException::class.java) { getProvidedClientData(browser(create(null), ByteArray(31))) }
        assertNull(getProvidedClientData(create(null)))
    }
    @Test fun actualDiscoveryAbiCarriesPaymentOptInAndPublicUserMetadata() {
        val id = byteArrayOf(1, 2, 3); val user = byteArrayOf(4)
        val detail = FidoCredentialDetails("owner", "Owner", user, id, true, true)
        assertTrue(detail.isPaymentCredential); assertTrue(detail.isDiscoverable)
        assertArrayEquals(id, detail.credentialId); assertArrayEquals(user, detail.userId)
        assertFalse(FidoCredentialDetails("owner", "Owner", user, id, true, false).isPaymentCredential)
    }
}
