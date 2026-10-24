/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest
import java.util.UUID

enum class DomainLifecycleKind { CREATE, ENROLL, ROTATE, RESET, ADD_MEMBER }
enum class DomainLifecyclePhase { PREPARED, LAUNCHED, INDETERMINATE, REFUSED, COMMITTED, CONFLICT }

/** Raw material and zero-epoch proposals are confined to the account's protected recovery store. */
class DomainLifecycleKey(val epoch: Int, material: ByteArray) : AutoCloseable {
    private val raw = material.copyOf()
    init { if (epoch < 0 || raw.size !in 16..4096) throw ProtocolException() }
    val material: ByteArray get() = raw.copyOf()
    fun encode(): ByteArray = (if (epoch == 0) byteArrayOf() else Proto.integer(1, epoch.toLong())) + Proto.field(2, raw)
    fun atEpoch(value: Int) = DomainLifecycleKey(value, raw)
    override fun close() { raw.fill(0) }
    override fun toString() = "DomainLifecycleKey[redacted]"
    companion object {
        fun decode(raw: ByteArray): DomainLifecycleKey {
            val value = Proto.decode(raw); val epoch = value.integer(1) ?: 0
            if (epoch !in 0..Int.MAX_VALUE) throw ProtocolException()
            val material = value.one(2)
            return try { DomainLifecycleKey(epoch.toInt(), material) } finally { material.fill(0) }
        }
    }
}

class DomainLifecycleSnapshot(val domain: ByteArray?, val members: List<ByteArray>, val recipients: List<ByteArray>) {
    fun encode() = (domain?.let { Proto.field(1, it) } ?: byteArrayOf()) +
        members.sortedBy { Proto.decode(it).string(1) }.fold(byteArrayOf()) { out, value -> out + Proto.field(2, value) } +
        recipients.sortedBy { Proto.decode(it).string(1) }.fold(byteArrayOf()) { out, value -> out + Proto.field(3, value) }
    fun digest(): ByteArray = MessageDigest.getInstance("SHA-256").digest(encode())
    override fun toString() = "DomainLifecycleSnapshot[redacted]"
    companion object {
        fun decode(raw: ByteArray): DomainLifecycleSnapshot {
            val p = Proto.decode(raw)
            return DomainLifecycleSnapshot(p.optional(1), p.bytes(2), p.bytes(3))
        }
    }
}

class DomainLifecycleAttempt(val id: String, val accountId: String, val domain: String, val kind: DomainLifecycleKind,
    val baseline: ByteArray, val request: ByteArray, val knownKeys: List<ByteArray>, val proposedKey: ByteArray?,
    val phase: DomainLifecyclePhase, val resultEpoch: Int = 0, val authorityCleared: Boolean = false,
    val baselineDigest: ByteArray? = null) {
    fun phase(value: DomainLifecyclePhase, epoch: Int = resultEpoch) = DomainLifecycleAttempt(id, accountId, domain,
        kind, baseline, request, knownKeys, proposedKey, value, epoch, authorityCleared, baselineDigest)
    fun cleared() = DomainLifecycleAttempt(id, accountId, domain, kind, baseline, request, knownKeys, proposedKey,
        phase, resultEpoch, true, baselineDigest)
    fun confirmedReset() = DomainLifecycleAttempt(id, accountId, domain, kind, byteArrayOf(), request, emptyList(), null,
        DomainLifecyclePhase.COMMITTED, 0, false, MessageDigest.getInstance("SHA-256").digest(baseline))
    fun encode() = Proto.integer(1, 1) + Proto.field(2, id) + Proto.field(3, accountId) + Proto.field(4, domain) +
        Proto.integer(5, kind.ordinal.toLong()) + Proto.field(6, baseline) + Proto.field(7, request) +
        knownKeys.fold(byteArrayOf()) { out, key -> out + Proto.field(8, key) } +
        (proposedKey?.let { Proto.field(9, it) } ?: byteArrayOf()) + Proto.integer(10, phase.ordinal.toLong()) +
        Proto.integer(11, resultEpoch.toLong()) + Proto.integer(12, if (authorityCleared) 1 else 0) +
        (baselineDigest?.let { Proto.field(13, it) } ?: byteArrayOf())
    fun destroy() { knownKeys.forEach { it.fill(0) }; proposedKey?.fill(0) }
    override fun toString() = "DomainLifecycleAttempt[redacted]"
    companion object {
        fun decode(raw: ByteArray): DomainLifecycleAttempt {
            val p = Proto.decode(raw)
            if (p.integer(1) != 1L) throw ProtocolException()
            val kind = p.integer(5)?.takeIf { it in 0 until DomainLifecycleKind.entries.size.toLong() }
                ?.let { DomainLifecycleKind.entries[it.toInt()] } ?: throw ProtocolException()
            val phase = p.integer(10)?.takeIf { it in 0 until DomainLifecyclePhase.entries.size.toLong() }
                ?.let { DomainLifecyclePhase.entries[it.toInt()] } ?: throw ProtocolException()
            val result = p.integer(11) ?: 0
            val keys = p.bytes(8)
            if (p.string(2).length !in 1..128 || p.string(3).length !in 1..256 || keys.size > 100 || result !in 0..Int.MAX_VALUE)
                throw ProtocolException()
            return DomainLifecycleAttempt(p.string(2), p.string(3), DomainLifecycleWire.checkedDomain(p.string(4)), kind,
                p.one(6), p.one(7), keys, p.optional(9), phase, result.toInt(), p.integer(12) == 1L, p.optional(13)).also {
                val field = if (kind == DomainLifecycleKind.RESET) 3 else 6
                if (Proto.decode(it.request).string(field) != it.id) throw ProtocolException()
            }
        }
    }
}

