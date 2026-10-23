/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import com.google.android.gms.auth.api.credentials.Credential
import org.microg.gms.auth.credentials.LegacyPasswordActivity
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Recheck after repository construction/read and again at the native result boundary. */
internal class LegacyPasswordAccountGuard(private val expectedUserId: String?, private val current: () -> String?) {
    fun check() { if (current() != expectedUserId) throw SecurityException() }
    fun <T> checked(action: () -> T): T { check(); val result = action(); check(); return result }
}

class LegacyPasswordRequest(val packageName: String, val uid: Int, val certificate: ByteArray, val mode: Int,
    val username: String?, val password: String?, val displayName: String?) {
    val realm: String get() = "android://${Base64.getUrlEncoder().withoutPadding().encodeToString(certificate)}@$packageName/"
    override fun toString() = "LegacyPasswordRequest[redacted]"
}

/** A resolution carries only a random handle; request values stay in encrypted storage. */
class LegacyPasswordRequests(private val context: Context, identity: String = "legacy-password-requests-v1") {
    private val store = AndroidVaultStore(context, identity)
    fun issue(packageName: String, uid: Int, mode: Int, credential: Credential? = null): PendingIntent {
        val certificate = certificate(packageName, uid)
        require(mode in GET..DELETE)
        val id = credential?.id
        if (mode != GET && (id.isNullOrEmpty() || id.length > 100_000 || !credential?.accountType.isNullOrEmpty())) throw ProtocolException()
        if (mode == SAVE && (credential?.password.isNullOrEmpty() || credential!!.password.length > 100_000)) throw ProtocolException()
        if ((credential?.name?.length ?: 0) > 100_000) throw ProtocolException()
        val now = SystemClock.elapsedRealtime()
        val boot = boot()
        if (boot < 0) throw SecurityException()
        val encoded = Proto.field(1, packageName) + Proto.integer(2, uid.toLong()) + Proto.field(3, certificate) +
            Proto.integer(4, mode.toLong()) + Proto.integer(5, now) + Proto.integer(6, boot) +
            (id?.let { Proto.field(7, it) } ?: byteArrayOf()) +
            (if (mode == SAVE) credential?.password?.let { Proto.field(8, it) } ?: byteArrayOf() else byteArrayOf()) +
            (if (mode == SAVE) credential?.name?.let { Proto.field(9, it) } ?: byteArrayOf() else byteArrayOf())
        val nonce = UUID.randomUUID().toString()
        try { store.locked { session ->
            val state = session.load()
            state.chromeLocalData.entries.removeAll { (_, raw) -> !fresh(Proto.decode(raw), now, boot) }
            if (state.chromeLocalData.size >= 128) throw ProtocolException()
            state.chromeLocalData[nonce] = encoded
            session.save(state)
        } } finally { encoded.fill(0) }
        return PendingIntent.getActivity(context, 0,
            Intent(context, LegacyPasswordActivity::class.java).setData(Uri.parse("microg-legacy-password:/request/$nonce")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
    }
    fun consume(uri: Uri?, callingPackage: String?): LegacyPasswordRequest = store.locked { session ->
        if (uri?.scheme != "microg-legacy-password" || uri.authority != null || uri.query != null || uri.fragment != null ||
            uri.pathSegments.size != 2 || uri.pathSegments[0] != "request") throw SecurityException()
        val state = session.load()
        val encoded = state.chromeLocalData.remove(uri.pathSegments[1]) ?: throw SecurityException()
        session.save(state)
        try {
            val value = Proto.decode(encoded)
            if (!fresh(value, SystemClock.elapsedRealtime(), boot())) throw SecurityException()
            val request = LegacyPasswordRequest(value.string(1), value.integer(2)?.toInt() ?: throw ProtocolException(), value.one(3),
                value.integer(4)?.toInt() ?: throw ProtocolException(), value.optionalString(7), value.optionalString(8), value.optionalString(9))
            if (callingPackage != request.packageName) throw SecurityException()
            recheck(request)
            request
        } finally { encoded.fill(0) }
    }
    fun recheck(request: LegacyPasswordRequest) {
        if (!MessageDigest.isEqual(certificate(request.packageName, request.uid), request.certificate)) throw SecurityException()
    }
    private fun certificate(packageName: String, uid: Int): ByteArray {
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (info.applicationInfo?.uid != uid) throw SecurityException()
        val signer = info.signingInfo?.apkContentsSigners?.singleOrNull() ?: throw SecurityException()
        return MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
    }
    private fun boot() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toLong()
    private fun fresh(value: Proto, now: Long, boot: Long): Boolean {
        val created = value.integer(5) ?: return false
        return boot >= 0 && value.integer(6) == boot && created <= now && now - created <= 300_000L
    }
    companion object { const val GET = 0; const val SAVE = 1; const val DELETE = 2 }
}
