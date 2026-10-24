/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.*
import com.google.security.cryptauth.lib.securegcm.D2DConnectionContext
import com.google.security.cryptauth.lib.securegcm.Ukey2Handshake
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Medium-independent classic Nearby receiver. Suitable for an RFCOMM/LAN socket;
 * not compatible with DCT's HTTPS framing. The medium owner must enforce a deadline.
 * Application acceptance is required before any authenticated payload is exposed. */
internal class ClassicNearbyChannel(input: InputStream, output: OutputStream) : Closeable {
    private var reader = DataInputStream(input)
    private var writer = DataOutputStream(output)
    private var handshake: Ukey2Handshake? = null
    private var verification: ByteArray? = null
    private var context: D2DConnectionContext? = null
    @Volatile private var closed = false
    private var pending = false
    private var pausedWrites: MutableList<V1Frame>? = null
    var endpointId: String? = null
        private set

    fun requestConnection(localId: String, localInfo: ByteArray, peerId: String): ByteArray {
        check(!closed && !pending && context == null)
        require(localId.length == 4 && peerId.length == 4 && localInfo.size <= 131)
        val request = ConnectionRequestFrame.newBuilder().setEndpointId(localId)
            .setEndpointName(localInfo.toString(Charsets.UTF_8))
            .setEndpointInfo(org.microg.gms.migration.protobuf.ByteString.copyFrom(localInfo))
            .setNonce(java.security.SecureRandom().nextInt())
            .addMediums(ConnectionRequestFrame.Medium.WIFI_LAN)
            .setKeepAliveIntervalMillis(5000).setKeepAliveTimeoutMillis(30000)
        writeFrame(V1Frame.newBuilder().setType(V1Frame.FrameType.CONNECTION_REQUEST)
            .setConnectionRequest(request).build(), false)
        val active = Ukey2Handshake.forInitiator(Ukey2Handshake.HandshakeCipher.P256_SHA512)
        writePacket(active.nextHandshakeMessage)
        active.parseHandshakeMessage(readPacket())
        writePacket(active.nextHandshakeMessage)
        val token = active.getVerificationString(32)
        endpointId = peerId; handshake = active; verification = token; pending = true
        return token.copyOf()
    }

    /** Returns an owned copy of the raw verification token for the app's consent UI. */
    fun receiveConnection(): Pair<ConnectionRequestFrame, ByteArray> {
        check(!closed && !pending && context == null)
        val incoming = parse(readPacket())
        require(incoming.type == V1Frame.FrameType.CONNECTION_REQUEST && incoming.hasConnectionRequest())
        val request = incoming.connectionRequest
        require(request.endpointId.length == 4 && request.endpointId.all { it.code in 33..126 })
        require(request.endpointInfo.size() <= 131 && request.endpointName.length <= 131)
        val active = Ukey2Handshake.forResponder(Ukey2Handshake.HandshakeCipher.P256_SHA512)
        active.parseHandshakeMessage(readPacket())
        writePacket(active.nextHandshakeMessage)
        active.parseHandshakeMessage(readPacket())
        val token = active.getVerificationString(32)
        endpointId = request.endpointId
        handshake = active
        verification = token
        pending = true
        return request to token.copyOf()
    }

    /** Call only after the app has authenticated the peer (UI or retained-secret proof).
     * Peer acceptance and our acceptance are both cleartext, per Nearby's classic stack. */
    fun accept(verifiedToken: ByteArray) {
        check(!closed && pending && context == null)
        require(MessageDigest.isEqual(verification, verifiedToken)) { "Wrong peer verification token" }
        writeFrame(V1Frame.newBuilder().setType(V1Frame.FrameType.CONNECTION_RESPONSE)
            .setConnectionResponse(ConnectionResponseFrame.newBuilder().setStatus(0)
                .setResponse(ConnectionResponseFrame.ResponseStatus.ACCEPT)).build(), encrypted = false)
        var response: V1Frame
        do {
            response = parse(readPacket())
            require(response.type == V1Frame.FrameType.CONNECTION_RESPONSE || response.type == V1Frame.FrameType.KEEP_ALIVE)
        } while (response.type == V1Frame.FrameType.KEEP_ALIVE)
        require(response.hasConnectionResponse())
        val decision = response.connectionResponse
        if (if (decision.hasResponse()) decision.response != ConnectionResponseFrame.ResponseStatus.ACCEPT else decision.status != 0) {
            close(); throw IOException("Nearby peer rejected connection")
        }
        val active = requireNotNull(handshake)
        active.verifyHandshake()
        context = active.toConnectionContext()
        handshake = null
        verification?.fill(0); verification = null
        pending = false
    }

    fun reject() {
        check(pending && !closed)
        try {
            writeFrame(V1Frame.newBuilder().setType(V1Frame.FrameType.CONNECTION_RESPONSE)
                .setConnectionResponse(ConnectionResponseFrame.newBuilder().setStatus(8004)
                    .setResponse(ConnectionResponseFrame.ResponseStatus.REJECT)).build(), encrypted = false)
        } finally { close() }
    }

    val isConnected: Boolean get() = context != null && !closed

    fun receive(): V1Frame {
        check(context != null && !closed)
        return parse(requireNotNull(context).decodeMessageFromPeer(readPacket()))
    }

    @Synchronized fun send(frame: V1Frame) {
        check(context != null && !closed)
        val paused = pausedWrites
        if(paused != null && frame.type != V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION) {
            require(paused.size<128); paused.add(frame); return
        }
        writeFrame(frame, encrypted = true)
    }

