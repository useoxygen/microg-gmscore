/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import java.security.MessageDigest
import org.microg.gms.common.GooglePackagePermission
import org.microg.gms.utils.ExtendedPackageInfo
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Exact installed image-Core UID; Android enforces that package's update signer. No credential-reading model API. */
class PasswordAgentFillProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Build.VERSION.SDK_INT < 28) throw SecurityException()
        val app = requireNotNull(context)
        val uid = Binder.getCallingUid()
        val core = app.packageManager.getApplicationInfo(CORE, 0)
        if (uid != core.uid || core.flags and ApplicationInfo.FLAG_SYSTEM == 0 ||
            app.packageManager.getPackagesForUid(uid)?.toSet() != setOf(CORE)) throw SecurityException()
        when (method) {
            "candidates", "set_access", "redeem" -> {
                // Core has already verified the focused field. Drop the remote identity before
                // AccountManager/vault work, and expose only fixed errors across this boundary.
                val identity = Binder.clearCallingIdentity()
                try { return AgentSignInBroker(app).call(method, arg, extras) }
                catch (_: Exception) { throw SecurityException("Sign-in unavailable") }
                finally { Binder.restoreCallingIdentity(identity) }
            }
            "cancel" -> { PasswordAgentFillRequests.cancel(arg ?: throw SecurityException()); return Bundle.EMPTY }
            "prepare" -> {
                if (arg != null || extras == null) throw SecurityException()
                val request = PasswordAgentFillRequest.from(app, extras)
                PasswordAgentFillRequests.put(request)
                val intent = Intent(app, PasswordAgentFillActivity::class.java)
                    .setData(Uri.parse("microg-password-agent:/fill/${request.handle}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    // The authenticated remote Binder caller is Core. AMS checks that the
                    // PendingIntent creator's package belongs to the current calling UID;
                    // create this Services-owned, explicit one-shot picker as Services.
                    val identity = Binder.clearCallingIdentity()
                    val picker = try {
                        PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
                    } finally { Binder.restoreCallingIdentity(identity) }
                    return Bundle().apply { putParcelable("picker", picker) }
                } catch (failure: Throwable) {
                    PasswordAgentFillRequests.remove(request.handle, request)
                    request.cancel()
                    throw failure
                }
            }
            else -> throw SecurityException()
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = throw SecurityException()
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = throw SecurityException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw SecurityException()
    companion object { private const val CORE = "ai.cyclon.core" }
}

/** In-memory, one-use selection and callback; process death, timeout and cancellation revoke it. */
internal class PasswordAgentFillTarget private constructor(val packageName: String, val uid: Int,
    private val certificate: ByteArray, val realm: String, private val web: Boolean) {
    fun valid(context: Context): Boolean = Build.VERSION.SDK_INT >= 28 && runCatching {
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners?.takeIf { it.size == 1 } ?: return@runCatching false
        info.applicationInfo?.uid == uid && (!web || if (packageName == "org.lineageos.jelly")
            info.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0 else trustedChrome(context, packageName)) &&
            MessageDigest.isEqual(certificate, MessageDigest.getInstance("SHA-256").digest(signers.single().toByteArray()))
    }.getOrDefault(false)
    companion object {
        fun from(context: Context, bundle: Bundle): PasswordAgentFillTarget {
            val pkg = bundle.getString("package")?.takeIf { it.isNotBlank() && it.length <= 255 } ?: throw SecurityException()
            val cert = bundle.getByteArray("certificate")?.takeIf { it.size == 32 }?.copyOf() ?: throw SecurityException()
            // web_origin is Core's attestation of Jelly's native bridge or the root controller's
            // authenticated Chrome DevTools main-frame capture. Never a model-provided URL.
            // General apps cannot select web realms, and model URL arguments never reach here.
            val origin = bundle.getString("web_origin")
            val realm = if (origin == null) "android://${cert.toByteString().base64Url().trimEnd('=')}@$pkg/" else {
                if (pkg != "org.lineageos.jelly" && !trustedChrome(context, pkg) || origin.length > 4096) throw SecurityException()
                val uri = java.net.URI(origin)
                if (uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.userInfo != null ||
                    uri.rawQuery != null || uri.rawFragment != null || uri.rawPath !in listOf("", "/")) throw SecurityException()
                PasswordData.webRealm(origin)
            }
            return PasswordAgentFillTarget(pkg, bundle.getInt("uid", -1), cert, realm, origin != null)
                .also { if (!it.valid(context)) throw SecurityException() }
        }
        private fun trustedChrome(context: Context, pkg: String): Boolean = pkg == "com.android.chrome" && runCatching {
            ExtendedPackageInfo(context, pkg).hasGooglePackagePermission(GooglePackagePermission.CREDENTIALS)
        }.getOrDefault(false)
    }
}

/** In-memory, one-use selection and callback; process death, timeout and cancellation revoke it. */
internal class PasswordAgentFillRequest private constructor(val handle: String, private val target: PasswordAgentFillTarget,
    val deadline: Long, private val receiver: ResultReceiver) {
    private val active = AtomicBoolean(true)
    @Volatile var close: (() -> Unit)? = null
    val packageName get() = target.packageName
    val realm get() = target.realm
    fun valid(context: Context): Boolean = active.get() && SystemClock.elapsedRealtime() < deadline && target.valid(context)
    fun deliver(context: Context, password: CharArray) {
        try {
            if (!valid(context) || !active.compareAndSet(true, false)) throw SecurityException()
            receiver.send(1, Bundle().apply { putCharArray("password", password) })
        } finally { password.fill('\u0000'); PasswordAgentFillRequests.remove(handle, this) }
    }
    fun cancel() {
        if (active.compareAndSet(true, false)) runCatching { receiver.send(0, Bundle.EMPTY) }
        Handler(Looper.getMainLooper()).post { close?.invoke() }
    }
    override fun toString() = "PasswordAgentFillRequest[redacted]"
    companion object {
        fun from(context: Context, bundle: Bundle): PasswordAgentFillRequest {
            val handle = bundle.getString("handle")?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: throw SecurityException()
            @Suppress("DEPRECATION")
            val receiver = bundle.getParcelable<ResultReceiver>("result") ?: throw SecurityException()
            val now = SystemClock.elapsedRealtime()
            val deadline = bundle.getLong("deadline")
            if (deadline <= now || deadline - now > 120_000) throw SecurityException()
            return PasswordAgentFillRequest(handle, PasswordAgentFillTarget.from(context, bundle), deadline, receiver)
                .also { if (!it.valid(context)) throw SecurityException() }
        }
    }
}

internal object PasswordAgentFillRequests {
    private val requests = ConcurrentHashMap<String, PasswordAgentFillRequest>()
    private val cancelled = LinkedHashMap<String, Long>()
    @Synchronized fun put(request: PasswordAgentFillRequest) {
        val now = SystemClock.elapsedRealtime()
        cancelled.entries.removeAll { it.value <= now }
        requests.values.filter { now >= it.deadline }.forEach { cancel(it.handle) }
        if (cancelled.containsKey(request.handle) || requests.isNotEmpty() || requests.putIfAbsent(request.handle, request) != null) throw SecurityException()
    }
    fun find(uri: Uri?): PasswordAgentFillRequest {
        if (uri?.scheme != "microg-password-agent" || uri.pathSegments?.size != 2 || uri.pathSegments[0] != "fill") throw SecurityException()
        return requests[uri.pathSegments[1]] ?: throw SecurityException()
    }
    fun remove(handle: String, request: PasswordAgentFillRequest) { requests.remove(handle, request) }
    @Synchronized fun cancel(handle: String) {
        // Core can cancel while vault lookup is still in progress, before the request is registered.
        // Remember that revocation so a late registration cannot deliver after cancellation.
        cancelled[handle] = SystemClock.elapsedRealtime() + 120_000
        while (cancelled.size > 1024) cancelled.remove(cancelled.keys.first())
        requests.remove(handle)?.cancel()
    }
}
