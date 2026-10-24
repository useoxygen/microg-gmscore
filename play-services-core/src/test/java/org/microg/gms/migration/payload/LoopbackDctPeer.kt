/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.payload

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real localhost HTTP for framing/lifecycle tests; deliberately makes no host PAKE/TLS claim. */
internal class LoopbackDctPeer(handler: (HttpExchange) -> Unit) : DctHttpPeer, Closeable {
    private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
    private val sockets = java.util.concurrent.CopyOnWriteArrayList<Socket>()
    private val executor = Executors.newCachedThreadPool()
    private val client = OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
    init { executor.execute {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (e: java.io.IOException) { break }
            sockets.add(socket)
            executor.execute { try { socket.soTimeout = 5000; handler(HttpExchange(socket)) } catch (e: Exception) { socket.close() } }
        }
    } }
    override fun control(offlineFrame: ByteArray) = request("/controls", "application/vnd.dct.control+proto", offlineFrame)
    override fun capabilities(message: ByteArray) = request("/capabilities", "application/vnd.dct.control+proto", message)
    override fun notifications(ack: Boolean) = request(if (ack) "/notifications?ack" else "/notifications", null, null)
    override fun request(path: String, contentType: String?, bytes: ByteArray?, headers: List<Pair<String, String>>): Call =
        client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}$path").header("x-dct-version", "v1")
            .apply { headers.forEach { (k, v) -> addHeader(k, v) }; if (bytes != null) post(bytes.toRequestBody(contentType?.toMediaType())) else get() }.build())
    override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); server.close(); sockets.forEach { it.close() }; executor.shutdownNow() }
    companion object {
        fun respond(exchange: HttpExchange, type: String, bytes: ByteArray) {
            exchange.responseHeaders.set("Content-Type", type)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }; exchange.close()
        }
    }
}

internal class HttpExchange(private val socket: Socket) {
    class Headers : LinkedHashMap<String, String>() {
        fun getFirst(name: String) = entries.firstOrNull { it.key.equals(name, true) }?.value
        fun set(name: String, value: String) { put(name, value) }
    }
    val requestHeaders = Headers()
    val responseHeaders = Headers()
    val requestMethod: String
    val requestURI: URI
    val requestBody: InputStream
    val responseBody get() = socket.getOutputStream()
    init {
        val input = socket.getInputStream()
        fun line(): String {
            val data = java.io.ByteArrayOutputStream()
            while (true) { val b = input.read(); if (b < 0) throw java.io.EOFException(); if (b == 10) break; require(data.size() < 8192); if (b != 13) data.write(b) }
            return data.toString("UTF-8")
        }
        val first = line().split(' '); requestMethod = first[0]; requestURI = URI(first[1])
        while (true) { val h = line(); if (h.isEmpty()) break; val split = h.indexOf(':'); requestHeaders[h.substring(0, split)] = h.substring(split + 1).trim() }
        val length = requestHeaders.getFirst("Content-Length")?.toInt() ?: 0
        require(length in 0..16*1024*1024)
        val bytes = ByteArray(length); java.io.DataInputStream(input).readFully(bytes); requestBody = ByteArrayInputStream(bytes)
    }
    fun sendResponseHeaders(status: Int, length: Long) {
        val headers = buildString {
            append("HTTP/1.1 $status Response\r\nConnection: close\r\nContent-Length: ${length.coerceAtLeast(0)}\r\n")
            responseHeaders.forEach { (name, value) -> append("$name: $value\r\n") }; append("\r\n")
        }
        responseBody.write(headers.toByteArray()); responseBody.flush()
    }
    fun close() { socket.close() }
}
