/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import org.microg.gms.migration.security.PlatformPakeTls
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

internal object PakeNegativeProbe {
    fun run() {
        val tls = PlatformPakeTls.create("abc234", "def567")
        val clients = listOf(PlatformPakeTls.create("abc234", "xyz234").client,
            PlatformPakeTls.create("xyz234", "def567").client, SSLContext.getInstance("TLSv1.3").apply { init(null, null, null) })
        clients.forEach { client ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                (tls.server.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket).use { server ->
                    server.soTimeout = 5000
                    val accepted = executor.submit<Boolean> {
                        (server.accept() as SSLSocket).use { socket -> socket.soTimeout = 5000
                            try { socket.startHandshake(); true } catch (e: javax.net.ssl.SSLException) { false }
                        }
                    }
                    (client.socketFactory.createSocket(InetAddress.getLoopbackAddress(), server.localPort) as SSLSocket).use { socket ->
                        socket.soTimeout = 5000
                        val passed = try { socket.startHandshake(); true } catch (e: javax.net.ssl.SSLException) { false }
                        check(!passed && !accepted.get(6, TimeUnit.SECONDS))
                    }
                }
            } finally { executor.shutdownNow() }
        }
    }
}
