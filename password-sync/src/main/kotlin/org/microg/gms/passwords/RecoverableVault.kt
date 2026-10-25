/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey

enum class RecoverableVaultPhase { WAITING_FOR_SNAPSHOT, PREPARED, VAULT_LAUNCHED, VAULT_CONFIRMED, JOIN_LAUNCHED, CONFIRMED, REVOKED }

/** An actual Android recoverable-keystore snapshot, never a locally fabricated recovery ciphertext. */
class RecoverableVaultSnapshot(val version: Int, val publicKey: ByteArray, val counter: Long, val attempts: Int,
    val serverParams: ByteArray, val protection: ByteArray, val recoveryKey: ByteArray, val wrappedKey: ByteArray) {
    init {
        if (version <= 0 || publicKey.size != 65 || publicKey[0] != 4.toByte() || attempts <= 0 ||
            serverParams.size != 17 || serverParams[0] != 1.toByte() || protection.size !in 1..65536 ||
            recoveryKey.size !in 1..65536 || wrappedKey.size !in 1..65536) throw ProtocolException()
    }
    override fun toString() = "RecoverableVaultSnapshot[redacted]"
}

class RecoverableVaultAttempt(val id: String, val accountId: String, val parent: String?, val domain: String,
    val epoch: Int, val keyDigest: ByteArray, val join: ByteArray, val update: ByteArray?, val phase: RecoverableVaultPhase) {
    fun phase(value: RecoverableVaultPhase, request: ByteArray? = update) =
        RecoverableVaultAttempt(id, accountId, parent, domain, epoch, keyDigest, join, request, value)
    fun encode() = Proto.field(1, id) + Proto.field(2, accountId) + (parent?.let { Proto.field(3, it) } ?: byteArrayOf()) +
        Proto.field(4, domain) + Proto.integer(5, epoch.toLong()) + Proto.field(6, keyDigest) + Proto.field(7, join) +
        (update?.let { Proto.field(8, it) } ?: byteArrayOf()) + Proto.integer(9, phase.ordinal.toLong())
    override fun toString() = "RecoverableVaultAttempt[redacted]"
    companion object {
        fun decode(raw: ByteArray): RecoverableVaultAttempt {
            val p = Proto.decode(raw)
            val phase = p.integer(9)?.takeIf { it in 0 until RecoverableVaultPhase.entries.size.toLong() }
                ?.let { RecoverableVaultPhase.entries[it.toInt()] } ?: throw ProtocolException()
            val epoch = p.integer(5) ?: 0
            if (p.string(1).length !in 1..128 || p.string(2).length !in 1..256 || epoch !in 1..Int.MAX_VALUE ||
                p.one(6).size != 32 || Proto.decode(p.one(7)).string(6) != p.string(1) ||
                (phase != RecoverableVaultPhase.WAITING_FOR_SNAPSHOT && p.optional(8) == null)) throw ProtocolException()
            return RecoverableVaultAttempt(p.string(1), p.string(2), p.optionalString(3)?.let(PasswordApi::checkedParent),
                DomainLifecycleWire.checkedDomain(p.string(4)), epoch.toInt(), p.one(6), p.one(7), p.optional(8), phase)
        }
    }
}

