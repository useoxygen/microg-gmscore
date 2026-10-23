/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest
import java.util.UUID

enum class PasskeyOwnerKind { RENAME, DELETE }
enum class PasskeyOwnerPhase { QUEUED, LAUNCHED, INDETERMINATE, REFUSED, CONFLICT, COMMITTED }

/** Before/after resources retain opaque ciphertext, version and unknown fields; stored only in account AEAD. */
class PasskeyOwnerStep(val before: ByteArray, val after: ByteArray?, val phase: PasskeyOwnerPhase = PasskeyOwnerPhase.QUEUED) {
    val name: String get() = PasskeyApi.checkedName(Proto.decode(before).string(1))
    fun phase(value: PasskeyOwnerPhase) = PasskeyOwnerStep(before, after, value)
    fun encode(): ByteArray = Proto.field(1, before) + (after?.let { Proto.field(2, it) } ?: byteArrayOf()) +
        Proto.integer(3, phase.ordinal.toLong())
    override fun toString() = "PasskeyOwnerStep[redacted]"
    companion object {
        fun decode(raw: ByteArray): PasskeyOwnerStep {
            val p = Proto.decode(raw)
            val phase = p.integer(3)?.takeIf { it in 0 until PasskeyOwnerPhase.entries.size.toLong() }
                ?.let { PasskeyOwnerPhase.entries[it.toInt()] } ?: throw ProtocolException()
            return PasskeyOwnerStep(p.one(1), p.optional(2), phase).also { it.name }
        }
    }
}

class PasskeyOwnerMutation(val id: String, val accountId: String, val parent: String, val kind: PasskeyOwnerKind,
    val steps: List<PasskeyOwnerStep>) {
    fun steps(values: List<PasskeyOwnerStep>) = PasskeyOwnerMutation(id, accountId, parent, kind, values)
    fun encode(): ByteArray {
        val p = Proto.decode(Proto.field(1, id) + Proto.field(2, accountId) + Proto.field(3, parent) +
            Proto.integer(4, kind.ordinal.toLong()))
        return p.replaceRepeated(5, steps.map(PasskeyOwnerStep::encode)).encode()
    }
    override fun toString() = "PasskeyOwnerMutation[redacted]"
    companion object {
        fun decode(raw: ByteArray): PasskeyOwnerMutation {
            val p = Proto.decode(raw)
            val id = p.string(1)
            val account = p.string(2)
            val parent = PasswordApi.checkedParent(p.string(3))
            val kind = p.integer(4)?.takeIf { it in 0 until PasskeyOwnerKind.entries.size.toLong() }
                ?.let { PasskeyOwnerKind.entries[it.toInt()] } ?: throw ProtocolException()
            val steps = p.bytes(5).map(PasskeyOwnerStep::decode)
            if (id.length !in 1..128 || account.length !in 1..256 || steps.isEmpty() || steps.size > 10_000 ||
                steps.map { it.name }.toSet().size != steps.size || (kind == PasskeyOwnerKind.RENAME && steps.size != 1) ||
                steps.any { (it.after != null) != (kind == PasskeyOwnerKind.RENAME) }) throw ProtocolException()
            steps.forEach { step ->
                PasskeyApi.checkedName(step.name, parent)
                step.after?.let { if (Proto.decode(it).string(1) != step.name) throw ProtocolException() }
            }
            return PasskeyOwnerMutation(id, account, parent, kind, steps)
        }
    }
}

/** Source ajno/ajnj/ajnk: active deletion includes hidden/older siblings; a shadowed row deletes only itself. */
object PasskeyOwnerMutations {
    fun shadowed(selected: SyncedPasskey, all: List<SyncedPasskey>): Boolean = all.any { item ->
        item.rpId == selected.rpId && item.shadowedCredentialIds.any { MessageDigest.isEqual(it, selected.credentialId) }
    }
    fun deletionGroup(selected: SyncedPasskey, all: List<SyncedPasskey>): List<SyncedPasskey> =
        if (shadowed(selected, all)) listOf(selected) else all.filter { it.rpId == selected.rpId &&
            MessageDigest.isEqual(it.userId, selected.userId) }

    fun renameMatches(actual: SyncedPasskey, desired: SyncedPasskey): Boolean = actual.sameRegistration(desired) &&
        actual.username == desired.username && actual.displayName == desired.displayName && actual.editedByUser

