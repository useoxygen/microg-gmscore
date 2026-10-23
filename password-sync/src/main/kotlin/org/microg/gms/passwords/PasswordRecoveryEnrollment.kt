/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.math.BigInteger
import org.bouncycastle.asn1.sec.SECNamedCurves

enum class EnrollmentPhase { PREPARED, LAUNCHED, INDETERMINATE, REFUSED, ENROLLED, REVOKED }
class EnrollmentAttempt(val id: String, val accountId: String, val parent: String, val epoch: Int,
    val request: ByteArray, val phase: EnrollmentPhase) {
    fun phase(value: EnrollmentPhase) = EnrollmentAttempt(id, accountId, parent, epoch, request, value)
    fun encode() = Proto.field(1, id) + Proto.field(2, accountId) + Proto.field(3, parent) + Proto.integer(4, epoch.toLong()) +
        Proto.field(5, request) + Proto.integer(6, phase.ordinal.toLong())
    override fun toString() = "EnrollmentAttempt[redacted]"
    companion object {
        fun decode(raw: ByteArray): EnrollmentAttempt {
            val p = Proto.decode(raw)
            val phase = p.integer(6)?.takeIf { it in 0 until EnrollmentPhase.entries.size.toLong() }
                ?.let { EnrollmentPhase.entries[it.toInt()] } ?: throw ProtocolException()
            val id = p.string(1); val account = p.string(2); val epoch = p.integer(4) ?: 0
            if (id.length !in 1..128 || account.length !in 1..256 || epoch !in 1..Int.MAX_VALUE) throw ProtocolException()
            val request = p.one(5)
            if (Proto.decode(request).string(6) != id) throw ProtocolException()
            return EnrollmentAttempt(id, account, PasswordApi.checkedParent(p.string(3)), epoch.toInt(), request, phase)
        }
    }
}

/** This entire state, including its one persistent private pair, lives only under account-bound device AEAD. */
class EnrollmentState(val pair: ByteArray? = null, val attempt: EnrollmentAttempt? = null, val history: List<ByteArray> = emptyList()) {
    fun encode(): ByteArray = Proto.decode(Proto.integer(1, 1) + (pair?.let { Proto.field(2, it) } ?: byteArrayOf()) +
        (attempt?.let { Proto.field(3, it.encode()) } ?: byteArrayOf())).replaceRepeated(4, history).encode()
    override fun toString() = "EnrollmentState[redacted]"
    companion object {
        fun decode(raw: ByteArray): EnrollmentState {
            if (raw.isEmpty()) return EnrollmentState()
            val p = Proto.decode(raw)
            if (p.integer(1) != 1L || p.bytes(4).size > 1000) throw ProtocolException()
            val pair = p.optional(2)?.also(EnrollmentWire::checkPair)
            val attempt = p.optional(3)?.let(EnrollmentAttempt::decode)
            if (attempt != null && pair == null) throw ProtocolException()
            return EnrollmentState(pair, attempt, p.bytes(4))
        }
    }
}

interface EnrollmentCrypto {
    fun generatePair(): ByteArray
    fun wrap(publicKey: ByteArray, material: ByteArray): ByteArray
    fun unwrap(pair: ByteArray, wrapped: ByteArray): ByteArray
}
interface EnrollmentRemote {
    fun domain(requestId: String): ByteArray
    /** Must return a complete, nonduplicated member snapshot for this exact domain. */
    fun members(requestId: String): List<ByteArray>
    fun join(request: ByteArray): ByteArray
}

