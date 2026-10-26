/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.bouncycastle.crypto.generators.SCrypt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID

enum class RecoveryRestorePhase { PREPARED, LAUNCHED, OPENED, IMPORT_LAUNCHED, RECOVERED, CONFIRMED, REFUSED, REVOKED }

class RecoveryRestoreVault(raw: ByteArray, val domain: String) {
    private val raw = raw.copyOf()
    private val value = Proto.decode(raw)
    val name = value.string(1)
    val parameters = value.one(2)
    val metadata = value.one(3)
    val challenge = value.one(6)
    val format = Proto.decode(metadata).integer(1)?.toInt() ?: throw ProtocolException()
    val handleType = Proto.decode(parameters).one(4).firstOrNull()?.toInt() ?: throw ProtocolException()
    val entry: ByteArray
    val digest: ByteArray
    val requestScopeDigest: ByteArray
    val remainingAttempts: Int?
    private val maxAttempts: Int
    init {
        DomainLifecycleWire.checkedDomain(domain)
        val params = Proto.decode(parameters)
        val specifiedMax = params.integer(3) ?: 0L
        if (specifiedMax !in 0..1000) throw ProtocolException()
        maxAttempts = specifiedMax.toInt().takeUnless { it == 0 } ?: 10
        if (raw.size > 256 * 1024 || name.length !in 1..1024 || format !in 1..3 ||
            params.one(1).size != 65 || params.one(1)[0] != 4.toByte() || params.one(2).size != 8 ||
            params.one(4).size != 17 || handleType !in setOf(1, 3) || challenge.size !in 1..65536 ||
            Proto.decode(metadata).one(7).size !in 1..65536) throw ProtocolException()
        val entries = value.bytes(5).map(Proto::decode)
        if (entries.size > 100 || entries.map { it.string(1) }.toSet().size != entries.size) throw ProtocolException()
        val selected = entries.singleOrNull { it.string(1) == RecoverableVaultWire.entry(domain) } ?: throw ProtocolException()
        if (selected.optional(3) != null) throw ProtocolException()
        val member = Proto.decode(selected.one(4))
        if (member.one(1).size != 65 || member.one(1)[0] != 4.toByte() || member.one(2).size != 125 ||
            member.one(3).size !in 1..65536) throw ProtocolException()
        entry = selected.encode()
        // ListVaults may issue another fresh challenge or update failed-guess counters. Neither changes the selected backup.
        digest = MessageDigest.getInstance("SHA-256").digest(value.replace(6, null).replace(7, null).encode())
        requestScopeDigest = MessageDigest.getInstance("SHA-256").digest(Proto.field(1, parameters) + Proto.field(2, metadata))
        remainingAttempts = value.integer(7)?.takeIf { it in 0..maxAttempts.toLong() }?.let { maxAttempts - it.toInt() }
    }
    fun encode() = raw.copyOf()
    fun domains(): List<String> = listOf("chromesync", "hw_protected").filter { candidate ->
        val present = value.bytes(5).any { Proto.decode(it).string(1) == RecoverableVaultWire.entry(candidate) }
        if (present) RecoveryRestoreVault(raw, candidate)
        present
    }
    /** RecoveryRequest owns its challenge; ListVaults refreshes only the backup entries and public scope. */
    fun withChallenge(challenge: ByteArray): RecoveryRestoreVault {
        if (challenge.size !in 1..65536) throw ProtocolException()
        return RecoveryRestoreVault(value.replace(6, challenge).encode(), domain)
    }
    fun verifierParams(): ByteArray {
        val p = Proto.decode(parameters)
        return p.one(1) + p.one(2) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(maxAttempts).array() + p.one(4)
    }
    override fun toString() = "RecoveryRestoreVault[redacted]"
}