    /** An unsupported bandwidth path fails explicitly while the existing medium stays live. */
    fun rejectUpgrade(upgrade: BandwidthUpgradeNegotiationFrame) {
        require(upgrade.eventType == BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE)
        send(V1Frame.newBuilder().setType(V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION)
            .setBandwidthUpgradeNegotiation(BandwidthUpgradeNegotiationFrame.newBuilder()
                .setEventType(BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_FAILURE)
                .setUpgradePathInfo(upgrade.upgradePathInfo)).build())
    }

    /** Responder side of public Nearby BWU. Called by the sole reader. All payload
     * writes pause while the prior encrypted channel drains; crypto counters are
     * reused unchanged on the new medium. Never disable encryption for throughput. */
    @Synchronized fun upgrade(input: InputStream, output: OutputStream, localId: String,
        expectsAck: Boolean, drain: (V1Frame) -> Unit) {
        check(context != null && !closed)
        require(localId.length == 4)
        val nextReader = DataInputStream(input); val nextWriter = DataOutputStream(output)
        fun plain(frame: V1Frame) { val bytes = OfflineFrame.newBuilder().setVersion(OfflineFrame.Version.V1).setV1(frame).build().toByteArray(); nextWriter.writeInt(bytes.size); nextWriter.write(bytes); nextWriter.flush() }
        fun negotiation(event: BandwidthUpgradeNegotiationFrame.EventType) = V1Frame.newBuilder()
            .setType(V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION)
            .setBandwidthUpgradeNegotiation(BandwidthUpgradeNegotiationFrame.newBuilder().setEventType(event)).build()
        var committed = false
        try {
            check(pausedWrites == null); pausedWrites = mutableListOf()
            plain(V1Frame.newBuilder().setType(V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION)
                .setBandwidthUpgradeNegotiation(BandwidthUpgradeNegotiationFrame.newBuilder()
                    .setEventType(BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION)
                    .setClientIntroduction(BandwidthUpgradeNegotiationFrame.ClientIntroduction.newBuilder()
                        .setEndpointId(localId).setSupportsDisablingEncryption(false))).build())
            if(expectsAck) {
                val ack = parse(readPacket(nextReader))
                require(ack.type == V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION &&
                    ack.bandwidthUpgradeNegotiation.eventType == BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION_ACK)
            }
            send(negotiation(BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL))
            committed = true
            var last = false; var safe = false
            val deadline = System.nanoTime()+30_000_000_000L
            var count = 0
            while(!safe) {
                check(System.nanoTime()<deadline && ++count<=128)
                val frame = receive()
                if(frame.type == V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION) {
                    when(frame.bandwidthUpgradeNegotiation.eventType) {
                        BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL -> {
                            check(!last); last=true
                            send(negotiation(BandwidthUpgradeNegotiationFrame.EventType.SAFE_TO_CLOSE_PRIOR_CHANNEL))
                        }
                        BandwidthUpgradeNegotiationFrame.EventType.SAFE_TO_CLOSE_PRIOR_CHANNEL -> { check(last); safe=true }
                        else -> throw IOException("Unexpected bandwidth-upgrade ordering")
                    }
                } else { check(!last); drain(frame) }
            }
            // Closing courtesy is intentionally cleartext and consumes no crypto sequence.
            writeFrame(V1Frame.newBuilder().setType(V1Frame.FrameType.DISCONNECTION)
                .setDisconnection(DisconnectionFrame.getDefaultInstance()).build(),false)
            runCatching { parse(readPacket()) } // peer may already have closed its prior socket
            val oldReader=reader; val oldWriter=writer
            reader=nextReader; writer=nextWriter
            runCatching { oldReader.close() }; runCatching { oldWriter.close() }
            val queued = requireNotNull(pausedWrites); pausedWrites = null
            queued.forEach { send(it) }
        } catch(e: Exception) {
            pausedWrites = null
            runCatching { nextReader.close() }; runCatching { nextWriter.close() }
            if(committed) close()
            throw e
        }
    }

    @Synchronized private fun writeFrame(frame: V1Frame, encrypted: Boolean) {
        val bytes = OfflineFrame.newBuilder().setVersion(OfflineFrame.Version.V1).setV1(frame).build().toByteArray()
        writePacket(if (encrypted) requireNotNull(context).encodeMessageToPeer(bytes) else bytes)
    }
    @Synchronized private fun writePacket(bytes: ByteArray) {
        check(!closed); require(bytes.size in 1..MAX_PACKET)
        writer.writeInt(bytes.size); writer.write(bytes); writer.flush()
    }
    private fun readPacket(input: DataInputStream = reader): ByteArray {
        check(!closed)
        val length = input.readInt()
        if (length !in 1..MAX_PACKET) throw IOException("Invalid Nearby frame length")
        return ByteArray(length).also(input::readFully)
    }
    override fun close() {
        closed = true
        verification?.fill(0); verification = null
        context = null; handshake = null; pending = false
        try { reader.close() } finally { writer.close() }
    }
    companion object {
        const val MAX_PACKET = 1024 * 1024 + 65536
        internal fun parse(bytes: ByteArray): V1Frame {
            require(bytes.size in 1..MAX_PACKET)
            val frame = OfflineFrame.parseFrom(bytes)
            require(frame.version == OfflineFrame.Version.V1 && frame.hasV1())
            require(frame.v1.hasType() && frame.v1.type != V1Frame.FrameType.UNKNOWN_FRAME_TYPE)
            return frame.v1
        }
    }
}