class DomainLifecycleState(val pair: ByteArray? = null, val attempt: DomainLifecycleAttempt? = null,
    val history: List<ByteArray> = emptyList()) {
    fun encode() = Proto.integer(1, 1) + (pair?.let { Proto.field(2, it) } ?: byteArrayOf()) +
        (attempt?.let { Proto.field(3, it.encode()) } ?: byteArrayOf()) +
        history.fold(byteArrayOf()) { out, item -> out + Proto.field(4, item) }
    fun destroy() { pair?.fill(0); attempt?.destroy(); history.forEach { it.fill(0) } }
    override fun toString() = "DomainLifecycleState[redacted]"
    companion object {
        fun decode(raw: ByteArray): DomainLifecycleState {
            if (raw.isEmpty()) return DomainLifecycleState()
            val p = Proto.decode(raw)
            if (p.integer(1) != 1L || p.bytes(4).size > 1000) throw ProtocolException()
            return DomainLifecycleState(p.optional(2)?.also(EnrollmentWire::checkPair),
                p.optional(3)?.let(DomainLifecycleAttempt::decode), p.bytes(4))
        }
    }
}

interface DomainLifecycleCrypto : EnrollmentCrypto {
    fun generateSharedKey(): ByteArray
    /** anwc.A: SecureBox shared-secret mode, new serialized key as AAD, plaintext single zero byte. */
    fun rotationProof(previous: ByteArray, next: ByteArray): ByteArray
}
interface DomainLifecycleRemote {
    /** Complete domain and member snapshots; recipients uses pinned rotation selection2. */
    fun snapshot(domain: String, requestId: String): DomainLifecycleSnapshot
    fun invoke(kind: DomainLifecycleKind, request: ByteArray): ByteArray
}

