/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.recaptcha.base

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import com.google.android.gms.common.Feature
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import com.google.android.gms.recaptchabase.ExecuteRequest
import com.google.android.gms.recaptchabase.InitRequest
import com.google.android.gms.recaptchabase.internal.IRecaptchaBaseCallbacks
import com.google.android.gms.recaptchabase.internal.IRecaptchaBaseService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    /** Host tests replace this before the first request; production always uses microG's DroidGuard runtime. */
    internal var guardFactory: () -> RecaptchaBaseGuard = { DroidGuardRecaptchaBaseGuard(this) }
    private val guard by lazy { guardFactory() }

    // Requests arrive on binder threads, where Lifecycle APIs (addObserver, lifecycleScope's first use) must not be called;
    // the service owns its scope and connections and closes them in onDestroy instead.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connections = HashSet<Connection>()
    private var destroyed = false

    /**
     * One session handed out by the broker, owned by the client that asked for it. [client] is the broker callback binder
     * that client sent with getService. Holding it keeps the client's callback object alive, so the client dropping its
     * own reference cannot end the link: its death notification means the process hosting it died. The session is then
     * closed. A client in this process never dies before the service, so its sessions close in onDestroy.
     */
    private inner class Connection(val session: RecaptchaBaseSession, val client: IBinder) : IBinder.DeathRecipient {
        override fun binderDied() {
            retire(this)
        }

        fun unlink() {
            try {
                client.unlinkToDeath(this, 0)
            } catch (e: NoSuchElementException) {
                // Never linked, or already unlinked.
            }
        }
    }

    internal val openSessionCount: Int get() = synchronized(connections) { connections.size }

    override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
        // Both are read on the broker's binder thread, before anything asynchronous happens.
        val callingUid = Binder.getCallingUid()
        val packageName = PackageUtils.getAndCheckCallingPackage(this, request.packageName)
        if (packageName == null) {
            Log.w(TAG, "No package for calling UID $callingUid")
            callback.onPostInitComplete(CommonStatusCodes.DEVELOPER_ERROR, null, null)
            return
        }
        val session = RecaptchaBaseSession(packageName, guard, scope)
        val connection = Connection(session, callback.asBinder())
        // A connection that cannot be registered (service destroyed, client already dead) is closed: its requests fail.
        register(connection)
        try {
            callback.onPostInitCompleteWithConnectionInfo(
                CommonStatusCodes.SUCCESS,
                RecaptchaBaseServiceImpl(session, callingUid),
                ConnectionInfo().apply { features = RECAPTCHA_BASE_FEATURES }
            )
        } catch (e: Exception) {
            // The client never received this session, so nothing else can use or close it.
            retire(connection)
            throw e
        }
    }

    private fun register(connection: Connection) {
        val registered = synchronized(connections) { !destroyed && connections.add(connection) }
        if (!registered) {
            connection.session.close()
            return
        }
        try {
            connection.client.linkToDeath(connection, 0)
        } catch (e: RemoteException) {
            retire(connection)
            return
        }
        // onDestroy may have taken the connection between registering and linking; it closed the session but could not
        // unlink a recipient that was not linked yet.
        val stillOpen = synchronized(connections) { connection in connections }
        if (!stillOpen) connection.unlink()
    }

    /** Removes [connection] and closes its session, once: client death, delivery failure and onDestroy each try. */
    private fun retire(connection: Connection) {
        val removed = synchronized(connections) { connections.remove(connection) }
        if (!removed) return
        connection.unlink()
        connection.session.close()
    }

    override fun onDestroy() {
        val open = synchronized(connections) {
            destroyed = true
            connections.toList().also { connections.clear() }
        }
        open.forEach {
            it.unlink()
            it.session.close()
        }
        scope.cancel()
        super.onDestroy()
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