/** fwyy/fwyx: the secret is already encoded by the protected native lock-screen input producer. */
object RecoveryRestoreWire {
    /** SDK requests name the vault parameters/protection, not a security domain. The returned entries determine it. */
    fun selectSdk(vaults: List<ByteArray>, preferredDomain: String, scopeDigest: ByteArray): RecoveryRestoreVault {
        DomainLifecycleWire.checkedDomain(preferredDomain)
        if (scopeDigest.size != 32) throw ProtocolException()
        val domains = listOf(preferredDomain) + listOf("chromesync", "hw_protected").filter { it != preferredDomain }
        return vaults.mapNotNull { raw -> domains.firstNotNullOfOrNull { candidate ->
            runCatching { RecoveryRestoreVault(raw, candidate) }.getOrNull() } }
            .singleOrNull { MessageDigest.isEqual(it.requestScopeDigest, scopeDigest) } ?: throw ConflictException()
    }
    /** fsjq and current Android LockscreenCredential use the same low-byte representation of valid32..127 chars. */
    fun secretBytes(format: Int, secret: CharArray, vaultType: Int = 1): ByteArray {
        if (vaultType == 3) {
            if (format !in 1..2 || secret.size !in 1..1024 || format == 1 &&
                (secret.size != 6 || secret.any { it !in '0'..'9' })) throw ProtocolException()
            // Chrome HashPINSlowly consumes UTF8 verbatim, without Android's truncation or normalization.
            val buffer = try { Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(secret)) }
            catch (_: java.nio.charset.CharacterCodingException) { throw ProtocolException() }
            return try {
                if (buffer.remaining() !in 1..1024) throw ProtocolException()
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            } finally { if (buffer.hasArray()) buffer.array().fill(0) }
        }
        if (vaultType != 1) throw ProtocolException()
        if (format !in 1..3 || secret.size !in 1..1024 || secret.any { it.code !in 32..127 } ||
            format == 1 && secret.any { it !in '0'..'9' } || format == 3 &&
            (secret.size !in 4..9 || secret.any { it !in '1'..'9' } || secret.toSet().size != secret.size)) throw ProtocolException()
        return ByteArray(secret.size) { secret[it].code.toByte() }
    }
    fun hash(metadata: ByteArray, secret: ByteArray): ByteArray {
        if (secret.size !in 1..1024) throw ProtocolException()
        val p = Proto.decode(metadata); val salt = p.one(5)
        if (salt.size > 1024) throw ProtocolException()
        return when (p.integer(4)) {
            1L -> {
                val buffer = ByteBuffer.allocate(salt.size + secret.size + 8).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(salt.size).put(salt).putInt(secret.size).put(secret).array()
                try { MessageDigest.getInstance("SHA-256").digest(buffer) } finally { buffer.fill(0) }
            }
            3L -> {
                val n = p.integer(6) ?: throw ProtocolException()
                // Bounded native work: genuine Android snapshots use N=4096. Reject hostile or unsupported KDF costs before guessing.
                if (n !in 2..65536 || (n and (n - 1)) != 0L) throw ProtocolException()
                SCrypt.generate(secret, salt, n.toInt(), 8, 1, 32)
            }
            else -> throw ProtocolException()
        }
    }
    fun request(vault: RecoveryRestoreVault, claim: ByteArray, id: String, ownVersion: Int, source: Int = 3): ByteArray {
        if (claim.size !in 1..65536 || ownVersion <= 0 || id.length !in 1..128 || source !in 3..4) throw ProtocolException()
        return Proto.field(2, Proto.decode(vault.parameters).one(4)) + Proto.field(3, claim) + Proto.field(4, vault.challenge) +
            Proto.integer(5, 1) + Proto.integer(8, source.toLong()) + Proto.field(9, RecoverableVaultWire.context(id, ownVersion))
    }
    /** ansi.b followed by ansi.a: wire enum order differs for SOFT_LOCKED and SLOW_DOWN. */
    fun sdkResult(code: Int): Int = when (code) {
        in 1..8 -> code
        9 -> 6
        10 -> 0
        else -> throw ProtocolException()
    }
    /** anrb copies iqcp.field1 verbatim; its unit is not reinterpreted by this consumer. */
    fun retryAfter(response: ByteArray): Long = Proto.decode(response).optional(3)?.let { Proto.decode(it).integer(1) } ?: 0L
    fun opened(response: ByteArray, selected: RecoveryRestoreVault): ByteArray {
        val p = Proto.decode(response)
        if (p.integer(1) != 1L) throw ProtocolException()
        val vault = p.one(2); val actual = Proto.decode(vault)
        // The pinned consumer uses the recovered ciphertext/entries, not an assumed echo of optional List metadata.
        if (actual.optionalString(1)?.let { it != selected.name } == true ||
            actual.optional(2)?.let { !it.contentEquals(selected.parameters) } == true ||
            actual.optional(3)?.let { !it.contentEquals(selected.metadata) } == true || actual.one(4).size !in 1..65536) throw ConflictException()
        val entries = actual.bytes(5).map(Proto::decode)
        if (entries.size > 100 || entries.map { it.string(1) }.toSet().size != entries.size ||
            entries.singleOrNull { it.string(1) == RecoverableVaultWire.entry(selected.domain) }?.encode()?.contentEquals(selected.entry) != true)
            throw ConflictException()
        return vault
    }
}