/** Account-bound device AEAD holds the stable full97 pair and exact finite requests, including unknown launches. */
class RecoverableVaultState(val pair: ByteArray? = null, val alias: String? = null, val handle: ByteArray? = null,
    val attempt: RecoverableVaultAttempt? = null, val history: List<ByteArray> = emptyList(), val resetReceipts: List<ByteArray> = emptyList(),
    val hardwarePair: ByteArray? = null) {
    fun pairFor(domain: String): ByteArray? = if (domain == "chromesync") pair else hardwarePair
    fun pairs(): Map<String, ByteArray> = listOfNotNull(pair?.let { "chromesync" to it }, hardwarePair?.let { "hw_protected" to it }).toMap()
    fun withAttempt(value: RecoverableVaultAttempt?) = RecoverableVaultState(pair, alias, handle, value, history, resetReceipts, hardwarePair)
    fun encode() = Proto.integer(1, 1) + (pair?.let { Proto.field(2, it) } ?: byteArrayOf()) +
        (alias?.let { Proto.field(3, it) } ?: byteArrayOf()) + (handle?.let { Proto.field(4, it) } ?: byteArrayOf()) +
        (attempt?.let { Proto.field(5, it.encode()) } ?: byteArrayOf()) +
        history.fold(byteArrayOf()) { out, value -> out + Proto.field(6, value) } +
        resetReceipts.fold(byteArrayOf()) { out, value -> out + Proto.field(7, value) } +
        (hardwarePair?.let { Proto.field(8, it) } ?: byteArrayOf())
    fun destroy() {
        pair?.fill(0); hardwarePair?.fill(0); handle?.fill(0); history.forEach { it.fill(0) }
        attempt?.let { it.keyDigest.fill(0); it.join.fill(0); it.update?.fill(0) }
    }
    override fun toString() = "RecoverableVaultState[redacted]"
    companion object {
        /** Confirmed reset scrubs only this domain's old wrapped authority; hashes retain finite provenance. */
        fun clearDomain(raw: ByteArray, domain: String): ByteArray {
            DomainLifecycleWire.checkedDomain(domain)
            val state = decode(raw)
            return try {
                val removed = state.attempt?.takeIf { it.domain == domain }
                val matching = state.history.filter { Proto.decode(it).optionalString(4) == domain }
                val prior = state.resetReceipts.singleOrNull { Proto.decode(it).string(1) == domain }
                val hash = MessageDigest.getInstance("SHA-256").run {
                    prior?.let(::update); matching.forEach(::update); removed?.encode()?.let(::update); digest()
                }
                val receipts = state.resetReceipts.filter { Proto.decode(it).string(1) != domain } +
                    listOf(Proto.field(1, domain) + Proto.field(2, hash))
                RecoverableVaultState(state.pair, state.alias, state.handle,
                    state.attempt?.takeUnless { it.domain == domain },
                    state.history.filter { Proto.decode(it).optionalString(4) != domain }, receipts, state.hardwarePair).encode()
            } finally { state.destroy() }
        }
        fun decode(raw: ByteArray): RecoverableVaultState {
            if (raw.isEmpty()) return RecoverableVaultState()
            val p = Proto.decode(raw)
            if (p.integer(1) != 1L || p.bytes(6).size > 100 || p.bytes(7).size > 2) throw ProtocolException()
            val pair = p.optional(2)?.also(EnrollmentWire::checkPair)
            val hardwarePair = p.optional(8)?.also(EnrollmentWire::checkPair)
            val alias = p.optionalString(3)?.also { if (!it.startsWith(RecoverableVaultWire.ALIAS_PREFIX) || it.length > 256) throw ProtocolException() }
            val handle = p.optional(4)?.also { if (it.size != 17 || it[0] != 1.toByte()) throw ProtocolException() }
            val attempt = p.optional(5)?.let(RecoverableVaultAttempt::decode)
            if (listOf(pair != null || hardwarePair != null, alias != null, handle != null).toSet().size != 1 ||
                attempt != null && (if (attempt.domain == "chromesync") pair else hardwarePair) == null) throw ProtocolException()
            val receipts = p.bytes(7).map(Proto::decode)
            if (receipts.map { it.string(1) }.toSet().size != receipts.size) throw ProtocolException()
            receipts.forEach { DomainLifecycleWire.checkedDomain(it.string(1)); if (it.one(2).size != 32) throw ProtocolException() }
            return RecoverableVaultState(pair, alias, handle, attempt, p.bytes(6), p.bytes(7), hardwarePair)
        }
    }
}