/** Pinned anwc.C/H/L/P. This deliberately supports only an existing chromesync physical-device member. */
object EnrollmentWire {
    const val DOMAIN = "users/me/securitydomains/chromesync"
    fun checkPair(pair: ByteArray) {
        if (pair.size != 97 || pair[32] != 4.toByte() || pair.take(32).all { it == 0.toByte() }) throw ProtocolException()
        val scalarBytes = pair.copyOfRange(0, 32)
        try {
            val curve = SECNamedCurves.getByName("secp256r1")
            val scalar = BigInteger(1, scalarBytes)
            if (scalar.signum() <= 0 || scalar >= curve.n ||
                !MessageDigest.isEqual(curve.g.multiply(scalar).normalize().getEncoded(false), pair.copyOfRange(32, 97))) throw ProtocolException()
        } finally { scalarBytes.fill(0) }
    }
    fun publicKey(pair: ByteArray): ByteArray { checkPair(pair); return pair.copyOfRange(32, 97) }
    fun memberName(public: ByteArray): String = "users/me/members/" + Base64.getUrlEncoder().withoutPadding().encodeToString(public)
    fun proof(material: ByteArray, public: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(material, "HmacSHA256")); doFinal(public)
    }
    fun currentEpoch(domain: ByteArray): Int {
        val p = Proto.decode(domain); val epoch = p.integer(2) ?: 0
        if (p.string(1) != DOMAIN || epoch !in 1..Int.MAX_VALUE) throw VaultLockedException()
        return epoch.toInt()
    }
    fun request(pair: ByteArray, keys: List<VersionedTrustedVaultKey>, requestId: String, crypto: EnrollmentCrypto): ByteArray {
        val public = publicKey(pair)
        if (requestId.isEmpty() || requestId.length > 128 || keys.isEmpty() || keys.size > 100 ||
            keys.map { it.epoch }.toSet().size != keys.size) throw ProtocolException()
        val member = Proto.field(1, memberName(public)) + Proto.field(2, public) + Proto.integer(4, 1) +
            Proto.field(6, Proto.field(3, Proto.integer(1, 1)))
        val wrapped = keys.sortedBy { it.epoch }.map { key ->
            val raw = key.material
            try { Proto.integer(1, key.epoch.toLong()) + Proto.field(2, crypto.wrap(public, raw)) + Proto.field(3, proof(raw, public)) }
            finally { raw.fill(0) }
        }
        // P() passes zero for proto3 field4 and no optional proof5; both are omitted on the wire.
        return Proto.field(1, Proto.field(1, DOMAIN)) + Proto.field(2, member) +
            wrapped.fold(byteArrayOf()) { out, key -> out + Proto.field(3, key) } + Proto.field(6, requestId)
    }
    fun checkRequest(request: ByteArray, pair: ByteArray, keys: List<VersionedTrustedVaultKey>, epoch: Int) {
        val p = Proto.decode(request); val public = publicKey(pair); val member = Proto.decode(p.one(2))
        if (Proto.decode(p.one(1)).string(1) != DOMAIN || member.string(1) != memberName(public) ||
            !MessageDigest.isEqual(member.one(2), public) || member.integer(4) != 1L ||
            !member.one(6).contentEquals(Proto.field(3, Proto.integer(1, 1))) ||
            p.integer(4)?.let { it != 0L } == true || p.optional(5) != null) throw ProtocolException()
        val entries = p.bytes(3).map(Proto::decode)
        if (entries.size != keys.size || keys.none { it.epoch == epoch } ||
            entries.map { it.integer(1) }.toSet() != keys.map { it.epoch.toLong() }.toSet()) throw ConflictException()
        for (key in keys) {
            val raw = key.material
            try { if (!MessageDigest.isEqual(entries.single { it.integer(1) == key.epoch.toLong() }.one(3), proof(raw, public))) throw ConflictException() }
            finally { raw.fill(0) }
        }
    }
    fun validateMember(member: ByteArray, pair: ByteArray, keys: List<VersionedTrustedVaultKey>, epoch: Int, crypto: EnrollmentCrypto) {
        val p = Proto.decode(member); val public = publicKey(pair)
        if (p.string(1) != memberName(public) || !MessageDigest.isEqual(p.one(2), public) || p.integer(4) != 1L) throw ProtocolException()
        val domain = p.bytes(3).map(Proto::decode).singleOrNull { it.string(1) == DOMAIN } ?: throw ProtocolException()
        val shared = domain.bytes(3).map(Proto::decode)
        if (shared.isEmpty() || shared.size > 100 || shared.map { it.integer(1) }.toSet().size != shared.size ||
            keys.none { it.epoch == epoch } || shared.none { it.integer(1) == epoch.toLong() }) throw ProtocolException()
        for (key in keys) {
            val record = shared.singleOrNull { it.integer(1) == key.epoch.toLong() } ?: throw ProtocolException()
            val raw = key.material
            var opened: ByteArray? = null
            try { opened = crypto.unwrap(pair, record.one(2))
                if (!MessageDigest.isEqual(opened, raw) || !MessageDigest.isEqual(record.one(3), proof(raw, public))) throw ProtocolException() }
            finally { raw.fill(0); opened?.fill(0) }
        }
    }
}

