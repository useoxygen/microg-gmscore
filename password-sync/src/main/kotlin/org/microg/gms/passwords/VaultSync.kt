/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.util.UUID

enum class MutationKind { CREATE, UPDATE, DELETE }
enum class MutationPhase { QUEUED, LAUNCHED, INDETERMINATE, CONFLICT }

/** All payload fields must be persisted under the phone's account-bound AEAD key. */
class Mutation(
    val id: String,
    val kind: MutationKind,
    val tag: String,
    val name: String?,
    val baseVersion: ByteArray?,
    val desired: ByteArray?,
    val phase: MutationPhase = MutationPhase.QUEUED
) {
    fun phase(value: MutationPhase) = Mutation(id, kind, tag, name, baseVersion, desired, value)
    fun encode(): ByteArray = Proto.field(1, id) + Proto.integer(2, kind.ordinal.toLong()) + Proto.field(3, tag) +
        (name?.let { Proto.field(4, it) } ?: byteArrayOf()) + (baseVersion?.let { Proto.field(5, it) } ?: byteArrayOf()) +
        (desired?.let { Proto.field(6, it) } ?: byteArrayOf()) + Proto.integer(7, phase.ordinal.toLong())
    override fun toString(): String = "Mutation[redacted]"
    companion object {
        fun decode(data: ByteArray): Mutation = Proto.decode(data).let { value ->
            val kind = MutationKind.values().getOrNull(value.integer(2)?.toInt() ?: -1) ?: throw ProtocolException()
            val phase = MutationPhase.values().getOrNull(value.integer(7)?.toInt() ?: -1) ?: throw ProtocolException()
            Mutation(value.string(1), kind, value.string(3), value.optionalString(4), value.optional(5), value.optional(6), phase)
        }
    }
}

class VaultState(
    var metadata: ByteArray? = null,
    val records: MutableList<ByteArray> = ArrayList(),
    val mutations: MutableList<Mutation> = ArrayList(),
    var lastSyncMillis: Long = 0,
    val chromeLocalData: MutableMap<String, ByteArray> = HashMap(),
    var customKey: ByteArray? = null,
    var pendingMetadata: ByteArray? = null,
    val preferences: MutableList<ByteArray> = ArrayList(),
    val preferenceMutations: MutableList<Mutation> = ArrayList(),
    var preferenceParent: String? = null,
    var deviceRegistration: DeviceRegistration? = null,
    var invalidations: SyncInvalidations = SyncInvalidations()
) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        fun append(value: ByteArray) {
            // Reject before durable replacement: saving a state the decoder cannot reopen
            // would strand its offline mutations. Streaming also avoids copying every prefix.
            if (value.size > Proto.MAX_BYTES - out.size()) throw ProtocolException()
            out.write(value, 0, value.size)
        }
        // Earlier writers must refuse unknown durable registration/journal fields rather
        // than erase them on their next save. v4 also fences durable refresh obligations.
        append(Proto.integer(1, 4))
        metadata?.let { append(Proto.field(2, it)) }
        records.forEach { append(Proto.field(3, it)) }
        mutations.forEach { append(Proto.field(4, it.encode())) }
        append(Proto.integer(5, lastSyncMillis))
        chromeLocalData.forEach { (tag, value) -> append(Proto.field(6, Proto.field(1, tag) + Proto.field(2, value))) }
        customKey?.let { append(Proto.field(7, it)) }
        pendingMetadata?.let { append(Proto.field(8, it)) }
        preferences.forEach { append(Proto.field(9, it)) }
        preferenceMutations.forEach { append(Proto.field(10, it.encode())) }
        preferenceParent?.let { append(Proto.field(11, it)) }
        deviceRegistration?.let { append(Proto.field(12, it.encode())) }
        append(Proto.field(13, invalidations.encode()))
        return out.toByteArray()
    }
    override fun toString(): String = "VaultState[redacted]"
    companion object {
        fun decode(data: ByteArray): VaultState = Proto.decode(data).let { proto ->
            if (proto.integer(1) !in setOf(1L, 2L, 3L, 4L)) throw ProtocolException()
            val state = VaultState(proto.optional(2), proto.bytes(3).toMutableList(),
                proto.bytes(4).map(Mutation::decode).toMutableList(), proto.integer(5) ?: 0)
            proto.bytes(6).forEach { raw -> Proto.decode(raw).let { state.chromeLocalData[it.string(1)] = it.one(2) } }
            state.customKey = proto.optional(7)
            state.pendingMetadata = proto.optional(8)
            state.preferences.addAll(proto.bytes(9))
            state.preferenceMutations.addAll(proto.bytes(10).map(Mutation::decode))
            state.preferenceParent = proto.optionalString(11)
            state.deviceRegistration = proto.optional(12)?.let(DeviceRegistration::decode)
            state.invalidations = proto.optional(13)?.let(SyncInvalidations::decode) ?: SyncInvalidations()
            if (state.mutations.map { it.id }.toSet().size != state.mutations.size) throw ProtocolException()
            if (state.preferenceMutations.map { it.id }.toSet().size != state.preferenceMutations.size) throw ProtocolException()
            state
        }
    }
}

