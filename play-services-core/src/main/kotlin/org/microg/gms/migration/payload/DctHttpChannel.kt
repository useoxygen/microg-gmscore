/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.payload

import android.net.Network
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.microg.gms.migration.security.PlatformPakeTls
import java.io.Closeable
import java.net.Inet6Address
import java.net.Proxy
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager

/** HTTP framing belongs to OkHttp. Authentication belongs exclusively to the platform PAKE TLS socket factory. */
internal class DctHttpChannel(network: Network, address: Inet6Address, port: Int, tls: PlatformPakeTls) : Closeable {
    private val base = HttpUrl.Builder().scheme("https").host(requireNotNull(address.hostAddress).substringBefore('%'))
        .port(port).build()
    // PAKE TLS has no certificate identity. This shim is never used to construct a certificate-TLS context.
    // The constructor accepts only PlatformPakeTls (private factory, PAKE credentials, no ordinary-TLS fallback).
    private val certificateShim = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).socketFactory(network.socketFactory)
        .dns { listOf(address) } // retain the resolved IPv6 scope; never consult default-network DNS
        .sslSocketFactory(tls.client.socketFactory, certificateShim).hostnameVerifier { _, _ -> true }
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()

    fun control(offlineFrame: ByteArray): Call = request("/controls", "application/vnd.dct.control+proto", offlineFrame)
    fun capabilities(message: ByteArray): Call = request("/capabilities", "application/vnd.dct.control+proto", message)
    fun notifications(ack: Boolean = false): Call = request(if (ack) "/notifications?ack" else "/notifications", null, null)
    fun request(path: String, contentType: String?, bytes: ByteArray?, headers: Map<String, String> = emptyMap()): Call =
        request(path, bytes?.toRequestBody(contentType?.toMediaType()), headers)
    fun request(path: String, body: RequestBody?, headers: Map<String, String> = emptyMap()): Call =
        client.newCall(DctHttpRequests.build(base, path, body, headers))

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

internal object DctHttpRequests {
    fun build(base: HttpUrl, path: String, body: RequestBody?, headers: Map<String, String>): Request {
        require(path.startsWith('/') && !path.startsWith("//") && '\\' !in path && path.none { it.code < 32 })
        val target = requireNotNull(base.resolve(path))
        require(target.scheme == "https" && target.host == base.host && target.port == base.port && target.fragment == null)
        val owned = setOf("host", "content-length", "transfer-encoding", "connection", "x-dct-version")
        return Request.Builder().url(target).header("x-dct-version", "v1").apply {
            headers.forEach { (name, value) ->
                require(name.lowercase() !in owned) { "Transport-owned DCT header" }
                addHeader(name, value)
            }
            if (body == null) get() else post(body)
        }.build()
    }
}