/** Caller serializes one account's enrollment, retaining the pair across every attempt and explicit closure. */
class EnrollmentManager(private val accountId: String, private val parent: String,
    private val keys: () -> List<VersionedTrustedVaultKey>, private val crypto: EnrollmentCrypto,
    private val remote: EnrollmentRemote, private val load: () -> EnrollmentState, private val save: (EnrollmentState) -> Unit,
    private val verifyOwner: () -> Unit, private val existingPair: () -> ByteArray? = { null }) {
    private fun check(attempt: EnrollmentAttempt) {
        if (attempt.accountId != accountId) throw VaultLockedException()
        if (attempt.parent != parent) throw VaultResetException()
        if (Proto.decode(attempt.request).string(6) != attempt.id) throw ProtocolException()
    }
    private fun <T> withKeys(action: (List<VersionedTrustedVaultKey>) -> T): T {
        verifyOwner()
        val material = keys()
        try {
            if (material.isEmpty() || material.size > 100 || material.map { it.epoch }.toSet().size != material.size) throw VaultLockedException()
            return action(material)
        } finally { material.forEach(VersionedTrustedVaultKey::destroy) }
    }
    private fun read(attempt: EnrollmentAttempt, pair: ByteArray, material: List<VersionedTrustedVaultKey>): Boolean {
        check(attempt); EnrollmentWire.checkRequest(attempt.request, pair, material, attempt.epoch)
        verifyOwner()
        if (EnrollmentWire.currentEpoch(remote.domain(attempt.id)) != attempt.epoch) throw VaultResetException()
        verifyOwner()
        val members = remote.members(attempt.id)
        verifyOwner()
        if (members.size > 10_000 || members.map { Proto.decode(it).string(1) }.toSet().size != members.size) throw ProtocolException()
        val member = members.singleOrNull { Proto.decode(it).string(1) == EnrollmentWire.memberName(EnrollmentWire.publicKey(pair)) }
        if (member != null) EnrollmentWire.validateMember(member, pair, material, attempt.epoch, crypto)
        // Recheck the domain after member I/O; enrollment never authorizes an epoch reset/rotation.
        verifyOwner()
        if (EnrollmentWire.currentEpoch(remote.domain(attempt.id)) != attempt.epoch) throw VaultResetException()
        verifyOwner(); return member != null
    }
    fun reconcile(): EnrollmentState {
        verifyOwner()
        val state = load(); val attempt = state.attempt ?: return state
        check(attempt)
        return withKeys { material ->
            val enrolled = read(attempt, state.pair ?: throw ProtocolException(), material)
            val phase = if (enrolled) EnrollmentPhase.ENROLLED else when (attempt.phase) {
                EnrollmentPhase.LAUNCHED -> EnrollmentPhase.INDETERMINATE
                EnrollmentPhase.ENROLLED -> EnrollmentPhase.REVOKED
                else -> attempt.phase
            }
            EnrollmentState(state.pair, attempt.phase(phase), state.history).also { verifyOwner(); save(it) }
        }
    }
    fun enroll() = withKeys { material ->
        val state = load()
        if (state.attempt != null) throw PendingMutationException()
        if (state.history.size >= 1000) throw ProtocolException()
        val id = UUID.randomUUID().toString()
        val epoch = EnrollmentWire.currentEpoch(remote.domain(id))
        verifyOwner()
        if (material.none { it.epoch == epoch }) throw VaultLockedException()
        val pair = state.pair ?: existingPair() ?: crypto.generatePair()
        EnrollmentWire.checkPair(pair)
        val attempt = EnrollmentAttempt(id, accountId, parent, epoch, EnrollmentWire.request(pair, material, id, crypto), EnrollmentPhase.PREPARED)
        val prepared = EnrollmentState(pair, attempt, state.history)
        verifyOwner(); save(prepared)
        if (read(attempt, pair, material)) save(prepared.copyAttempt(attempt.phase(EnrollmentPhase.ENROLLED)))
        else launch(prepared, material)
    }
    fun retry(id: String) {
        val state = reconcile(); val old = state.attempt ?: return
        if (old.id != id) throw ConflictException()
        if (old.phase == EnrollmentPhase.ENROLLED) return
        if (state.history.size >= 999) throw ProtocolException()
        withKeys { material ->
            // Recheck after the owner's fresh key snapshot as well; never resend an already confirmed member.
            if (read(old, state.pair ?: throw ProtocolException(), material)) {
                verifyOwner(); save(state.copyAttempt(old.phase(EnrollmentPhase.ENROLLED))); return@withKeys
            }
            // Keep the same pair and wrapped bytes; only the explicit attempt identifier changes.
            val nextId = UUID.randomUUID().toString()
            val request = Proto.decode(old.request).replace(6, nextId.toByteArray(Charsets.UTF_8)).encode()
            val next = EnrollmentAttempt(nextId, accountId, parent, old.epoch, request, EnrollmentPhase.PREPARED)
            EnrollmentWire.checkRequest(request, state.pair ?: throw ProtocolException(), material, old.epoch)
            val prepared = EnrollmentState(state.pair, next, state.history + listOf(old.encode()))
            verifyOwner(); save(prepared)
            launch(prepared, material)
        }
    }
    private fun launch(state: EnrollmentState, material: List<VersionedTrustedVaultKey>) {
        val attempt = state.attempt ?: throw ProtocolException()
        val pair = state.pair ?: throw ProtocolException()
        verifyOwner(); save(state.copyAttempt(attempt.phase(EnrollmentPhase.LAUNCHED)))
        var dispatched = false
        var returned = false
        try {
            verifyOwner(); dispatched = true
            val response = Proto.decode(remote.join(attempt.request)).one(1)
            returned = true
            verifyOwner()
            if (EnrollmentWire.currentEpoch(response) != attempt.epoch || !read(attempt, pair, material)) throw ProtocolException()
            verifyOwner(); save(state.copyAttempt(attempt.phase(EnrollmentPhase.ENROLLED)))
        } catch (failure: Exception) {
            save(state.copyAttempt(attempt.phase(when {
                !dispatched -> EnrollmentPhase.PREPARED
                !returned && failure is PasswordRpcException && !failure.outcomeUnknown -> EnrollmentPhase.REFUSED
                else -> EnrollmentPhase.INDETERMINATE
            })))
            throw failure
        }
    }
    fun close(id: String) {
        verifyOwner()
        val state = load(); val attempt = state.attempt ?: return
        if (attempt.accountId != accountId) throw VaultLockedException()
        if (attempt.id != id || attempt.phase == EnrollmentPhase.ENROLLED || state.history.size >= 1000) throw ConflictException()
        save(EnrollmentState(state.pair, null, state.history + listOf(attempt.encode())))
    }
    private fun EnrollmentState.copyAttempt(value: EnrollmentAttempt) = EnrollmentState(pair, value, history)
    override fun toString() = "EnrollmentManager[redacted]"
}
