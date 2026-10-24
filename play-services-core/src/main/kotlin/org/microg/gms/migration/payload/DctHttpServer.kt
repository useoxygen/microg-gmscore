/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.payload

import okhttp3.RequestBody
import okio.buffer
import okio.sink
import java.io.*
import java.net.*
import java.util.concurrent.*
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

internal data class DctHttpRequest(val method: String, val path: String, val headers: Map<String, String>, val bytes: ByteArray?)
internal data class DctHttpReply(val type: String, val body: RequestBody, val headers: Map<String, String> = emptyMap(), val status: Int = 200, val dispose: () -> Unit = {})

/** Bounded HTTP/1.1 over the session's PAKE socket. No certificate-TLS or cleartext production factory. */
internal class DctHttpServer private constructor(private val socket: ServerSocket,
    private val allowed: (Socket) -> Boolean, private val handler: (DctHttpRequest) -> DctHttpReply,
    private val authenticated: () -> Unit, private val failure: (Int) -> Unit) : Closeable {
    val port get() = socket.localPort
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(12))
    private val acceptor = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    fun start() { acceptor.execute {
        while (!closed) {
            val peer = try { socket.accept() } catch (e: IOException) { break }
            if (!allowed(peer)) { peer.close(); continue }
            connections.add(peer)
            try { workers.execute {
                try {
                    peer.soTimeout = 35_000
                    (peer as? SSLSocket)?.apply { useClientMode = false; enabledProtocols = arrayOf("TLSv1.3"); startHandshake() }
                    val input = peer.getInputStream().buffered()
                    val request = read(input)
                    // A parsed HTTP request after startHandshake proves the upgraded PAKE channel.
                    authenticated()
                    val reply = handler(request)
                    try { write(peer.getOutputStream(), reply) } finally { reply.dispose() }
                } catch (e: Exception) {
                    if (!closed && e is javax.net.ssl.SSLHandshakeException) failure(8016)
                    if (!closed && e is org.microg.gms.migration.controller.DctFailure && e.status in 8016..8019) failure(e.status)
                } finally { connections.remove(peer); runCatching { peer.close() } }
            } } catch (e: RejectedExecutionException) { connections.remove(peer); peer.close() }
        }
    } }
    override fun close() { closed = true; socket.close(); connections.forEach { runCatching { it.close() } }; workers.shutdownNow(); acceptor.shutdownNow() }
    companion object {
        fun pake(tls: org.microg.gms.migration.security.PlatformPakeTls, allowed: (Socket) -> Boolean,
                 handler: (DctHttpRequest) -> DctHttpReply, authenticated: () -> Unit, failure: (Int) -> Unit): DctHttpServer {
            val socket = tls.server.serverSocketFactory.createServerSocket() as SSLServerSocket
            try { socket.bind(InetSocketAddress(InetAddress.getByName("::"), 0), 8)
                return DctHttpServer(socket, allowed, handler, authenticated, failure)
            } catch (e: Exception) { socket.close(); throw e }
        }
        // Internal injection exercises the identical parser/writer using loopback HTTP on a host JVM.
        fun loopback(socket: ServerSocket, handler: (DctHttpRequest) -> DctHttpReply) =
            DctHttpServer(socket.also { require(it.inetAddress.isLoopbackAddress) }, { it.inetAddress.isLoopbackAddress }, handler, {}, {})
        private fun line(input: InputStream, limit: Int = 8192): String {
            val bytes = ByteArrayOutputStream()
            while (true) { val b = input.read(); if (b < 0) throw EOFException()
                if (b == 10) { val data = bytes.toByteArray(); require(data.isNotEmpty() && data.last() == 13.toByte()); return String(data, 0, data.size - 1, Charsets.US_ASCII) }
                require(bytes.size() < limit); bytes.write(b)
            }
        }
        fun read(input: InputStream): DctHttpRequest {
            val first = line(input).split(' '); require(first.size == 3 && first[2] == "HTTP/1.1" && first[0] in listOf("GET", "POST"))
            val path = first[1]; val uri = URI(path)
            require(path.startsWith('/') && !path.startsWith("//") && '\\' !in path && uri.scheme == null && uri.authority == null && uri.fragment == null)
            val headers = linkedMapOf<String, String>(); var total = 0
            while (true) { val value = line(input); total += value.length + 2; require(total <= 32768)
                if (value.isEmpty()) break
                val colon = value.indexOf(':'); require(colon > 0)
                val name = value.substring(0, colon).lowercase(); require(name.matches(Regex("[a-z0-9!#$%&'*+.^_`|~-]+")) && name !in headers)
                headers[name] = value.substring(colon + 1).trim()
            }
            require(headers["x-dct-version"] == "v1")
            val length = headers["content-length"]?.toLongOrNull()
            require(headers["content-length"] == null || length != null)
            require(length == null || length in 0..(16L * 1024 * 1024))
            require(headers["transfer-encoding"] == null || headers["transfer-encoding"] == "chunked" && length == null)
            val bytes = if (first[0] == "GET") { require(length == null || length == 0L); require(headers["transfer-encoding"] == null); null }
            else if (headers["transfer-encoding"] == "chunked") {
                val out = ByteArrayOutputStream()
                while (true) { val size = line(input, 128).substringBefore(';').toInt(16); require(size >= 0 && size <= 16 * 1024 * 1024 - out.size())
                    if (size == 0) { require(line(input).isEmpty()); break }
                    val part = ByteArray(size); DataInputStream(input).readFully(part); out.write(part); require(line(input, 2).isEmpty())
                }; out.toByteArray()
            } else ByteArray(requireNotNull(length).toInt()).also { DataInputStream(input).readFully(it) }
            return DctHttpRequest(first[0], path, headers, bytes)
        }
        private fun write(output: OutputStream, reply: DctHttpReply) {
            val length = reply.body.contentLength()
            val headers = linkedMapOf("Content-Type" to reply.type, "x-dct-version" to "v1", "Connection" to "close")
            reply.headers.forEach { (key, value) -> require(key.matches(Regex("[A-Za-z0-9-]+")) && value.none { it.code < 32 || it.code == 127 }); require(key.lowercase() !in setOf("content-length", "transfer-encoding", "connection", "content-type")); headers[key] = value }
            require(reply.type.none { it.code < 32 || it.code == 127 })
            if (length >= 0) headers["Content-Length"] = length.toString() else headers["Transfer-Encoding"] = "chunked"
            output.write(buildString { append("HTTP/1.1 ").append(reply.status).append(" DCT\r\n"); headers.forEach { (k,v) -> append(k).append(": ").append(v).append("\r\n") }; append("\r\n") }.toByteArray(Charsets.US_ASCII))
            val stream = if (length >= 0) output else object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(bytes: ByteArray, offset: Int, count: Int) { if (count == 0) return; output.write("${count.toString(16)}\r\n".toByteArray()); output.write(bytes, offset, count); output.write("\r\n".toByteArray()) }
                override fun flush() = output.flush()
            }
            val sink = stream.sink().buffer(); reply.body.writeTo(sink); sink.flush()
            if (length < 0) output.write("0\r\n\r\n".toByteArray())
            output.flush()
        }
    }
}
