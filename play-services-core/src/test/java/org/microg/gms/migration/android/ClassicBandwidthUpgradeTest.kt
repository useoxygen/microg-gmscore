/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.*
import com.google.security.cryptauth.lib.securegcm.Ukey2Handshake
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.util.concurrent.*

class ClassicBandwidthUpgradeTest {
    private fun read(input: DataInputStream): ByteArray = ByteArray(input.readInt().also { require(it in 1..ClassicNearbyChannel.MAX_PACKET) }).also(input::readFully)
    private fun write(output: DataOutputStream, bytes: ByteArray) { output.writeInt(bytes.size); output.write(bytes); output.flush() }
    private fun encode(frame: V1Frame) = OfflineFrame.newBuilder().setVersion(OfflineFrame.Version.V1).setV1(frame).build().toByteArray()
    private fun negotiation(event: BandwidthUpgradeNegotiationFrame.EventType) = V1Frame.newBuilder().setType(V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION).setBandwidthUpgradeNegotiation(BandwidthUpgradeNegotiationFrame.newBuilder().setEventType(event)).build()
    private val keepAlive = V1Frame.newBuilder().setType(V1Frame.FrameType.KEEP_ALIVE).setKeepAlive(KeepAliveFrame.getDefaultInstance()).build()
    private fun runUpgrade(rejectIntroduction: Boolean) {
        ServerSocket(0).use { oldServer -> ServerSocket(0).use { newServer ->
            val pool=Executors.newSingleThreadExecutor()
            try {
                val peer=pool.submit<Unit> {
                    oldServer.accept().use { old ->
                        old.soTimeout=5000
                        val input=DataInputStream(old.getInputStream()); val output=DataOutputStream(old.getOutputStream())
                        write(output,encode(V1Frame.newBuilder().setType(V1Frame.FrameType.CONNECTION_REQUEST).setConnectionRequest(ConnectionRequestFrame.newBuilder().setEndpointId("SRC1").setEndpointName("source")).build()))
                        val source=Ukey2Handshake.forInitiator(Ukey2Handshake.HandshakeCipher.P256_SHA512)
                        write(output,source.nextHandshakeMessage); source.parseHandshakeMessage(read(input)); write(output,source.nextHandshakeMessage)
                        source.getVerificationString(32)
                        assertEquals(V1Frame.FrameType.CONNECTION_RESPONSE,ClassicNearbyChannel.parse(read(input)).type)
                        write(output,encode(V1Frame.newBuilder().setType(V1Frame.FrameType.CONNECTION_RESPONSE).setConnectionResponse(ConnectionResponseFrame.newBuilder().setResponse(ConnectionResponseFrame.ResponseStatus.ACCEPT)).build()))
                        source.verifyHandshake(); val crypto=source.toConnectionContext()
                        fun receiveOld()=ClassicNearbyChannel.parse(crypto.decodeMessageFromPeer(read(input)))
                        fun sendOld(frame: V1Frame)=write(output,crypto.encodeMessageToPeer(encode(frame)))
                        newServer.accept().use { next ->
                            next.soTimeout=5000
                            val nextInput=DataInputStream(next.getInputStream()); val nextOutput=DataOutputStream(next.getOutputStream())
                            val intro=ClassicNearbyChannel.parse(read(nextInput)).bandwidthUpgradeNegotiation
                            assertEquals(BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION,intro.eventType)
                            assertEquals("TGT1",intro.clientIntroduction.endpointId)
                            assertFalse(intro.clientIntroduction.supportsDisablingEncryption)
                            if(rejectIntroduction) {
                                write(nextOutput,encode(keepAlive))
                                sendOld(keepAlive); assertEquals(keepAlive,receiveOld())
                            } else {
                                write(nextOutput,encode(negotiation(BandwidthUpgradeNegotiationFrame.EventType.CLIENT_INTRODUCTION_ACK)))
                                assertEquals(BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL,receiveOld().bandwidthUpgradeNegotiation.eventType)
                                // A prior-channel payload/keepalive can still precede LAST_WRITE.
                                sendOld(keepAlive)
                                sendOld(negotiation(BandwidthUpgradeNegotiationFrame.EventType.LAST_WRITE_TO_PRIOR_CHANNEL))
                                assertEquals(BandwidthUpgradeNegotiationFrame.EventType.SAFE_TO_CLOSE_PRIOR_CHANNEL,receiveOld().bandwidthUpgradeNegotiation.eventType)
                                sendOld(negotiation(BandwidthUpgradeNegotiationFrame.EventType.SAFE_TO_CLOSE_PRIOR_CHANNEL))
                                assertEquals(V1Frame.FrameType.DISCONNECTION,ClassicNearbyChannel.parse(read(input)).type)
                                write(output,encode(V1Frame.newBuilder().setType(V1Frame.FrameType.DISCONNECTION).setDisconnection(DisconnectionFrame.getDefaultInstance()).build()))
                                write(nextOutput,crypto.encodeMessageToPeer(encode(keepAlive)))
                                // Decode with the SAME context: restarting crypto counters must fail.
                                assertEquals(keepAlive,ClassicNearbyChannel.parse(crypto.decodeMessageFromPeer(read(nextInput))))
                            }
                        }
                    }
                }
                Socket("127.0.0.1",oldServer.localPort).use { old ->
                    old.soTimeout=5000
                    ClassicNearbyChannel(old.getInputStream(),old.getOutputStream()).use { target ->
                        val (_,token)=target.receiveConnection(); target.accept(token); token.fill(0)
                        Socket("127.0.0.1",newServer.localPort).use { next ->
                            next.soTimeout=5000; var drained=0
                            val action={ target.upgrade(next.getInputStream(),next.getOutputStream(),"TGT1",true) { assertEquals(keepAlive,it); drained++ } }
                            if(rejectIntroduction) { assertThrows(IllegalArgumentException::class.java,action); assertTrue(target.isConnected) }
                            else { action(); assertEquals(1,drained) }
                            target.send(target.receive())
                            peer.get(5,TimeUnit.SECONDS)
                        }
                    }
                }
            } finally { pool.shutdownNow() }
        } }
    }
    @Test fun upgradeDrainsOldSocketAndPreservesCryptoSequenceOnNewSocket() = runUpgrade(false)
    @Test fun invalidIntroductionAckLeavesOriginalEncryptedChannelUsable() = runUpgrade(true)
}
