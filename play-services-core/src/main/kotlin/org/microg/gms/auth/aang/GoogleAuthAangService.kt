/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.aang

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.RemoteException
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.Feature
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.microg.gms.AbstractGmsServiceBroker
import org.microg.gms.auth.aang.AangParcelables.*
import org.microg.gms.common.GmsService
import org.microg.gms.common.GooglePackagePermission
import org.microg.gms.common.PackageUtils
import org.microg.gms.utils.ExtendedPackageInfo
import java.util.EnumSet

internal const val AANG_DESCRIPTOR = "com.google.android.gms.auth.aang.internal.IGoogleAuthAangService"
internal const val AANG_CALLBACK = "com.google.android.gms.auth.aang.internal.IGoogleAuthAangCallbacks"
internal val AANG_FEATURES get() = arrayOf(Feature("google_auth_api", 1L))

/** The three google_auth_api operations used by Switch. Sync/reauth/remove are not advertised. */
class GoogleAuthAangService : LifecycleService() {
    private val broker = object : AbstractGmsServiceBroker(EnumSet.of(GmsService.GOOGLE_AUTH_AANG)) {
        override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
            val caller = try { authorizeAangCaller(this@GoogleAuthAangService, request.packageName) }
            catch (_: SecurityException) {
                callback.onPostInitComplete(ConnectionResult.DEVELOPER_ERROR, null, null)
                return
            }
            val uid = Binder.getCallingUid()
            val binder = GoogleAuthAangBinder(uid, AangAccountBackend(this@GoogleAuthAangService, caller),
                authorize = { authorizeAangCaller(this@GoogleAuthAangService, caller) },
                execute = { action -> lifecycleScope.launch(Dispatchers.IO) { action() }; Unit })
            callback.onPostInitCompleteWithConnectionInfo(ConnectionResult.SUCCESS, binder,
                ConnectionInfo().apply { features = AANG_FEATURES })
        }
    }

    override fun onBind(intent: Intent): IBinder { super.onBind(intent); return broker }
}

internal fun authorizeAangCaller(context: android.content.Context, suggested: String?): String {
    val caller = PackageUtils.getAndCheckCallingPackage(context, suggested) ?: throw SecurityException("Missing caller")
    if (!ExtendedPackageInfo(context, caller).hasGooglePackagePermission(GooglePackagePermission.ACCOUNT)) {
        throw SecurityException("Account access denied")
    }
    return caller
}

internal interface AangBackend {
    fun getAccounts(request: GetAccountsRequest): GetAccountsResponse
    fun getToken(request: GetTokenRequest): GetTokenResponse
    fun hasCapabilities(request: HasCapabilitiesRequest): Int
}

internal class AangFailure(val status: Status) : Exception()

/** Codes and synchronous service replies: real GMS amoa:70-108; callbacks: Switch ijf:24-62. */
internal class GoogleAuthAangBinder(
    private val uid: Int,
    private val backend: AangBackend,
    private val authorize: () -> Unit,
    private val execute: (() -> Unit) -> Unit
) : Binder() {
    init { attachInterface(null, AANG_DESCRIPTOR) }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code > 0xffffff) return super.onTransact(code, data, reply, flags)
        if (Binder.getCallingUid() != uid) throw SecurityException("Auth binder belongs to another uid")
        authorize()
        data.enforceInterface(AANG_DESCRIPTOR)
        if (code !in setOf(1, 2, 4)) return false
        require(data.dataAvail() <= 65536) { "Auth request too large" }
        val callback = requireNotNull(data.readStrongBinder())
        val request = if (data.readInt().also { require(it in 0..1) } == 0) null else when (code) {
            1 -> GetAccountsRequest.CREATOR.createFromParcel(data)
            2 -> GetTokenRequest.CREATOR.createFromParcel(data)
            else -> HasCapabilitiesRequest.CREATOR.createFromParcel(data)
        }
        require(data.dataAvail() == 0) { "Unexpected auth request fields" }
        execute {
            var status = Status.SUCCESS
            val result = try {
                if (request == null) throw AangFailure(Status(10, "Missing request"))
                when (code) {
                    1 -> backend.getAccounts(request as GetAccountsRequest)
                    2 -> backend.getToken(request as GetTokenRequest)
                    else -> backend.hasCapabilities(request as HasCapabilitiesRequest)
                }
            } catch (e: AangFailure) { status = e.status; null }
              catch (_: SecurityException) { status = Status(10, "Account access denied"); null }
              catch (_: IllegalArgumentException) { status = Status(10, "Invalid auth request"); null }
              catch (_: Exception) { status = Status(8, "Auth operation failed"); null }
            val output = Parcel.obtain()
            try {
                output.writeInterfaceToken(AANG_CALLBACK)
                output.writeInt(1); status.writeToParcel(output, 0)
                if (code == 4) output.writeInt(result as? Int ?: -1) else {
                    output.writeInt(if (result == null) 0 else 1)
                    (result as? Parcelable)?.writeToParcel(output, 0)
                }
                callback.transact(if (code == 4) 3 else code, output, null, IBinder.FLAG_ONEWAY)
            } catch (_: RemoteException) {
                // The client may exit during account recovery/network I/O. Its death must not kill GMS.
            } finally { output.recycle() }
        }
        reply?.writeNoException()
        return true
    }
}
