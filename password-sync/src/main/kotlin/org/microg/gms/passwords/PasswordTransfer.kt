/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest
import java.util.UUID

/** A stable account identity prevents a removed/re-added account inheriting an old transfer. */
class PasswordStoreIdentity(val accountName: String? = null, val userId: String? = null, val vaultParent: String? = null) {
    init {
        if ((accountName == null) != (userId == null) || accountName?.isBlank() == true || userId?.isBlank() == true || accountName == null && vaultParent != null) throw ProtocolException()
        vaultParent?.let(PasswordApi::checkedParent)
    }
    fun encode() = (accountName?.let { Proto.field(1, it) } ?: byteArrayOf()) + (userId?.let { Proto.field(2, it) } ?: byteArrayOf()) +
        (vaultParent?.let { Proto.field(3, it) } ?: byteArrayOf())
    fun same(other: PasswordStoreIdentity) = accountName == other.accountName && userId == other.userId && vaultParent == other.vaultParent
    override fun toString() = "PasswordStoreIdentity[redacted]"
    companion object { fun decode(raw: ByteArray) = Proto.decode(raw).let { PasswordStoreIdentity(it.optionalString(1), it.optionalString(2), it.optionalString(3)) } }
}

enum class PasswordTransferPhase { COPY, REMOVE_SOURCE }

/** All fields, including account labels, must live inside an account-bound encrypted store. */
class PasswordTransferOperation(val id: String, val source: PasswordStoreIdentity?, val destination: PasswordStoreIdentity,
    val original: ByteArray?, val desired: ByteArray, val phase: PasswordTransferPhase = PasswordTransferPhase.COPY) {
    fun withPhase(phase: PasswordTransferPhase) = PasswordTransferOperation(id, source, destination, original, desired, phase)
    fun encode() = Proto.field(1, id) + (source?.let { Proto.field(2, it.encode()) } ?: byteArrayOf()) +
        Proto.field(3, destination.encode()) + (original?.let { Proto.field(4, it) } ?: byteArrayOf()) +
        Proto.field(5, desired) + Proto.integer(6, phase.ordinal.toLong())
    override fun toString() = "PasswordTransferOperation[redacted]"
    companion object {
        fun decode(raw: ByteArray) = Proto.decode(raw).let { proto ->
            PasswordTransferOperation(proto.string(1), proto.optional(2)?.let(PasswordStoreIdentity::decode),
                PasswordStoreIdentity.decode(proto.one(3)), proto.optional(4), proto.one(5),
                PasswordTransferPhase.values().getOrNull(proto.integer(6)?.toInt() ?: -1) ?: throw ProtocolException()).also {
                if ((it.source == null) != (it.original == null)) throw ProtocolException()
                PasswordData.decode(Proto.decode(it.desired).one(1))
                it.original?.let { old -> PasswordData.decode(Proto.decode(old).one(1)) }
            }
        }
    }
}

interface PasswordTransferEndpoint {
    /** Must finish a complete authenticated snapshot, or throw. */
    fun refresh(): SyncReceipt
    /** Committed remote/local records only; never substitute an optimistic outgoing edit. */
    fun committed(tag: String): ByteArray?
    fun pending(tag: String): Boolean
    /** Atomic compare-and-insert, refusing an existing, different credential. */
    fun insertIfAbsent(chrome: ByteArray)
    /** Atomic compare-and-delete, refusing a changed source or any existing operation. */
    fun deleteIfUnchanged(chrome: ByteArray)
}

/** Owner-driven copy/verify/delete. An exception retains the encrypted journal and the source. */
class PasswordTransferEngine(private val journal: VaultStore,
    private val endpoint: (PasswordStoreIdentity) -> PasswordTransferEndpoint) {
    fun operations(): List<PasswordTransferOperation> = journal.locked { it.load().ownerTransfers.toList() }
    fun start(source: PasswordStoreIdentity?, destination: PasswordStoreIdentity, original: ByteArray?, desired: ByteArray): String = journal.locked { session ->
        if ((source == null) != (original == null)) throw ProtocolException()
        val data = PasswordData.decode(Proto.decode(desired).one(1))
        val old = original?.let { PasswordData.decode(Proto.decode(it).one(1)) }
        if (source?.same(destination) == true && old?.tag() == data.tag()) throw ConflictException()
        val state = session.load()
        if (state.ownerTransfers.size >= 1000) throw ProtocolException()
        if (state.ownerTransfers.any { it.destination.same(destination) && tag(it.desired) == data.tag() ||
                source != null && it.source?.same(source) == true && it.original?.let(::tag) == old?.tag() }) throw PendingMutationException()
        val id = UUID.randomUUID().toString()
        state.ownerTransfers.add(PasswordTransferOperation(id, source, destination, original?.copyOf(), desired.copyOf()))
        session.save(state)
        id
    }
    /** Resume is an explicit owner action, never scheduled automatically. */
    fun resume(id: String) = journal.locked { session ->
        val state = session.load()
        var operation = state.ownerTransfers.singleOrNull { it.id == id } ?: throw ConflictException()
        val destination = endpoint(operation.destination)
        val destinationTag = tag(operation.desired)
        destination.refresh()
        if (destination.pending(destinationTag)) throw PendingMutationException()
        var copied = destination.committed(destinationTag)
        if (copied == null) {
            if (operation.phase != PasswordTransferPhase.COPY) throw ConflictException()
            destination.insertIfAbsent(operation.desired)
            destination.refresh()
            if (destination.pending(destinationTag)) throw PendingMutationException()
            copied = destination.committed(destinationTag)
        }
        if (copied == null || !samePassword(copied, operation.desired)) throw ConflictException()
        operation = operation.withPhase(PasswordTransferPhase.REMOVE_SOURCE)
        state.ownerTransfers[state.ownerTransfers.indexOfFirst { it.id == id }] = operation
        session.save(state)
        operation.source?.let { identity ->
            val source = endpoint(identity)
            val original = operation.original ?: throw ProtocolException()
            val originalTag = tag(original)
            source.refresh()
            if (source.pending(originalTag)) throw PendingMutationException()
            val current = source.committed(originalTag)
            if (current != null) {
                if (!samePassword(current, original)) throw ConflictException()
                source.deleteIfUnchanged(original)
                source.refresh()
            }
            if (source.pending(originalTag) || source.committed(originalTag) != null) throw PendingMutationException()
        }
        // The payload remains in the journal if a destination changed during source deletion.
        destination.refresh()
        if (destination.pending(destinationTag) || destination.committed(destinationTag)?.let { samePassword(it, operation.desired) } != true)
            throw ConflictException()
        state.ownerTransfers.removeAll { it.id == id }
        session.save(state)
    }

    /** Dropping a journal never undoes or retries either vault's independently durable mutation. */
    fun drop(id: String) = journal.locked { session ->
        val state = session.load()
        if (!state.ownerTransfers.removeAll { it.id == id }) throw ConflictException()
        session.save(state)
    }

    companion object {
        private fun tag(raw: ByteArray) = PasswordData.decode(Proto.decode(raw).one(1)).tag()
        fun samePassword(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(Proto.decode(a).one(1), Proto.decode(b).one(1))
    }
}