class RecoveryRestoreAttempt(val id: String, val accountId: String, val domain: String, val parent: String?, val vault: ByteArray,
    val request: ByteArray, val phase: RecoveryRestorePhase, val response: ByteArray? = null, val pair: ByteArray? = null,
    val resultCode: Int? = null, val pairs: Map<String, ByteArray> = emptyMap(), val allDomains: Boolean = false) {
    fun phase(value: RecoveryRestorePhase, response: ByteArray? = this.response, pair: ByteArray? = this.pair,
        result: Int? = resultCode, pairs: Map<String, ByteArray> = this.pairs) =
        RecoveryRestoreAttempt(id, accountId, domain, parent, vault, request, value, response, pair, result, pairs, allDomains)
    fun encode() = Proto.field(1, id) + Proto.field(2, accountId) + Proto.field(3, domain) +
        (parent?.let { Proto.field(4, it) } ?: byteArrayOf()) + Proto.field(5, vault) + Proto.field(6, request) +
        Proto.integer(7, phase.ordinal.toLong()) + (response?.let { Proto.field(8, it) } ?: byteArrayOf()) +
        (pair?.let { Proto.field(9, it) } ?: byteArrayOf()) + (resultCode?.let { Proto.integer(10, it.toLong()) } ?: byteArrayOf()) +
        pairs.toSortedMap().entries.fold(byteArrayOf()) { out, (domain, material) ->
            out + Proto.field(11, Proto.field(1, domain) + Proto.field(2, material)) } +
        if (allDomains) Proto.integer(12, 1) else byteArrayOf()
    override fun toString() = "RecoveryRestoreAttempt[redacted]"
    companion object {
        fun decode(raw: ByteArray): RecoveryRestoreAttempt {
            val p = Proto.decode(raw)
            val phase = p.integer(7)?.takeIf { it in 0 until RecoveryRestorePhase.entries.size.toLong() }
                ?.let { RecoveryRestorePhase.entries[it.toInt()] } ?: throw ProtocolException()
            val domain = DomainLifecycleWire.checkedDomain(p.string(3))
            val vault = RecoveryRestoreVault(p.one(5), domain); val request = Proto.decode(p.one(6))
            val pair = p.optional(9)?.also(EnrollmentWire::checkPair)
            val pairs = p.bytes(11).map { value -> val entry = Proto.decode(value)
                DomainLifecycleWire.checkedDomain(entry.string(1)) to entry.one(2).also(EnrollmentWire::checkPair) }
            val allDomains = when (p.integer(12)) { null, 0L -> false; 1L -> true; else -> throw ProtocolException() }
            if (pairs.size > 2 || pairs.map { it.first }.toSet().size != pairs.size ||
                pairs.isNotEmpty() && (!allDomains || pairs.map { it.first }.toSet() != vault.domains().toSet()) ||
                allDomains && phase in setOf(RecoveryRestorePhase.RECOVERED, RecoveryRestorePhase.CONFIRMED) && pairs.isEmpty()) throw ProtocolException()
            if (p.string(1).length !in 1..128 || p.string(2).length !in 1..256 ||
                request.one(2).contentEquals(Proto.decode(vault.parameters).one(4)).not() ||
                !request.one(4).contentEquals(vault.challenge) || request.integer(5) != 1L || request.integer(8) != 3L ||
                Proto.decode(request.one(9)).string(2) != p.string(1) ||
                (phase in setOf(RecoveryRestorePhase.RECOVERED, RecoveryRestorePhase.CONFIRMED) && pair == null)) throw ProtocolException()
            pair?.let { if (!EnrollmentWire.publicKey(it).contentEquals(Proto.decode(Proto.decode(vault.entry).one(4)).one(1))) throw ProtocolException() }
            pairs.forEach { (candidate, material) ->
                val selected = RecoveryRestoreVault(vault.encode(), candidate)
                if (!EnrollmentWire.publicKey(material).contentEquals(Proto.decode(Proto.decode(selected.entry).one(4)).one(1))) throw ProtocolException()
            }
            if (pairs.isNotEmpty() && pairs.singleOrNull { it.first == domain }?.second?.contentEquals(pair) != true) throw ProtocolException()
            return RecoveryRestoreAttempt(p.string(1), p.string(2), domain, p.optionalString(4)?.let(PasswordApi::checkedParent),
                p.one(5), p.one(6), phase, p.optional(8), pair, p.integer(10)?.toInt(), pairs.toMap(), allDomains)
        }
    }
}
class RecoveryRestoreState(val attempt: RecoveryRestoreAttempt? = null, val history: List<ByteArray> = emptyList(), val resets: List<ByteArray> = emptyList()) {
    fun encode() = Proto.integer(1, 2) + (attempt?.let { Proto.field(2, it.encode()) } ?: byteArrayOf()) +
        history.fold(byteArrayOf()) { out, value -> out + Proto.field(3, value) } +
        resets.fold(byteArrayOf()) { out, value -> out + Proto.field(4, value) }
    fun destroy() { attempt?.let { it.pair?.fill(0); it.pairs.values.forEach { pair -> pair.fill(0) }; it.request.fill(0); it.response?.fill(0); it.vault.fill(0) }; history.forEach { it.fill(0) } }
    override fun toString() = "RecoveryRestoreState[redacted]"
    companion object {
        fun decode(raw: ByteArray): RecoveryRestoreState {
            if (raw.isEmpty()) return RecoveryRestoreState()
            val p = Proto.decode(raw)
            val version = p.integer(1) ?: throw ProtocolException()
            if (version !in 1L..2L || p.bytes(3).size > 100 || p.bytes(4).size > 2 || version == 1L && p.optional(2)?.let {
                val attempt = Proto.decode(it); attempt.bytes(11).isNotEmpty() || attempt.integer(12) == 1L } == true) throw ProtocolException()
            return RecoveryRestoreState(p.optional(2)?.let(RecoveryRestoreAttempt::decode), p.bytes(3), p.bytes(4))
        }
        fun clearDomain(raw: ByteArray, domain: String): ByteArray {
            DomainLifecycleWire.checkedDomain(domain); val state = decode(raw)
            return try {
                val removed = state.attempt?.takeIf { it.domain == domain || domain in it.pairs ||
                    it.allDomains && domain in RecoveryRestoreVault(it.vault, it.domain).domains() }
                val history = state.history.filter { Proto.decode(it).string(1) != domain }
                val digest = MessageDigest.getInstance("SHA-256").run {
                    state.resets.filter { Proto.decode(it).string(1) == domain }.forEach(::update)
                    state.history.filter { Proto.decode(it).string(1) == domain }.forEach(::update)
                    removed?.encode()?.let { encoded -> try { update(encoded) } finally { encoded.fill(0) } }; digest()
                }
                RecoveryRestoreState(state.attempt?.takeUnless { it === removed }, history,
                    state.resets.filter { Proto.decode(it).string(1) != domain } + listOf(Proto.field(1, domain) + Proto.field(2, digest))).encode()
            } finally { state.destroy() }
        }
    }
}

