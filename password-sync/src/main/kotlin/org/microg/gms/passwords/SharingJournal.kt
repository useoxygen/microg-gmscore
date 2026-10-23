/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest
import java.util.UUID

enum class SharingOperationKind { SEND, ACCEPT, DISMISS, AUTOMATIC }
enum class SharingOperationPhase {
    PREPARED, IMPORTED, LAUNCHED, INDETERMINATE, REFUSED, COMPLETE, OWNER_RESOLVED;
    val terminal get() = this in setOf(REFUSED, COMPLETE, OWNER_RESOLVED)
}

class SharingOperation(val id: String, val kind: SharingOperationKind, val phase: SharingOperationPhase, val parent: String,
    val source: String, val sourceDigest: ByteArray, val request: ByteArray, val passwords: List<ByteArray> = emptyList(),
    val receipt: ByteArray? = null, val ownerResolvedAtMillis: Long? = null, val invalidPasswordCount: Int = 0,
    val invitationGuid: String? = null) {
    fun withPhase(value: SharingOperationPhase, receipt: ByteArray? = this.receipt) =
        SharingOperation(id, kind, value, parent, source, sourceDigest, request, passwords, receipt, ownerResolvedAtMillis, invalidPasswordCount, invitationGuid)
    fun encode(): ByteArray = Proto.field(1, id) + Proto.integer(2, kind.ordinal.toLong()) + Proto.integer(3, phase.ordinal.toLong()) +
        Proto.field(4, parent) + Proto.field(5, source) + Proto.field(6, sourceDigest) + Proto.field(7, request) +
        passwords.fold(byteArrayOf()) { out, item -> out + Proto.field(8, item) } + (receipt?.let { Proto.field(9, it) } ?: byteArrayOf()) +
        (ownerResolvedAtMillis?.let { Proto.integer(10, it) } ?: byteArrayOf()) + Proto.integer(11, invalidPasswordCount.toLong()) +
        (invitationGuid?.let { Proto.field(12, it) } ?: byteArrayOf())
    override fun toString() = "SharingOperation[redacted]"
    companion object {
        fun decode(raw: ByteArray): SharingOperation {
            val proto = Proto.decode(raw)
            val kind = SharingOperationKind.values().getOrNull(proto.integer(2)?.toInt() ?: -1) ?: throw ProtocolException()
            val phase = SharingOperationPhase.values().getOrNull(proto.integer(3)?.toInt() ?: -1) ?: throw ProtocolException()
            val parent = PasswordApi.checkedParent(proto.string(4))
            val source = proto.string(5); val digest = proto.one(6); val request = proto.one(7)
            val passwords = proto.bytes(8)
            if (runCatching { UUID.fromString(proto.string(1)) }.isFailure || source.isEmpty() || source.length > 4096 ||
                source.any(Char::isISOControl) || digest.size != 32 || request.size > 1024 * 1024 || passwords.size > 100) throw ProtocolException()
            passwords.forEach { PasswordData.decode(Proto.decode(it).one(1)) }
            if (kind !in setOf(SharingOperationKind.ACCEPT, SharingOperationKind.AUTOMATIC) && passwords.isNotEmpty()) throw ProtocolException()
            if (kind == SharingOperationKind.ACCEPT && passwords.isEmpty()) throw ProtocolException()
            val invalid = proto.integer(11) ?: 0
            if (invalid !in 0L..100L || kind != SharingOperationKind.AUTOMATIC && invalid != 0L || passwords.size + invalid > 100) throw ProtocolException()
            val guid = proto.optionalString(12)
            if (guid != null && (guid.length > 128 || runCatching { UUID.fromString(guid) }.isFailure)) throw ProtocolException()
            val resolvedAt = proto.integer(10)
            if (phase == SharingOperationPhase.OWNER_RESOLVED && (kind != SharingOperationKind.SEND || resolvedAt == null || resolvedAt <= 0) ||
                phase != SharingOperationPhase.OWNER_RESOLVED && resolvedAt != null) throw ProtocolException()
            return SharingOperation(proto.string(1), kind, phase, parent, source, digest, request, passwords, proto.optional(9), resolvedAt, invalid.toInt(), guid)
        }
        fun digest(raw: ByteArray) = MessageDigest.getInstance("SHA-256").digest(raw)
    }
}

