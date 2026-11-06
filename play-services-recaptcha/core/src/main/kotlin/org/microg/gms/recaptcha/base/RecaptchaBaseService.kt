/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.recaptcha.base

import android.os.Binder
import android.os.Parcel
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.Feature
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import com.google.android.gms.recaptchabase.ExecuteRequest
import com.google.android.gms.recaptchabase.InitRequest
import com.google.android.gms.recaptchabase.internal.IRecaptchaBaseCallbacks
import com.google.android.gms.recaptchabase.internal.IRecaptchaBaseService
import org.microg.gms.BaseService
import org.microg.gms.common.GmsService
import org.microg.gms.common.PackageUtils
import org.microg.gms.utils.warnOnTransactionIssues

private const val TAG = "RecaptchaBase"

/** Features Play services advertises for reCAPTCHA Base; the client SDK checks these exact names. */
internal val RECAPTCHA_BASE_FEATURES = arrayOf(
    Feature("INIT", 1),
    Feature("EXECUTE", 1),
)

/**
 * reCAPTCHA Base (Play services service 380). Each connection is bound to the package and UID the broker verified, and
 * produces DroidGuard results for that package only.
 */
class RecaptchaBaseService : BaseService(TAG, GmsService.RECAPTCHABASE) {
    private val guard by lazy { DroidGuardRecaptchaBaseGuard(this) }

    override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
        // Both are read on the broker's binder thread, before anything asynchronous happens.
        val callingUid = Binder.getCallingUid()
        val packageName = PackageUtils.getAndCheckCallingPackage(this, request.packageName)
        if (packageName == null) {
            Log.w(TAG, "No package for calling UID $callingUid")
            callback.onPostInitComplete(CommonStatusCodes.DEVELOPER_ERROR, null, null)
            return
        }
        val session = RecaptchaBaseSession(packageName, guard, lifecycleScope)
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
            session.close()
        } else {
            lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) session.close()
            })
        }
        callback.onPostInitCompleteWithConnectionInfo(
            CommonStatusCodes.SUCCESS,
            RecaptchaBaseServiceImpl(session, callingUid),
            ConnectionInfo().apply { features = RECAPTCHA_BASE_FEATURES }
        )
    }
}

/**
 * The binder handed to one verified caller. Every transaction must come from [boundUid]; another UID that obtains this
 * binder is refused with a [SecurityException] before any request is read into the session.
 */
internal class RecaptchaBaseServiceImpl(
    private val session: RecaptchaBaseSession,
    private val boundUid: Int,
    private val callingUid: () -> Int = Binder::getCallingUid,
) : IRecaptchaBaseService.Stub() {

    override fun init(callbacks: IRecaptchaBaseCallbacks?, request: InitRequest?) {
        checkCaller()
        requireNotNull(callbacks) { "callbacks required" }
        session.init(callbacks)
    }

    override fun execute(callbacks: IRecaptchaBaseCallbacks?, request: ExecuteRequest?) {
        checkCaller()
        requireNotNull(callbacks) { "callbacks required" }
        session.execute(callbacks, request?.nonce, request?.executeId)
    }

    private fun checkCaller() {
        val uid = callingUid()
        if (uid != boundUid) {
            Log.w(TAG, "UID $uid used a reCAPTCHA Base connection of UID $boundUid")
            throw SecurityException("reCAPTCHA Base connection belongs to another caller")
        }
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
        warnOnTransactionIssues(code, reply, flags, TAG) { super.onTransact(code, data, reply, flags) }
}
