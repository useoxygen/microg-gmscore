/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.fido.core

import android.content.Context
import android.content.Intent
import com.google.android.gms.fido.fido2.api.common.RequestOptions
import com.google.android.gms.fido.fido2.api.common.FidoCredentialDetails

/** Implemented by the embedding Services application in every process; fido-core has no dependency on its vault. */
interface AccountPasskeyBackendProvider {
    val accountPasskeyBackend: AccountPasskeyBackend?
}
interface AccountPasskeyBackend {
    /** Only the real native account consumer may advertise hybrid PRF. */
    fun supportsHybridPrf(context: Context): Boolean = false
    suspend fun available(context: Context, options: RequestOptions, callerPackage: String, source: String): Boolean
    fun request(context: Context, options: RequestOptions, callerPackage: String, source: String): Intent
    /** Only an independently verified browser may delegate SPC RP validation to its payment security checks. */
    fun delegatedBrowserRequest(context: Context, options: RequestOptions, callerPackage: String): Boolean = false
    suspend fun credentialList(context: Context, rpId: String, callerPackage: String): List<FidoCredentialDetails> = emptyList()
}
