/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.credentials.provider.CallingAppInfo
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Exact facets only. Cross-site/app affiliations require independently verified Digital Asset Links. */
@RequiresApi(34)
class PasswordCaller private constructor(val realms: Set<String>, val identity: ByteArray) {
    companion object {
        private const val GOOGLE_RELEASE_CERT = "F0:FD:6C:5B:41:0F:25:CB:25:C3:B5:33:46:C8:97:2F:AE:30:F8:EE:74:11:DF:91:04:80:AD:6B:2D:60:DB:83"
        private val BROWSER_PACKAGES = listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary")
        private val BROWSER_ALLOWLIST = "{\"apps\":[" + BROWSER_PACKAGES.joinToString(",") { pkg ->
            "{\"type\":\"android\",\"info\":{\"package_name\":\"$pkg\",\"signatures\":[{\"build\":\"release\",\"cert_fingerprint_sha256\":\"$GOOGLE_RELEASE_CERT\"}]}}"
        } + "]}"
        fun from(info: CallingAppInfo): PasswordCaller {
            val origin = info.getOrigin(BROWSER_ALLOWLIST)
            val signers = info.signingInfo.apkContentsSigners
            if (signers.isNullOrEmpty() || signers.size != 1 || info.packageName.isBlank()) throw SecurityException("Unsupported credential caller")
            val digest = MessageDigest.getInstance("SHA-256").digest(signers.single().toByteArray())
            val realms = if (origin != null) setOf(PasswordData.webRealm(origin)) else setOf(
                "android://${Base64.getUrlEncoder().withoutPadding().encodeToString(digest)}@${info.packageName}/")
            val identity = MessageDigest.getInstance("SHA-256").digest(
                Proto.field(1, info.packageName) + Proto.field(2, digest) + Proto.field(3, origin.orEmpty()))
            return PasswordCaller(realms, identity)
        }
    }
}

class PasswordRequestTicket(val account: Account?, val tag: String?, val realm: String, val caller: ByteArray, val payload: ByteArray?, val mode: Int) {
    override fun toString(): String = "PasswordRequestTicket[redacted]"
}

/** Mutable system fill-in extras cannot change the encrypted, one-use account/facet selection. */
class PasswordRequestTickets(private val context: Context) {
    private val store = AndroidVaultStore(context, "credential-provider-tickets-v1")
    fun issue(account: Account?, tag: String?, realm: String, caller: ByteArray, create: Boolean): PendingIntent {
        return issue(account, tag, realm, caller, if (create) 1 else 0, null, PasswordProxyActivity::class.java)
    }
    fun issueAutofill(account: Account?, tag: String?, realm: String, caller: ByteArray, save: Boolean, payload: ByteArray): PendingIntent =
        issue(account, tag, realm, caller, if (save) 3 else 2, payload, PasswordAutofillActivity::class.java)
    private fun issue(account: Account?, tag: String?, realm: String, caller: ByteArray, mode: Int, payload: ByteArray?, activity: Class<*>): PendingIntent {
        val nonce = UUID.randomUUID().toString()
        val now = SystemClock.elapsedRealtime()
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toLong()
        val data = (account?.let { Proto.field(1, it.name) } ?: byteArrayOf()) + (tag?.let { Proto.field(2, it) } ?: byteArrayOf()) +
            Proto.field(3, realm) + Proto.field(4, caller) + Proto.integer(5, now) + Proto.integer(6, boot) +
            Proto.integer(7, mode.toLong()) + (payload?.let { Proto.field(8, it) } ?: byteArrayOf())
        store.locked { session ->
            val state = session.load()
            state.chromeLocalData.entries.removeAll { (_, value) -> !fresh(Proto.decode(value), now, boot) }
            if (state.chromeLocalData.size >= 256) throw ProtocolException()
            state.chromeLocalData[nonce] = data
            session.save(state)
        }
        val intent = Intent(context, activity).setData(Uri.parse("microg-password:/request/$nonce"))
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT)
    }
    fun consume(uri: Uri?, caller: ByteArray, create: Boolean): PasswordRequestTicket = consume(uri, caller, if (create) 1 else 0)
    fun inspect(uri: Uri?): PasswordRequestTicket = store.locked { session ->
        val nonce = nonce(uri)
        val value = Proto.decode(session.load().chromeLocalData[nonce] ?: throw SecurityException())
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toLong()
        if (!fresh(value, SystemClock.elapsedRealtime(), boot)) throw SecurityException()
        decode(value)
    }
    fun consume(uri: Uri?, caller: ByteArray, mode: Int): PasswordRequestTicket = store.locked { session ->
        val nonce = nonce(uri)
        val state = session.load()
        val encoded = state.chromeLocalData.remove(nonce) ?: throw SecurityException("Expired credential selection")
        session.save(state)
        val value = Proto.decode(encoded)
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toLong()
        if (!fresh(value, SystemClock.elapsedRealtime(), boot) || !MessageDigest.isEqual(value.one(4), caller) ||
            value.integer(7) != mode.toLong()) throw SecurityException("Credential selection changed")
        decode(value)
    }
    private fun nonce(uri: Uri?): String {
        if (uri?.scheme != "microg-password" || uri.pathSegments?.size != 2 || uri.pathSegments[0] != "request") throw SecurityException()
        return uri.pathSegments[1]
    }
    private fun decode(value: Proto) = PasswordRequestTicket(value.optionalString(1)?.let { Account(it, "com.google") },
        value.optionalString(2), value.string(3), value.one(4), value.optional(8), value.integer(7)?.toInt() ?: throw ProtocolException())
    private fun fresh(value: Proto, now: Long, boot: Long): Boolean {
        val created = value.integer(5) ?: return false
        return boot >= 0 && value.integer(6) == boot && created <= now && now - created <= 300_000L
    }
}