/** Account-bound journal. A launched send can never be replayed after a crash or uncertain receipt. */
class SharingJournal(private val store: VaultStore) {
    fun operations(): List<SharingOperation> = store.locked { session -> read(session.load().sharingJournal) }
    fun get(id: String) = operations().singleOrNull { it.id == id } ?: throw ConflictException()
    fun prepare(operation: SharingOperation): String = store.locked { session ->
        if (operation.phase != SharingOperationPhase.PREPARED) throw ProtocolException()
        val state = session.load(); val all = read(state.sharingJournal).toMutableList()
        fun terminal(item: SharingOperation) = item.phase.terminal
        while (all.count(::terminal) > 32 || all.size >= 128 && all.any(::terminal)) {
            all.removeAt(all.indexOfFirst(::terminal))
        }
        if (all.size >= 128 || all.any { it.id == operation.id || it.source == operation.source &&
                !it.phase.terminal }) throw PendingMutationException()
        all.add(operation); state.sharingJournal = encode(all); session.save(state); operation.id
    }
    fun transition(id: String, expected: Set<SharingOperationPhase>, phase: SharingOperationPhase, receipt: ByteArray? = null): SharingOperation =
        store.locked { session ->
            val state = session.load(); val all = read(state.sharingJournal).toMutableList()
            val index = all.indexOfFirst { it.id == id }; if (index < 0) throw ConflictException()
            val old = all[index]
            if (old.phase !in expected || old.phase.terminal || phase == SharingOperationPhase.OWNER_RESOLVED ||
                old.phase in setOf(SharingOperationPhase.LAUNCHED, SharingOperationPhase.INDETERMINATE) &&
                phase !in setOf(SharingOperationPhase.INDETERMINATE, SharingOperationPhase.REFUSED, SharingOperationPhase.COMPLETE) ||
                old.kind == SharingOperationKind.SEND && old.phase != SharingOperationPhase.PREPARED &&
                phase !in setOf(SharingOperationPhase.INDETERMINATE, SharingOperationPhase.REFUSED, SharingOperationPhase.COMPLETE)) throw PendingMutationException()
            val updated = old.withPhase(phase, receipt ?: old.receipt)
            all[index] = updated; state.sharingJournal = encode(all); session.save(state); updated
        }
    /** Releases only the duplicate fence. The old request and receipt remain closed and cannot be replayed. */
    fun resolveUnknownSend(id: String, acknowledgesPossibleDelivery: Boolean, nowMillis: Long, authorize: () -> Unit): SharingOperation =
        store.locked { session ->
            if (!acknowledgesPossibleDelivery || nowMillis <= 0) throw ConflictException()
            val state = session.load(); val all = read(state.sharingJournal).toMutableList()
            val index = all.indexOfFirst { it.id == id }; if (index < 0) throw ConflictException()
            val old = all[index]
            if (old.kind != SharingOperationKind.SEND || old.phase !in setOf(SharingOperationPhase.PREPARED, SharingOperationPhase.LAUNCHED, SharingOperationPhase.INDETERMINATE))
                throw PendingMutationException()
            authorize()
            val resolved = SharingOperation(old.id, old.kind, SharingOperationPhase.OWNER_RESOLVED, old.parent, old.source,
                old.sourceDigest, old.request, old.passwords, old.receipt, nowMillis)
            all[index] = resolved; state.sharingJournal = encode(all); session.save(state); resolved
        }
    private fun encode(all: List<SharingOperation>): ByteArray = all.fold(Proto.integer(1, 1)) { out, item -> out + Proto.field(2, item.encode()) }
    private fun read(raw: ByteArray?): List<SharingOperation> {
        if (raw == null) return emptyList()
        val proto = Proto.decode(raw)
        if (proto.integer(1) != 1L || proto.bytes(2).size > 128) throw ProtocolException()
        return proto.bytes(2).map(SharingOperation::decode).also { if (it.map { item -> item.id }.toSet().size != it.size) throw ProtocolException() }
    }
}
