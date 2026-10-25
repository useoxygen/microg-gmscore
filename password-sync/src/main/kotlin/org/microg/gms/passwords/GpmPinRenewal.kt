/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

enum class GpmPinRenewalPhase { PREPARED, VAULT_LAUNCHED, VAULT_CONFIRMED, JOIN_LAUNCHED, CONFIRMED, REVOKED }
class GpmPinRenewalAttempt(val id: String, val accountId: String, val epoch: Int, val keysDigest: ByteArray,
    val previousMember: ByteArray, val pair: ByteArray, val update: ByteArray, val join: ByteArray, val phase: GpmPinRenewalPhase) {
    fun phase(value: GpmPinRenewalPhase) = GpmPinRenewalAttempt(id, accountId, epoch, keysDigest, previousMember, pair, update, join, value)
    fun encode() = Proto.field(1, id) + Proto.field(2, accountId) + Proto.integer(3, epoch.toLong()) + Proto.field(4, keysDigest) +
        Proto.field(5, previousMember) + Proto.field(6, pair) + Proto.field(7, update) + Proto.field(8, join) + Proto.integer(9, phase.ordinal.toLong())
    fun destroy() { keysDigest.fill(0); previousMember.fill(0); pair.fill(0); update.fill(0); join.fill(0) }
    override fun toString() = "GpmPinRenewalAttempt[redacted]"
    companion object {
        fun decode(raw: ByteArray): GpmPinRenewalAttempt {
            val p = Proto.decode(raw); val epoch = p.integer(3) ?: 0
            val phase = p.integer(9)?.takeIf { it in 0 until GpmPinRenewalPhase.entries.size.toLong() }
                ?.let { GpmPinRenewalPhase.entries[it.toInt()] } ?: throw ProtocolException()
            if (p.string(1).length !in 1..128 || p.string(2).length !in 1..256 || epoch !in 1..Int.MAX_VALUE || p.one(4).size != 32)
                throw ProtocolException()
            return GpmPinRenewalAttempt(p.string(1), p.string(2), epoch.toInt(), p.one(4), p.one(5), p.one(6), p.one(7), p.one(8), phase).also {
                EnrollmentWire.checkPair(it.pair)
                if (Proto.decode(it.join).string(6) != it.id || Proto.decode(Proto.decode(it.update).one(7)).string(2) != it.id) throw ProtocolException()
            }
        }
    }
}
class GpmPinRenewalState(val attempt: GpmPinRenewalAttempt? = null, val history: List<ByteArray> = emptyList()) {
    fun encode() = Proto.integer(1, 1) + (attempt?.let { Proto.field(2, it.encode()) } ?: byteArrayOf()) +
        history.fold(byteArrayOf()) { out, raw -> out + Proto.field(3, raw) }
    fun destroy() { attempt?.destroy(); history.forEach { it.fill(0) } }
    override fun toString() = "GpmPinRenewalState[redacted]"
    companion object {
        fun decode(raw: ByteArray): GpmPinRenewalState {
            if (raw.isEmpty()) return GpmPinRenewalState()
            val p = Proto.decode(raw)
            if (p.integer(1) != 1L || p.bytes(3).size > 100) throw ProtocolException()
            return GpmPinRenewalState(p.optional(2)?.let(GpmPinRenewalAttempt::decode), p.bytes(3))
        }
        fun clearDomain(raw: ByteArray, domain: String): ByteArray {
            DomainLifecycleWire.checkedDomain(domain)
            if (domain != "hw_protected") return raw.copyOf()
            val state = decode(raw)
            val receipts = state.history + listOfNotNull(state.attempt?.encode())
            return try {
                val hashes = receipts.map {
                Proto.field(1, MessageDigest.getInstance("SHA-256").digest(it))
                }
                val bounded = if (hashes.size <= 100) hashes else listOf(Proto.field(1,
                    MessageDigest.getInstance("SHA-256").digest(hashes.take(2).fold(byteArrayOf()) { out, item -> out + Proto.field(1, item) }))) + hashes.drop(2)
                GpmPinRenewalState(history = bounded).encode()
            } finally { receipts.forEach { it.fill(0) }; state.destroy() }
        }
    }
}

