/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.security

import java.security.GeneralSecurityException
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.ManagerFactoryParameters
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Uses the OS implementation of the prerelease SPAKE2+ TLS extension. Never falls back to certificate TLS. */
internal class PlatformPakeTls private constructor(val client: SSLContext, val server: SSLContext) {
    companion object {
        fun create(sessionId: String, password: String, handshakeLimit: Int = 24): PlatformPakeTls {
            require(sessionId.length == 6 && password.length == 6) { "Invalid DCT credentials" }
            require(handshakeLimit in 1..24)
            try {
                val optionClass = Class.forName("android.net.ssl.PakeOption")
                val optionBuilder = Class.forName("android.net.ssl.PakeOption\$Builder")
                val option = optionBuilder.getConstructor(String::class.java).newInstance("SPAKE2PLUS_PRERELEASE")
                val add = optionBuilder.getMethod("addMessageComponent", String::class.java, ByteArray::class.java)
                val components = mapOf(
                    "password" to password.toByteArray(Charsets.UTF_8),
                    "context" to "os-migration".toByteArray(Charsets.UTF_8),
                    "server-handshake-limit" to byteArrayOf(handshakeLimit.toByte()),
                    "client-handshake-limit" to byteArrayOf(handshakeLimit.toByte())
                )
                components.forEach { (name, value) -> add.invoke(option, name, value) }
                val builtOption = optionBuilder.getMethod("build").invoke(option)
                val serverId = sessionId.toByteArray(Charsets.UTF_8)
                val clientId = byteArrayOf()
                val clientBuilder = Class.forName("android.net.ssl.PakeClientKeyManagerParameters\$Builder")
                val clientParams = clientBuilder.getConstructor().newInstance()
                clientBuilder.getMethod("setClientId", ByteArray::class.java).invoke(clientParams, clientId)
                clientBuilder.getMethod("setServerId", ByteArray::class.java).invoke(clientParams, serverId)
                clientBuilder.getMethod("addOption", optionClass).invoke(clientParams, builtOption)
                val serverBuilder = Class.forName("android.net.ssl.PakeServerKeyManagerParameters\$Builder")
                val serverParams = serverBuilder.getConstructor().newInstance()
                serverBuilder.getMethod("setOptions", ByteArray::class.java, ByteArray::class.java, List::class.java)
                    .invoke(serverParams, clientId, serverId, listOf(builtOption))
                val trust = TrustManagerFactory.getInstance("PAKE")
                trust.init(null as ManagerFactoryParameters?)
                fun context(params: ManagerFactoryParameters): SSLContext {
                    val keys = KeyManagerFactory.getInstance("PAKE", trust.provider)
                    keys.init(params)
                    check(keys.keyManagers.isNotEmpty()) { "PAKE key manager unavailable" }
                    return SSLContext.getInstance("TLSv1.3", trust.provider).apply {
                        init(keys.keyManagers, trust.trustManagers, null)
                    }
                }
                return PlatformPakeTls(
                    context(clientBuilder.getMethod("build").invoke(clientParams) as ManagerFactoryParameters),
                    context(serverBuilder.getMethod("build").invoke(serverParams) as ManagerFactoryParameters)
                )
            } catch (e: ReflectiveOperationException) {
                throw GeneralSecurityException("Platform DCT PAKE API unavailable", e)
            }
        }
    }
}
