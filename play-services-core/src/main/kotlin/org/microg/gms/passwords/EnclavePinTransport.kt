/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.content.Context
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.microg.gms.auth.folsom.requestOauthToken
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One fresh pinned+attested Noise session per transaction. No websocket logging or automatic mutation retries. */
internal class EnclavePinTransport(private val context: Context, private val account: Account, private val authorize: () -> Unit) {
    private val stopped = AtomicBoolean(false)
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    @Volatile private var active: WebSocket? = null
    private class Packet(val bytes: ByteArray? = null)
    fun cancel() { stopped.set(true); active?.cancel(); http.dispatcher.cancelAll() }
    private fun verify() { if (stopped.get() || Thread.currentThread().isInterrupted) throw VaultLockedException(); authorize() }
    fun transact(commands: ByteArray, identity: EnclavePinIdentity?, rapt: String? = null): ByteArray {
        verify()
        // OAuth scope is the exact Chrome145 kPasskeysEnclaveOAuth2Scope; never a substituted cryptauth token.
        val token = context.requestOauthToken(account.name, "oauth2:https://www.googleapis.com/auth/secureidentity.action")
        verify()
        if (rapt != null && (rapt.isBlank() || rapt.length > 16_384 || rapt.any { it == '\r' || it == '\n' })) throw ProtocolException()
        val queue = ArrayBlockingQueue<Packet>(4)
        val request = Request.Builder().url("https://enclave.ua5v.com/enclave")
            .header("Authorization", "Bearer $token").header("Sec-WebSocket-Protocol", "cloudauthenticator")
            .header("Origin", "https://enclave.ua5v.com").apply { if (rapt != null) header("Reauthentication", rapt) }.build()
        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (response.header("Sec-WebSocket-Protocol") != "cloudauthenticator" || !queue.offer(Packet(byteArrayOf()))) webSocket.cancel()
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size > EnclaveCbor.MAX + 256 * 1024 || !queue.offer(Packet(bytes.toByteArray()))) webSocket.cancel()
            }
            override fun onMessage(webSocket: WebSocket, text: String) { webSocket.cancel(); queue.offer(Packet()) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { queue.offer(Packet()) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { queue.offer(Packet()) }
        })
        active = socket
        fun receive(): ByteArray { verify(); val packet = queue.poll(45, TimeUnit.SECONDS) ?: throw VaultLockedException()
            verify(); return packet.bytes ?: throw VaultLockedException() }
        EnclaveNoise().use { noise ->
            try {
                if (receive().isNotEmpty()) throw ProtocolException()
                if (!socket.send(noise.start().toByteString())) throw VaultLockedException()
                val response = receive()
                val handshakeHash = try {
                    if (response.size <= 81) throw ProtocolException()
                    noise.finish(response.copyOfRange(0, 81)).also {
                        EnclaveAttestation.verify(response.copyOfRange(81, response.size), it)
                    }
                } finally { response.fill(0) }
                verify()
                val envelope = linkedMapOf<String, Any>("encoded_requests" to commands)
                if (identity != null) {
                    val descriptor = identity.readStable()
                    val digest = EnclaveNoise.sha(commands); val message = handshakeHash + digest
                    try { envelope["device_id"] = descriptor.deviceId; envelope["auth_level"] = descriptor.level; envelope["sig"] = identity.sign(message) }
                    finally { digest.fill(0); message.fill(0) }
                }
                handshakeHash.fill(0)
                val plain = EnclaveCbor.encode(envelope)
                try { verify(); if (!socket.send(noise.seal(plain).toByteString())) throw VaultLockedException() }
                finally { plain.fill(0); envelope.values.filterIsInstance<ByteArray>().filter { it !== commands }.forEach { it.fill(0) } }
                val encrypted = receive(); val output = try { noise.open(encrypted) } finally { encrypted.fill(0) }
                try {
                    val map = EnclaveCbor.map(EnclaveCbor.decode(output))
                    try {
                        // Transact's outer "ok" is distinct from each command's own "ok" result.
                        val value = map["ok"] as? List<*> ?: throw VaultLockedException()
                        verify(); return EnclaveCbor.encode(value)
                    } finally { EnclaveCbor.destroy(map) }
                } finally { output.fill(0) }
            } finally { socket.cancel(); if (active === socket) active = null; while (true) (queue.poll() ?: break).bytes?.fill(0) }
        }
    }
}