interface RecoverableVaultPlatform {
    /** Idempotent for this exact persisted alias and handle; no key replacement or foreign alias adoption. */
    fun prepare(alias: String, handle: ByteArray)
    fun snapshot(alias: String, handle: ByteArray): RecoverableVaultSnapshot?
    /** anwh.i wraps the COMPLETE anrr.d = scalar32||public65 using the actual OS AES key. */
    fun wrap(alias: String, pair: ByteArray): ByteArray
    fun synced(alias: String)
}
interface RecoverableVaultRemote {
    fun domain(domain: String, id: String): ByteArray
    fun members(domain: String, id: String): List<ByteArray>
    fun vaults(id: String): List<ByteArray>
    fun update(request: ByteArray): ByteArray
    fun join(request: ByteArray): ByteArray
}

/** Independently transcribed field contracts hgtm/q/s/v/w/k/l, aosv and anwc.L; no proprietary source copied. */
object RecoverableVaultWire {
    const val ALIAS_PREFIX = "com.google.android.gms.auth.folsom/v1/"
    const val ENTRY = "security_domain_member_key"
    const val HARDWARE_ENTRY = "security_domain_member_key_encrypted_locally"
    fun entry(domain: String): String = if (DomainLifecycleWire.checkedDomain(domain) == "chromesync") ENTRY else HARDWARE_ENTRY
    fun memberType(domain: String): Long { DomainLifecycleWire.checkedDomain(domain); return 2 } // anwc.L -> hzxm.a(4).
    fun wrapPair(key: SecretKey, pair: ByteArray): ByteArray {
        EnrollmentWire.checkPair(pair)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key)
            if (iv.size != 12) throw ProtocolException()
            iv + doFinal(pair)
        }
    }
    fun handle(nonce: Long, deviceId: Long): ByteArray = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
        .put(1.toByte()).putLong(nonce).putLong(deviceId).array()
    fun protection(format: Int, algorithm: Int, salt: ByteArray, memory: Int, certPath: ByteArray): ByteArray {
        if (format !in 1..3 || algorithm !in 1..2 || salt.size !in 1..1024 || memory < 0 || certPath.size !in 1..65536) throw ProtocolException()
        return Proto.integer(1, format.toLong()) + (if (format == 3) Proto.integer(2, 3) + Proto.integer(3, 3) else byteArrayOf()) +
            Proto.integer(4, if (algorithm == 1) 1 else 3) + Proto.field(5, salt) + Proto.integer(6, memory.toLong()) + Proto.field(7, certPath)
    }
    fun context(id: String, ownVersion: Int) = Proto.field(2, id) + Proto.integer(3, ownVersion.toLong())
    fun list(id: String, ownVersion: Int, token: String = "", source: Int? = null) = Proto.integer(1, 50) +
        (if (token.isEmpty()) byteArrayOf() else Proto.field(2, token)) + Proto.field(7, context(id, ownVersion)) +
        (source?.let { Proto.integer(8, it.toLong()) } ?: byteArrayOf())
    fun keysDigest(keys: List<DomainLifecycleKey>): ByteArray {
        if (keys.isEmpty() || keys.size > 100 || keys.any { it.epoch <= 0 } || keys.map { it.epoch }.toSet().size != keys.size) throw ProtocolException()
        val encoded = keys.sortedBy { it.epoch }.fold(byteArrayOf()) { out, key -> out + Proto.field(1, key.encode()) }
        return try { MessageDigest.getInstance("SHA-256").digest(encoded) } finally { encoded.fill(0) }
    }
    fun join(domain: String, pair: ByteArray, keys: List<DomainLifecycleKey>, id: String, crypto: EnrollmentCrypto): ByteArray {
        val public = EnrollmentWire.publicKey(pair)
        val member = Proto.field(1, EnrollmentWire.memberName(public)) + Proto.field(2, public) + Proto.integer(4, memberType(domain))
        return Proto.field(1, Proto.field(1, DomainLifecycleWire.name(domain))) + Proto.field(2, member) +
            keys.sortedBy { it.epoch }.fold(byteArrayOf()) { out, key -> val raw = key.material
                try { out + Proto.field(3, Proto.integer(1, key.epoch.toLong()) + Proto.field(2, crypto.wrap(public, raw)) +
                    Proto.field(3, EnrollmentWire.proof(raw, public))) } finally { raw.fill(0) } } + Proto.field(6, id)
    }
    fun update(snapshot: RecoverableVaultSnapshot, pairs: Map<String, Pair<ByteArray, ByteArray>>, id: String, ownVersion: Int): ByteArray {
        if (pairs.isEmpty() || pairs.size > 2 || ownVersion <= 0) throw ProtocolException()
        val params = Proto.field(1, snapshot.publicKey) + Proto.field(2, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(snapshot.counter).array()) +
            Proto.integer(3, snapshot.attempts.toLong()) + Proto.field(4, snapshot.serverParams)
        val entries = pairs.entries.sortedBy { entry(it.key) }.fold(byteArrayOf()) { out, (domain, value) ->
            val public = EnrollmentWire.publicKey(value.first)
            if (value.second.size != 125) throw ProtocolException() // nonce12 + complete pair97 + tag16.
            out + Proto.field(5, Proto.field(1, entry(domain)) + Proto.field(4, Proto.field(1, public) +
                Proto.field(2, value.second) + Proto.field(3, snapshot.wrappedKey)))
        }
        val vault = Proto.field(2, params) + Proto.field(3, snapshot.protection) + Proto.field(4, snapshot.recoveryKey) + entries
        return Proto.field(3, vault) + Proto.integer(6, ownVersion.toLong()) + Proto.field(7, context(id, ownVersion))
    }
    fun vaultMatches(actual: ByteArray, expected: ByteArray): Boolean {
        val a = Proto.decode(actual); val b = Proto.decode(expected)
        // The response's server-assigned name may be new. All cryptographic inputs and entries must match.
        return (2..4).all { field -> (a.optional(field) ?: byteArrayOf()).contentEquals(b.optional(field) ?: byteArrayOf()) } &&
            a.bytes(5).let { values -> val wanted = b.bytes(5)
                values.size == wanted.size && values.indices.all { values[it].contentEquals(wanted[it]) } }
    }
    fun snapshotMatches(snapshot: RecoverableVaultSnapshot, expected: ByteArray): Boolean {
        val p = Proto.decode(expected); val params = Proto.decode(p.one(2))
        val entries = p.bytes(5).map { Proto.decode(Proto.decode(it).one(4)) }
        if (entries.isEmpty()) return false
        return params.one(1).contentEquals(snapshot.publicKey) &&
            params.one(2).contentEquals(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(snapshot.counter).array()) &&
            params.integer(3) == snapshot.attempts.toLong() && params.one(4).contentEquals(snapshot.serverParams) &&
            p.one(3).contentEquals(snapshot.protection) && p.one(4).contentEquals(snapshot.recoveryKey) &&
            entries.all { it.one(3).contentEquals(snapshot.wrappedKey) }
    }
    fun sameHandle(vault: ByteArray, handle: ByteArray) = Proto.decode(vault).optional(2)?.let { Proto.decode(it).one(4).contentEquals(handle) } == true
    fun checkOwnVault(vault: ByteArray, pairs: Map<String, ByteArray>) {
        val entries = Proto.decode(vault).bytes(5).map(Proto::decode)
        if (entries.isEmpty() || entries.size > 2 || entries.map { it.string(1) }.toSet().size != entries.size) throw ConflictException()
        entries.forEach { item ->
            val pair = pairs.entries.singleOrNull { entry(it.key) == item.string(1) }?.value ?: throw ConflictException()
            if (item.optional(3) != null || !MessageDigest.isEqual(Proto.decode(item.one(4)).one(1), EnrollmentWire.publicKey(pair))) throw ConflictException()
        }
    }
    fun memberMatches(raw: ByteArray, domain: String, pair: ByteArray, keys: List<DomainLifecycleKey>, crypto: EnrollmentCrypto): Boolean {
        val p = Proto.decode(raw); val public = EnrollmentWire.publicKey(pair)
        if (p.string(1) != EnrollmentWire.memberName(public) || !MessageDigest.isEqual(p.one(2), public) || p.integer(4) != memberType(domain)) throw ConflictException()
        val shared = p.bytes(3).map(Proto::decode).singleOrNull { it.string(1) == DomainLifecycleWire.name(domain) }?.bytes(3)?.map(Proto::decode) ?: return false
        if (shared.size > 100 || shared.map { it.integer(1) }.toSet().size != shared.size) throw ProtocolException()
        for (key in keys) {
            val record = shared.singleOrNull { it.integer(1) == key.epoch.toLong() } ?: return false
            val rawKey = key.material
            val opened = crypto.unwrap(pair, record.one(2))
            try { if (!MessageDigest.isEqual(rawKey, opened) || !MessageDigest.isEqual(record.one(3), EnrollmentWire.proof(rawKey, public))) throw ProtocolException() }
            finally { rawKey.fill(0); opened.fill(0) }
        }
        return true
    }
}