/** Pinned anwc.J/H/L/P/U and anqx. No recovery-vault/PIN or hardware-attestation proof is synthesized. */
object DomainLifecycleWire {
    fun checkedDomain(domain: String): String {
        if (domain !in setOf("chromesync", "hw_protected")) throw ProtocolException()
        return domain
    }
    fun name(domain: String) = "users/me/securitydomains/${checkedDomain(domain)}"
    fun epoch(snapshot: DomainLifecycleSnapshot, domain: String): Int {
        val raw = snapshot.domain ?: return 0
        val p = Proto.decode(raw); val epoch = p.integer(2) ?: 0
        if (p.string(1) != name(domain) || epoch !in 0..Int.MAX_VALUE) throw ProtocolException()
        return epoch.toInt()
    }
    fun validate(snapshot: DomainLifecycleSnapshot, domain: String) {
        epoch(snapshot, domain)
        for (list in listOf(snapshot.members, snapshot.recipients)) {
            if (list.size > 4096 || list.map { Proto.decode(it).string(1) }.toSet().size != list.size) throw ProtocolException()
            list.forEach { raw ->
                val p = Proto.decode(raw)
                val public = p.one(2)
                if (public.size != 65 || public[0] != 4.toByte() || p.string(1) != EnrollmentWire.memberName(public) ||
                    p.bytes(3).map(Proto::decode).none { it.string(1) == name(domain) }) throw ProtocolException()
            }
        }
        val byName = snapshot.members.associateBy { Proto.decode(it).string(1) }
        if (snapshot.recipients.any { byName[Proto.decode(it).string(1)]?.contentEquals(it) != true }) throw ConflictException()
    }
    fun join(domain: String, pair: ByteArray, keys: List<DomainLifecycleKey>, id: String, crypto: DomainLifecycleCrypto): ByteArray =
        joinPublic(domain, EnrollmentWire.publicKey(pair), keys, id, crypto, true, 0)
    fun joinPublic(domain: String, public: ByteArray, keys: List<DomainLifecycleKey>, id: String,
        crypto: DomainLifecycleCrypto, physical: Boolean, options: Int): ByteArray {
        if (public.size != 65 || public[0] != 4.toByte() || options !in 0..6) throw ProtocolException()
        val member = Proto.field(1, EnrollmentWire.memberName(public)) + Proto.field(2, public) +
            if (physical) Proto.integer(4, 1) + Proto.field(6, Proto.field(3, Proto.integer(1, 1))) else byteArrayOf()
        if (keys.isEmpty() || keys.size > 100 || keys.map { it.epoch }.toSet().size != keys.size) throw ProtocolException()
        val wrapped = keys.sortedBy { it.epoch }.fold(byteArrayOf()) { out, key ->
            val raw = key.material
            try { out + Proto.field(3, (if (key.epoch == 0) byteArrayOf() else Proto.integer(1, key.epoch.toLong())) +
                Proto.field(2, crypto.wrap(public, raw)) + Proto.field(3, EnrollmentWire.proof(raw, public))) }
            finally { raw.fill(0) }
        }
        return Proto.field(1, Proto.field(1, name(domain))) + Proto.field(2, member) + wrapped +
            (if (options == 0) byteArrayOf() else Proto.integer(4, options.toLong())) + Proto.field(6, id)
    }
    fun reset(domain: String, id: String) = Proto.field(1, name(domain)) + Proto.field(3, id)
    fun rotate(domain: String, snapshot: DomainLifecycleSnapshot, old: DomainLifecycleKey, next: DomainLifecycleKey,
        id: String, crypto: DomainLifecycleCrypto): ByteArray {
        if (old.epoch <= 0 || old.epoch != epoch(snapshot, domain) || next.epoch != 0 || snapshot.recipients.isEmpty()) throw ConflictException()
        val raw = next.material
        val previous = old.encode(); val proposed = next.encode()
        return try {
            val recipients = snapshot.recipients.sortedBy { Proto.decode(it).string(1) }.fold(byteArrayOf()) { out, bytes ->
                val member = Proto.decode(bytes); val public = member.one(2)
                out + Proto.field(3, Proto.field(1, member.string(1)) + Proto.field(2, crypto.wrap(public, raw)) +
                    Proto.field(3, EnrollmentWire.proof(raw, public)))
            }
            Proto.field(1, name(domain)) + Proto.integer(2, old.epoch.toLong()) + recipients +
                Proto.field(4, crypto.rotationProof(previous, proposed)) + Proto.field(6, id)
        } finally { raw.fill(0); previous.fill(0); proposed.fill(0) }
    }
    fun memberHas(member: ByteArray, domain: String, pair: ByteArray, keys: List<DomainLifecycleKey>, crypto: DomainLifecycleCrypto): Boolean {
        val p = Proto.decode(member); val public = EnrollmentWire.publicKey(pair)
        if (p.string(1) != EnrollmentWire.memberName(public) || !MessageDigest.isEqual(p.one(2), public) || p.integer(4) != 1L)
            throw ProtocolException()
        val shared = p.bytes(3).map(Proto::decode).singleOrNull { it.string(1) == name(domain) }?.bytes(3)?.map(Proto::decode)
            ?: throw ProtocolException()
        if (shared.size > 100 || shared.map { it.integer(1) }.toSet().size != shared.size) throw ProtocolException()
        for (key in keys) {
            val found = shared.singleOrNull { it.integer(1) == key.epoch.toLong() } ?: return false
            val raw = key.material
            var opened: ByteArray? = null
            try {
                opened = crypto.unwrap(pair, found.one(2))
                if (!MessageDigest.isEqual(opened, raw) || !MessageDigest.isEqual(found.one(3), EnrollmentWire.proof(raw, public))) throw ProtocolException()
            } finally { raw.fill(0); opened?.fill(0) }
        }
        return true
    }
}

