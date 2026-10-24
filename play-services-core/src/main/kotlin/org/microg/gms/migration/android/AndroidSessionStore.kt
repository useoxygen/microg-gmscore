/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import android.util.AtomicFile
import java.io.*

/** No-backup, per-UID retained session; bounded lifetime and atomic replacement.
 * Only a confirmed bootstrap producer writes. Never stores Google account tokens.
 * Files contain secrets and must not be copied into diagnostics or logged. */
internal class AndroidSessionStore(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    private fun file(uid: Int): AtomicFile { require(uid >= 0); return AtomicFile(File(directory,"session-$uid")) }
    fun clear(uid: Int) = synchronized(LOCK) { file(uid).delete() }
    fun load(uid: Int): AndroidPostSetupState = synchronized(LOCK) {
        var expiryTime = 0L
        val retained = runCatching {
            val f = file(uid); require(f.baseFile.length() in 1..256)
            DataInputStream(f.openRead()).use { input ->
                require(input.readInt() == 1)
                val expiry = input.readLong(); require(expiry > now() && expiry - now() <= TTL)
                expiryTime = expiry
                val id = input.readUTF(); require(id.toLong() > 0)
                val secret = ByteArray(32); input.readFully(secret); require(input.read() == -1)
                try { AndroidPostSetupState.VerifiedBootstrap(id,secret) } finally { secret.fill(0) }
            }
        }.getOrElse { clear(uid); null }
        AndroidPostSetupState(retained, { clear(uid) }, { now() < expiryTime })
    }
    fun commit(uid: Int, session: AndroidPostSetupState.VerifiedBootstrap) = synchronized(LOCK) {
        check(directory.exists() || directory.mkdirs())
        val hint = session.hint(); val secret = requireNotNull(hint.bytes(3)); val id = requireNotNull(hint.text(2)); require(id.toLong() > 0)
        val target = file(uid); val stream = target.startWrite()
        try {
            DataOutputStream(stream).apply { writeInt(1); writeLong(now()+TTL); writeUTF(id); write(secret); flush() }
            target.finishWrite(stream)
            check(target.baseFile.setReadable(false,false)); check(target.baseFile.setReadable(true,true))
            check(target.baseFile.setWritable(false,false)); check(target.baseFile.setWritable(true,true))
        } catch(e: Throwable) { target.failWrite(stream); target.delete(); throw e }
        finally { secret.fill(0); session.close() }
    }
    companion object {
        // Activity and service use different store instances in the same process.
        private val LOCK=Any()
        // GMS26.41.31 keev.n: shared_secret_valid_seconds=432000.
        const val TTL = 432000L*1000 }
}
