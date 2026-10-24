/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import org.microg.gms.migration.nearby.DctRecord
import java.io.Closeable

/** Per-client state. Only a verified bootstrap producer may construct a retained session.
 * The Binder API cannot install a secret or turn an arbitrary hint into authenticated state. */
internal class AndroidPostSetupState(private var session: VerifiedBootstrap? = null) : Closeable {
    internal class VerifiedBootstrap(val id: String, sharedSecret: ByteArray) : Closeable {
        private val secret = sharedSecret.copyOf().also { require(it.size == 32) }
        private var closed = false
        init { require(id.isNotEmpty() && id.length <= 64) }
        fun handshake(): PostSetupTargetHandshake { check(!closed); return PostSetupTargetHandshake(secret) }
        fun hint(): DctRecord { check(!closed); return DctRecord(AndroidSetupSchemas.hint, mapOf(2 to id, 3 to secret.copyOf())) }
        override fun close() { closed = true; secret.fill(0) }
    }
    private var handshake: PostSetupTargetHandshake? = null
    @Synchronized fun hint(): DctRecord? = session?.hint()
    @Synchronized fun start(): ByteArray? {
        val retained = session ?: return null
        handshake?.close()
        val next = retained.handshake()
        return try { next.start().also { handshake = next } }
        catch (e: Exception) { next.close(); throw e }
    }
    @Synchronized fun finish(message: ByteArray): Pair<ByteArray, ByteArray>? {
        if (session == null) return null
        return checkNotNull(handshake) { "Post-setup handshake not started" }.finish(message)
    }
    @Synchronized fun authenticate(message: ByteArray, hmac: ByteArray): ByteArray? {
        if (session == null) return null
        val active = checkNotNull(handshake) { "Post-setup handshake not started" }
        return try { active.authenticate(message, hmac) } finally { active.close(); handshake = null }
    }
    @Synchronized override fun close() {
        handshake?.close(); handshake = null
        session?.close(); session = null
    }
}

internal object AndroidSetupSchemas {
    private val B = DctRecord.Kind.Bytes
    val hint = mapOf(2 to DctRecord.Kind.Text, 3 to B)
    val handshake = mapOf(2 to B, 3 to B)
    val auth = mapOf(2 to B)
}
