/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.fido.core.hybrid

import android.app.Activity
import com.google.android.gms.fido.fido2.api.common.BrowserRequestOptions
import com.google.android.gms.fido.fido2.api.common.RequestOptions
import org.microg.gms.fido.core.RequestOptionsType
import org.microg.gms.fido.core.rpId
import org.microg.gms.fido.core.type
import org.microg.gms.fido.core.ui.hybrid.HybridAuthenticateActivity
import org.microg.gms.fido.core.ui.AuthenticatorActivity

/** Both native hops are private Activities. The exported QR Activity alone cannot mint a live Noise lease. */
object HybridAccountRequests {
    fun verifyAuthenticator(activity: Activity, options: RequestOptions, claim: Boolean = false, claimed: Boolean = false): String {
        check(activity.javaClass == AuthenticatorActivity::class.java && activity.packageName == "com.google.android.gms" && activity.callingActivity?.packageName == activity.packageName &&
            activity.callingActivity?.className == HybridAuthenticateActivity::class.java.name)
        return activity.intent.getStringExtra(HybridAccountSessions.KEY_REQUEST)?.also {
            verify(it, options, claim, claimed)
        } ?: throw SecurityException("Hybrid request expired")
    }
    fun verify(token: String?, options: RequestOptions, claim: Boolean = false, claimed: Boolean = true) {
        check(options is BrowserRequestOptions && options.origin.toString() == HybridAccountSessions.CONTEXT)
        HybridAccountSessions.requireLive(token, options.serializeToBytes(), options.rpId, options.clientDataHash,
            options.type == RequestOptionsType.REGISTER, claim, claimed)
    }
}
