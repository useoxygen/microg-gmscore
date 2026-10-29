/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import android.os.*
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.migration.nearby.DctRecord
import org.microg.gms.migration.nearby.NEARBY_DESCRIPTOR
import org.microg.gms.migration.writeNullable
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.*
import java.util.concurrent.*

/** ClassicNearbyBinder as Switch's advertiser, with a stock-GMS-like source driven over loopback sockets. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class ClassicBinderUpgradeTest {
    private val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127,0,0,1))
    private val prefix = "com.google.android.gms.nearby.internal.connection."
    private val events = LinkedBlockingQueue<kotlin.Pair<Int,DctRecord>>()
    private val closeables = mutableListOf<AutoCloseable>()
    private val lifecycle = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(prefix+"IConnectionLifecycleListener")
            val schema = when(code) { 2 -> ClassicSchemas.initiated; 3 -> ClassicSchemas.connected; 4 -> ClassicSchemas.disconnected; 5 -> ClassicSchemas.bandwidth; else -> return true }
            events.add(code to requireNotNull(DctRecord.nullable(data, schema)))
            return true
        }
    }
    @After fun tearDown() { closeables.reversed().forEach { runCatching { it.close() } }; RetainedSessionUse.reset() }

    private fun event(code: Int, seconds: Long = 10): DctRecord {
        val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            val (observed, record) = events.poll(deadline-System.nanoTime(), TimeUnit.NANOSECONDS) ?: throw AssertionError("No lifecycle event $code")
            if (observed == code) return record
        }
    }
    private fun noEvent(code: Int, millis: Long) {
        val deadline = System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis)
        while (true) { val (observed, _) = events.poll(deadline-System.nanoTime(), TimeUnit.NANOSECONDS) ?: return; assertNotEquals(code, observed) }
    }
    /** The next frame from the binder, skipping its keep-alives. */
    private fun ClassicNearbyChannel.next(): V1Frame { while (true) { val frame = receive(); if (frame.type != V1Frame.FrameType.KEEP_ALIVE) return frame } }
    private fun retry(request: Boolean) = V1Frame.newBuilder().setType(V1Frame.FrameType.BANDWIDTH_UPGRADE_RETRY).setBandwidthUpgradeRetry(BandwidthUpgradeRetryFrame.newBuilder()
        .addAllSupportedMedium(listOf(BandwidthUpgradeRetryFrame.Medium.WIFI_DIRECT, BandwidthUpgradeRetryFrame.Medium.WIFI_HOTSPOT, BandwidthUpgradeRetryFrame.Medium.WIFI_LAN)).setIsRequest(request)).build()

    /** Connects a source that asked for WIFI_LAN upgrades over a Bluetooth-like link (medium 2) and accepts it on both sides. */
    private fun connect(switch: Boolean, acceptMs: Long = 10_000): ClassicNearbyChannel {
        val uid = Binder.getCallingUid()
        val binder = ClassicNearbyBinder(RuntimeEnvironment.getApplication(), uid, switchUid = if (switch) uid else null,
            upgradeListener = { ClassicUpgradeListener(loopback) }, upgradeAcceptMs = acceptMs).also { closeables.add(it) }
        val server = ServerSocket(0, 1, loopback).also { closeables.add(it) }
        val sourceSocket = Socket(loopback, server.localPort).apply { soTimeout = 15000 }.also { closeables.add(it) }
        val targetSocket = server.accept().also { closeables.add(it) }
        binder.acceptIncomingForTest(ClassicRadio.Link(targetSocket.getInputStream(), targetSocket.getOutputStream(), 2) { targetSocket.close() }, lifecycle)
        val source = ClassicNearbyChannel(sourceSocket.getInputStream(), sourceSocket.getOutputStream()).also { closeables.add(it) }
        val token = source.requestConnection("SRC1", "source".toByteArray(), "TGT1", listOf(ConnectionRequestFrame.Medium.WIFI_LAN))
        assertEquals("SRC1", event(2).text(1))
        val result = LinkedBlockingQueue<Int>()
        val callback = object : Binder() { override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean { data.enforceInterface(prefix+"IResultListener"); result.add(data.readInt()); return true } }
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(NEARBY_DESCRIPTOR)
            data.writeNullable(DctRecord(ClassicSchemas.accept, mapOf(1 to callback, 3 to "SRC1", 5 to Binder())))
            assertTrue(binder.transact(2006, data, reply, 0)); reply.readException()
        } finally { data.recycle(); reply.recycle() }
        assertEquals(0, result.poll(5, TimeUnit.SECONDS))
        source.accept(token)
        assertEquals(0, event(3).int(2))
        return source
    }
    private fun assertFailureReported() { val bandwidth = event(5); assertEquals(0, bandwidth.int(2)); assertEquals(2, bandwidth.int(3)); assertEquals(2, bandwidth.int(12)) }

    @Test fun switchConnectionIsOfferedWifiLanAtOnceAndReportsHighBandwidth() {
        val source = connect(switch = true)
        val offer = source.next()
        assertEquals(BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE, offer.bandwidthUpgradeNegotiation.eventType)
        val path = offer.bandwidthUpgradeNegotiation.upgradePathInfo
        assertEquals(BandwidthUpgradeNegotiationFrame.UpgradePathInfo.Medium.WIFI_LAN, path.medium)
        Socket(InetAddress.getByAddress(path.wifiLanSocket.ipAddress.toByteArray()), path.wifiLanSocket.wifiPort).use { next ->
            next.soTimeout = 15000
            source.upgrade(next.getInputStream(), next.getOutputStream(), "SRC1", path.supportsClientIntroductionAck) { }
            val bandwidth = event(5)
            assertEquals("SRC1", bandwidth.text(1))
            assertEquals(3, bandwidth.int(2)) // HIGH
            assertEquals(ClassicUpgradeListener.WIFI_LAN, bandwidth.int(3))
            assertEquals(1, bandwidth.int(12))
        }
    }

    @Test fun otherTransportsAreNeverOfferedButStaySafeFromRetryAndUnknownFrames() {
        val source = connect(switch = false)
        source.send(V1Frame.newBuilder().setType(V1Frame.FrameType.AUTO_RESUME).setAutoResume(AutoResumeFrame.getDefaultInstance()).build())
        source.send(retry(request = true))
        // The first frame back is the retry answer, not an offer: no mediums, since this transport does not initiate.
        val answer = source.next()
        assertEquals(V1Frame.FrameType.BANDWIDTH_UPGRADE_RETRY, answer.type)
        assertFalse(answer.bandwidthUpgradeRetry.isRequest)
        assertEquals(0, answer.bandwidthUpgradeRetry.supportedMediumCount)
        noEvent(5, 500); noEvent(4, 0)
    }

    @Test fun retryDuringAnOpenOfferIsAnsweredAndPeerFailureIsReported() {
        val source = connect(switch = true)
        val offer = source.next()
        assertEquals(BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE, offer.bandwidthUpgradeNegotiation.eventType)
        source.send(retry(request = true)) // as GMS does ~500 ms after connecting
        val answer = source.next()
        assertEquals(V1Frame.FrameType.BANDWIDTH_UPGRADE_RETRY, answer.type)
        assertEquals(listOf(BandwidthUpgradeRetryFrame.Medium.WIFI_LAN), answer.bandwidthUpgradeRetry.supportedMediumList)
        assertFalse(answer.bandwidthUpgradeRetry.isRequest)
        source.rejectUpgrade(offer.bandwidthUpgradeNegotiation)
        assertFailureReported()
        noEvent(4, 300) // the Bluetooth connection stays up
    }

    @Test fun unansweredOfferTimesOutWithAFailureEvent() {
        val source = connect(switch = true, acceptMs = 600)
        assertEquals(BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE, source.next().bandwidthUpgradeNegotiation.eventType)
        assertFailureReported()
        noEvent(4, 300)
    }

    @Test fun atMostThreeOffersPerConnection() {
        val source = connect(switch = true)
        repeat(3) { attempt ->
            val offer = source.next()
            assertEquals("offer ${attempt+1}", BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE, offer.bandwidthUpgradeNegotiation.eventType)
            source.rejectUpgrade(offer.bandwidthUpgradeNegotiation)
            assertFailureReported()
            source.send(retry(request = false)) // GMS asks again
        }
        source.send(retry(request = true))
        // The third retry opened no fourth offer: the next frame is the answer to this request.
        assertEquals(V1Frame.FrameType.BANDWIDTH_UPGRADE_RETRY, source.next().type)
        noEvent(5, 300)
    }
}