    /** Full snapshots only confirm outcomes. They never queue or launch an unknown attempt again. */
    fun reconcile(state: PasskeyState, keys: VaultKeys): PasskeyState {
        val operation = state.ownerMutation ?: return state
        if (state.parent != operation.parent) throw VaultResetException()
        val byName = state.records.associateBy { PasskeyApi.checkedName(Proto.decode(it).string(1), operation.parent) }
        if (byName.size != state.records.size) throw ProtocolException()
        val steps = operation.steps.map { step ->
            if (step.phase == PasskeyOwnerPhase.COMMITTED) return@map step
            val current = byName[step.name]
            val achieved = if (step.after == null) current == null else current?.let {
                renameMatches(PasskeyResourceCodec.read(it, keys), PasskeyResourceCodec.read(step.after, keys))
            } == true
            when {
                achieved -> step.phase(PasskeyOwnerPhase.COMMITTED)
                current == null || !current.contentEquals(step.before) -> step.phase(PasskeyOwnerPhase.CONFLICT)
                step.phase == PasskeyOwnerPhase.LAUNCHED -> step.phase(PasskeyOwnerPhase.INDETERMINATE)
                else -> step
            }
        }
        // Completion frees the live slot, retaining the exact per-resource receipt in the encrypted history.
        val replacement = operation.steps(steps)
        return if (steps.all { it.phase == PasskeyOwnerPhase.COMMITTED }) archive(state, replacement)
        else state.ownerState(replacement)
    }
    fun archive(state: PasskeyState, operation: PasskeyOwnerMutation): PasskeyState {
        if (state.ownerHistory.size >= 1000) throw ProtocolException()
        return state.ownerState(null, history = state.ownerHistory + listOf(operation.encode()))
    }
}

