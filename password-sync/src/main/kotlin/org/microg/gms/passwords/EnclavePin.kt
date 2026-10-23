/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.crypto.generators.SCrypt

enum class EnclavePinPhase { REGISTER_PREPARED, REGISTER_LAUNCHED, WRAP_PREPARED, WRAP_LAUNCHED, PIN_PREPARED, PIN_LAUNCHED,
    VAULT_PREPARED, VAULT_LAUNCHED, VAULT_CONFIRMED, JOIN_LAUNCHED, CONFIRMED, REVOKED }
class EnclavePinAttempt(val id: String, val accountId: String, val epoch: Int, val keysDigest: ByteArray,
    val previous: ByteArray, val commands: ByteArray, val pinMetadata: ByteArray, val update: ByteArray, val join: ByteArray,
    val phase: EnclavePinPhase) {
    fun phase(value: EnclavePinPhase) = EnclavePinAttempt(id, accountId, epoch, keysDigest, previous, commands, pinMetadata, update, join, value)
    fun encode() = Proto.field(1, id) + Proto.field(2, accountId) + Proto.integer(3, epoch.toLong()) + Proto.field(4, keysDigest) +
        Proto.field(5, previous) + Proto.field(6, commands) + Proto.field(7, pinMetadata) + Proto.field(8, update) + Proto.field(9, join) + Proto.integer(10, phase.ordinal.toLong())
    fun destroy() { listOf(keysDigest, previous, commands, pinMetadata, update, join).forEach { it.fill(0) } }
    override fun toString() = "EnclavePinAttempt[redacted]"
    companion object {
        fun decode(raw: ByteArray): EnclavePinAttempt {
            val p = Proto.decode(raw); val epoch = p.integer(3) ?: throw ProtocolException()
            val phase = p.integer(10)?.takeIf { it in 0 until EnclavePinPhase.entries.size.toLong() } ?: throw ProtocolException()
            if (p.string(1).length !in 1..128 || p.string(2).length !in 1..256 || epoch !in 1..Int.MAX_VALUE || p.one(4).size != 32) throw ProtocolException()
            return EnclavePinAttempt(p.string(1), p.string(2), epoch.toInt(), p.one(4), p.one(5), p.one(6), p.one(7), p.one(8), p.one(9), EnclavePinPhase.entries[phase.toInt()])
        }
    }
}
class EnclavePinState(val alias: String = "", val spki: ByteArray = byteArrayOf(), val level: String = "", val registered: Boolean = false,
    val memberPublic: ByteArray = byteArrayOf(), val memberPrivate: ByteArray = byteArrayOf(), val epoch: Int = 0,
    val wrappedSecret: ByteArray = byteArrayOf(), val keysDigest: ByteArray = byteArrayOf(), val attempt: EnclavePinAttempt? = null,
    val history: List<ByteArray> = emptyList()) {
    fun withAttempt(next: EnclavePinAttempt?, receipts: List<ByteArray> = history) = EnclavePinState(alias, spki, level, registered,
        memberPublic, memberPrivate, epoch, wrappedSecret, keysDigest, next, receipts)
    fun encode() = Proto.integer(1, 1) + Proto.field(2, alias) + Proto.field(3, spki) + Proto.field(4, level) + Proto.integer(5, if (registered) 1 else 0) +
        Proto.field(6, memberPublic) + Proto.field(7, memberPrivate) + Proto.integer(8, epoch.toLong()) + Proto.field(9, wrappedSecret) + Proto.field(10, keysDigest) +
        (attempt?.let { Proto.field(11, it.encode()) } ?: byteArrayOf()) + history.fold(byteArrayOf()) { out, raw -> out + Proto.field(12, raw) }
    fun destroy() { listOf(spki, memberPublic, memberPrivate, wrappedSecret, keysDigest).forEach { it.fill(0) }; attempt?.destroy(); history.forEach { it.fill(0) } }
    override fun toString() = "EnclavePinState[redacted]"
    companion object {
        fun decode(raw: ByteArray): EnclavePinState {
            if (raw.isEmpty()) return EnclavePinState()
            val p = Proto.decode(raw)
            if (p.integer(1) != 1L || p.bytes(12).size > 100 || p.string(2).length > 256 || p.one(3).size > 4096 ||
                p.string(4) !in setOf("", "hw", "sw") || p.integer(5) !in setOf(0L, 1L) || (p.integer(8) ?: -1) !in 0..Int.MAX_VALUE.toLong()) throw ProtocolException()
            return EnclavePinState(p.string(2), p.one(3), p.string(4), p.integer(5) == 1L, p.one(6), p.one(7), p.integer(8)!!.toInt(),
                p.one(9), p.one(10), p.optional(11)?.let(EnclavePinAttempt::decode), p.bytes(12)).also {
                if (it.registered && (it.spki.isEmpty() || it.memberPublic.size != 65 || it.memberPrivate.isEmpty()) ||
                    it.wrappedSecret.isNotEmpty() && (it.epoch <= 0 || it.keysDigest.size != 32)) throw ProtocolException()
            }
        }
        fun receipt(attempt: EnclavePinAttempt): ByteArray = Proto.field(1, attempt.id) + Proto.integer(2, attempt.phase.ordinal.toLong()) + Proto.field(3, EnclaveNoise.sha(attempt.encode()))
        fun clearDomain(raw: ByteArray, domain: String): ByteArray {
            DomainLifecycleWire.checkedDomain(domain)
            if (domain != GpmPinRenewalWire.DOMAIN) return raw.copyOf()
            val state = decode(raw)
            return try {
                val hashes = (state.history + listOfNotNull(state.attempt?.let(::receipt))).map { Proto.field(1, EnclaveNoise.sha(it)) }
                val bounded = if (hashes.size <= 100) hashes else listOf(Proto.field(1, EnclaveNoise.sha(hashes[0] + hashes[1]))) + hashes.drop(2)
                EnclavePinState(history = bounded).encode()
            } finally { state.destroy() }
        }
    }
}
interface EnclavePinRemote {
    fun transact(commands: ByteArray, signed: Boolean, rapt: String? = null): ByteArray
    fun domain(id: String): ByteArray
    fun members(id: String): List<ByteArray>
    fun vaults(id: String): List<ByteArray>
    fun update(request: ByteArray): ByteArray
    fun join(request: ByteArray): ByteArray
}
/** Exact Chrome145 commands and protobuf producer. PIN material is only inside the protected account journal until wrapped. */
object EnclavePinWire {
    fun register(spki: ByteArray, level: String): ByteArray {
        if (spki.size !in 65..4096 || level !in setOf("hw", "sw")) throw ProtocolException()
        return EnclaveCbor.encode(listOf(mapOf("cmd" to "device/register", "device_id" to EnclaveNoise.sha(spki), "pub_keys" to mapOf(level to spki)),
            mapOf("cmd" to "keys/genpair", "purpose" to "security domain member key")))
    }
    fun ok(raw: ByteArray, count: Int): List<Any> {
        val response = EnclaveCbor.decode(raw) as? List<*> ?: throw ProtocolException()
        if (response.size != count) { EnclaveCbor.destroy(response); throw ProtocolException() }
        return try { response.map { val map = EnclaveCbor.map(it); if (map.containsKey("err")) throw VaultLockedException()
            map["ok"] ?: throw ProtocolException() } } catch (failure: Throwable) { EnclaveCbor.destroy(response); throw failure }
    }
    fun preparePin(pin: ByteArray, wrapped: ByteArray, cert: ByteArray, sig: ByteArray): Pair<ByteArray, ByteArray> {
        if (pin.size !in 1..1024 || wrapped.isEmpty() || cert.size !in 1..262144 || sig.size !in 1..262144) throw ProtocolException()
        Proto.utf8(pin)
        val salt = GpmPinCryptography.random(16); val claim = GpmPinCryptography.random(32)
        val hash = SCrypt.generate(pin, salt, 16384, 8, 1, 32)
        try {
            val form = if (pin.size == 6 && pin.all { it in 48.toByte()..57.toByte() }) 1 else 2
            val metadata = Proto.field(2, claim) + Proto.integer(4, form.toLong()) + Proto.integer(5, 1) + Proto.integer(6, 16384) + Proto.field(7, salt)
            return EnclaveCbor.encode(listOf(mapOf("cmd" to "recovery_key_store/wrap_pin_and_secret", "pin_hash" to hash,
                "pin_claim_key" to claim, "cert_xml" to cert, "sig_xml" to sig, "wrapped_secret" to wrapped))) to metadata
        } finally { salt.fill(0); claim.fill(0); hash.fill(0) }
    }
    fun finishPin(attempt: EnclavePinAttempt, raw: ByteArray, ownVersion: Int, currentKey: ByteArray): EnclavePinAttempt {
        val responses = ok(raw, 1)
        try {
            val result = EnclaveCbor.map(responses.single()); val wrapped = EnclaveCbor.map(result["wrapped"])
            fun bytes(name: String, range: IntRange = 1..65536) = EnclaveCbor.bytes(wrapped[name], range)
            val public = bytes("app_public_key", 65..65).also(EnclaveNoise::publicKey)
            val counter = bytes("counter_id", 8..8); val handle = bytes("vault_handle", 17..17)
            if (handle[0] != 3.toByte()) throw ProtocolException()
            val max = (wrapped["max_attempts"] as? Long)?.takeIf { it in 1..Int.MAX_VALUE } ?: throw ProtocolException()
            val serial = (wrapped["serial"] as? Long)?.takeIf { it in 0..Int.MAX_VALUE } ?: throw ProtocolException()
            val certificates = wrapped["certs_in_path"] as? List<*> ?: throw ProtocolException()
            if (certificates.size !in 1..10) throw ProtocolException()
            val path = DERSequence(ASN1EncodableVector().apply { certificates.forEach { add(ASN1Primitive.fromByteArray(EnclaveCbor.bytes(it, 1..16384))) } }).encoded
            val pin = EnclaveCbor.bytes(result["wrapped_pin"], 28..65536)
            val plain = GpmPinCryptography.openMetadata(pin, currentKey)
            try {
                val parsed = EnclaveCbor.map(EnclaveCbor.decode(plain)); val metadata = Proto.decode(attempt.pinMetadata)
                val command = EnclaveCbor.decode(attempt.commands) as? List<*> ?: throw ProtocolException()
                try {
                    val input = EnclaveCbor.map(command.single())
                    if (!EnclaveCbor.bytes(parsed[1L], 32..32).contentEquals(EnclaveCbor.bytes(input["pin_hash"], 32..32)) ||
                        !EnclaveCbor.bytes(parsed[3L], 32..32).contentEquals(metadata.one(2)) ||
                        !EnclaveCbor.bytes(parsed[4L], 8..8).contentEquals(counter) ||
                        !EnclaveCbor.bytes(parsed[5L], 16..16).contentEquals(handle.copyOfRange(1, 17)) || parsed[6L] != serial ||
                        !EnclaveCbor.bytes(parsed[7L]).contentEquals(bytes("cohort_public_key"))) throw ProtocolException()
                } finally { EnclaveCbor.destroy(parsed); EnclaveCbor.destroy(command) }
            } finally { plain.fill(0) }
            val params = Proto.field(1, bytes("cohort_public_key")) + Proto.field(2, counter) + Proto.integer(3, max) + Proto.field(4, handle)
            val meta = Proto.decode(attempt.pinMetadata)
            val protection = Proto.integer(1, meta.integer(4)!!) + Proto.integer(4, 3) + Proto.field(5, meta.one(7)) + Proto.integer(6, 16384) + Proto.field(7, path)
            val entry = Proto.field(1, GpmPinRenewalWire.ENTRY) + Proto.field(4, Proto.field(1, public) +
                Proto.field(2, bytes("wrapped_app_private_key")) + Proto.field(3, bytes("wrapped_wrapping_key")))
            val vault = Proto.field(2, params) + Proto.field(3, protection) + Proto.field(4, bytes("encrypted_recovery_key")) + Proto.field(5, entry)
            val update = Proto.field(3, vault) + Proto.integer(6, ownVersion.toLong()) + Proto.field(7, RecoverableVaultWire.context(attempt.id, ownVersion))
            val metadata = Proto.field(1, pin) + attempt.pinMetadata
            val member = Proto.field(1, EnrollmentWire.memberName(public)) + Proto.field(2, public) + Proto.integer(4, 5) +
                Proto.field(6, Proto.field(5, Proto.field(2, metadata)))
            val proof = EnclaveCbor.bytes(result["member_proof"], 32..32)
            if (!MessageDigest.isEqual(proof, EnrollmentWire.proof(currentKey, public))) throw ProtocolException()
            val join = Proto.field(1, Proto.field(1, DomainLifecycleWire.name(GpmPinRenewalWire.DOMAIN))) + Proto.field(2, member) +
                Proto.field(3, Proto.integer(1, attempt.epoch.toLong()) + Proto.field(2, EnclaveCbor.bytes(result["wrapped_sds"])) + Proto.field(3, proof)) +
                (attempt.previous.takeIf { it.isNotEmpty() }?.let { Proto.field(5, Proto.decode(it).one(2)) } ?: byteArrayOf()) + Proto.field(6, attempt.id)
            return EnclavePinAttempt(attempt.id, attempt.accountId, attempt.epoch, attempt.keysDigest, attempt.previous,
                byteArrayOf(), byteArrayOf(), update, join, EnclavePinPhase.VAULT_PREPARED)
        } finally { EnclaveCbor.destroy(responses) }
    }
    fun memberMatches(actual: ByteArray, attempt: EnclavePinAttempt): Boolean {
        val expected = Proto.decode(Proto.decode(attempt.join).one(2)); val member = Proto.decode(actual)
        if (member.string(1) != expected.string(1) || !member.one(2).contentEquals(expected.one(2)) || member.integer(4) != 5L ||
            !GpmPinRenewalWire.metadata(actual).contentEquals(GpmPinRenewalWire.metadata(expected.encode()))) return false
        val membership = member.bytes(3).map(Proto::decode).singleOrNull { it.string(1) == DomainLifecycleWire.name(GpmPinRenewalWire.DOMAIN) } ?: return false
        val expectedKey = Proto.decode(Proto.decode(attempt.join).bytes(3).single())
        val key = membership.bytes(3).map(Proto::decode).singleOrNull { it.integer(1) == attempt.epoch.toLong() } ?: return false
        return key.one(2).contentEquals(expectedKey.one(2)) && key.one(3).contentEquals(expectedKey.one(3))
    }
}

