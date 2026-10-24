/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.Proxy
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Connect before wrapping: SSLSocketFactory.createSocket(address, port) has no connect deadline. */
internal object PakeProbeSocket {
    fun connect(tls: SSLContext, address: InetAddress, port: Int): SSLSocket {
        val transport = Socket(Proxy.NO_PROXY)
        try {
            transport.connect(InetSocketAddress(address, port), 5000)
            transport.soTimeout = 5000
            return (tls.socketFactory.createSocket(transport, address.hostAddress, port, true) as SSLSocket)
                .apply { soTimeout = 5000 }
        } catch (error: Throwable) {
            transport.close()
            throw error
        }
    }
}