interface RecoveryRestorePlatform : AutoCloseable {
    fun start(vault: RecoveryRestoreVault, secretHash: ByteArray): ByteArray
    fun recover(opened: ByteArray, vault: RecoveryRestoreVault): ByteArray
    fun recoverDomains(opened: ByteArray, vault: RecoveryRestoreVault, domains: List<String>): Map<String, ByteArray> {
        if (domains != listOf(vault.domain)) throw ProtocolException()
        return mapOf(vault.domain to recover(opened, vault))
    }
}
interface RecoveryRestoreRemote {
    fun vaults(): List<ByteArray>
    fun open(request: ByteArray): ByteArray
    /** Exact current member/private pair and complete domain/rotation validation; no guessed or cached keys. */
    fun keys(domain: String, pair: ByteArray, id: String): List<DomainLifecycleKey>
}
interface RecoveryRestoreSink {
    /** Real native consumer atomically saves domain keys and confirmation against the expected protected journal. */
    fun commit(before: RecoveryRestoreState, after: RecoveryRestoreState, keys: List<DomainLifecycleKey>)
    fun commitDomains(before: RecoveryRestoreState, after: RecoveryRestoreState, keys: Map<String, List<DomainLifecycleKey>>) {
        if (keys.keys != setOf(before.attempt?.domain)) throw ProtocolException()
        commit(before, after, keys.values.single())
    }
}
class RecoveryRestoreManager(private val accountId: String, private val domain: String, private val parent: String?,
    private val ownVersion: Int, private val remote: RecoveryRestoreRemote, private val platform: RecoveryRestorePlatform,
    private val sink: RecoveryRestoreSink, private val load: () -> RecoveryRestoreState, private val save: (RecoveryRestoreState) -> Unit,
    private val authorize: () -> Unit) {
    private fun checked(a: RecoveryRestoreAttempt) {
        if (a.accountId != accountId || a.domain != domain) throw VaultLockedException()
        if (a.parent != parent) throw VaultResetException()
    }
    fun restore(digest: ByteArray, secretHash: ByteArray, challengeOverride: ByteArray? = null, allDomains: Boolean = false) {
        authorize()
        var state = load()
        if (allDomains) {
            // This branch is reached only by a fresh admitted SDK call. Terminal receipts
            // need no replay; uncertain launches are never archived automatically.
            val terminal = state.attempt?.takeIf { it.phase in setOf(RecoveryRestorePhase.CONFIRMED, RecoveryRestorePhase.REFUSED) }
            if (terminal != null) {
                if (terminal.accountId != accountId) throw VaultLockedException()
                val receipt = receipt(terminal)
                val retained = boundedSdkHistory(state.history, 99)
                save(RecoveryRestoreState(null, retained + listOf(receipt), state.resets)); state = load()
            }
            if (state.attempt == null && state.history.size >= 100) {
                save(RecoveryRestoreState(null, boundedSdkHistory(state.history, 99), state.resets)); state = load()
            }
        }
        if (state.attempt != null) throw PendingMutationException()
        if (state.history.size >= 100 || digest.size != 32 || secretHash.size != 32) throw ProtocolException()
        val listed = remote.vaults().mapNotNull { runCatching { RecoveryRestoreVault(it, domain) }.getOrNull() }
            .singleOrNull { MessageDigest.isEqual(it.digest, digest) } ?: throw ConflictException()
        val vault = challengeOverride?.let(listed::withChallenge) ?: listed
        val domains = if (allDomains) vault.domains() else listOf(domain)
        authorize()
        val id = UUID.randomUUID().toString()
        val claim = platform.start(vault, secretHash)
        val request = try { RecoveryRestoreWire.request(vault, claim, id, ownVersion) } finally { claim.fill(0) }
        var attempt = RecoveryRestoreAttempt(id, accountId, domain, parent, vault.encode(), request, RecoveryRestorePhase.PREPARED, allDomains = allDomains)
        fun change(next: RecoveryRestoreAttempt) { authorize(); attempt = next; save(RecoveryRestoreState(attempt, state.history, state.resets)) }
        change(attempt)
        change(attempt.phase(RecoveryRestorePhase.LAUNCHED))
        authorize()
        val response = remote.open(request) // One physical guess. Any exception leaves its durable launch fence.
        val code = Proto.decode(response).integer(1)?.takeIf { it in 1..10 }?.toInt() ?: throw ProtocolException()
        if (code != 1) { change(attempt.phase(RecoveryRestorePhase.REFUSED, response = response, result = code)); return }
        domains.forEach { RecoveryRestoreWire.opened(response, RecoveryRestoreVault(vault.encode(), it)) }
        change(attempt.phase(RecoveryRestorePhase.OPENED, response = response, result = code))
        change(attempt.phase(RecoveryRestorePhase.IMPORT_LAUNCHED))
        authorize()
        val pairs = platform.recoverDomains(Proto.decode(response).one(2), vault, domains)
        try {
            if (pairs.keys != domains.toSet()) throw ProtocolException()
            pairs.forEach { (candidate, pair) ->
                EnrollmentWire.checkPair(pair)
                val entry = RecoveryRestoreVault(vault.encode(), candidate).entry
                if (!EnrollmentWire.publicKey(pair).contentEquals(Proto.decode(Proto.decode(entry).one(4)).one(1))) throw ConflictException()
            }
            change(attempt.phase(RecoveryRestorePhase.RECOVERED, pair = pairs.getValue(domain).copyOf(),
                pairs = if (allDomains) pairs.mapValues { it.value.copyOf() } else emptyMap()))
        } finally { pairs.values.forEach { it.fill(0) } }
        finish()
    }
    fun finish(expectedAttemptId: String? = null, expectedScope: ByteArray? = null) {
        authorize(); val state = load(); val attempt = state.attempt ?: throw ConflictException(); checked(attempt)
        if (expectedAttemptId != null && (expectedAttemptId.length !in 1..128 || attempt.id != expectedAttemptId) ||
            expectedScope != null && (expectedScope.size != 32 || !MessageDigest.isEqual(expectedScope,
                RecoveryRestoreVault(attempt.vault, attempt.domain).requestScopeDigest))) throw ConflictException()
        if (attempt.phase == RecoveryRestorePhase.CONFIRMED) return
        if (attempt.phase != RecoveryRestorePhase.RECOVERED) throw PendingMutationException()
        val pairs = attempt.pairs.takeIf { it.isNotEmpty() } ?: mapOf(domain to (attempt.pair ?: throw ProtocolException()))
        val material = LinkedHashMap<String, List<DomainLifecycleKey>>()
        try {
            pairs.forEach { (candidate, pair) ->
                val keys = remote.keys(candidate, pair, attempt.id).also { material[candidate] = it }
                if (keys.isEmpty() || keys.size > 100 || keys.any { it.epoch <= 0 } ||
                    keys.map { it.epoch }.toSet().size != keys.size) throw ProtocolException()
            }
            authorize()
            sink.commitDomains(state, RecoveryRestoreState(attempt.phase(RecoveryRestorePhase.CONFIRMED), state.history, state.resets), material)
            authorize()
        } finally { material.values.forEach { it.forEach(DomainLifecycleKey::close) } }
    }
    fun close(id: String) {
        authorize(); val state = load(); val a = state.attempt ?: return
        if (a.id != id || a.accountId != accountId || a.domain != domain || state.history.size >= 100) throw ConflictException()
        save(RecoveryRestoreState(null, state.history + listOf(receipt(a)), state.resets))
    }
    private fun receipt(a: RecoveryRestoreAttempt): ByteArray {
        val encoded = a.encode()
        val digest = try { MessageDigest.getInstance("SHA-256").digest(encoded) } finally { encoded.fill(0) }
        return Proto.field(1, a.domain) + Proto.field(2, a.id) + Proto.integer(3, a.phase.ordinal.toLong()) + Proto.field(4, digest)
    }
    private fun boundedSdkHistory(history: List<ByteArray>, limit: Int): List<ByteArray> {
        val retained = history.toMutableList()
        while (retained.size > limit) {
            val index = retained.indexOfFirst { Proto.decode(it).integer(3) in setOf(
                RecoveryRestorePhase.CONFIRMED.ordinal.toLong(), RecoveryRestorePhase.REFUSED.ordinal.toLong()) }
            if (index < 0) throw PendingMutationException()
            retained.removeAt(index)
        }
        return retained
    }
}