/** Real production coordinator. The caller holds both account/password and passkey store locks for its lifetime. */
class PasskeyOwnerManagement(private val accountId: String, private val parent: String, private val keys: VaultKeys,
    private val api: PasskeyApi, private val token: ByteArray, private val load: () -> PasskeyState,
    private val save: (PasskeyState) -> Unit, private val verifyOwner: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis) {

    private fun checkOperation(operation: PasskeyOwnerMutation) {
        if (operation.accountId != accountId) throw VaultLockedException()
        if (operation.parent != parent) throw VaultResetException()
    }
    fun refresh(): PasskeyState {
        verifyOwner()
        val old = load()
        old.ownerMutation?.let(::checkOperation)
        val records = api.snapshot(parent)
        verifyOwner()
        return PasskeySnapshots.reconcile(old, parent, records, keys, now()).also(save)
    }
    private fun selected(state: PasskeyState, baseline: ByteArray): SyncedPasskey {
        if (state.pending != null || state.ownerMutation != null) throw PendingMutationException()
        val name = PasskeyApi.checkedName(Proto.decode(baseline).string(1), parent)
        val actual = state.records.singleOrNull { Proto.decode(it).string(1) == name } ?: throw ConflictException()
        if (!actual.contentEquals(baseline)) throw ConflictException()
        return PasskeyResourceCodec.read(actual, keys)
    }
    fun rename(baseline: ByteArray, username: String, displayName: String) {
        val state = refresh()
        val data = selected(state, baseline)
        if (data.hidden) throw ConflictException()
        val after = PasskeyResourceCodec.write(data.withOwnerNames(username, displayName), keys, baseline)
        prepare(state, PasskeyOwnerKind.RENAME, listOf(PasskeyOwnerStep(baseline.copyOf(), after)))
    }
    fun delete(baseline: ByteArray, reviewedGroup: List<ByteArray>) {
        val state = refresh()
        val data = selected(state, baseline)
        val all = state.records.map { PasskeyResourceCodec.read(it, keys) }
        val tags = PasskeyOwnerMutations.deletionGroup(data, all).map { it.tag }.toSet()
        val steps = state.records.filter { PasskeyResourceCodec.read(it, keys).tag in tags }
            .sortedBy { Proto.decode(it).string(1) }.map { PasskeyOwnerStep(it.copyOf(), null) }
        val reviewed = reviewedGroup.sortedBy { Proto.decode(it).string(1) }
        if (steps.size != reviewed.size || steps.indices.any { !steps[it].before.contentEquals(reviewed[it]) })
            throw ConflictException()
        prepare(state, PasskeyOwnerKind.DELETE, steps)
    }
    private fun prepare(state: PasskeyState, kind: PasskeyOwnerKind, steps: List<PasskeyOwnerStep>) {
        if (state.ownerHistory.size >= 1000 || token.isEmpty()) throw ProtocolException()
        val operation = PasskeyOwnerMutation(UUID.randomUUID().toString(), accountId, parent, kind, steps)
        verifyOwner(); save(state.ownerState(operation))
        execute(operation.id)
    }
    /** Explicit retry archives the prior launch receipt and creates a distinct attempt after a fresh baseline check. */
    fun retry(id: String) {
        val state = refresh()
        val operation = state.ownerMutation ?: return
        if (operation.id != id) throw ConflictException()
        checkOperation(operation)
        if (operation.steps.any { it.phase == PasskeyOwnerPhase.CONFLICT }) throw ConflictException()
        if (state.ownerHistory.size >= 999) throw ProtocolException()
        val queued = operation.steps.filter { it.phase != PasskeyOwnerPhase.COMMITTED }
            .map { PasskeyOwnerStep(it.before.copyOf(), it.after?.copyOf()) }
        val archived = PasskeyOwnerMutations.archive(state, operation)
        verifyOwner(); save(archived)
        prepare(archived, operation.kind, queued)
    }
    private fun execute(id: String) {
        val operation = load().ownerMutation ?: return
        checkOperation(operation)
        if (operation.id != id) throw ConflictException()
        for (index in operation.steps.indices) {
            var state = load()
            var current = state.ownerMutation ?: return
            checkOperation(current)
            if (current.id != id) throw ConflictException()
            val step = current.steps[index]
            if (step.phase != PasskeyOwnerPhase.QUEUED) continue
            val before = state.records.singleOrNull { Proto.decode(it).string(1) == step.name }
            if (before == null || !before.contentEquals(step.before)) throw ConflictException()
            fun change(phase: PasskeyOwnerPhase, records: List<ByteArray> = state.records) {
                current = current.steps(current.steps.mapIndexed { at, value -> if (at == index) value.phase(phase) else value })
                state = state.ownerState(current, records)
                save(state)
            }
            verifyOwner(); change(PasskeyOwnerPhase.LAUNCHED)
            var dispatched = false
            try {
                verifyOwner(); dispatched = true
                val records = if (step.after == null) {
                    api.delete(step.before, token)
                    state.records.filter { Proto.decode(it).string(1) != step.name }
                } else {
                    val result = api.update(step.after, token)
                    if (PasskeyApi.checkedName(Proto.decode(result).string(1), parent) != step.name ||
                        !PasskeyOwnerMutations.renameMatches(PasskeyResourceCodec.read(result, keys),
                            PasskeyResourceCodec.read(step.after, keys))) throw ProtocolException()
                    state.records.map { if (Proto.decode(it).string(1) == step.name) result else it }
                }
                verifyOwner(); change(PasskeyOwnerPhase.COMMITTED, records)
            } catch (failure: Exception) {
                change(when {
                    !dispatched -> PasskeyOwnerPhase.QUEUED
                    failure is PasswordRpcException && !failure.outcomeUnknown -> PasskeyOwnerPhase.REFUSED
                    else -> PasskeyOwnerPhase.INDETERMINATE
                })
                throw failure
            }
        }
        val finished = load()
        finished.ownerMutation?.takeIf { it.steps.all { step -> step.phase == PasskeyOwnerPhase.COMMITTED } }?.let {
            verifyOwner(); save(PasskeyOwnerMutations.archive(finished, it))
        }
    }
    /** Explicit closure does not undo a completed/possible server change. Old-birthday bytes remain recoverable. */
    fun close(id: String) {
        verifyOwner()
        val state = load()
        val operation = state.ownerMutation ?: return
        if (operation.accountId != accountId) throw VaultLockedException()
        if (operation.id != id) throw ConflictException()
        save(PasskeyOwnerMutations.archive(state, operation))
    }
    override fun toString() = "PasskeyOwnerManagement[redacted]"
}