interface VaultSession { fun load(): VaultState; fun save(state: VaultState) }
interface VaultStore { fun <T> locked(action: (VaultSession) -> T): T }

/** Network execution is serialized per account, with a durable launch fence before every mutation. */
class VaultSync(private val api: PasswordApi, private val store: VaultStore, private val device: DeviceTokenProvider? = null,
    private val now: () -> Long = System::currentTimeMillis) {
    fun enqueue(kind: MutationKind, tag: String, desired: ByteArray? = null): String = store.locked { session ->
        enqueue(session, kind, tag, desired)
    }

    /** Caller may include local Chrome metadata in the same durable transaction. */
    fun enqueue(session: VaultSession, kind: MutationKind, tag: String, desired: ByteArray? = null): String {
        val state = session.load()
        val pending = state.mutations.singleOrNull { it.tag == tag }
        if (pending != null && pending.phase != MutationPhase.QUEUED) throw PendingMutationException()
        val existing = state.records.singleOrNull { Proto.decode(it).optionalString(2) == tag }
        if (pending?.kind == MutationKind.CREATE && kind == MutationKind.DELETE) {
            state.mutations.remove(pending)
            state.chromeLocalData.remove(tag)
            session.save(state)
            return pending.id
        }
        when (kind) {
            MutationKind.CREATE -> if (existing != null || desired == null) throw ConflictException()
            MutationKind.UPDATE -> if (existing == null || desired == null) throw ConflictException()
            MutationKind.DELETE -> if (existing == null || desired != null) throw ConflictException()
        }
        if (desired != null && Proto.decode(desired).string(2) != tag) throw ProtocolException()
        val old = existing?.let(Proto::decode)
        if (kind == MutationKind.UPDATE) {
            val proposed = Proto.decode(desired!!)
            if (proposed.string(1) != old!!.string(1) || !equal(proposed.optional(5), old.optional(5))) throw ConflictException()
        }
        val operation = Mutation(pending?.id ?: UUID.randomUUID().toString(), kind, tag, old?.string(1), old?.optional(5), desired)
        if (pending != null) state.mutations.remove(pending)
        state.mutations.add(operation)
        session.save(state)
        return operation.id
    }

    fun synchronize(unlock: (ByteArray) -> VaultKeys): SyncReceipt = retryStaleMetadata { _, progress ->
        synchronizeOnce(unlock, progress)
    }

    private fun synchronizeOnce(unlock: (ByteArray) -> VaultKeys, progress: SyncProgress): SyncReceipt = store.locked { session ->
        var state = session.load()
        val metadata = api.metadata()
        val parent = PasswordApi.checkedParent(Proto.decode(metadata).string(1))
        val oldParent = state.metadata?.let { Proto.decode(it).string(1) }
        if (oldParent != null && oldParent != parent && state.mutations.isNotEmpty()) throw VaultResetException()
        val keys = try { unlock(metadata) } catch (failure: VaultLockedException) {
            // Keep the previous authenticated cache while making new encryption metadata available to owner recovery.
            state.pendingMetadata = metadata
            session.save(state)
            throw failure
        }
        try {
            val records = snapshot(parent)
            // Complete and authenticate the snapshot before replacing durable data or inferring deletions.
            records.forEach { entity ->
                val proto = Proto.decode(entity)
                PasswordApi.checkedName(proto.string(1))
                if (!proto.string(1).startsWith(parent + "/passwords/")) throw ProtocolException()
                PasswordResourceCodec.plaintext(entity, keys)
            }
            state.metadata = metadata
            state.pendingMetadata = null
            state.records.clear(); state.records.addAll(records)
            state.lastSyncMillis = now()
            state.invalidations = state.invalidations.acknowledgePasswords()
            session.save(state)
            val clientToken = device?.token(session, state, parent) ?: byteArrayOf()
            for (pending in state.mutations.toList()) {
                val index = state.mutations.indexOfFirst { it.id == pending.id }
                if (index < 0 || pending.phase == MutationPhase.CONFLICT) continue
                val remote = state.records.singleOrNull { Proto.decode(it).string(2) == pending.tag }
                if (pending.phase != MutationPhase.QUEUED) {
                    // A response can be lost after Google commits. Reconcile; never replay an uncertain effect.
                    val applied = when (pending.kind) {
                        MutationKind.DELETE -> remote == null
                        else -> remote != null && samePayload(keys, remote, pending.desired ?: throw ProtocolException())
                    }
                    if (applied) state.mutations.removeAt(index)
                    else state.mutations[index] = pending.phase(MutationPhase.INDETERMINATE)
                    session.save(state)
                    continue
                }
                val compatible = when (pending.kind) {
                    MutationKind.CREATE -> remote == null
                    else -> remote != null && Proto.decode(remote).string(1) == pending.name &&
                        equal(Proto.decode(remote).optional(5), pending.baseVersion)
                }
                if (!compatible) {
                    state.mutations[index] = pending.phase(MutationPhase.CONFLICT)
                    session.save(state)
                    continue
                }
                // Complete local validation/crypto before recording a potentially launched effect.
                val outgoing = pending.desired?.let { reencrypt(keys, it) }
                state.mutations[index] = pending.phase(MutationPhase.LAUNCHED)
                session.save(state)
                try {
                    val response = when (pending.kind) {
                        MutationKind.CREATE -> api.create(parent, outgoing!!, clientToken)
                        MutationKind.UPDATE -> api.update(outgoing!!, clientToken)
                        MutationKind.DELETE -> { api.delete(pending.name!!, clientToken); null }
                    }
                    if (response != null) {
                        val proto = Proto.decode(response)
                        if (!proto.string(1).startsWith(parent + "/passwords/") || proto.string(2) != pending.tag ||
                            !samePayload(keys, response, pending.desired!!)) throw ProtocolException()
                    }
                    state.records.removeAll { Proto.decode(it).string(2) == pending.tag }
                    if (response != null) state.records.add(response)
                    state.mutations.removeAll { it.id == pending.id }
                    session.save(state)
                    progress.uploaded++
                } catch (failure: Exception) {
                    // Saving the launch fence succeeded; even a local failure now requires reconciliation.
                    state = session.load()
                    val position = state.mutations.indexOfFirst { it.id == pending.id }
                    if (position >= 0) {
                        state.mutations[position] = pending.phase(
                            if (failure is PasswordRpcException && !failure.outcomeUnknown) {
                                if (failure.code in setOf(6, 10)) MutationPhase.CONFLICT else MutationPhase.QUEUED
                            } else MutationPhase.INDETERMINATE)
                        session.save(state)
                    }
                    throw failure
                }
            }
            // Readback remains necessary for eventual deletes and server-side normalization.
            SyncReceipt(state.records.size, progress.uploaded, state.mutations.count { it.phase == MutationPhase.CONFLICT },
                state.mutations.count { it.phase == MutationPhase.INDETERMINATE || it.phase == MutationPhase.LAUNCHED })
        } finally { keys.destroy() }
    }

    private fun snapshot(parent: String): List<ByteArray> {
        val entries = ArrayList<ByteArray>()
        var bytes = 0L
        val tokens = HashSet<String>()
        var token: ByteArray? = null
        repeat(256) {
            val response = Proto.decode(api.list(parent, token))
            val page = response.bytes(1)
            entries.addAll(page)
            bytes += page.sumOf { it.size.toLong() + 16 }
            if (entries.size > 100_000 || bytes > Proto.MAX_BYTES / 2) throw ProtocolException()
            token = response.optional(2)?.takeIf { it.isNotEmpty() }
            if (token == null) {
                if (entries.map { Proto.decode(it).string(2) }.toSet().size != entries.size) throw ProtocolException()
                return entries
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(token).joinToString("") { "%02x".format(it.toInt() and 255) }
            if (!tokens.add(digest)) throw ProtocolException()
        }
        throw ProtocolException()
    }

    private fun samePayload(keys: VaultKeys, left: ByteArray, right: ByteArray): Boolean {
        val a = PasswordResourceCodec.plaintext(left, keys).encode()
        val b = PasswordResourceCodec.plaintext(right, keys).encode()
        return try { MessageDigest.isEqual(a, b) } finally { a.fill(0); b.fill(0) }
    }
    private fun reencrypt(keys: VaultKeys, entity: ByteArray): ByteArray {
        return PasswordResourceCodec.reencrypt(entity, keys)
    }
    private fun equal(a: ByteArray?, b: ByteArray?): Boolean = a != null && b != null && MessageDigest.isEqual(a, b)
}

data class SyncReceipt(val downloaded: Int, val uploaded: Int, val conflicts: Int, val indeterminate: Int)
class ConflictException : Exception("Password changed remotely; review required")
class PendingMutationException : Exception("A password change is already pending")
class VaultResetException : Exception("Remote password vault changed; pending changes require review")
