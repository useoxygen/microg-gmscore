/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom.utils

import org.microg.gms.auth.folsom.AccountData
import org.microg.gms.auth.folsom.DomainData
import org.microg.gms.passwords.*
import java.security.MessageDigest
import java.util.UUID

internal object FolsomConsentPolicy {
    val coupled = setOf("chromesync", "hw_protected")
    fun effective(account: AccountData?, domain: String, overrideDomain: String? = null, overrideValue: Boolean? = null): Boolean {
        require(FolsomDomainPolicy.valid(domain))
        if (overrideValue != null && (domain == overrideDomain || domain in coupled && overrideDomain in coupled)) return overrideValue
        return account?.domains?.any { entry -> (entry.key == domain || domain in coupled && entry.key in coupled) && entry.value_?.consent == true } == true
    }
    fun needsPrompt(account: AccountData?, domain: String, firstRecoveredEpoch: Int?): Boolean =
        !effective(account, domain) && (firstRecoveredEpoch ?: 0) > 0
    fun store(account: AccountData, userId: String, domain: String, value: Boolean): AccountData {
        require(FolsomDomainPolicy.valid(domain) && userId.isNotBlank())
        val bound = FolsomAccountProtection.bind(account, userId)
        val old = bound.domains.singleOrNull { it.key == domain }?.value_ ?: DomainData(googleUserId = userId)
        if (old.googleUserId != null && old.googleUserId != userId) throw SecurityException("Recovery account changed")
        val replacement = AccountData.DomainsEntry(key = domain, value_ = old.copy(consent = value, googleUserId = userId))
        return bound.copy(domains = if (bound.domains.any { it.key == domain }) bound.domains.map { if (it.key == domain) replacement else it } else bound.domains + replacement)
    }
}
internal enum class FolsomConsentMutationKind { LEAVE, UPDATE }
internal enum class FolsomConsentMutationPhase { QUEUED, LAUNCHED, COMMITTED }
internal enum class FolsomConsentPhase { PREPARED, SYNC_LAUNCHED, SYNCED, COMMITTED }
internal class FolsomConsentMutation(val kind: FolsomConsentMutationKind, val domain: String, val request: ByteArray,
    val phase: FolsomConsentMutationPhase = FolsomConsentMutationPhase.QUEUED) {
    fun phase(next: FolsomConsentMutationPhase) = FolsomConsentMutation(kind, domain, request, next)
    fun encode() = Proto.integer(1, kind.ordinal.toLong()) + Proto.field(2, domain) + Proto.field(3, request) + Proto.integer(4, phase.ordinal.toLong())
    companion object {
        fun decode(raw: ByteArray): FolsomConsentMutation {
            val p = Proto.decode(raw)
            return FolsomConsentMutation(FolsomConsentMutationKind.entries[p.integer(1)?.toInt() ?: throw ProtocolException()],
                DomainLifecycleWire.checkedDomain(p.string(2)), p.one(3), FolsomConsentMutationPhase.entries[p.integer(4)?.toInt() ?: throw ProtocolException()])
        }
    }
    override fun toString() = "FolsomConsentMutation[redacted]"
}
internal class FolsomConsentAttempt(val id: String, val accountId: String, val domain: String, val value: Boolean,
    val review: ByteArray, val mutations: List<FolsomConsentMutation>, val phase: FolsomConsentPhase) {
    fun phase(next: FolsomConsentPhase, mutations: List<FolsomConsentMutation> = this.mutations) =
        FolsomConsentAttempt(id, accountId, domain, value, review, mutations, next)
    fun encode() = Proto.field(1, id) + Proto.field(2, accountId) + Proto.field(3, domain) + Proto.integer(4, if (value) 1 else 0) +
        Proto.field(5, review) + mutations.fold(byteArrayOf()) { out, m -> out + Proto.field(6, m.encode()) } + Proto.integer(7, phase.ordinal.toLong())
    companion object {
        fun decode(raw: ByteArray): FolsomConsentAttempt {
            val p = Proto.decode(raw)
            if (p.string(1).length !in 1..128 || p.string(2).length !in 1..256 || p.integer(4)?.let { it in 0L..1L } != true || p.bytes(6).size > 4) throw ProtocolException()
            return FolsomConsentAttempt(p.string(1), p.string(2), DomainLifecycleWire.checkedDomain(p.string(3)), p.integer(4) == 1L,
                p.one(5), p.bytes(6).map(FolsomConsentMutation::decode), FolsomConsentPhase.entries[p.integer(7)?.toInt() ?: throw ProtocolException()])
        }
    }
    override fun toString() = "FolsomConsentAttempt[redacted]"
}
internal class FolsomConsentState(val attempt: FolsomConsentAttempt? = null, val history: List<ByteArray> = emptyList()) {
    fun encode() = Proto.integer(1, 1) + (attempt?.let { Proto.field(2, it.encode()) } ?: byteArrayOf()) +
        history.fold(byteArrayOf()) { out, raw -> out + Proto.field(3, raw) }
    fun destroy() { attempt?.let { it.review.fill(0); it.mutations.forEach { m -> m.request.fill(0) } }; history.forEach { it.fill(0) } }
    companion object {
        fun decode(raw: ByteArray): FolsomConsentState {
            if (raw.isEmpty()) return FolsomConsentState()
            val p = Proto.decode(raw)
            if (p.integer(1) != 1L || p.bytes(3).size > 100) throw ProtocolException()
            return FolsomConsentState(p.optional(2)?.let(FolsomConsentAttempt::decode), p.bytes(3))
        }
        fun clearDomain(raw: ByteArray, domain: String): ByteArray {
            DomainLifecycleWire.checkedDomain(domain); val state = decode(raw)
            return try {
                val a = state.attempt
                if (a == null || a.domain != domain && !(a.domain in FolsomConsentPolicy.coupled && domain in FolsomConsentPolicy.coupled)) raw.copyOf()
                else FolsomConsentState(history = compact(state.history + MessageDigest.getInstance("SHA-256").digest(a.encode()))).encode()
            } finally { state.destroy() }
        }
        fun compact(receipts: List<ByteArray>): List<ByteArray> = if (receipts.size <= 100) receipts else
            listOf(MessageDigest.getInstance("SHA-256").digest(receipts[0] + receipts[1])) + receipts.drop(2)
    }
    override fun toString() = "FolsomConsentState[redacted]"
}
internal object FolsomConsentWire {
    fun leave(domain: String, memberPublic: ByteArray, id: String): ByteArray =
        Proto.field(1, DomainLifecycleWire.name(domain)) + Proto.field(2, EnrollmentWire.memberName(memberPublic)) + Proto.field(3, id)
    fun update(domain: String, memberPublic: ByteArray, backupEnabled: Boolean, consent: Boolean, id: String): ByteArray {
        if (domain != "chromesync") throw ProtocolException()
        val flags = Proto.integer(1, if (backupEnabled) 1 else 0) + Proto.integer(2, if (consent) 1 else 0)
        val membership = Proto.field(1, DomainLifecycleWire.name(domain)) + Proto.field(6, Proto.field(1, flags))
        val member = Proto.field(1, EnrollmentWire.memberName(memberPublic)) + Proto.field(3, membership)
        return Proto.field(1, member) + Proto.field(2, Proto.field(1, "memberships.metadata.lockscreen_membership_metadata")) + Proto.field(3, id)
    }
}
internal interface FolsomConsentRemote {
    fun review(domain: String, value: Boolean, id: String): ByteArray
    fun mutations(review: ByteArray, id: String): List<FolsomConsentMutation>
    fun synchronize(review: ByteArray, id: String): Boolean
    fun synchronized(review: ByteArray, id: String): Boolean
    fun verify(review: ByteArray)
    fun invoke(kind: FolsomConsentMutationKind, request: ByteArray): ByteArray
    fun applied(mutation: FolsomConsentMutation): Boolean
}
/** The shared LSKF journal may finish a previously reviewed target before configuring the next one. */
internal object FolsomConsentLowerJournals {
    fun synchronize(targets: List<String>, sync: (String) -> RecoverableVaultStatus): Boolean {
        if (targets.size > 2 || targets.toSet().size != targets.size) throw ProtocolException()
        targets.forEach(DomainLifecycleWire::checkedDomain)
        for (target in targets) {
            var status = sync(target)
            if (status.domain != target) {
                if (status.domain !in targets) return false
                sync(status.domain)
                status = sync(target)
            }
            if (status.domain != target || status.phase !in setOf(RecoverableVaultPhase.WAITING_FOR_SNAPSHOT,
                    RecoverableVaultPhase.PREPARED, RecoverableVaultPhase.VAULT_CONFIRMED, RecoverableVaultPhase.CONFIRMED)) return false
        }
        return true
    }
}
/** Coupled consent invokes real lower journals; no launched enrollment, leave or update is automatically replayed. */
internal class FolsomConsentManager(private val accountId: String, private val remote: FolsomConsentRemote,
    private val load: () -> FolsomConsentState, private val save: (FolsomConsentState, FolsomConsentState) -> Unit,
    private val commit: (FolsomConsentState, FolsomConsentState, String, Boolean) -> Unit, private val authorize: () -> Unit) {
    private fun checked(): FolsomConsentState = load().also { state ->
        authorize(); if (state.attempt?.accountId?.let { it != accountId } == true) throw VaultLockedException()
    }
    private fun persist(before: FolsomConsentState, after: FolsomConsentState): FolsomConsentState {
        authorize(); save(before, after); authorize(); return after
    }
    private fun finish(state: FolsomConsentState): FolsomConsentState {
        val a = state.attempt ?: return state
        if (a.phase != FolsomConsentPhase.SYNCED || a.mutations.any { it.phase != FolsomConsentMutationPhase.COMMITTED }) return state
        remote.verify(a.review); authorize()
        val done = FolsomConsentState(a.phase(FolsomConsentPhase.COMMITTED), state.history)
        commit(state, done, a.domain, a.value); authorize(); return done
    }
    fun reconcile(): FolsomConsentState {
        var state = checked(); var a = state.attempt ?: return state
        if (a.phase == FolsomConsentPhase.COMMITTED) return state
        remote.verify(a.review); authorize()
        if (a.phase == FolsomConsentPhase.SYNC_LAUNCHED && remote.synchronized(a.review, a.id)) {
            val mutations = remote.mutations(a.review, a.id)
            if (mutations.size > 4 || mutations.any { it.phase != FolsomConsentMutationPhase.QUEUED }) throw ProtocolException()
            state = persist(state, FolsomConsentState(a.phase(FolsomConsentPhase.SYNCED, mutations), state.history)); a = state.attempt!!
        }
        for (index in a.mutations.indices) {
            val m = a.mutations[index]
            if (m.phase != FolsomConsentMutationPhase.LAUNCHED || !remote.applied(m)) continue
            authorize()
            val next = a.mutations.mapIndexed { i, value -> if (i == index) m.phase(FolsomConsentMutationPhase.COMMITTED) else value }
            state = persist(state, FolsomConsentState(a.phase(a.phase, next), state.history)); a = state.attempt!!
        }
        return finish(state)
    }
    /** Save only the finite review. No RecoveryController setup, HTTP mutation or consent grant occurs here. */
    fun prepare(domain: String, value: Boolean, reviewedDigest: ByteArray? = null): FolsomConsentState {
        DomainLifecycleWire.checkedDomain(domain)
        val state = checked(); val old = state.attempt
        if (old != null && old.phase != FolsomConsentPhase.COMMITTED) {
            if (old.domain != domain || old.value != value) throw PendingMutationException()
            return state
        }
        val history = FolsomConsentState.compact(state.history + listOfNotNull(old?.let { MessageDigest.getInstance("SHA-256").digest(it.encode()) }))
        val id = UUID.randomUUID().toString(); val review = remote.review(domain, value, id); authorize()
        if (reviewedDigest != null && (reviewedDigest.size != 32 || !MessageDigest.isEqual(reviewedDigest, MessageDigest.getInstance("SHA-256").digest(review))))
            throw ConflictException()
        // Actual member requests are derived from the same scope after the genuine lower setup.
        // They are persisted separately before dispatch; preparing an owner prompt never initializes that setup.
        val attempt = FolsomConsentAttempt(id, accountId, domain, value, review, emptyList(), FolsomConsentPhase.PREPARED)
        return persist(state, FolsomConsentState(attempt, history))
    }
    fun set(domain: String, value: Boolean, reviewedDigest: ByteArray? = null): FolsomConsentState {
        DomainLifecycleWire.checkedDomain(domain); var state = checked()
        var attempt = state.attempt
        if (attempt != null && attempt.phase != FolsomConsentPhase.COMMITTED) {
            if (attempt.domain != domain || attempt.value != value) throw PendingMutationException()
            state = reconcile(); attempt = state.attempt
            if (attempt?.phase !in setOf(FolsomConsentPhase.PREPARED, FolsomConsentPhase.SYNCED)) return state
        } else {
            state = prepare(domain, value, reviewedDigest); attempt = state.attempt
        }
        var a = attempt ?: return state
        if (a.phase == FolsomConsentPhase.PREPARED) {
            remote.verify(a.review)
            state = persist(state, FolsomConsentState(a.phase(FolsomConsentPhase.SYNC_LAUNCHED), state.history))
            if (!remote.synchronize(a.review, a.id)) return state
            authorize()
            val mutations = remote.mutations(a.review, a.id)
            if (mutations.size > 4 || mutations.any { it.phase != FolsomConsentMutationPhase.QUEUED }) throw ProtocolException()
            state = persist(state, FolsomConsentState(a.phase(FolsomConsentPhase.SYNCED, mutations), state.history)); a = state.attempt!!
        }
        if (a.phase != FolsomConsentPhase.SYNCED) return state
        for (index in a.mutations.indices) {
            val m = a.mutations[index]
            if (m.phase != FolsomConsentMutationPhase.QUEUED) continue
            remote.verify(a.review); authorize()
            val launched = a.mutations.mapIndexed { i, old -> if (i == index) m.phase(FolsomConsentMutationPhase.LAUNCHED) else old }
            state = persist(state, FolsomConsentState(a.phase(a.phase, launched), state.history)); a = state.attempt!!
            remote.invoke(m.kind, m.request).fill(0); authorize()
            state = reconcile(); a = state.attempt ?: return state
            if (a.mutations[index].phase != FolsomConsentMutationPhase.COMMITTED) return state
        }
        return finish(state)
    }
    fun review(id: String): ByteArray {
        val a = checked().attempt?.takeIf { it.id == id } ?: throw ConflictException()
        return MessageDigest.getInstance("SHA-256").digest(remote.review(a.domain, a.value, id)).also { authorize() }
    }
    fun close(id: String): FolsomConsentState {
        val state = checked(); val a = state.attempt?.takeIf { it.id == id } ?: throw ConflictException()
        val history = FolsomConsentState.compact(state.history + listOf(MessageDigest.getInstance("SHA-256").digest(a.encode())))
        return persist(state, FolsomConsentState(history = history))
    }
    fun retry(id: String, reviewedDigest: ByteArray): FolsomConsentState {
        val old = checked().attempt?.takeIf { it.id == id } ?: throw ConflictException()
        val reconciled = reconcile()
        if (reconciled.attempt?.phase == FolsomConsentPhase.COMMITTED) return reconciled
        val fresh = review(id)
        if (reviewedDigest.size != 32 || !MessageDigest.isEqual(fresh, reviewedDigest)) throw ConflictException()
        close(id)
        // Only a fresh explicit owner action reaches here. The old launched request remains a hashed receipt.
        return set(old.domain, old.value, reviewedDigest)
    }
}
