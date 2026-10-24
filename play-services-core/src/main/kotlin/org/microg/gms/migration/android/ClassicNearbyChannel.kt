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
    private val reader = DataInputStream(input)
    private val writer = DataOutputStream(output)
    private var handshake: Ukey2Handshake? = null
    private var verification: ByteArray? = null
    private var context: D2DConnectionContext? = null
    @Volatile private var closed = false
    private var pending = false
    var endpointId: String? = null
        private set

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

    fun receive(): V1Frame {
        check(context != null && !closed)
        return parse(requireNotNull(context).decodeMessageFromPeer(readPacket()))
    }

    fun send(frame: V1Frame) {
        check(context != null && !closed)
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

    @Synchronized private fun writeFrame(frame: V1Frame, encrypted: Boolean) {
        val bytes = OfflineFrame.newBuilder().setVersion(OfflineFrame.Version.V1).setV1(frame).build().toByteArray()
        writePacket(if (encrypted) requireNotNull(context).encodeMessageToPeer(bytes) else bytes)
    }
    @Synchronized private fun writePacket(bytes: ByteArray) {
        check(!closed); require(bytes.size in 1..MAX_PACKET)
        writer.writeInt(bytes.size); writer.write(bytes); writer.flush()
    }
    private fun readPacket(): ByteArray {
        check(!closed)
        val length = reader.readInt()
        if (length !in 1..MAX_PACKET) throw IOException("Invalid Nearby frame length")
        return ByteArray(length).also(reader::readFully)
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
