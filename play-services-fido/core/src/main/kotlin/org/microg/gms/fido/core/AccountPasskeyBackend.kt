/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.fido.core

import android.content.Context
import android.content.Intent
import com.google.android.gms.fido.fido2.api.common.RequestOptions

/** Implemented by the embedding Services application in every process; fido-core has no dependency on its vault. */
interface AccountPasskeyBackendProvider {
    val accountPasskeyBackend: AccountPasskeyBackend?
}
interface AccountPasskeyBackend {
    suspend fun available(context: Context, options: RequestOptions, callerPackage: String, source: String): Boolean
    fun request(context: Context, options: RequestOptions, callerPackage: String, source: String): Intent
}
