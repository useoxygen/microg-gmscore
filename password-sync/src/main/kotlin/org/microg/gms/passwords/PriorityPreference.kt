/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** PriorityPreference resource (GMS iakx); JSON values remain opaque and unknown fields survive edits. */
class PriorityPreference private constructor(private val proto: Proto) {
    val id: String? get() = proto.optionalString(1)
    val tag: String get() = proto.string(2)
    val version: ByteArray? get() = proto.optional(3)
    val name: String get() = proto.string(4)
    val value: String get() = proto.optionalString(5).orEmpty()
    fun encode(): ByteArray = proto.encode()
    fun withValue(value: String) = decode(proto.replace(5, checkedValue(value).toByteArray(Charsets.UTF_8)).encode())
    override fun toString(): String = "PriorityPreference[redacted]"
    companion object {
        fun decode(raw: ByteArray) = PriorityPreference(Proto.decode(raw)).also {
            checkedName(it.name); checkedValue(it.value)
            if (it.tag != tagFor(it.name)) throw ProtocolException()
        }
        fun create(name: String, value: String) = decode(Proto.field(2, tagFor(checkedName(name))) +
            Proto.field(4, name) + Proto.field(5, checkedValue(value)))
        fun tagFor(name: String): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(
            Proto.field(163425, byteArrayOf()) + PasswordData.escapePath(name).toByteArray(Charsets.UTF_8)))
        private fun checkedName(value: String): String = value.also {
            if (it.isEmpty() || it.length > 1024 || it.any(Char::isISOControl)) throw ProtocolException()
        }
        private fun checkedValue(value: String): String = value.also { if (it.toByteArray(Charsets.UTF_8).size > 64 * 1024) throw ProtocolException() }
    }
}

/** Separate journal: preference sync never needs the password cryptographer (GMS besa/besi.a). */
class PreferenceSync(private val api: PasswordApi, private val store: VaultStore, private val device: DeviceTokenProvider? = null) {
    fun enqueue(name: String, value: String): String = store.locked { session ->
        val state = session.load()
        if (state.preferenceParent == null) throw VaultLockedException()
        val tag = PriorityPreference.tagFor(name)
        val previous = state.preferenceMutations.singleOrNull { it.tag == tag }
        if (previous != null && previous.phase != MutationPhase.QUEUED) throw PendingMutationException()
        val remote = state.preferences.map(PriorityPreference::decode).singleOrNull { it.tag == tag }
        val desired = remote?.withValue(value) ?: PriorityPreference.create(name, value)
        val pending = Mutation(previous?.id ?: UUID.randomUUID().toString(),
            if (remote == null) MutationKind.CREATE else MutationKind.UPDATE, tag, remote?.id, remote?.version, desired.encode())
        if (previous != null) state.preferenceMutations.remove(previous)
        state.preferenceMutations.add(pending)
        session.save(state)
        pending.id
    }

