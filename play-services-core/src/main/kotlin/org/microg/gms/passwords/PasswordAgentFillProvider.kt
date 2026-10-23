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
import java.util.Base64
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
            "cancel" -> { PasswordAgentFillRequests.cancel(arg ?: throw SecurityException()); return Bundle.EMPTY }
            "prepare" -> {
                if (arg != null || extras == null) throw SecurityException()
                val request = PasswordAgentFillRequest.from(app, extras)
                PasswordAgentFillRequests.put(request)
                val intent = Intent(app, PasswordAgentFillActivity::class.java)
                    .setData(Uri.parse("microg-password-agent:/fill/${request.handle}"))
                val picker = PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
                return Bundle().apply { putParcelable("picker", picker) }
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
internal class PasswordAgentFillRequest private constructor(val handle: String, val packageName: String, val uid: Int,
    private val certificate: ByteArray, val deadline: Long, private val receiver: ResultReceiver) {
    private val active = AtomicBoolean(true)
    @Volatile var close: (() -> Unit)? = null
    val realm = "android://${Base64.getUrlEncoder().withoutPadding().encodeToString(certificate)}@$packageName/"
    fun valid(context: Context): Boolean = active.get() && SystemClock.elapsedRealtime() < deadline && runCatching {
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners?.takeIf { it.size == 1 } ?: return@runCatching false
        info.applicationInfo?.uid == uid && MessageDigest.isEqual(certificate,
            MessageDigest.getInstance("SHA-256").digest(signers.single().toByteArray()))
    }.getOrDefault(false)
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
            val pkg = bundle.getString("package")?.takeIf { it.isNotBlank() && it.length <= 255 } ?: throw SecurityException()
            val certificate = bundle.getByteArray("certificate")?.takeIf { it.size == 32 } ?: throw SecurityException()
            @Suppress("DEPRECATION")
            val receiver = bundle.getParcelable<ResultReceiver>("result") ?: throw SecurityException()
            val now = SystemClock.elapsedRealtime()
            val deadline = bundle.getLong("deadline")
            if (deadline <= now || deadline - now > 120_000) throw SecurityException()
            return PasswordAgentFillRequest(handle, pkg, bundle.getInt("uid", -1), certificate.copyOf(), deadline, receiver)
                .also { if (!it.valid(context)) throw SecurityException() }
        }
    }
}

internal object PasswordAgentFillRequests {
    private val requests = ConcurrentHashMap<String, PasswordAgentFillRequest>()
    @Synchronized fun put(request: PasswordAgentFillRequest) {
        requests.values.filter { SystemClock.elapsedRealtime() >= it.deadline }.forEach { cancel(it.handle) }
        if (requests.isNotEmpty() || requests.putIfAbsent(request.handle, request) != null) throw SecurityException()
    }
    fun find(uri: Uri?): PasswordAgentFillRequest {
        if (uri?.scheme != "microg-password-agent" || uri.pathSegments?.size != 2 || uri.pathSegments[0] != "fill") throw SecurityException()
        return requests[uri.pathSegments[1]] ?: throw SecurityException()
    }
    fun remove(handle: String, request: PasswordAgentFillRequest) { requests.remove(handle, request) }
    fun cancel(handle: String) { requests.remove(handle)?.cancel() }
}