/** One protected account session. Unknown uploads/joins reconcile by reads and are never replayed. */
class RecoverableVaultManager(private val accountId: String, private val parent: String?, private val domain: String,
    private val ownVersion: Int, private val crypto: EnrollmentCrypto, private val platform: RecoverableVaultPlatform,
    private val remote: RecoverableVaultRemote, private val keys: () -> List<DomainLifecycleKey>,
    private val load: () -> RecoverableVaultState, private val save: (RecoverableVaultState) -> Unit,
    private val authorize: () -> Unit, private val alias: () -> String, private val handle: () -> ByteArray) {
    init { DomainLifecycleWire.checkedDomain(domain) }
    private fun checked(a: RecoverableVaultAttempt) {
        if (a.accountId != accountId || a.domain != domain) throw VaultLockedException()
        if (a.parent != parent) throw VaultResetException()
    }
    private fun <T> fresh(a: RecoverableVaultAttempt?, action: (List<DomainLifecycleKey>, Int) -> T): T {
        authorize(); a?.let(::checked)
        val material = keys()
        try {
            val epoch = Proto.decode(remote.domain(domain, a?.id ?: UUID.randomUUID().toString())).let {
                if (it.string(1) != DomainLifecycleWire.name(domain)) throw ProtocolException()
                it.integer(2)?.takeIf { value -> value in 1..Int.MAX_VALUE }?.toInt() ?: throw VaultLockedException()
            }
            val digest = RecoverableVaultWire.keysDigest(material)
            if (material.none { it.epoch == epoch } || a != null && (a.epoch != epoch || !MessageDigest.isEqual(digest, a.keyDigest))) throw VaultResetException()
            authorize(); return action(material, epoch).also { authorize() }
        } finally { material.forEach(DomainLifecycleKey::close) }
    }
    fun prepare() = fresh(null) { material, epoch ->
        val old = load()
        if (old.attempt != null) throw PendingMutationException()
        if (old.history.size >= 100) throw ProtocolException()
        val pair = old.pairFor(domain) ?: crypto.generatePair().also(EnrollmentWire::checkPair)
        val id = UUID.randomUUID().toString()
        val attempt = RecoverableVaultAttempt(id, accountId, parent, domain, epoch, RecoverableVaultWire.keysDigest(material),
            RecoverableVaultWire.join(domain, pair, material, id, crypto), null, RecoverableVaultPhase.WAITING_FOR_SNAPSHOT)
        // A new explicitly authorized backup gets a fresh handle; only continuation reuses its exact OS snapshot.
        val state = RecoverableVaultState(if (domain == "chromesync") pair else old.pair, alias(),
            handle(), attempt, old.history, old.resetReceipts, if (domain == "hw_protected") pair else old.hardwarePair)
        authorize(); save(state)
        platform.prepare(state.alias!!, state.handle!!)
        advance(state, false)
    }
    fun reconcile(): RecoverableVaultState {
        authorize()
        val state = load(); val a = state.attempt ?: return state
        checked(a)
        return fresh(a) { material, _ ->
            if (a.phase == RecoverableVaultPhase.WAITING_FOR_SNAPSHOT || a.phase == RecoverableVaultPhase.PREPARED) return@fresh state
            val wanted = Proto.decode(a.update ?: throw ProtocolException()).one(3)
            val found = remote.vaults(a.id).filter { RecoverableVaultWire.sameHandle(it, state.handle!!) }
            if (found.size > 1) throw ProtocolException()
            if (found.isEmpty() || !RecoverableVaultWire.vaultMatches(found.single(), wanted)) {
                if (a.phase == RecoverableVaultPhase.CONFIRMED) return@fresh state.withAttempt(a.phase(RecoverableVaultPhase.REVOKED))
                    .also { authorize(); save(it) }
                return@fresh state
            }
            RecoverableVaultWire.checkOwnVault(found.single(), state.pairs())
            val pair = state.pairFor(domain) ?: throw ProtocolException()
            val name = EnrollmentWire.memberName(EnrollmentWire.publicKey(pair))
            val members = remote.members(domain, a.id)
            if (members.size > 4096 || members.map { Proto.decode(it).string(1) }.toSet().size != members.size) throw ProtocolException()
            val member = members.singleOrNull { Proto.decode(it).string(1) == name }
            val confirmed = member != null && RecoverableVaultWire.memberMatches(member, domain, pair, material, crypto)
            val after = Proto.decode(remote.domain(domain, a.id))
            if (after.string(1) != DomainLifecycleWire.name(domain) || after.integer(2) != a.epoch.toLong()) throw VaultResetException()
            authorize()
            val next = state.withAttempt(a.phase(if (confirmed) RecoverableVaultPhase.CONFIRMED else when (a.phase) {
                RecoverableVaultPhase.CONFIRMED, RecoverableVaultPhase.REVOKED -> RecoverableVaultPhase.REVOKED
                RecoverableVaultPhase.JOIN_LAUNCHED -> a.phase
                else -> RecoverableVaultPhase.VAULT_CONFIRMED
            }))
            save(next)
            if (confirmed) platform.synced(state.alias!!)
            next
        }
    }
    fun continueOnce(id: String) {
        val state = reconcile(); val attempt = state.attempt ?: throw ConflictException()
        if (attempt.id != id) throw ConflictException()
        advance(state, true)
    }
    /** A fresh admitted consent request can advance known stages, never resend an uncertain upload or Join. */
    fun syncConsent(): RecoverableVaultState {
        authorize()
        var state = load()
        state.attempt?.let { attempt ->
            if (attempt.accountId != accountId) throw VaultLockedException()
            if (attempt.domain != domain && attempt.phase !in setOf(RecoverableVaultPhase.CONFIRMED, RecoverableVaultPhase.REVOKED)) return state
            if (attempt.domain == domain) {
                checked(attempt)
                if (attempt.phase == RecoverableVaultPhase.CONFIRMED) state = reconcile()
            }
        }
        val old = state.attempt
        if (old != null && (old.domain != domain || old.phase == RecoverableVaultPhase.REVOKED)) {
            if (old.phase !in setOf(RecoverableVaultPhase.CONFIRMED, RecoverableVaultPhase.REVOKED) || state.history.size >= 100)
                throw PendingMutationException()
            // Keep both stable member identities. Reenabling after a verified Leave creates a fresh finite request.
            save(RecoverableVaultState(state.pair, state.alias, state.handle, null, state.history + listOf(old.encode()),
                state.resetReceipts, state.hardwarePair)); state = load()
        }
        if (state.attempt == null) { prepare(); state = load() }
        if (state.attempt?.phase in setOf(RecoverableVaultPhase.VAULT_LAUNCHED, RecoverableVaultPhase.JOIN_LAUNCHED)) state = reconcile()
        if (state.attempt?.phase in setOf(RecoverableVaultPhase.WAITING_FOR_SNAPSHOT, RecoverableVaultPhase.PREPARED,
                RecoverableVaultPhase.VAULT_CONFIRMED)) continueOnce(state.attempt?.id ?: throw ProtocolException())
        authorize(); return load()
    }
    private fun advance(input: RecoverableVaultState, dispatch: Boolean) = fresh(input.attempt) { _, _ ->
        var state = input; var a = state.attempt ?: throw ProtocolException()
        if (a.phase == RecoverableVaultPhase.WAITING_FOR_SNAPSHOT) {
            platform.prepare(state.alias!!, state.handle!!)
            val snapshot = platform.snapshot(state.alias, state.handle) ?: return@fresh
            if (!snapshot.serverParams.contentEquals(state.handle)) throw ConflictException()
            val existing = remote.vaults(a.id).filter { RecoverableVaultWire.sameHandle(it, state.handle) }
            if (existing.size > 1) throw ProtocolException()
            existing.forEach { RecoverableVaultWire.checkOwnVault(it, state.pairs()) }
            val wrapped = state.pairs().mapValues { (_, pair) -> pair to platform.wrap(state.alias, pair) }
            val update = try { RecoverableVaultWire.update(snapshot, wrapped, a.id, ownVersion) }
                finally { wrapped.values.forEach { it.second.fill(0) } }
            a = a.phase(RecoverableVaultPhase.PREPARED, update); state = state.withAttempt(a)
            authorize(); save(state)
        }
        if (!dispatch || a.phase == RecoverableVaultPhase.CONFIRMED) return@fresh
        if (a.phase == RecoverableVaultPhase.REVOKED) throw ConflictException()
        if (a.phase in setOf(RecoverableVaultPhase.VAULT_LAUNCHED, RecoverableVaultPhase.JOIN_LAUNCHED)) throw PendingMutationException()
        if (a.phase == RecoverableVaultPhase.PREPARED) {
            val current = platform.snapshot(state.alias!!, state.handle!!) ?: throw PendingMutationException()
            if (!RecoverableVaultWire.snapshotMatches(current, Proto.decode(a.update!!).one(3))) throw ConflictException()
            authorize(); save(state.withAttempt(a.phase(RecoverableVaultPhase.VAULT_LAUNCHED)))
            authorize()
            val response = remote.update(a.update!!)
            // The returned object alone cannot establish successful backup.
            if (!RecoverableVaultWire.vaultMatches(response, Proto.decode(a.update).one(3))) throw ProtocolException()
            authorize()
            state = reconcile(); a = state.attempt ?: throw ProtocolException()
            if (a.phase != RecoverableVaultPhase.VAULT_CONFIRMED && a.phase != RecoverableVaultPhase.CONFIRMED) throw PendingMutationException()
        }
        if (a.phase == RecoverableVaultPhase.VAULT_CONFIRMED) {
            authorize(); save(state.withAttempt(a.phase(RecoverableVaultPhase.JOIN_LAUNCHED)))
            authorize(); remote.join(a.join)
            authorize()
            if (reconcile().attempt?.phase != RecoverableVaultPhase.CONFIRMED) throw PendingMutationException()
        }
    }
    fun close(id: String) {
        authorize(); val state = load(); val a = state.attempt ?: return
        if (a.id != id || a.accountId != accountId || a.domain != domain) throw ConflictException()
        if (state.history.size >= 100) throw ProtocolException()
        save(RecoverableVaultState(state.pair, state.alias, state.handle, null, state.history + listOf(a.encode()), state.resetReceipts, state.hardwarePair))
    }
}
