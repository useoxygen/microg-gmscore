/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.fido.fido2.api.common.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.fido.core.hybrid.HybridAccountSessions

@RunWith(AndroidJUnit4::class)
class HybridPrfParcelAndroidTest {
    @Test fun sdkBuilderPrfSurvivesActualPrivateOptionsParcelAndResponseParcel() {
        val input = ByteArray(64) { it.toByte() }
        val extensions = AuthenticationExtensions.Builder().setPrfExtension(PrfExtension(arrayOf(null, input))).build()
        val base = PublicKeyCredentialRequestOptions.Builder().setRpId("example.invalid").setChallenge(ByteArray(32))
            .setAuthenticationExtensions(extensions).build()
        val options = BrowserPublicKeyCredentialRequestOptions.Builder().setPublicKeyCredentialRequestOptions(base)
            .setOrigin(android.net.Uri.parse(HybridAccountSessions.CONTEXT)).setClientDataHash(ByteArray(32) { 5 }).build()
        val raw = options.serializeToBytes()
        val decoded = BrowserPublicKeyCredentialRequestOptions.deserializeFromBytes(raw)
        assertArrayEquals(input, decoded.publicKeyCredentialRequestOptions.authenticationExtensions!!.prfExtension!!.entries[1])
        assertArrayEquals(raw, decoded.serializeToBytes())
        val output = AuthenticationExtensionsPrfOutputs(false, input)
        val credential = PublicKeyCredential.Builder().setId("AQ").setRawId(byteArrayOf(1))
            .setResponse(AuthenticatorAssertionResponse(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4), byteArrayOf(5)))
            .setAuthenticationExtensionsClientOutputs(AuthenticationExtensionsClientOutputs(null, null, null, output, null)).build()
        val restored = PublicKeyCredential.deserializeFromBytes(credential.serializeToBytes())
        assertArrayEquals(input, restored.clientExtensionResults!!.prfOutputs!!.results)
    }
}
