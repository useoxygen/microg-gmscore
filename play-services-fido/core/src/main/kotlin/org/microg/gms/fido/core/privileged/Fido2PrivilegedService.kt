/*
 * SPDX-FileCopyrightText: 2022 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.fido.core.privileged

import android.app.KeyguardManager
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Context.KEYGUARD_SERVICE
import android.content.Intent
import android.os.Build.VERSION.SDK_INT
import android.os.Binder
import android.os.Parcel
import android.util.Log
import androidx.core.app.PendingIntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import com.google.android.gms.fido.fido2.api.IBooleanCallback
import com.google.android.gms.fido.fido2.api.ICredentialListCallback
import com.google.android.gms.fido.fido2.api.common.BrowserPublicKeyCredentialCreationOptions
import com.google.android.gms.fido.fido2.api.common.BrowserPublicKeyCredentialRequestOptions
import com.google.android.gms.fido.fido2.internal.privileged.IFido2PrivilegedCallbacks
import com.google.android.gms.fido.fido2.internal.privileged.IFido2PrivilegedService
import org.microg.gms.BaseService
import org.microg.gms.common.GmsService
import org.microg.gms.common.PackageUtils
import org.microg.gms.common.GmsService.FIDO2_PRIVILEGED_API
import org.microg.gms.fido.core.FEATURES
import org.microg.gms.fido.core.AccountPasskeyBackendProvider
import org.microg.gms.fido.core.ui.AuthenticatorActivity
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.SOURCE_BROWSER
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_SOURCE
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_OPTIONS
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_SERVICE
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_TYPE
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.TYPE_REGISTER
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.TYPE_SIGN
import org.microg.gms.utils.warnOnTransactionIssues

const val TAG = "Fido2Privileged"

class Fido2PrivilegedService : BaseService(TAG, FIDO2_PRIVILEGED_API) {
    override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
        val packageName = PackageUtils.getAndCheckCallingPackage(this, request.packageName) ?: throw SecurityException()
        callback.onPostInitCompleteWithConnectionInfo(
            CommonStatusCodes.SUCCESS,
            Fido2PrivilegedServiceImpl(this, lifecycle, packageName, Binder.getCallingUid()).asBinder(),
            ConnectionInfo().apply { features = FEATURES }
        );
    }
}

class Fido2PrivilegedServiceImpl(private val context: Context, override val lifecycle: Lifecycle,
    private val packageName: String, private val uid: Int) :
    IFido2PrivilegedService.Stub(), LifecycleOwner {
    private fun checkCaller() {
        if (Binder.getCallingUid() != uid || PackageUtils.getAndCheckCallingPackage(context, packageName) != packageName) throw SecurityException()
    }
    override fun getRegisterPendingIntent(callbacks: IFido2PrivilegedCallbacks, options: BrowserPublicKeyCredentialCreationOptions) {
        checkCaller()
        lifecycleScope.launchWhenStarted {
            val intent = Intent(context, AuthenticatorActivity::class.java)
                .putExtra(KEY_SERVICE, FIDO2_PRIVILEGED_API.SERVICE_ID)
                .putExtra(KEY_SOURCE, SOURCE_BROWSER)
                .putExtra(KEY_TYPE, TYPE_REGISTER)
                .putExtra(KEY_OPTIONS, options.serializeToBytes())

            val pendingIntent =
                PendingIntentCompat.getActivity(context, options.hashCode(), intent, FLAG_UPDATE_CURRENT, false)
            callbacks.onPendingIntent(Status.SUCCESS, pendingIntent)
        }
    }

    override fun getSignPendingIntent(callbacks: IFido2PrivilegedCallbacks, options: BrowserPublicKeyCredentialRequestOptions) {
        checkCaller()
        lifecycleScope.launchWhenStarted {
            val intent = Intent(context, AuthenticatorActivity::class.java)
                .putExtra(KEY_SERVICE, FIDO2_PRIVILEGED_API.SERVICE_ID)
                .putExtra(KEY_SOURCE, SOURCE_BROWSER)
                .putExtra(KEY_TYPE, TYPE_SIGN)
                .putExtra(KEY_OPTIONS, options.serializeToBytes())

            val pendingIntent =
                PendingIntentCompat.getActivity(context, options.hashCode(), intent, FLAG_UPDATE_CURRENT, false)
            callbacks.onPendingIntent(Status.SUCCESS, pendingIntent)
        }
    }

    override fun isUserVerifyingPlatformAuthenticatorAvailable(callbacks: IBooleanCallback) {
        checkCaller()
        lifecycleScope.launchWhenStarted {
            if (SDK_INT < 24) {
                callbacks.onBoolean(false)
            } else {
                val keyguardManager = context.getSystemService(KEYGUARD_SERVICE) as? KeyguardManager?
                callbacks.onBoolean(keyguardManager?.isDeviceSecure == true)
            }
        }
    }

    override fun getCredentialList(callbacks: ICredentialListCallback, rpId: String) {
        checkCaller()
        lifecycleScope.launchWhenStarted {
            try {
                val backend = (context.applicationContext as? AccountPasskeyBackendProvider)?.accountPasskeyBackend
                val result = backend?.credentialList(context, rpId, packageName).orEmpty()
                if (context.packageManager.getApplicationInfo(packageName, 0).uid != uid) throw SecurityException()
                callbacks.onCredentialList(result)
            } catch (_: Exception) { runCatching { callbacks.onError(Status.INTERNAL_ERROR) } }
        }
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
        warnOnTransactionIssues(code, reply, flags, TAG) { super.onTransact(code, data, reply, flags) }
}