/** A path verified against the platform's actual Google root, with the signed certificate-file serial. */
class GpmPinCertificate(val serial: Long, val publicKey: ByteArray, val certPath: ByteArray) {
    init { if (serial < 0 || publicKey.size !in 65..4096 || certPath.size !in 1..65536) throw ProtocolException() }
    override fun toString() = "GpmPinCertificate[public]"
}
interface GpmPinRenewalCrypto {
    fun pair(): ByteArray
    fun random(size: Int): ByteArray
    fun openMetadata(encrypted: ByteArray, domainKey: ByteArray): ByteArray
    fun sealMetadata(plain: ByteArray, domainKey: ByteArray): ByteArray
    fun aes(key: ByteArray, plain: ByteArray): ByteArray
    fun secureBox(public: ByteArray?, shared: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray
    fun wrapMember(public: ByteArray, key: ByteArray): ByteArray
    fun unwrapMember(pair: ByteArray, wrapped: ByteArray): ByteArray
}
interface GpmPinRenewalRemote {
    fun domain(id: String): ByteArray
    fun members(id: String): List<ByteArray>
    fun vaults(id: String): List<ByteArray>
    fun update(request: ByteArray): ByteArray
    fun join(request: ByteArray): ByteArray
}

/** Pinned anvx/anyp/anwc.r: renew an existing PIN; no PIN enrollment, password collection, or enclave identity. */
object GpmPinRenewalWire {
    const val DOMAIN = "hw_protected"
    const val ENTRY = "security_domain_member_key_encrypted_locally"
    fun metadata(member: ByteArray): ByteArray {
        val p = Proto.decode(member)
        if (p.integer(4) != 5L || p.string(1) != EnrollmentWire.memberName(p.one(2))) throw ProtocolException()
        val metadata = Proto.decode(p.one(6))
        if ((2..7).filter { it != 5 }.any { metadata.optional(it) != null }) throw ProtocolException()
        return Proto.decode(metadata.one(5)).one(2)
    }
    fun pinMember(members: List<ByteArray>): ByteArray {
        if (members.size > 4096 || members.map { Proto.decode(it).string(1) }.toSet().size != members.size) throw ProtocolException()
        return members.filter { Proto.decode(it).integer(4) == 5L }.singleOrNull()?.also(::metadata) ?: throw VaultLockedException()
    }
    fun epoch(raw: ByteArray): Int = Proto.decode(raw).let {
        if (it.string(1) != DomainLifecycleWire.name(DOMAIN)) throw ProtocolException()
        it.integer(2)?.takeIf { value -> value in 1..Int.MAX_VALUE }?.toInt() ?: throw VaultLockedException()
    }
    /** Bounded canonical CBOR integer-key map. Unknown/duplicate fields cannot be silently discarded. */
    private fun readCbor(raw: ByteArray): Map<Int, Any> {
        if (raw.size !in 1..16384) throw ProtocolException()
        var at = 0
        fun byte(): Int = if (at < raw.size) raw[at++].toInt() and 255 else throw ProtocolException()
        fun size(additional: Int): Long = when {
            additional < 24 -> additional.toLong()
            additional in 24..27 -> {
                var result = 0L
                repeat(1 shl (additional - 24)) { if (result < 0 || result > (Long.MAX_VALUE ushr 8)) throw ProtocolException(); result = (result shl 8) or byte().toLong() }
                if (result < 0) throw ProtocolException(); result
            }
            else -> throw ProtocolException()
        }
        val head = byte(); if (head ushr 5 != 5) throw ProtocolException()
        val count = size(head and 31); if (count !in 1..7) throw ProtocolException()
        val out = LinkedHashMap<Int, Any>()
        repeat(count.toInt()) {
            val tag = byte(); if (tag ushr 5 != 0) throw ProtocolException()
            val key = size(tag and 31).takeIf { it in 1..7 }?.toInt() ?: throw ProtocolException()
            val value = byte(); val n = size(value and 31)
            val decoded: Any = when (value ushr 5) {
                0 -> n
                2 -> { if (n > raw.size - at) throw ProtocolException(); raw.copyOfRange(at, at + n.toInt()).also { at += n.toInt() } }
                else -> throw ProtocolException()
            }
            if (out.put(key, decoded) != null) throw ProtocolException()
        }
        if (at != raw.size) throw ProtocolException()
        return out
    }
    private fun cbor(values: Map<Int, Any>): ByteArray {
        val out = ByteArrayOutputStream()
        fun head(major: Int, n: Long) {
            if (n < 0) throw ProtocolException()
            if (n < 24) out.write((major shl 5) or n.toInt()) else {
                val bytes = when { n <= 255 -> 1; n <= 65535 -> 2; n <= 0xffffffffL -> 4; else -> 8 }
                out.write((major shl 5) or when (bytes) { 1 -> 24; 2 -> 25; 4 -> 26; else -> 27 })
                for (i in bytes - 1 downTo 0) out.write((n ushr (i * 8)).toInt() and 255)
            }
        }
        head(5, values.size.toLong())
        values.toSortedMap().forEach { (key, value) -> head(0, key.toLong()); when (value) {
            is Long -> head(0, value)
            is ByteArray -> { head(2, value.size.toLong()); out.write(value) }
            else -> throw ProtocolException()
        } }
        return out.toByteArray()
    }
    class PinData(val wrapped: ByteArray, val hash: ByteArray, val serial: Long) : AutoCloseable {
        override fun close() { wrapped.fill(0); hash.fill(0) }
        override fun toString() = "GpmPinData[redacted]"
    }
    fun open(member: ByteArray, keys: List<DomainLifecycleKey>, crypto: GpmPinRenewalCrypto): PinData {
        val wrapped = metadata(member); val p = Proto.decode(wrapped)
        if (p.integer(4) !in setOf(1L, 2L) || p.integer(6)?.let { it in 2..1048576 && it and (it - 1) == 0L } != true || p.one(7).size != 16 || p.one(2).size != 32)
            throw ProtocolException()
        val encrypted = p.one(1)
        try {
            for (key in keys.sortedByDescending { it.epoch }) {
                val raw = key.material
                val plain = try { crypto.openMetadata(encrypted, raw) } catch (_: Exception) { continue } finally { raw.fill(0) }
                try {
                    val values = readCbor(plain)
                    try {
                    val hash = values[1] as? ByteArray ?: throw ProtocolException()
                    val claim = values[3] as? ByteArray ?: throw ProtocolException()
                    val counter = values[4] as? ByteArray ?: throw ProtocolException()
                    val handle = values[5] as? ByteArray ?: throw ProtocolException()
                    val serial = values[6] as? Long ?: throw ProtocolException()
                    val thm = values[7] as? ByteArray ?: throw ProtocolException()
                    if (hash.size != 32 || claim.size != 32 || counter.size != 8 || handle.size !in setOf(16, 17) ||
                        thm.size !in 65..4096 || values[2]?.let { it != 0L } == true || !MessageDigest.isEqual(claim, p.one(2))) throw ProtocolException()
                    return PinData(wrapped.copyOf(), hash.copyOf(), serial)
                    } finally { values.values.filterIsInstance<ByteArray>().forEach { it.fill(0) } }
                } finally { plain.fill(0) }
            }
            throw VaultLockedException()
        } finally { wrapped.fill(0); encrypted.fill(0) }
    }
    fun build(member: ByteArray, pin: PinData, keys: List<DomainLifecycleKey>, certificate: GpmPinCertificate,
        id: String, accountId: String, ownVersion: Int, crypto: GpmPinRenewalCrypto): GpmPinRenewalAttempt {
        val current = keys.maxByOrNull { it.epoch } ?: throw VaultLockedException()
        val pair = crypto.pair().also(EnrollmentWire::checkPair)
        val app = crypto.random(32); val recovery = crypto.random(32); val claim = crypto.random(32)
        val counter = crypto.random(8); val handle = byteArrayOf(3) + crypto.random(16)
        val public = EnrollmentWire.publicKey(pair)
        val params = Proto.field(1, certificate.publicKey) + Proto.field(2, counter) + Proto.integer(3, 10) + Proto.field(4, handle)
        val rawKey = current.material
        try {
            val encodedPin = cbor(mapOf(1 to pin.hash, 2 to 0L, 3 to claim, 4 to counter, 5 to handle, 6 to certificate.serial, 7 to certificate.publicKey))
            val metadata = try { Proto.decode(pin.wrapped).replace(1, crypto.sealMetadata(encodedPin, rawKey)).replace(2, claim).encode() }
                finally { encodedPin.fill(0) }
            val pinProto = Proto.decode(pin.wrapped)
            val protection = Proto.integer(1, pinProto.integer(4)!!) + Proto.integer(4, 3) + Proto.field(5, pinProto.one(7)) +
                Proto.integer(6, pinProto.integer(6)!!) + Proto.field(7, certificate.certPath)
            val inner = crypto.secureBox(null, pin.hash, "V1 locally_encrypted_recovery_key".toByteArray(), recovery)
            val outer = try { crypto.secureBox(certificate.publicKey, pin.hash, "V1 THM_encrypted_recovery_key".toByteArray() + params, inner) }
                finally { inner.fill(0) }
            val wrappedApp = crypto.secureBox(null, recovery, "V1 encrypted_application_key".toByteArray(), app)
            val entry = Proto.field(1, ENTRY) + Proto.field(4, Proto.field(1, public) + Proto.field(2, crypto.aes(app, pair)) + Proto.field(3, wrappedApp))
            val vault = Proto.field(2, params) + Proto.field(3, protection) + Proto.field(4, outer) + Proto.field(5, entry)
            val update = Proto.field(3, vault) + Proto.integer(6, ownVersion.toLong()) + Proto.field(7, RecoverableVaultWire.context(id, ownVersion))
            val old = Proto.decode(member); val oldMetadata = Proto.decode(old.one(6)); val oldPin = Proto.decode(oldMetadata.one(5))
            // Generated Wire adapters encode known fields first and retained unknown fields last.
            // Keep that canonical layout before persisting the exact request, including future metadata.
            val pinMetadata = Proto.field(2, metadata) + (oldPin.integer(3)?.let { Proto.integer(3, it) } ?: byteArrayOf()) +
                oldPin.replace(2, null).replace(3, null).encode()
            val memberMetadata = (oldMetadata.integer(1)?.let { Proto.integer(1, it) } ?: byteArrayOf()) + Proto.field(5, pinMetadata) +
                oldMetadata.replace(1, null).replace(5, null).encode()
            val nextMember = Proto.field(1, EnrollmentWire.memberName(public)) + Proto.field(2, public) + Proto.integer(4, 5) +
                Proto.field(6, memberMetadata)
            val join = Proto.field(1, Proto.field(1, DomainLifecycleWire.name(DOMAIN))) + Proto.field(2, nextMember) +
                keys.sortedBy { it.epoch }.fold(byteArrayOf()) { out, key -> val raw = key.material
                    try { out + Proto.field(3, Proto.integer(1, key.epoch.toLong()) + Proto.field(2, crypto.wrapMember(public, raw)) +
                        Proto.field(3, EnrollmentWire.proof(raw, public))) } finally { raw.fill(0) } } +
                Proto.field(5, old.one(2)) + Proto.field(6, id)
            return GpmPinRenewalAttempt(id, accountId, current.epoch, RecoverableVaultWire.keysDigest(keys), member.copyOf(), pair.copyOf(), update, join, GpmPinRenewalPhase.PREPARED)
        } finally { rawKey.fill(0); pair.fill(0); app.fill(0); recovery.fill(0); claim.fill(0); counter.fill(0); handle.fill(0) }
    }
    fun memberMatches(raw: ByteArray, attempt: GpmPinRenewalAttempt, keys: List<DomainLifecycleKey>, crypto: GpmPinRenewalCrypto): Boolean {
        val expected = Proto.decode(Proto.decode(attempt.join).one(2)); val actual = Proto.decode(raw)
        if (actual.string(1) != expected.string(1) || !MessageDigest.isEqual(actual.one(2), expected.one(2)) || actual.integer(4) != 5L ||
            !MessageDigest.isEqual(metadata(raw), metadata(expected.encode())) ||
            (Proto.decode(Proto.decode(actual.one(6)).one(5)).integer(3) ?: 0L) !=
            (Proto.decode(Proto.decode(expected.one(6)).one(5)).integer(3) ?: 0L)) return false
        val entries = actual.bytes(3).map(Proto::decode).singleOrNull { it.string(1) == DomainLifecycleWire.name(DOMAIN) }?.bytes(3)?.map(Proto::decode) ?: return false
        if (entries.size > 100 || entries.map { it.integer(1) }.toSet().size != entries.size) throw ProtocolException()
        return keys.all { key -> val entry = entries.singleOrNull { it.integer(1) == key.epoch.toLong() } ?: return@all false
            val rawKey = key.material; val opened = crypto.unwrapMember(attempt.pair, entry.one(2))
            try { MessageDigest.isEqual(rawKey, opened) && MessageDigest.isEqual(entry.one(3), EnrollmentWire.proof(rawKey, actual.one(2))) }
            finally { rawKey.fill(0); opened.fill(0) } }
    }
}

/** Durable upload then member replacement; reopen only reads and cannot replay either uncertain mutation. */
class GpmPinRenewalManager(private val accountId: String, private val ownVersion: Int, private val crypto: GpmPinRenewalCrypto,
    private val remote: GpmPinRenewalRemote, private val keys: () -> List<DomainLifecycleKey>, private val load: () -> GpmPinRenewalState,
    private val save: (GpmPinRenewalState) -> Unit, private val authorize: () -> Unit) {
    private fun <T> fresh(attempt: GpmPinRenewalAttempt?, action: (List<DomainLifecycleKey>, List<ByteArray>) -> T): T {
        authorize()
        if (attempt != null && attempt.accountId != accountId) throw VaultLockedException()
        val material = keys()
        try {
            val id = attempt?.id ?: UUID.randomUUID().toString()
            val before = GpmPinRenewalWire.epoch(remote.domain(id)); val members = remote.members(id)
            val after = GpmPinRenewalWire.epoch(remote.domain(id))
            if (before != after || material.none { it.epoch == after } || material.any { it.epoch > after }) throw VaultResetException()
            val digest = RecoverableVaultWire.keysDigest(material)
            if (attempt != null && (attempt.epoch != after || !MessageDigest.isEqual(attempt.keysDigest, digest))) throw VaultResetException()
            authorize(); return action(material, members).also { authorize() }
        } finally { material.forEach(DomainLifecycleKey::close) }
    }
    fun prepare(certificate: GpmPinCertificate): Boolean = fresh(null) { material, members ->
        val state = load()
        if (state.attempt != null && state.attempt.phase != GpmPinRenewalPhase.CONFIRMED) throw PendingMutationException()
        val history = state.history + listOfNotNull(state.attempt?.encode())
        if (history.size >= 100) throw ProtocolException()
        val member = GpmPinRenewalWire.pinMember(members)
        GpmPinRenewalWire.open(member, material, crypto).use { pin ->
            if (pin.serial >= certificate.serial) return@fresh false
            val attempt = GpmPinRenewalWire.build(member, pin, material, certificate, UUID.randomUUID().toString(), accountId, ownVersion, crypto)
            authorize(); save(GpmPinRenewalState(attempt, history)); true
        }
    }
    fun reconcile(): GpmPinRenewalState {
        authorize(); val state = load(); val attempt = state.attempt ?: return state
        try { return fresh(attempt) { material, _ ->
            if (attempt.phase == GpmPinRenewalPhase.PREPARED) return@fresh state
            val wanted = Proto.decode(attempt.update).one(3)
            val handle = Proto.decode(Proto.decode(wanted).one(2)).one(4)
            val vault = remote.vaults(attempt.id).filter { RecoverableVaultWire.sameHandle(it, handle) }
            // Vault I/O can race another device's domain/member change. Read its authority again afterwards.
            if (GpmPinRenewalWire.epoch(remote.domain(attempt.id)) != attempt.epoch) throw VaultResetException()
            val members = remote.members(attempt.id)
            if (GpmPinRenewalWire.epoch(remote.domain(attempt.id)) != attempt.epoch) throw VaultResetException()
            authorize()
            if (vault.size > 1) throw ProtocolException()
            if (vault.singleOrNull()?.let { RecoverableVaultWire.vaultMatches(it, wanted) } != true) {
                if (attempt.phase != GpmPinRenewalPhase.CONFIRMED) return@fresh state
                return@fresh GpmPinRenewalState(attempt.phase(GpmPinRenewalPhase.REVOKED), state.history).also { authorize(); save(it) }
            }
            val confirmed = members.singleOrNull { Proto.decode(it).string(1) == EnrollmentWire.memberName(EnrollmentWire.publicKey(attempt.pair)) }
                ?.let { runCatching { GpmPinRenewalWire.memberMatches(it, attempt, material, crypto) }.getOrDefault(false) } == true
            val next = if (confirmed) GpmPinRenewalPhase.CONFIRMED else if (attempt.phase == GpmPinRenewalPhase.VAULT_LAUNCHED)
                GpmPinRenewalPhase.VAULT_CONFIRMED else if (attempt.phase == GpmPinRenewalPhase.CONFIRMED) GpmPinRenewalPhase.REVOKED else attempt.phase
            GpmPinRenewalState(attempt.phase(next), state.history).also { authorize(); save(it) }
        } } catch (failure: VaultResetException) {
            if (attempt.phase == GpmPinRenewalPhase.CONFIRMED) {
                authorize(); save(GpmPinRenewalState(attempt.phase(GpmPinRenewalPhase.REVOKED), state.history))
            }
            throw failure
        }
    }
    fun continueOnce(id: String) {
        var state = reconcile(); var attempt = state.attempt ?: throw ProtocolException()
        if (attempt.id != id) throw ConflictException()
        if (attempt.phase in setOf(GpmPinRenewalPhase.VAULT_LAUNCHED, GpmPinRenewalPhase.JOIN_LAUNCHED, GpmPinRenewalPhase.CONFIRMED, GpmPinRenewalPhase.REVOKED)) return
        fresh(attempt) { _, members -> if (!MessageDigest.isEqual(GpmPinRenewalWire.pinMember(members), attempt.previousMember)) throw ConflictException() }
        if (attempt.phase == GpmPinRenewalPhase.PREPARED) {
            authorize(); save(GpmPinRenewalState(attempt.phase(GpmPinRenewalPhase.VAULT_LAUNCHED), state.history))
            authorize(); remote.update(attempt.update); authorize()
            state = reconcile(); attempt = state.attempt ?: throw ProtocolException()
        }
        if (attempt.phase == GpmPinRenewalPhase.VAULT_CONFIRMED) {
            fresh(attempt) { _, members -> if (!MessageDigest.isEqual(GpmPinRenewalWire.pinMember(members), attempt.previousMember)) throw ConflictException() }
            authorize(); save(GpmPinRenewalState(attempt.phase(GpmPinRenewalPhase.JOIN_LAUNCHED), state.history))
            authorize(); remote.join(attempt.join); authorize(); reconcile()
        }
    }
    /** Explicit owner closure retains the complete original receipt; no upload/join is dispatched or repeated. */
    fun close(id: String) {
        authorize(); val state = load(); val attempt = state.attempt ?: return
        if (attempt.id != id || attempt.accountId != accountId || state.history.size >= 100) throw ConflictException()
        authorize(); save(GpmPinRenewalState(history = state.history + listOf(attempt.encode())))
    }
}
