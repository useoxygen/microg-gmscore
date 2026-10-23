/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.BuildConfig
import com.squareup.wire.GrpcClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.microg.gms.auth.folsom.requestOauthToken
import org.microg.gms.auth.folsom.vault.VaultServiceClient
import org.microg.gms.auth.folsom.vault.VaultListRequest
import org.microg.gms.auth.folsom.vault.VaultUpdateRequest
import org.microg.gms.auth.folsom.vault.VaultOpenRequest
import org.microg.gms.gcm.AuthHeaderInterceptor
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real configured admission and this installation's certificate. Never borrows a Google app's API key or signer. */
internal class RecoverableVaultTransport(context: Context, private val account: Account, private val accountId: String,
    private val authorize: () -> Unit) {
    private val context = context.applicationContext
    private val stopped = AtomicBoolean(false)
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    private fun verify() {
        if (stopped.get() || Thread.currentThread().isInterrupted) throw VaultLockedException()
        authorize()
        val accounts = AccountManager.get(context)
        if (account.type != "com.google" || !accounts.getAccountsByType("com.google").contains(account) ||
            accountId.isBlank() || accounts.getUserData(account, "GoogleUserId") != accountId) throw VaultLockedException()
    }
    fun cancel() { stopped.set(true); http.dispatcher.cancelAll() }
    private fun service(): VaultServiceClient {
        verify()
        if (BuildConfig.RECOVERABLE_VAULT_API_KEY.isBlank()) throw VaultLockedException()
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val certificate = info.signingInfo?.apkContentsSigners?.singleOrNull()?.toByteArray() ?: throw VaultLockedException()
        val digest = MessageDigest.getInstance("SHA-1").digest(certificate).joinToString("") { "%02X".format(it.toInt() and 255) }
        val token = context.requestOauthToken(account.name)
        verify()
        val client = http.newBuilder().addInterceptor(AuthHeaderInterceptor(token)).addInterceptor { chain ->
            verify()
            chain.proceed(chain.request().newBuilder().header("X-Goog-Api-Key", BuildConfig.RECOVERABLE_VAULT_API_KEY)
                .header("X-Android-Package", context.packageName).header("X-Android-Cert", digest).build())
        }.build()
        return GrpcClient.Builder().client(client).baseUrl("https://cryptauthvault.googleapis.com/")
            .minMessageToCompress(Long.MAX_VALUE).build().create(VaultServiceClient::class)
    }
    fun list(id: String, source: Int? = null): List<ByteArray> {
        verify()
        val result = ArrayList<ByteArray>(); val seen = HashSet<String>(); var token = ""; var size = 0
        do {
            if (!seen.add(token) || seen.size > 100) throw ProtocolException()
            val request = VaultListRequest.ADAPTER.decode(RecoverableVaultWire.list(id, BuildConfig.VERSION_CODE, token, source))
            val response = service().ListVaults().executeBlocking(request)
            verify()
            response.vaults.forEach { vault ->
                val raw = vault.encode(); size += raw.size
                if (result.size >= 4096 || size > Proto.MAX_BYTES) throw ProtocolException()
                result.add(raw)
            }
            token = response.nextPageToken.orEmpty()
            if (token.length > 4096) throw ProtocolException()
        } while (token.isNotEmpty())
        if (result.map { Proto.decode(it).string(1) }.toSet().size != result.size) throw ProtocolException()
        verify(); return result
    }
    fun update(request: ByteArray): ByteArray {
        verify()
        val body = VaultUpdateRequest.ADAPTER.decode(request)
        if (!body.encode().contentEquals(request)) throw ProtocolException()
        // After this call is launched, every exceptional outcome stays fenced until read-only reconciliation.
        return service().UpdateVault().executeBlocking(body).encode()
    }
    /** A recovery claim can consume a server-side guess. Caller must durably fence this exact request before dispatch. */
    fun open(request: ByteArray): ByteArray {
        verify()
        val body = VaultOpenRequest.ADAPTER.decode(request)
        if (!body.encode().contentEquals(request)) throw ProtocolException()
        val call = service().OpenVault()
        return RecoveryRestoreNetwork.execute { call.executeBlocking(body) }.encode()
    }
    /** Only RecoveryController's built-in root validation can turn these public bytes into trusted state. */
    fun certificates(): Pair<ByteArray, ByteArray> {
        fun fetch(suffix: String): ByteArray {
            verify()
            val request = Request.Builder().url("https://www.gstatic.com/cryptauthvault/v0/10019.cert$suffix.xml").build()
            return http.newCall(request).execute().use { response ->
                if (response.code != 200 || response.body == null) throw ProtocolException()
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                response.body!!.byteStream().use { stream ->
                    while (true) { val count = stream.read(buffer); if (count < 0) break
                        if (count > 256 * 1024 - out.size()) throw ProtocolException(); out.write(buffer, 0, count) }
                }
                verify(); out.toByteArray().also { if (it.isEmpty()) throw ProtocolException() }
            }
        }
        val certificate = fetch("")
        return try { certificate to fetch(".sig") } catch (failure: Throwable) { certificate.fill(0); throw failure }
    }
}
