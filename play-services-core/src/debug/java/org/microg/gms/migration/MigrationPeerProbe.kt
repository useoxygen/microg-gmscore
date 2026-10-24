/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.microg.gms.migration.controller.*
import org.microg.gms.migration.payload.DctHttpPeer
import org.microg.gms.migration.payload.DctHttpRequests
import org.microg.gms.migration.security.DctIntegrity
import org.microg.gms.migration.security.PlatformPakeTls
import java.io.Closeable
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager

/** Real Android PAKE/provider ↔ Mac BoringSSL over adb reverse; no radio or iPhone acceptance claim. */
internal class MigrationPeerProbe(private val context: Context, private val port: Int, private val done: () -> Unit) : Closeable {
    @Volatile private var controls: DctControlChannel? = null
    private val thread = Thread {
        val result = runCatching {
            require(port in 1024..65535)
            val tls = PlatformPakeTls.create("abc234", "def567") // public synthetic credentials only
            val certificateShim = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>, type: String) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>, type: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val client = OkHttpClient.Builder().sslSocketFactory(tls.client.socketFactory, certificateShim)
                .hostnameVerifier { _, _ -> true }.proxy(java.net.Proxy.NO_PROXY).retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false).readTimeout(35, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
            val peer = object : DctHttpPeer, Closeable {
                private val base = HttpUrl.Builder().scheme("https").host("127.0.0.1").port(port).build()
                override fun request(path: String, contentType: String?, bytes: ByteArray?, headers: List<Pair<String,String>>): Call =
                    client.newCall(DctHttpRequests.build(base, path, bytes?.toRequestBody(contentType?.toMediaType()), headers))
                override fun control(offlineFrame: ByteArray) = request("/controls", DctControlChannel.CONTROL_TYPE, offlineFrame)
                override fun capabilities(message: ByteArray) = request("/capabilities", DctControlChannel.CONTROL_TYPE, message)
                override fun notifications(ack: Boolean) = request(if (ack) "/notifications?ack" else "/notifications", null, null)
                override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
            }
            DctControlChannel(peer, "Synthetic target", "Cyclon", "public-synthetic-salt", { _, _ -> },
                { status -> Log.i("GmsDct", "Mac fixture channel closed status=$status") }).use { channel ->
                controls = channel; channel.start()
                DctHandshake(channel, "Cyclon", "Synthetic target", stage = { Log.i("GmsDct", it) }, token = DctIntegrity(context)::token).run("public-synthetic-salt")
            }
            "PASS Android PAKE HTTP and Integrity exchange with synthetic Mac Apple fixture; no iPhone verdict acceptance"
        }.getOrElse { "BLOCKED Mac fixture status=${(it as? DctFailure)?.status ?: 8012}; cause=${it.javaClass.simpleName}" }
        Log.i("GmsSwitchLab", result); done()
    }
    fun start() = thread.start()
    override fun close() { controls?.close(); thread.interrupt() }
}