/** Durable per-account commands. A launched command never becomes replayable, even after process death or refusal. */
class EnclavePinManager(private val accountId: String, private val ownVersion: Int, private val remote: EnclavePinRemote,
    private val keys: () -> List<DomainLifecycleKey>, private val load: () -> EnclavePinState, private val save: (EnclavePinState) -> Unit,
    private val authorize: () -> Unit) {
    private fun persist(state: EnclavePinState) { authorize(); save(state); authorize() }
    private fun <T> fresh(attempt: EnclavePinAttempt? = null, action: (List<DomainLifecycleKey>, List<ByteArray>) -> T): T {
        authorize(); if (attempt != null && attempt.accountId != accountId) throw VaultLockedException()
        val material = keys()
        try {
            val id = attempt?.id ?: UUID.randomUUID().toString()
            val before = GpmPinRenewalWire.epoch(remote.domain(id)); val members = remote.members(id)
            val after = GpmPinRenewalWire.epoch(remote.domain(id))
            if (before != after || material.none { it.epoch == after } || material.any { it.epoch > after }) throw VaultResetException()
            if (attempt != null && (attempt.epoch != after || !attempt.keysDigest.contentEquals(RecoverableVaultWire.keysDigest(material)))) throw VaultResetException()
            authorize(); return action(material, members).also { authorize() }
        } finally { material.forEach(DomainLifecycleKey::close) }
    }
    fun hasPin(): Boolean = fresh { _, members -> members.any { Proto.decode(it).integer(4) == 5L } }
    fun prepareAlias(alias: String) {
        authorize(); val state = load()
        if (state.alias.isNotEmpty() || state.attempt != null || alias.isBlank() || alias.length > 256) throw PendingMutationException()
        persist(EnclavePinState(alias = alias, history = state.history))
    }
    private fun newAttempt(phase: EnclavePinPhase, commands: ByteArray, metadata: ByteArray = byteArrayOf(), previous: ByteArray = byteArrayOf()): EnclavePinAttempt =
        fresh { material, _ -> EnclavePinAttempt(UUID.randomUUID().toString(), accountId, material.maxOf { it.epoch }, RecoverableVaultWire.keysDigest(material),
            previous, commands, metadata, byteArrayOf(), byteArrayOf(), phase) }
    private fun archive(state: EnclavePinState, attempt: EnclavePinAttempt): List<ByteArray> {
        if (state.history.size >= 100) throw ProtocolException()
        return state.history + listOf(EnclavePinState.receipt(attempt))
    }
    fun register(spki: ByteArray, level: String) {
        authorize(); var state = load()
        if (state.registered) return
        if (state.alias.isBlank() || state.attempt != null) throw PendingMutationException()
        val attempt = newAttempt(EnclavePinPhase.REGISTER_PREPARED, EnclavePinWire.register(spki, level))
        state = EnclavePinState(state.alias, spki, level, attempt = attempt, history = state.history); persist(state)
        persist(state.withAttempt(attempt.phase(EnclavePinPhase.REGISTER_LAUNCHED)))
        val response = remote.transact(attempt.commands, false)
        try {
            authorize(); val results = EnclavePinWire.ok(response, 2)
            try {
                val generated = EnclaveCbor.map(results[1]); val public = EnclaveCbor.bytes(generated["pub_key"], 65..65).also(EnclaveNoise::publicKey)
                val private = EnclaveCbor.bytes(generated["priv_key"], 1..65536)
                fresh(attempt) { _, _ -> persist(EnclavePinState(state.alias, spki, level, true, public, private, history = archive(state, attempt.phase(EnclavePinPhase.REGISTER_LAUNCHED)))) }
            } finally { EnclaveCbor.destroy(results) }
        } finally { response.fill(0) }
    }
    fun wrapCurrentKey() = fresh { material, _ ->
        val state = load(); if (!state.registered || state.attempt != null) throw PendingMutationException()
        val current = material.maxByOrNull { it.epoch } ?: throw VaultLockedException(); val digest = RecoverableVaultWire.keysDigest(material)
        if (state.epoch == current.epoch && state.keysDigest.contentEquals(digest) && state.wrappedSecret.isNotEmpty()) return@fresh
        val raw = current.material
        val command = try { EnclaveCbor.encode(listOf(mapOf("cmd" to "keys/wrap", "purpose" to "security domain secret", "key" to raw))) } finally { raw.fill(0) }
        val attempt = EnclavePinAttempt(UUID.randomUUID().toString(), accountId, current.epoch, digest, byteArrayOf(), command, byteArrayOf(), byteArrayOf(), byteArrayOf(), EnclavePinPhase.WRAP_PREPARED)
        persist(state.withAttempt(attempt)); persist(state.withAttempt(attempt.phase(EnclavePinPhase.WRAP_LAUNCHED)))
        val response = remote.transact(command, true)
        try { authorize(); val result = EnclavePinWire.ok(response, 1)
            try { val wrapped = EnclaveCbor.bytes(result.single(), 1..65536)
                fresh(attempt) { _, _ -> persist(EnclavePinState(state.alias, state.spki, state.level, true, state.memberPublic, state.memberPrivate,
                    current.epoch, wrapped, digest, history = archive(state, attempt.phase(EnclavePinPhase.WRAP_LAUNCHED)))) }
            } finally { EnclaveCbor.destroy(result) }
        } finally { response.fill(0) }
    }
    fun setPin(pin: ByteArray, change: Boolean, rapt: String, certificates: Pair<ByteArray, ByteArray>) {
        if (rapt.isBlank() || rapt.length > 16384) throw VaultLockedException()
        fresh { material, members ->
            val state = load(); if (!state.registered || state.wrappedSecret.isEmpty() || state.attempt != null) throw PendingMutationException()
            if (state.epoch != material.maxOf { it.epoch } || !state.keysDigest.contentEquals(RecoverableVaultWire.keysDigest(material))) throw VaultResetException()
            val pins = members.filter { Proto.decode(it).integer(4) == 5L }
            if (pins.size > 1 || change != pins.isNotEmpty()) throw ConflictException()
            val previous = pins.singleOrNull()?.also(GpmPinRenewalWire::metadata) ?: byteArrayOf()
            val (command, metadata) = EnclavePinWire.preparePin(pin, state.wrappedSecret, certificates.first, certificates.second)
            val attempt = EnclavePinAttempt(UUID.randomUUID().toString(), accountId, state.epoch, state.keysDigest, previous, command, metadata, byteArrayOf(), byteArrayOf(), EnclavePinPhase.PIN_PREPARED)
            persist(state.withAttempt(attempt)); persist(state.withAttempt(attempt.phase(EnclavePinPhase.PIN_LAUNCHED)))
            val response = remote.transact(command, true, rapt)
            try { authorize(); fresh(attempt) { actual, currentMembers ->
                requirePrevious(attempt, currentMembers)
                val raw = actual.single { it.epoch == attempt.epoch }.material
                val prepared = try { EnclavePinWire.finishPin(attempt, response, ownVersion, raw) } finally { raw.fill(0) }
                persist(state.withAttempt(prepared))
            } } finally { response.fill(0) }
        }
        continueOnce(load().attempt?.id ?: throw ProtocolException())
    }
    private fun requirePrevious(attempt: EnclavePinAttempt, members: List<ByteArray>) {
        val pins = members.filter { Proto.decode(it).integer(4) == 5L }
        if (attempt.previous.isEmpty()) { if (pins.isNotEmpty()) throw ConflictException() }
        else if (pins.size != 1 || !pins.single().contentEquals(attempt.previous)) throw ConflictException()
    }
    fun reconcile(): EnclavePinState {
        authorize(); val state = load(); val attempt = state.attempt ?: return state
        if (attempt.phase.ordinal < EnclavePinPhase.VAULT_PREPARED.ordinal) return state // No read API proves an unreturned enclave command result.
        return try { fresh(attempt) { _, _ ->
            val wanted = Proto.decode(attempt.update).one(3); val handle = Proto.decode(Proto.decode(wanted).one(2)).one(4)
            val vaults = remote.vaults(attempt.id).filter { RecoverableVaultWire.sameHandle(it, handle) }
            if (vaults.size > 1) throw ProtocolException()
            val matched = vaults.singleOrNull()?.let { RecoverableVaultWire.vaultMatches(it, wanted) } == true
            fresh(attempt) { _, members ->
                val member = members.singleOrNull { Proto.decode(it).string(1) == Proto.decode(Proto.decode(attempt.join).one(2)).string(1) }
                val confirmed = matched && member?.let { EnclavePinWire.memberMatches(it, attempt) } == true
                val next = when {
                    confirmed -> EnclavePinPhase.CONFIRMED
                    attempt.phase == EnclavePinPhase.CONFIRMED -> EnclavePinPhase.REVOKED
                    matched && attempt.phase == EnclavePinPhase.VAULT_LAUNCHED -> EnclavePinPhase.VAULT_CONFIRMED
                    else -> attempt.phase
                }
                state.withAttempt(attempt.phase(next)).also(::persist)
            }
        } } catch (failure: VaultResetException) {
            if (attempt.phase == EnclavePinPhase.CONFIRMED) persist(state.withAttempt(attempt.phase(EnclavePinPhase.REVOKED)))
            throw failure
        }
    }
    fun continueOnce(id: String) {
        var state = reconcile(); var attempt = state.attempt ?: throw ConflictException()
        if (attempt.id != id) throw ConflictException()
        if (attempt.phase == EnclavePinPhase.VAULT_PREPARED) {
            fresh(attempt) { _, members -> requirePrevious(attempt, members) }
            persist(state.withAttempt(attempt.phase(EnclavePinPhase.VAULT_LAUNCHED)))
            remote.update(attempt.update); authorize(); state = reconcile(); attempt = state.attempt ?: throw ProtocolException()
        }
        if (attempt.phase == EnclavePinPhase.VAULT_CONFIRMED) {
            fresh(attempt) { _, members -> requirePrevious(attempt, members) }
            persist(state.withAttempt(attempt.phase(EnclavePinPhase.JOIN_LAUNCHED)))
            remote.join(attempt.join); authorize(); reconcile()
        }
    }
    fun close(id: String) {
        authorize(); val state = load(); val attempt = state.attempt ?: return
        if (attempt.id != id || attempt.accountId != accountId) throw ConflictException()
        val history = archive(state, attempt)
        // An unknown registration has no usable server-issued member key. A new explicit attempt gets a new identity.
        persist(if (!state.registered) EnclavePinState(history = history) else state.withAttempt(null, history))
    }
}
