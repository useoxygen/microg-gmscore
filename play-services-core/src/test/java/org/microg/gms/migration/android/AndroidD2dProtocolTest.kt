/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.*
import org.microg.gms.migration.protobuf.ByteString
import com.google.security.cryptauth.lib.securegcm.Ukey2Handshake
import com.google.security.cryptauth.lib.securemessage.CryptoOps
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

class AndroidD2dProtocolTest {
    private fun sourceHandshake() = Ukey2Handshake.forResponder(Ukey2Handshake.HandshakeCipher.P256_SHA512)
    private fun read(input: DataInputStream) = ByteArray(input.readInt().also { require(it in 1..D2dPipeChannel.MAX_FRAME) }).also(input::readFully)
    private fun write(out: DataOutputStream, bytes: ByteArray) { out.writeInt(bytes.size); out.write(bytes); out.flush() }

    @Test fun directTransferUsesSourceOfferTargetInitiatorAndEncryptedJson() {
        ServerSocket(0).use { server ->
            val pool = Executors.newSingleThreadExecutor()
            try {
                val peer = pool.submit<ByteArray> {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = DataInputStream(socket.getInputStream()); val out = DataOutputStream(socket.getOutputStream())
                        write(out, byteArrayOf(9))
                        assertArrayEquals(byteArrayOf(8), read(input))
                        val source = sourceHandshake()
                        source.parseHandshakeMessage(read(input)); write(out, source.nextHandshakeMessage)
                        source.parseHandshakeMessage(read(input)); source.getVerificationString(4); source.verifyHandshake()
                        val crypto = source.toConnectionContext()
                        write(out, byteArrayOf(1, 1, 2, 3, 5, 8))
                        write(out, crypto.encodeMessageToPeer("{\"protocolVersion\":3}".toByteArray()))
                        crypto.decodeMessageFromPeer(read(input))
                    }
                }
                Socket("127.0.0.1", server.localPort).use { socket ->
                    socket.soTimeout = 5000
                    D2dPipeChannel(socket.getInputStream(), socket.getOutputStream()).use { target ->
                        target.negotiateTarget()
                        assertEquals("{\"protocolVersion\":3}", target.receive().toString(Charsets.UTF_8))
                        target.send("{\"bootstrapState\":3}".toByteArray())
                        assertEquals("{\"bootstrapState\":3}", peer.get(5, TimeUnit.SECONDS).toString(Charsets.UTF_8))
                    }
                }
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun directTransferRejectsCleartextAndInvalidLengthBeforeAllocating() {
        listOf(0, -1, Int.MAX_VALUE).forEach { length ->
            val bytes = ByteArrayOutputStream().apply { DataOutputStream(this).writeInt(length) }.toByteArray()
            D2dPipeChannel(ByteArrayInputStream(bytes), ByteArrayOutputStream()).use {
                assertThrows(IOException::class.java) { it.negotiateTarget() }
            }
        }
        val bytes = ByteArrayOutputStream().apply { val out = DataOutputStream(this); write(out, byteArrayOf(1)) }.toByteArray()
        val out = ByteArrayOutputStream()
        D2dPipeChannel(ByteArrayInputStream(bytes), out).use { assertThrows(IOException::class.java) { it.negotiateTarget() } }
        assertEquals(0, out.size())
    }

    @Test fun postSetupProofMatchesIndependentUpstreamHkdfAndRejectsReplay() {
        val secret = ByteArray(32) { (it + 1).toByte() }
        PostSetupTargetHandshake(secret).use { target ->
            val source = sourceHandshake()
            source.parseHandshakeMessage(target.start())
            val (finish, proof) = target.finish(source.nextHandshakeMessage)
            source.parseHandshakeMessage(finish)
            val verification = source.getVerificationString(32)
            assertArrayEquals(PostSetupTargetHandshake.hmac(secret, verification), proof)
            source.verifyHandshake()
            val sourceContext = source.toConnectionContext()
            val challenge = ByteArray(32) { (it + 70).toByte() }
            val derived = target.authenticate(challenge, PostSetupTargetHandshake.hmac(secret, challenge))
            assertArrayEquals(CryptoOps.hkdf(SecretKeySpec(sourceContext.sessionUnique, "HmacSHA256"), secret,
                "smartdevice.postsetup.key".toByteArray(), 32), derived)
            assertThrows(IllegalStateException::class.java) { target.authenticate(challenge, proof) }
        }
    }

    @Test fun postSetupWrongSecretNeverReturnsAKey() {
        PostSetupTargetHandshake(ByteArray(32) { 4 }).use { target ->
            val source = sourceHandshake()
            source.parseHandshakeMessage(target.start())
            val (finish, _) = target.finish(source.nextHandshakeMessage)
            source.parseHandshakeMessage(finish)
            assertThrows(SecurityException::class.java) { target.authenticate(ByteArray(32) { 2 }, ByteArray(32)) }
            assertThrows(IllegalStateException::class.java) { target.start() }
        }
    }

    @Test fun classicNearbyWaitsForBothAcceptancesThenEncryptsOfflineFrames() {
        ServerSocket(0).use { server ->
            val pool = Executors.newSingleThreadExecutor()
            try {
                val peer = pool.submit<ByteArray> {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = DataInputStream(socket.getInputStream()); val out = DataOutputStream(socket.getOutputStream())
                        fun plain(frame: V1Frame) = write(out, OfflineFrame.newBuilder().setVersion(OfflineFrame.Version.V1).setV1(frame).build().toByteArray())
                        plain(V1Frame.newBuilder().setType(V1Frame.FrameType.CONNECTION_REQUEST)
                            .setConnectionRequest(ConnectionRequestFrame.newBuilder().setEndpointId("ABCD").setEndpointInfo(ByteString.copyFromUtf8("Source"))).build())
                        val source = Ukey2Handshake.forInitiator(Ukey2Handshake.HandshakeCipher.P256_SHA512)
                        write(out, source.nextHandshakeMessage); source.parseHandshakeMessage(read(input)); write(out, source.nextHandshakeMessage)
                        val token = source.getVerificationString(32)
                        assertEquals(ConnectionResponseFrame.ResponseStatus.ACCEPT, ClassicNearbyChannel.parse(read(input)).connectionResponse.response)
                        plain(V1Frame.newBuilder().setType(V1Frame.FrameType.CONNECTION_RESPONSE)
                            .setConnectionResponse(ConnectionResponseFrame.newBuilder().setStatus(0).setResponse(ConnectionResponseFrame.ResponseStatus.ACCEPT)).build())
                        source.verifyHandshake(); val crypto = source.toConnectionContext()
                        val frame = OfflineFrame.newBuilder().setVersion(OfflineFrame.Version.V1)
                            .setV1(V1Frame.newBuilder().setType(V1Frame.FrameType.KEEP_ALIVE).setKeepAlive(KeepAliveFrame.getDefaultInstance())).build()
                        write(out, crypto.encodeMessageToPeer(frame.toByteArray()))
                        val echoed = ClassicNearbyChannel.parse(crypto.decodeMessageFromPeer(read(input)))
                        assertEquals(V1Frame.FrameType.KEEP_ALIVE, echoed.type)
                        token
                    }
                }
                Socket("127.0.0.1", server.localPort).use { socket ->
                    socket.soTimeout = 5000
                    ClassicNearbyChannel(socket.getInputStream(), socket.getOutputStream()).use { target ->
                        assertThrows(IllegalStateException::class.java) { target.receive() }
                        val (request, token) = target.receiveConnection()
                        assertEquals("ABCD", request.endpointId)
                        assertThrows(IllegalArgumentException::class.java) { target.accept(ByteArray(32)) }
                        target.accept(token)
                        target.send(target.receive())
                        assertArrayEquals(token, peer.get(5, TimeUnit.SECONDS))
                    }
                }
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun unknownOfflineVersionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ClassicNearbyChannel.parse(byteArrayOf(8, 0)) }
    }

    private class PayloadSink : ClassicNearbyPayloads.Sink {
        val bytes = ByteArrayOutputStream(); var completions = 0; var failures = 0
        override fun open(header: PayloadTransferFrame.PayloadHeader): OutputStream = bytes
        override fun progress(id: Long, bytes: Long) {}
        override fun complete(id: Long, bytes: Long) { completions++ }
        override fun failed(id: Long) { failures++ }
    }
    private fun chunk(offset: Long, body: String, last: Boolean = false, total: Long = 4) = PayloadTransferFrame.newBuilder()
        .setPacketType(PayloadTransferFrame.PacketType.DATA)
        .setPayloadHeader(PayloadTransferFrame.PayloadHeader.newBuilder().setId(42).setTotalSize(total)
            .setType(PayloadTransferFrame.PayloadHeader.PayloadType.FILE).setFileName("../../ignored"))
        .setPayloadChunk(PayloadTransferFrame.PayloadChunk.newBuilder().setOffset(offset).setFlags(if (last) 1 else 0)
            .setBody(ByteString.copyFromUtf8(body))).build()

    @Test fun payloadCompletesOnlyAfterOrderedChunksAndExactLength() {
        val sink = PayloadSink()
        ClassicNearbyPayloads(sink, 20).use { receiver ->
            receiver.receive(chunk(0, "ab")); assertEquals(0, sink.completions)
            receiver.receive(chunk(2, "cd", true)); assertEquals(1, sink.completions)
            assertEquals("abcd", sink.bytes.toString("UTF-8")); assertEquals(0, sink.failures)
        }
    }
    @Test fun payloadRejectsGapsTruncationAndBudgetOverflow() {
        listOf(chunk(3, "x"), chunk(2, "c", true), chunk(2, "cde", true)).forEach { bad ->
            val sink = PayloadSink()
            ClassicNearbyPayloads(sink, 20).use { receiver ->
                receiver.receive(chunk(0, "ab"))
                assertThrows(IllegalArgumentException::class.java) { receiver.receive(bad) }
                assertEquals(0, sink.completions); assertEquals(1, sink.failures)
            }
        }
        val sink = PayloadSink()
        ClassicNearbyPayloads(sink, 3).use { receiver ->
            assertThrows(IllegalArgumentException::class.java) { receiver.receive(chunk(0, "abcd", true)) }
            assertEquals(0, sink.completions)
        }
    }
    @Test fun completedFilesDoNotConsumeTheNextPayloadsBudget() {
        val sink=PayloadSink()
        // Scaled reproduction of twenty1GiB files with a16GiB per-file maximum.
        ClassicNearbyPayloads(sink,16*1024).use { receiver ->
            repeat(20) { id ->
                receiver.receive(chunk(0,"x".repeat(1024),true,1024).toBuilder()
                    .setPayloadHeader(chunk(0,"",total=1024).payloadHeader.toBuilder().setId(id.toLong())).build())
            }
        }
        assertEquals(20,sink.completions); assertEquals(0,sink.failures)
    }
    @Test fun storageAdmissionUsesCurrentFreeSpaceAndCanRecover() {
        var available=3L; val bytes=ByteArrayOutputStream()
        ClassicStorageOutputStream(bytes) { available }.use { output ->
            output.write(byteArrayOf(1,2)); available=1
            assertThrows(IllegalArgumentException::class.java) { output.write(byteArrayOf(3,4)) }
            available=2; output.write(byteArrayOf(3,4))
        }
        assertArrayEquals(byteArrayOf(1,2,3,4),bytes.toByteArray())
    }
    @Test fun unknownLengthStreamStillHasAPerPayloadLimit() {
        val sink=PayloadSink()
        ClassicNearbyPayloads(sink,3).use { receiver ->
            fun stream(offset: Long, body: String)=chunk(offset,body,total=-1).toBuilder().setPayloadHeader(chunk(0,"",total=-1).payloadHeader.toBuilder().setType(PayloadTransferFrame.PayloadHeader.PayloadType.STREAM)).build()
            receiver.receive(stream(0,"ab"))
            assertThrows(IllegalArgumentException::class.java) { receiver.receive(stream(2,"cd")) }
        }
        assertEquals(0,sink.completions); assertEquals(1,sink.failures)
    }
    @Test fun closingPayloadsReportsPartialAsFailed() {
        val sink = PayloadSink()
        ClassicNearbyPayloads(sink, 20).use { it.receive(chunk(0, "ab")) }
        assertEquals(1, sink.failures); assertEquals(0, sink.completions)
    }
    @Test fun completedPayloadCannotReopenAndOverwriteItsSink() {
        val sink = PayloadSink()
        ClassicNearbyPayloads(sink, 20).use { receiver ->
            receiver.receive(chunk(0, "abcd", true))
            assertThrows(IllegalArgumentException::class.java) { receiver.receive(chunk(0, "evil", true)) }
            assertEquals("abcd", sink.bytes.toString("UTF-8"))
            assertEquals(1, sink.completions)
        }
    }
}
