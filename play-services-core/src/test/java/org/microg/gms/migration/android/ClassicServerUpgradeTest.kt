/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.*
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame.EventType
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.util.concurrent.*

/** microG as Nearby's advertiser (BWU initiator) against microG's own client upgrade path. */
class ClassicServerUpgradeTest {
    private val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127,0,0,1))
    private val keepAlive = V1Frame.newBuilder().setType(V1Frame.FrameType.KEEP_ALIVE).setKeepAlive(KeepAliveFrame.getDefaultInstance()).build()
    private fun retry(vararg mediums: BandwidthUpgradeRetryFrame.Medium) = V1Frame.newBuilder().setType(V1Frame.FrameType.BANDWIDTH_UPGRADE_RETRY)
        .setBandwidthUpgradeRetry(BandwidthUpgradeRetryFrame.newBuilder().addAllSupportedMedium(mediums.toList()).setIsRequest(true)).build()
    private fun negotiation(event: EventType) = V1Frame.newBuilder().setType(V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION)
        .setBandwidthUpgradeNegotiation(BandwidthUpgradeNegotiationFrame.newBuilder().setEventType(event)).build()
    private fun payload(id: Long) = V1Frame.newBuilder().setType(V1Frame.FrameType.PAYLOAD_TRANSFER).setPayloadTransfer(PayloadTransferFrame.newBuilder()
        .setPacketType(PayloadTransferFrame.PacketType.DATA).setPayloadHeader(PayloadTransferFrame.PayloadHeader.newBuilder().setId(id)
            .setType(PayloadTransferFrame.PayloadHeader.PayloadType.BYTES).setTotalSize(1))
        .setPayloadChunk(PayloadTransferFrame.PayloadChunk.newBuilder().setOffset(0).setFlags(1)
            .setBody(org.microg.gms.migration.protobuf.ByteString.copyFrom(byteArrayOf(7))))).build()

    /** An accepted, encrypted Nearby connection: [target] advertised, [source] connected as "SRC1". */
    private class Connected(val oldServer: ServerSocket, val targetSocket: Socket, val sourceSocket: Socket,
        val target: ClassicNearbyChannel, val source: ClassicNearbyChannel) : Closeable {
        override fun close() { listOf(target, source, targetSocket, sourceSocket, oldServer).forEach { runCatching { it.close() } } }
    }
    private fun connected(pool: ExecutorService): Connected {
        val oldServer = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127,0,0,1)))
        val sourceSocket = Socket(oldServer.inetAddress, oldServer.localPort).apply { soTimeout = 5000 }
        val targetSocket = oldServer.accept().apply { soTimeout = 5000 }
        val target = ClassicNearbyChannel(targetSocket.getInputStream(), targetSocket.getOutputStream())
        val source = ClassicNearbyChannel(sourceSocket.getInputStream(), sourceSocket.getOutputStream())
        val requested = pool.submit<ByteArray> { source.requestConnection("SRC1", "source".toByteArray(), "TGT1", listOf(ConnectionRequestFrame.Medium.WIFI_LAN)) }
        val (request, targetToken) = target.receiveConnection()
        assertEquals("SRC1", request.endpointId)
        assertEquals(listOf(ConnectionRequestFrame.Medium.WIFI_LAN), request.mediumsList)
        val sourceToken = requested.get(5, TimeUnit.SECONDS)
        val sourceAccepted = pool.submit { source.accept(sourceToken) }
        target.accept(targetToken); sourceAccepted.get(5, TimeUnit.SECONDS)
        return Connected(oldServer, targetSocket, sourceSocket, target, source)
    }

    @Test fun wifiLanOfferMatchesNearbyInitiatorWireFormat() {
        val path = ClassicUpgradeListener.wifiLanPath(InetAddress.getByAddress(byteArrayOf(192.toByte(),168.toByte(),1,20)), 4321)
        // UpgradePathInfo: medium(1)=WIFI_LAN(5); wifi_lan_socket(3){ip_address(1), wifi_port(2), address_candidates(3){ip_address(1), port(2)}};
        // supports_client_introduction_ack(9)=true. Same fields Nearby's ForBwuWifiLanPathAvailable sets.
        val candidate = byteArrayOf(0x0a,4,192.toByte(),168.toByte(),1,20, 0x10,0xe1.toByte(),0x21)
        val socket = candidate + byteArrayOf(0x1a, candidate.size.toByte()) + candidate
        val expected = byteArrayOf(0x08,5, 0x1a, socket.size.toByte()) + socket + byteArrayOf(0x48,1)
        assertArrayEquals(expected, path.toByteArray())
        assertFalse(path.hasSupportsDisablingEncryption())
        assertThrows(IllegalArgumentException::class.java) { ClassicUpgradeListener.wifiLanPath(InetAddress.getByName("::1"), 4321) }
    }

    @Test fun offeredAddressIsRoutableWifiIpv4() {
        val linkLocal = InetAddress.getByAddress(byteArrayOf(169.toByte(),254.toByte(),3,4))
        val v6 = InetAddress.getByName("2001:db8::1")
        val lan = InetAddress.getByAddress(byteArrayOf(10,0,0,7))
        assertEquals(lan, ClassicUpgradeListener.wifiLanAddress(listOf(v6, linkLocal, loopback, lan)))
        assertNull(ClassicUpgradeListener.wifiLanAddress(listOf(v6, linkLocal, loopback)))
    }

    @Test fun retryAndUnknownFramesNeverEndTheConnection() {
        assertEquals(ClassicFrameAction.UPGRADE_RETRY, classicFrameAction(retry(BandwidthUpgradeRetryFrame.Medium.WIFI_DIRECT,
            BandwidthUpgradeRetryFrame.Medium.WIFI_HOTSPOT, BandwidthUpgradeRetryFrame.Medium.WIFI_LAN)))
        assertEquals(ClassicFrameAction.UPGRADE_PATH_AVAILABLE, classicFrameAction(negotiation(EventType.UPGRADE_PATH_AVAILABLE)))
        assertEquals(ClassicFrameAction.IGNORE, classicFrameAction(negotiation(EventType.UPGRADE_FAILURE)))
        assertEquals(ClassicFrameAction.IGNORE, classicFrameAction(negotiation(EventType.SAFE_TO_CLOSE_PRIOR_CHANNEL)))
        assertEquals(ClassicFrameAction.IGNORE, classicFrameAction(V1Frame.newBuilder().setType(V1Frame.FrameType.AUTO_RESUME).build()))
        assertEquals(ClassicFrameAction.DISCONNECT, classicFrameAction(V1Frame.newBuilder().setType(V1Frame.FrameType.DISCONNECTION).build()))
        // OfflineFrame{version=V1, v1{type=99}}: a frame type newer than these protos.
        val newer = byteArrayOf(0x08,1, 0x12,2, 0x08,99)
        assertThrows(IllegalArgumentException::class.java) { ClassicNearbyChannel.parse(newer) }
        val parsed = ClassicNearbyChannel.parse(newer, allowUnknownType = true)
        assertEquals(V1Frame.FrameType.UNKNOWN_FRAME_TYPE, parsed.type)
        assertEquals(ClassicFrameAction.IGNORE, classicFrameAction(parsed))
    }

    @Test fun advertiserUpgradesToWifiLanAndKeepsCryptoSequence() {
        val pool = Executors.newCachedThreadPool()
        try { connected(pool).use { pair ->
            ClassicUpgradeListener(loopback).use { listener ->
                val handled = CopyOnWriteArrayList<V1Frame>()
                val drained = CopyOnWriteArrayList<V1Frame>()
                val client = pool.submit<Unit> {
                    // GMS's first frames after connecting: its supported mediums, and traffic on the prior medium.
                    pair.source.send(retry(BandwidthUpgradeRetryFrame.Medium.WIFI_LAN)); pair.source.send(keepAlive)
                    val offer = pair.source.receive()
                    assertEquals(ClassicFrameAction.UPGRADE_PATH_AVAILABLE, classicFrameAction(offer))
                    val path = offer.bandwidthUpgradeNegotiation.upgradePathInfo
                    assertEquals(BandwidthUpgradeNegotiationFrame.UpgradePathInfo.Medium.WIFI_LAN, path.medium)
                    assertTrue(path.supportsClientIntroductionAck)
                    val lan = path.wifiLanSocket
                    assertEquals(lan.addressCandidatesList.single().port, lan.wifiPort)
                    Socket(InetAddress.getByAddress(lan.ipAddress.toByteArray()), lan.wifiPort).use { next ->
                        next.soTimeout = 5000
                        pair.source.send(payload(1)) // still before the source's LAST_WRITE: drained on the prior medium
                        pair.source.upgrade(next.getInputStream(), next.getOutputStream(), "SRC1", path.supportsClientIntroductionAck) { drained.add(it) }
                        pair.source.send(payload(2))
                        assertEquals(keepAlive, pair.source.receive())
                    }
                }
                val link = awaitUpgradeConnection(pair.target, pair.targetSocket.getInputStream(), listener, 5000) { handled.add(it) }
                assertEquals(ClassicUpgradeListener.WIFI_LAN, link.medium)
                link.use {
                    val prior = CopyOnWriteArrayList<V1Frame>()
                    pair.target.acceptUpgrade(link.input, link.output) { prior.add(it) }
                    // The retry/keep-alive sent before the offer arrived were handled while the offer was open.
                    assertEquals(listOf(V1Frame.FrameType.BANDWIDTH_UPGRADE_RETRY, V1Frame.FrameType.KEEP_ALIVE), (handled + prior).map { it.type }.take(2))
                    assertEquals(1L, (handled + prior).single { it.type == V1Frame.FrameType.PAYLOAD_TRANSFER }.payloadTransfer.payloadHeader.id)
                    // Both directions now run on the new socket with the same, continuing UKEY2 context.
                    assertEquals(2L, pair.target.receive().payloadTransfer.payloadHeader.id)
                    pair.target.send(keepAlive)
                    client.get(10, TimeUnit.SECONDS)
                    assertTrue(drained.isEmpty())
                    assertTrue(pair.target.isConnected)
                }
            }
        } } finally { pool.shutdownNow() }
    }

    @Test fun peerUpgradeFailureEndsOfferAndKeepsPriorMedium() {
        val pool = Executors.newCachedThreadPool()
        try { connected(pool).use { pair ->
            ClassicUpgradeListener(loopback).use { listener ->
                val client = pool.submit<Unit> {
                    val offer = pair.source.receive()
                    pair.source.rejectUpgrade(offer.bandwidthUpgradeNegotiation) // what a peer that cannot reach the path sends
                    assertEquals(keepAlive, pair.source.receive())
                    pair.source.send(payload(3))
                }
                val error = assertThrows(IOException::class.java) {
                    awaitUpgradeConnection(pair.target, pair.targetSocket.getInputStream(), listener, 10000) { fail("unexpected $it") }
                }
                assertTrue(error.message!!.contains("could not use"))
                pair.target.send(keepAlive)
                assertEquals(3L, pair.target.receive().payloadTransfer.payloadHeader.id)
                client.get(5, TimeUnit.SECONDS)
            }
        } } finally { pool.shutdownNow() }
    }

    @Test fun unansweredOfferTimesOutAndKeepsPriorMedium() {
        val pool = Executors.newCachedThreadPool()
        try { connected(pool).use { pair ->
            ClassicUpgradeListener(loopback).use { listener ->
                val started = System.nanoTime()
                assertThrows(IOException::class.java) { awaitUpgradeConnection(pair.target, pair.targetSocket.getInputStream(), listener, 600) { } }
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started) < 5000)
                assertEquals(ClassicFrameAction.UPGRADE_PATH_AVAILABLE, classicFrameAction(pair.source.receive()))
                pair.source.send(keepAlive); assertEquals(keepAlive, pair.target.receive())
            }
        } } finally { pool.shutdownNow() }
    }

    @Test fun introductionForAnotherEndpointIsRefusedBeforeHandoff() {
        val pool = Executors.newCachedThreadPool()
        try { connected(pool).use { pair ->
            ClassicUpgradeListener(loopback).use { listener ->
                val client = pool.submit<Unit> {
                    val lan = pair.source.receive().bandwidthUpgradeNegotiation.upgradePathInfo.wifiLanSocket
                    Socket(InetAddress.getByAddress(lan.ipAddress.toByteArray()), lan.wifiPort).use { next ->
                        val intro = OfflineFrame.newBuilder().setVersion(OfflineFrame.Version.V1).setV1(V1Frame.newBuilder()
                            .setType(V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION).setBandwidthUpgradeNegotiation(BandwidthUpgradeNegotiationFrame.newBuilder()
                                .setEventType(EventType.CLIENT_INTRODUCTION).setClientIntroduction(BandwidthUpgradeNegotiationFrame.ClientIntroduction.newBuilder().setEndpointId("XXXX")))).build().toByteArray()
                        DataOutputStream(next.getOutputStream()).apply { writeInt(intro.size); write(intro); flush() }
                        assertEquals(-1, next.getInputStream().read()) // no ACK; the target closes it
                    }
                    assertEquals(keepAlive, pair.source.receive())
                }
                val link = awaitUpgradeConnection(pair.target, pair.targetSocket.getInputStream(), listener, 5000) { }
                link.use { assertThrows(IllegalArgumentException::class.java) { pair.target.acceptUpgrade(link.input, link.output) { } } }
                assertTrue(pair.target.isConnected)
                pair.target.send(keepAlive)
                client.get(5, TimeUnit.SECONDS)
            }
        } } finally { pool.shutdownNow() }
    }
}
