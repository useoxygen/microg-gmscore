/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import org.microg.gms.auth.credentials.provider.PublicKeyProxyActivity
import java.security.MessageDigest
import java.util.UUID

/** Only an opaque, one-use handle crosses the mutable Credential Manager PendingIntent boundary. */
internal class PasskeyRequestTickets(private val context: Context) {
    private val store = AndroidPasskeyStore(context, "provider-tickets", {})
    fun issue(vault: SyncedPasskeyVault, tag: String?, rp: String, caller: ByteArray, request: String, create: Boolean): PendingIntent {
        vault.check()
        val nonce = UUID.randomUUID().toString()
        val now = SystemClock.elapsedRealtime()
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toLong()
        if (boot < 0) throw VaultLockedException()
        val ticket = Proto.field(1, vault.account.name) + Proto.field(2, vault.accountId) +
            (tag?.let { Proto.field(3, it) } ?: byteArrayOf()) + Proto.field(4, SyncedPasskey.checkedRp(rp)) +
            Proto.field(5, caller) + Proto.field(6, digest(request)) + Proto.integer(7, if (create) 1 else 0) +
            Proto.integer(8, now) + Proto.integer(9, boot) + Proto.field(10, vault.parent())
        store.locked { session ->
            val raw = session.load()
            val entries = try { Proto.decode(raw).bytes(1).map(Proto::decode).filter { fresh(Proto.decode(it.one(2)), now, boot) } }
                finally { raw.fill(0) }
            if (entries.size >= 256) throw ProtocolException()
            val output = entries.fold(byteArrayOf()) { out, value -> out + Proto.field(1, value.encode()) } +
                Proto.field(1, Proto.field(1, nonce) + Proto.field(2, ticket))
            try { session.save(output) } finally { output.fill(0) }
        }
        val intent = Intent(context, PublicKeyProxyActivity::class.java).setData(Uri.parse("microg-passkey:/request/$nonce"))
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT)
    }
    fun consume(uri: Uri?, caller: ByteArray, request: String, create: Boolean): Proto = store.locked { session ->
        if (uri?.scheme != "microg-passkey" || uri.pathSegments?.size != 2 || uri.pathSegments[0] != "request") throw SecurityException()
        val nonce = uri.pathSegments[1]
        val raw = session.load()
        val entries = try { Proto.decode(raw).bytes(1).map(Proto::decode) } finally { raw.fill(0) }
        val selected = entries.singleOrNull { it.string(1) == nonce } ?: throw SecurityException()
        val remainder = entries.filter { it.string(1) != nonce }.fold(byteArrayOf()) { out, item -> out + Proto.field(1, item.encode()) }
        try { session.save(remainder) } finally { remainder.fill(0) }
        val ticket = Proto.decode(selected.one(2))
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toLong()
        if (!fresh(ticket, SystemClock.elapsedRealtime(), boot) || ticket.integer(7) != (if (create) 1L else 0L) ||
            !MessageDigest.isEqual(caller, ticket.one(5)) || !MessageDigest.isEqual(digest(request), ticket.one(6))) throw SecurityException()
        ticket
    }
    private fun fresh(p: Proto, now: Long, boot: Long): Boolean = p.integer(9) == boot && boot >= 0 &&
        p.integer(8)?.let { now >= it && now - it < 120_000 } == true
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