    fun synchronize(parent: String): SyncReceipt = store.locked { session ->
        PasswordApi.checkedParent(parent)
        var state = session.load()
        if (state.preferenceParent != null && state.preferenceParent != parent && state.preferenceMutations.isNotEmpty()) throw VaultResetException()
        val snapshot = snapshot(parent)
        state.preferences.clear(); state.preferences.addAll(snapshot)
        state.preferenceParent = parent
        session.save(state)
        val clientToken = device?.token(session, state, parent) ?: byteArrayOf()
        var uploaded = 0
        for (pending in state.preferenceMutations.toList()) {
            val index = state.preferenceMutations.indexOfFirst { it.id == pending.id }
            if (index < 0 || pending.phase == MutationPhase.CONFLICT) continue
            if (pending.kind == MutationKind.DELETE) throw ProtocolException()
            val desired = PriorityPreference.decode(pending.desired ?: throw ProtocolException())
            val remote = state.preferences.map(PriorityPreference::decode).singleOrNull { it.tag == pending.tag }
            if (pending.phase != MutationPhase.QUEUED) {
                if (remote != null && sameValue(remote, desired)) state.preferenceMutations.removeAt(index)
                else state.preferenceMutations[index] = pending.phase(MutationPhase.INDETERMINATE)
                session.save(state)
                continue
            }
            val compatible = if (pending.kind == MutationKind.CREATE) remote == null else
                remote != null && remote.id == pending.name && remote.version != null && pending.baseVersion != null &&
                    MessageDigest.isEqual(remote.version, pending.baseVersion)
            if (!compatible) {
                state.preferenceMutations[index] = pending.phase(MutationPhase.CONFLICT)
                session.save(state)
                continue
            }
            state.preferenceMutations[index] = pending.phase(MutationPhase.LAUNCHED)
            session.save(state)
            try {
                val response = if (pending.kind == MutationKind.CREATE) api.createPreference(parent, desired.encode(), clientToken)
                    else api.updatePreference(desired.encode(), clientToken)
                val result = validate(parent, response)
                if (!sameValue(result, desired)) throw ProtocolException()
                state.preferences.removeAll { PriorityPreference.decode(it).tag == pending.tag }
                state.preferences.add(response)
                state.preferenceMutations.removeAll { it.id == pending.id }
                session.save(state)
                uploaded++
            } catch (failure: Exception) {
                state = session.load()
                val position = state.preferenceMutations.indexOfFirst { it.id == pending.id }
                if (position >= 0) {
                    state.preferenceMutations[position] = pending.phase(
                        if (failure is PasswordRpcException && !failure.outcomeUnknown) {
                            if (failure.code in setOf(6, 9, 10)) MutationPhase.CONFLICT else MutationPhase.QUEUED
                        } else MutationPhase.INDETERMINATE)
                    session.save(state)
                }
                throw failure
            }
        }
        SyncReceipt(state.preferences.size, uploaded, state.preferenceMutations.count { it.phase == MutationPhase.CONFLICT },
            state.preferenceMutations.count { it.phase == MutationPhase.INDETERMINATE || it.phase == MutationPhase.LAUNCHED })
    }

    fun value(name: String): String? = store.locked { session ->
        val state = session.load()
        val tag = PriorityPreference.tagFor(name)
        val pending = state.preferenceMutations.singleOrNull { it.tag == tag && it.phase != MutationPhase.CONFLICT }
        (pending?.desired ?: state.preferences.singleOrNull { PriorityPreference.decode(it).tag == tag })
            ?.let(PriorityPreference::decode)?.takeIf { it.name == name }?.value
    }

    private fun snapshot(parent: String): List<ByteArray> {
        val entries = ArrayList<ByteArray>()
        var bytes = 0L
        var token: ByteArray? = null
        val tokens = HashSet<String>()
        repeat(256) {
            val page = Proto.decode(api.listPreferences(parent, token))
            for (raw in page.bytes(1)) {
                validate(parent, raw)
                entries.add(raw); bytes += raw.size.toLong() + 16
                if (bytes > 2 * 1024 * 1024 || entries.size > 10_000) throw ProtocolException()
            }
            token = page.optional(2)?.takeIf { it.isNotEmpty() }
            if (token == null) {
                val preferences = entries.map(PriorityPreference::decode)
                if (preferences.map { it.tag }.toSet().size != entries.size || preferences.map { it.id }.toSet().size != entries.size) throw ProtocolException()
                return entries
            }
            if (!tokens.add(Base64.getEncoder().encodeToString(token))) throw ProtocolException()
        }
        throw ProtocolException()
    }
    private fun validate(parent: String, raw: ByteArray): PriorityPreference = PriorityPreference.decode(raw).also {
        val id = PasswordApi.checkedPreferenceName(it.id ?: throw ProtocolException())
        if (!id.startsWith(parent + "/")) throw ProtocolException()
    }
    private fun sameValue(a: PriorityPreference, b: PriorityPreference) = a.tag == b.tag && a.name == b.name && a.value == b.value
}
