/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest

/** Stored only under account-bound AEAD. No shared fido.db rows are migrated into a Google account. */
class PasskeyState(val parent: String? = null, val records: List<ByteArray> = emptyList(), val fetchedMillis: Long = 0,
    val pending: ByteArray? = null, val pendingLaunched: Boolean = false, val abandoned: List<ByteArray> = emptyList(),
    val ownerMutation: PasskeyOwnerMutation? = null, val ownerHistory: List<ByteArray> = emptyList()) {
    fun encode(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun append(bytes: ByteArray) { if (bytes.size > Proto.MAX_BYTES - out.size()) throw ProtocolException(); out.write(bytes) }
        // Older writers refuse request-bound secret mutation authority.
        append(Proto.integer(1, 3)); parent?.let { append(Proto.field(2, it)) }
        records.forEach { append(Proto.field(3, it)) }
        append(Proto.integer(4, fetchedMillis)); pending?.let { append(Proto.field(5, it)) }
        append(Proto.integer(6, if (pendingLaunched) 1 else 0)); abandoned.forEach { append(Proto.field(7, it)) }
        ownerMutation?.let { append(Proto.field(8, it.encode())) }; ownerHistory.forEach { append(Proto.field(9, it)) }
        return out.toByteArray()
    }
    fun ownerState(mutation: PasskeyOwnerMutation?, records: List<ByteArray> = this.records,
        history: List<ByteArray> = ownerHistory): PasskeyState = PasskeyState(parent, records, fetchedMillis, pending,
        pendingLaunched, abandoned, mutation, history)
    override fun toString(): String = "PasskeyState[redacted]"
    companion object {
        fun decode(raw: ByteArray): PasskeyState {
            if (raw.isEmpty()) return PasskeyState()
            val p = Proto.decode(raw)
            if (p.integer(1) !in setOf(1L, 2L, 3L) || p.bytes(3).size > 10_000 || p.integer(4)?.let { it < 0 } == true ||
                p.integer(6) !in setOf(0L, 1L)) throw ProtocolException()
            if (p.integer(1) == 1L && (p.optional(8) != null || p.bytes(9).isNotEmpty())) throw ProtocolException()
            if (p.integer(1) != 3L && p.optional(8)?.let { PasskeyOwnerMutation.decode(it).kind == PasskeyOwnerKind.SECRET_UPDATE } == true) throw ProtocolException()
            return PasskeyState(p.optionalString(2)?.let(PasswordApi::checkedParent), p.bytes(3), p.integer(4) ?: 0,
                p.optional(5), p.integer(6) == 1L, p.bytes(7).also { if (it.size > 1000) throw ProtocolException() },
                p.optional(8)?.let(PasskeyOwnerMutation::decode), p.bytes(9).also { if (it.size > 1000) throw ProtocolException() })
        }
    }
}

/** A complete authenticated snapshot precedes replacement or reconciliation of an uncertain create. */
object PasskeySnapshots {
    /** Validate local state, then read through a complete fresh snapshot; an unrelated cached RP cannot hide additions. */
    fun candidates(cached: () -> List<SyncedPasskey>, fetch: () -> List<SyncedPasskey>): List<SyncedPasskey> {
        try { cached() } catch (_: VaultResetException) { /* A new birthday can replace a cache with no unresolved create. */ }
        return fetch()
    }
    fun reconcile(previous: PasskeyState, parent: String, records: List<ByteArray>, keys: VaultKeys, now: Long): PasskeyState {
        PasswordApi.checkedParent(parent)
        if (now < 0 || records.size > 10_000) throw ProtocolException()
        if (previous.parent != null && previous.parent != parent && (previous.pending != null || previous.ownerMutation != null))
            throw VaultResetException()
        val decoded = records.map { PasskeyResourceCodec.read(it, keys) }
        SyncedPasskey.visible(decoded) // Duplicate sync IDs and ambiguous identities cannot enter the cache silently.
        val pending = previous.pending?.let { PasskeyResourceCodec.read(it, keys) }
        val confirmed = pending?.let { selected -> decoded.singleOrNull { it.tag == selected.tag }?.sameRegistration(selected) } == true
        val replacement = PasskeyState(parent, records.map { it.copyOf() }, now, if (confirmed) null else previous.pending,
            !confirmed && previous.pendingLaunched, previous.abandoned, previous.ownerMutation, previous.ownerHistory)
        return PasskeyOwnerMutations.reconcile(replacement, keys)
    }
}
