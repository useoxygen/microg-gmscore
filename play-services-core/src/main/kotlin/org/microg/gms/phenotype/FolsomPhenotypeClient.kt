/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.phenotype

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.telephony.TelephonyManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.microg.gms.auth.AuthManager
import org.microg.gms.checkin.CheckinPreferences
import org.microg.gms.checkin.LastCheckinInfo
import org.microg.gms.passwords.Proto
import org.microg.gms.passwords.ProtocolException
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream

/** Explicit native fetch only. No startup scheduling, account enumeration, secret logging or invented enrollment. */
internal class FolsomPhenotypeClient(context: Context, private val account: Account, private val accountId: String,
                                    private val authorize: () -> Unit) {
    private val context = context.applicationContext
    private val cancelled = AtomicBoolean()
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    class Receipt(val configurationReturned: Boolean, val committed: Boolean, val assignedFlagCount: Int) {
        override fun toString() = "FolsomPhenotypeReceipt[redacted]"
    }
    fun cancel() { cancelled.set(true); http.dispatcher.cancelAll() }
    private fun verify() {
        check(!cancelled.get() && !Thread.currentThread().isInterrupted)
        authorize()
        val manager = AccountManager.get(context)
        check(account.type == "com.google" && accountId.isNotBlank() && manager.getAccountsByType("com.google").contains(account) &&
            manager.getUserData(account, "GoogleUserId") == accountId) { "Approved account changed" }
    }
    fun refresh(): Receipt {
        if (Build.VERSION.SDK_INT < 28) throw ProtocolException()
        verify()
        check(CheckinPreferences.isEnabled(context)) { "Device configuration requests are disabled" }
        val captured = installation(context)
        fun authorized() {
            verify()
            check(CheckinPreferences.isEnabled(context) && MessageDigest.isEqual(captured.registration.identity,
                installation(context).registration.identity)) { "Device configuration identity changed" }
        }
        val store = FolsomPhenotypeStore(context)
        val launched = store.locked(::authorized) { session ->
            val raw = session.load()
            val before = try { raw?.let(FolsomPhenotypeProtocol::decode)?.takeIf {
                MessageDigest.isEqual(it.identity, captured.registration.identity)
            } ?: FolsomPhenotypeProtocol.State(captured.registration.identity) } finally { raw?.fill(0) }
            FolsomPhenotypeProtocol.begin(before).also { next -> next.encode().let { try { session.save(it) } finally { it.fill(0) } } }
        }
        val request = FolsomPhenotypeProtocol.request(captured.registration, launched)
        val auth = AuthManager(context, account.name, context.packageName, SCOPE).apply {
            // This is the actual installed signer, including Cyclon installations with a dedicated signer.
            // appIsGms() rewrites it to Google's certificate, so this client deliberately uses the ordinary app path.
            setPackageSignature(captured.sha1)
        }
        authorized(); auth.setPermitted(true); authorized()
        fun bearer(): String {
            authorized()
            val value = auth.requestAuth(false).auth?.takeIf { it.isNotBlank() } ?: throw ProtocolException()
            authorized(); return value
        }
        val compressed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(request) } }.toByteArray()
        request.fill(0)
        try {
            var token = bearer()
            repeat(2) { retry ->
                authorized()
                val call = Request.Builder().url(URL).header("Authorization", "Bearer $token").header("Content-Encoding", "gzip")
                    .post(compressed.toRequestBody("application/x-protobuf".toMediaType())).build()
                val result = http.newCall(call).execute().use { response ->
                    authorized()
                    if (response.code == 401 && retry == 0) null else {
                        check(response.code in 200..299 && response.body != null) { "Device configuration request refused" }
                        response.body!!.byteStream().use { input ->
                            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (true) { val count = input.read(buffer); if (count < 0) break
                                if (count > FolsomPhenotypeProtocol.MAX_BYTES - out.size()) throw ProtocolException()
                                out.write(buffer, 0, count) }
                            out.toByteArray()
                        }
                    }
                }
                authorized()
                if (result != null) {
                    try {
                        val returned = Proto.decode(result).bytes(1).any { app ->
                            val id = Proto.decode(Proto.decode(app).one(1))
                            Proto.decode(id.one(3)).integer(1) == -1L
                        }
                        val next = store.locked(::authorized) { session ->
                            val raw = session.load() ?: throw ProtocolException()
                            try {
                                FolsomPhenotypeProtocol.response(captured.registration, launched, FolsomPhenotypeProtocol.decode(raw), result)
                                    .also { state -> state.encode().let { try { session.save(it) } finally { it.fill(0) } } }
                            } finally { raw.fill(0) }
                        }
                        authorized()
                        context.contentResolver.notifyChange(Uri.parse("content://com.google.android.gms.phenotype/${FolsomPromptFlagPolicy.NAMESPACE}"), null)
                        return Receipt(returned, next.assigned, FolsomPhenotypeProtocol.values(next).size)
                    } finally { result.fill(0) }
                }
                authorized(); auth.invalidateAuthToken(); authorized(); token = bearer()
            }
            throw ProtocolException()
        } finally { compressed.fill(0) }
    }
    override fun toString() = "FolsomPhenotypeClient[redacted]"
    private class Installation(val registration: FolsomPhenotypeProtocol.Registration, val sha1: String)
    companion object {
        private const val SCOPE = "oauth2:https://www.googleapis.com/auth/experimentsandconfigs"
        private const val URL = "https://www.googleapis.com/experimentsandconfigs/v1/getExperimentsAndConfigs?r=19&c=1"
        /** Called by the provider too: a captured pre-update/pre-check-in assignment cannot re-enable a flag. */
        private fun installation(context: Context): Installation {
            if (Build.VERSION.SDK_INT < 28) throw ProtocolException()
            check(context.packageName == FolsomPhenotypeProtocol.PACKAGE)
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signer = info.signingInfo?.apkContentsSigners?.singleOrNull()?.toByteArray() ?: throw ProtocolException()
            check(info.applicationInfo?.uid == Process.myUid())
            val version = info.longVersionCode
            val androidId = LastCheckinInfo.read(context).androidId
            check(androidId != 0L) { "Device check-in identity unavailable" }
            fun string(number: Int, value: String): ByteArray {
                check(value.toByteArray().size <= 4096); return Proto.field(number, value)
            }
            val locale = Locale.getDefault()
            var device = Proto.integer(1, androidId) + Proto.integer(3, Build.VERSION.SDK_INT.toLong()) +
                string(4, Build.MODEL) + string(5, Build.PRODUCT) + string(6, Build.ID) + string(7, "0") +
                string(8, Build.HARDWARE) + string(9, Build.DEVICE) + string(11, locale.language) + string(12, locale.country) +
                string(13, Build.MANUFACTURER) + string(14, Build.BRAND) + string(15, Build.BOARD) + string(17, Build.FINGERPRINT) +
                Proto.integer(19, version) + string(26, Build.TYPE)
            runCatching { context.getSystemService(TelephonyManager::class.java)?.let { phone ->
                phone.simOperator?.let { device += string(10, it) }
                device += Proto.integer(33, phone.simCarrierId.toLong())
            } }
            runCatching { Build.getRadioVersion()?.let { device += string(16, it) } }
            // The complete map is present on API31, or API30 with the genuine R extension2.
            if (Build.VERSION.SDK_INT >= 31 || (Build.VERSION.SDK_INT >= 30 &&
                    android.os.ext.SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2))
                android.os.ext.SdkExtensions.getAllExtensionVersions().toSortedMap().forEach { (id, value) ->
                    device += Proto.field(34, Proto.integer(1, id.toLong()) + Proto.integer(2, value.toLong()))
                }
            val fingerprint = FolsomPhenotypeProtocol.digest(Proto.field(1, context.packageName) + Proto.integer(2, version) +
                Proto.field(3, FolsomPhenotypeProtocol.digest(signer)) + Proto.field(4, device))
            val sha1 = MessageDigest.getInstance("SHA-1").digest(signer).joinToString("") { "%02x".format(it.toInt() and 255) }
            return Installation(FolsomPhenotypeProtocol.Registration(version, fingerprint, device), sha1)
        }
        internal fun committedValues(context: Context): Map<String, Any> {
            if (Build.VERSION.SDK_INT < 28) return emptyMap()
            return FolsomPhenotypeStore(context).locked {
                val raw = it.load() ?: return@locked emptyMap()
                try {
                    val state = FolsomPhenotypeProtocol.decode(raw)
                    val captured = runCatching { installation(context).registration.identity }.getOrNull() ?: return@locked emptyMap()
                    if (!MessageDigest.isEqual(state.identity, captured) || !MessageDigest.isEqual(captured,
                            installation(context).registration.identity)) emptyMap() else FolsomPhenotypeProtocol.values(state)
                } finally { raw.fill(0) }
            }
        }
    }
}