/** One account/domain operation under a native OS lock. Network uncertainty is never permission to replay. */
class DomainLifecycleManager(private val accountId: String, private val domain: String, private val crypto: DomainLifecycleCrypto,
    private val remote: DomainLifecycleRemote, private val load: () -> DomainLifecycleState, private val save: (DomainLifecycleState) -> Unit,
    private val keys: () -> List<DomainLifecycleKey>, private val verifyOwner: () -> Unit, private val existingPair: () -> ByteArray?) {
    init { DomainLifecycleWire.checkedDomain(domain) }
    fun review(): DomainLifecycleSnapshot {
        verifyOwner()
        return remote.snapshot(domain, UUID.randomUUID().toString()).also { DomainLifecycleWire.validate(it, domain); verifyOwner() }
    }
    private fun checked(attempt: DomainLifecycleAttempt) {
        if (attempt.accountId != accountId || attempt.domain != domain) throw VaultLockedException()
        val p = Proto.decode(attempt.request)
        if (p.string(if (attempt.kind == DomainLifecycleKind.RESET) 3 else 6) != attempt.id) throw ProtocolException()
    }
    fun prepare(kind: DomainLifecycleKind, reviewedDigest: ByteArray, externalPublic: ByteArray? = null, joinOptions: Int = 0) {
        verifyOwner()
        val state = load()
        val previous = state.attempt
        if (previous != null && (previous.phase != DomainLifecyclePhase.COMMITTED ||
                previous.kind == DomainLifecycleKind.RESET && !previous.authorityCleared)) throw PendingMutationException()
        if (state.history.size >= 999) throw ProtocolException()
        val snapshot = review()
        if (!MessageDigest.isEqual(snapshot.digest(), reviewedDigest)) throw ConflictException()
        val epoch = DomainLifecycleWire.epoch(snapshot, domain)
        val id = UUID.randomUUID().toString()
        val pair = state.pair ?: existingPair() ?: if (kind in setOf(DomainLifecycleKind.CREATE, DomainLifecycleKind.ENROLL)) crypto.generatePair() else null
        pair?.let(EnrollmentWire::checkPair)
        val known = if (kind in setOf(DomainLifecycleKind.ENROLL, DomainLifecycleKind.ROTATE, DomainLifecycleKind.ADD_MEMBER)) keys() else emptyList()
        var proposed: DomainLifecycleKey? = null
        try {
            if (kind !in setOf(DomainLifecycleKind.RESET, DomainLifecycleKind.ADD_MEMBER) && pair == null) throw VaultLockedException()
            val body = when (kind) {
                DomainLifecycleKind.CREATE -> {
                    if (epoch != 0 || snapshot.members.isNotEmpty()) throw ConflictException()
                    val raw = crypto.generateSharedKey()
                    proposed = try { DomainLifecycleKey(0, raw) } finally { raw.fill(0) }
                    DomainLifecycleWire.join(domain, pair!!, listOf(proposed!!), id, crypto)
                }
                DomainLifecycleKind.ENROLL -> {
                    if (epoch <= 0 || known.none { it.epoch == epoch }) throw VaultLockedException()
                    DomainLifecycleWire.join(domain, pair!!, known, id, crypto)
                }
                DomainLifecycleKind.ROTATE -> {
                    val old = known.singleOrNull { it.epoch == epoch } ?: throw VaultLockedException()
                    val own = snapshot.recipients.singleOrNull { Proto.decode(it).string(1) == EnrollmentWire.memberName(EnrollmentWire.publicKey(pair!!)) }
                        ?: throw VaultLockedException()
                    if (!DomainLifecycleWire.memberHas(own, domain, pair!!, listOf(old), crypto)) throw VaultLockedException()
                    val raw = crypto.generateSharedKey()
                    proposed = try { DomainLifecycleKey(0, raw) } finally { raw.fill(0) }
                    DomainLifecycleWire.rotate(domain, snapshot, old, proposed!!, id, crypto)
                }
                DomainLifecycleKind.RESET -> DomainLifecycleWire.reset(domain, id)
                DomainLifecycleKind.ADD_MEMBER -> {
                    if (epoch <= 0 || known.none { it.epoch == epoch } || externalPublic == null) throw VaultLockedException()
                    DomainLifecycleWire.joinPublic(domain, externalPublic, known, id, crypto, false, joinOptions)
                }
            }
            val attempt = DomainLifecycleAttempt(id, accountId, domain, kind, snapshot.encode(), body, known.map { it.encode() },
                proposed?.encode(), DomainLifecyclePhase.PREPARED)
            val prepared = DomainLifecycleState(pair, attempt, state.history + listOfNotNull(state.attempt?.encode()))
            verifyOwner(); save(prepared)
            dispatch(prepared)
        } finally { known.forEach { it.close() }; proposed?.close() }
    }
    /** Only authenticated readback can commit; it may finish a launched attempt after process death. */
    fun reconcile(): DomainLifecycleState {
        verifyOwner(); val state = load(); val attempt = state.attempt ?: return state
        checked(attempt)
        if (attempt.phase == DomainLifecyclePhase.COMMITTED) return state
        val current = review()
        val currentEpoch = DomainLifecycleWire.epoch(current, domain)
        val previousEpoch = DomainLifecycleWire.epoch(DomainLifecycleSnapshot.decode(attempt.baseline), domain)
        var achieved = attempt.kind == DomainLifecycleKind.RESET && currentEpoch == 0 && current.members.isEmpty()
        if (attempt.kind != DomainLifecycleKind.RESET && currentEpoch > 0 &&
            (attempt.kind !in setOf(DomainLifecycleKind.ENROLL, DomainLifecycleKind.ADD_MEMBER) || currentEpoch == previousEpoch) &&
            (attempt.kind != DomainLifecycleKind.ROTATE || currentEpoch > previousEpoch)) {
            val external = if (attempt.kind == DomainLifecycleKind.ADD_MEMBER) Proto.decode(Proto.decode(attempt.request).one(2)).one(2) else null
            val pair = state.pair
            val public = external ?: EnrollmentWire.publicKey(pair ?: throw ProtocolException())
            val own = current.members.singleOrNull { Proto.decode(it).string(1) == EnrollmentWire.memberName(public) }
            if (own != null) {
                val expected = if (attempt.proposedKey != null) listOf(DomainLifecycleKey.decode(attempt.proposedKey).use { it.atEpoch(currentEpoch) })
                    else attempt.knownKeys.map(DomainLifecycleKey::decode)
                try {
                    achieved = if (external == null) DomainLifecycleWire.memberHas(own, domain, pair!!, expected, crypto) else {
                        val actual = Proto.decode(own)
                        if (!MessageDigest.isEqual(actual.one(2), external)) throw ProtocolException()
                        val shared = actual.bytes(3).map(Proto::decode).single { it.string(1) == DomainLifecycleWire.name(domain) }.bytes(3).map(Proto::decode)
                        val sent = Proto.decode(attempt.request).bytes(3).map(Proto::decode)
                        expected.all { key ->
                            val raw = key.material
                            try {
                                val record = shared.singleOrNull { it.integer(1) == key.epoch.toLong() }
                                val requested = sent.single { it.integer(1) == key.epoch.toLong() }
                                record != null && MessageDigest.isEqual(record.one(3), EnrollmentWire.proof(raw, external)) &&
                                    MessageDigest.isEqual(record.one(2), requested.one(2))
                            } finally { raw.fill(0) }
                        }
                    }
                }
                finally { expected.forEach { it.close() } }
            }
        }
        val next = when {
            achieved && attempt.kind == DomainLifecycleKind.RESET -> attempt.confirmedReset()
            achieved -> attempt.phase(DomainLifecyclePhase.COMMITTED, currentEpoch)
            attempt.phase == DomainLifecyclePhase.LAUNCHED -> attempt.phase(DomainLifecyclePhase.INDETERMINATE)
            else -> attempt
        }
        // The reset's verified absence makes old private/wrapped recovery bytes unnecessary.
        // Keep only receipt hashes; retaining the phone pair plus old wrapped keys would retain old authority.
        val history = if (achieved && attempt.kind == DomainLifecycleKind.RESET) state.history.map {
            Proto.field(1, MessageDigest.getInstance("SHA-256").digest(it))
        } else state.history
        return DomainLifecycleState(state.pair, next, history).also { verifyOwner(); save(it) }
    }
    private fun dispatch(state: DomainLifecycleState) {
        val attempt = state.attempt ?: throw ProtocolException(); checked(attempt)
        val current = review()
        if (!MessageDigest.isEqual(current.digest(), DomainLifecycleSnapshot.decode(attempt.baseline).digest())) throw ConflictException()
        verifyOwner(); save(DomainLifecycleState(state.pair, attempt.phase(DomainLifecyclePhase.LAUNCHED), state.history))
        var dispatched = false
        try {
            verifyOwner(); dispatched = true
            remote.invoke(attempt.kind, attempt.request)
            verifyOwner()
            if (reconcile().attempt?.phase != DomainLifecyclePhase.COMMITTED) throw ProtocolException()
        } catch (failure: Exception) {
            val phase = if (!dispatched) DomainLifecyclePhase.PREPARED else DomainLifecyclePhase.INDETERMINATE
            // Keep the durable launch when authorization has expired; never resurrect a removed account's data.
            verifyOwner()
            // An atomic save can commit before its final acknowledgement fails. Its confirmed
            // receipt (and reset redaction) takes precedence over this older in-memory attempt.
            val durable = load()
            val latest = durable.attempt
            if (latest?.id == attempt.id && latest.phase != DomainLifecyclePhase.COMMITTED)
                save(DomainLifecycleState(durable.pair, latest.phase(phase), durable.history))
            throw failure
        }
    }
    fun retry(id: String, reviewedDigest: ByteArray) {
        val state = reconcile(); val old = state.attempt ?: return
        checked(old)
        if (old.id != id) throw ConflictException()
        if (old.phase == DomainLifecyclePhase.COMMITTED) return
        val current = review()
        if (!MessageDigest.isEqual(current.digest(), reviewedDigest) ||
            !MessageDigest.isEqual(current.digest(), DomainLifecycleSnapshot.decode(old.baseline).digest())) throw ConflictException()
        if (state.history.size >= 999) throw ProtocolException()
        val nextId = UUID.randomUUID().toString()
        val body = Proto.decode(old.request).replace(if (old.kind == DomainLifecycleKind.RESET) 3 else 6,
            nextId.toByteArray(Charsets.UTF_8)).encode()
        val next = DomainLifecycleAttempt(nextId, accountId, domain, old.kind, old.baseline, body, old.knownKeys,
            old.proposedKey, DomainLifecyclePhase.PREPARED)
        val prepared = DomainLifecycleState(state.pair, next, state.history + listOf(old.encode()))
        verifyOwner(); save(prepared); dispatch(prepared)
    }
    fun close(id: String) {
        verifyOwner(); val state = load(); val old = state.attempt ?: return
        checked(old)
        if (old.id != id || state.history.size >= 1000 || old.kind == DomainLifecycleKind.RESET &&
            !old.authorityCleared && old.phase != DomainLifecyclePhase.PREPARED) throw ConflictException()
        save(DomainLifecycleState(state.pair, null, state.history + listOf(old.encode())))
    }
    /** Native caller invokes this only after its account-bound password recovery-cache clear succeeds. */
    fun acknowledgeResetAuthorityCleared(id: String) {
        verifyOwner(); val state = load(); val old = state.attempt ?: throw ProtocolException()
        checked(old)
        if (old.id != id || old.kind != DomainLifecycleKind.RESET || old.phase != DomainLifecyclePhase.COMMITTED) throw ConflictException()
        save(DomainLifecycleState(state.pair, old.cleared(), state.history))
    }
}
