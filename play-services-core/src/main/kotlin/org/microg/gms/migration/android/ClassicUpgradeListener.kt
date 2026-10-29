/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame.UpgradePathInfo
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ServiceAddress
import org.microg.gms.migration.protobuf.ByteString
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/** Initiator counterpart of [ClassicUpgradeConnector]: offers a Wi-Fi LAN upgrade path on
 * the device's current Wi-Fi IPv4 address and accepts the peer's one connection to it.
 * Listens only while an offer is open; never changes a network, radio or saved setting. */
internal class ClassicUpgradeListener(address: InetAddress) : Closeable {
    private val server = ServerSocket(0, 1, address)
    /** The UPGRADE_PATH_AVAILABLE path, as Nearby's WIFI_LAN initiator builds it. */
    val path: UpgradePathInfo = wifiLanPath(address, server.localPort)

    /** Waits at most [timeoutMs] for the peer's connection; null when none arrived yet. */
    fun poll(timeoutMs: Int): ClassicRadio.Link? {
        server.soTimeout = timeoutMs
        val socket = try { server.accept() } catch (_: SocketTimeoutException) { return null }
        return try {
            socket.soTimeout = 30000
            ClassicRadio.Link(socket.getInputStream(), socket.getOutputStream(), WIFI_LAN) { socket.close() }
        } catch (e: Exception) { socket.close(); throw e }
    }

    override fun close() = server.close()

    companion object {
        const val WIFI_LAN = 5

        /** Opens a listener on the current Wi-Fi IPv4 address, or null without one. */
        fun wifiLan(context: Context): ClassicUpgradeListener? {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            @Suppress("DEPRECATION")
            val addresses = manager.allNetworks.filter { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                .flatMap { manager.getLinkProperties(it)?.linkAddresses?.map { link -> link.address }.orEmpty() }
            return wifiLanAddress(addresses)?.let(::ClassicUpgradeListener)
        }

        /** Nearby's WIFI_LAN offer: no link-local, loopback, wildcard or multicast address. */
        internal fun wifiLanAddress(addresses: List<InetAddress>): Inet4Address? = addresses.filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress && !it.isMulticastAddress }

        /** Matches Nearby's ForBwuWifiLanPathAvailable: the IPv4 address and port in both the
         * legacy fields and the candidate list, and a CLIENT_INTRODUCTION_ACK promised. */
        internal fun wifiLanPath(address: InetAddress, port: Int): UpgradePathInfo {
            require(address is Inet4Address && port in 1..65535)
            val bytes = ByteString.copyFrom(address.address)
            return UpgradePathInfo.newBuilder().setMedium(UpgradePathInfo.Medium.WIFI_LAN)
                .setSupportsClientIntroductionAck(true)
                .setWifiLanSocket(UpgradePathInfo.WifiLanSocket.newBuilder().setIpAddress(bytes).setWifiPort(port)
                    .addAddressCandidates(ServiceAddress.newBuilder().setIpAddress(bytes).setPort(port)))
                .build()
        }
    }
}
