/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.phenotype

import org.microg.gms.passwords.Proto
import org.microg.gms.passwords.ProtocolException
import java.security.MessageDigest
import java.util.UUID

/** Pinned hika/hijp/hikk/hijq/hiki/hikf. Device flags never borrow an account's configuration. */
internal object FolsomPhenotypeProtocol {
    const val PACKAGE = "com.google.android.gms"
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_TOKEN = 16 * 1024
    const val MAX_PARTITIONS = 256
    const val MAX_FLAGS = 4096
    const val REASON = 19 // hijk.FORCE_SYNC: an explicit native request, never a background polling loop.
    const val CLIENT = 1 // excw's GMS client (enum value2 minus1).
    private fun fail(): Nothing = throw ProtocolException()
    private fun checked(raw: ByteArray): Proto = if (raw.size > MAX_BYTES) fail() else Proto.decode(raw)
    private fun removed(p: Proto, n: Int) = p.encode().size - p.replace(n, null).encode().size
    internal fun integer(p: Proto, n: Int): Long? = p.integer(n).also { value ->
        if (removed(p, n) != (value?.let { Proto.integer(n, it).size } ?: 0)) fail()
    }
    internal fun bytes(p: Proto, n: Int, max: Int = MAX_TOKEN): ByteArray? = p.optional(n).also { value ->
        if (value != null && value.size > max || removed(p, n) != (value?.let { Proto.field(n, it).size } ?: 0)) fail()
    }
    internal fun string(p: Proto, n: Int, max: Int = MAX_TOKEN): String? = bytes(p, n, max)?.let(Proto::utf8)
    private fun repeated(p: Proto, n: Int, max: Int): List<ByteArray> = p.bytes(n).also { values ->
        if (values.size > max || removed(p, n) != values.sumOf { Proto.field(n, it).size }) fail()
    }
    internal fun digest(raw: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(raw)

    /** Registration version and Android package are this installation's, not a Google version/signer claim. */
    class Registration(val version: Long, identity: ByteArray, device: ByteArray) {
        val identity = identity.copyOf()
        val device = device.copyOf()
        init { if (version !in 1..Int.MAX_VALUE.toLong() || identity.size != 32 || device.isEmpty() || device.size > 65536) fail() }
        override fun toString() = "FolsomRegistration[redacted]"
    }
    class Partition(val id: Long, tag: ByteArray, flags: List<ByteArray>) {
        val tag = tag.copyOf()
        val flags = flags.map(ByteArray::copyOf)
        init { if (tag.isEmpty() || tag.size > MAX_TOKEN || flags.size > MAX_FLAGS) fail() }
        fun encode(): ByteArray = Proto.field(1, Proto.integer(1, id) + Proto.field(2, tag)) + flags.fold(byteArrayOf()) { out, flag ->
            bounded(out + Proto.field(2, flag))
        }
        override fun toString() = "FolsomPartition[redacted]"
    }
    class State(identity: ByteArray, val generation: Long = 0, val pending: String? = null, val assigned: Boolean = false,
                val timestamp: Long = 0, val serving: Long = 0, requestTag: ByteArray? = null, val pseudonym: String? = null,
                applicationToken: ByteArray? = null, experimentToken: ByteArray? = null, val serverToken: String? = null,
                partitions: List<Partition> = emptyList()) {
        val identity = identity.copyOf()
        val requestTag = requestTag?.copyOf()
        val applicationToken = applicationToken?.copyOf()
        val experimentToken = experimentToken?.copyOf()
        val partitions = partitions.toList()
        init {
            if (identity.size != 32 || generation < 0 || timestamp < 0 || serving < 0 || partitions.size > MAX_PARTITIONS ||
                partitions.map { it.id }.toSet().size != partitions.size || pending != null && UUID.fromString(pending).toString() != pending) fail()
            listOf(requestTag, applicationToken, experimentToken).forEach { if (it != null && it.size > MAX_TOKEN) fail() }
            if (pseudonym != null && !validPseudonym(pseudonym) || serverToken != null && serverToken.toByteArray().size > MAX_TOKEN) fail()
        }
        fun encode(): ByteArray = bounded(Proto.integer(1, 1) + Proto.field(2, identity) + Proto.integer(3, generation) +
            (pending?.let { Proto.field(4, it) } ?: byteArrayOf()) + Proto.integer(5, if (assigned) 1 else 0) +
            Proto.integer(6, timestamp) + Proto.integer(7, serving) +
            (requestTag?.let { Proto.field(8, it) } ?: byteArrayOf()) + (pseudonym?.let { Proto.field(9, it) } ?: byteArrayOf()) +
            (applicationToken?.let { Proto.field(10, it) } ?: byteArrayOf()) +
            (experimentToken?.let { Proto.field(11, it) } ?: byteArrayOf()) + (serverToken?.let { Proto.field(12, it) } ?: byteArrayOf()) +
            partitions.fold(byteArrayOf()) { out, part -> bounded(out + Proto.field(13, part.encode())) })
        override fun toString() = "FolsomServerState[redacted]"
    }
    fun decode(raw: ByteArray): State {
        val p = checked(raw)
        if (integer(p, 1) != 1L) fail()
        val assigned = integer(p, 5) ?: fail()
        if (assigned !in 0..1) fail()
        return State(bytes(p, 2, 32) ?: fail(), integer(p, 3) ?: fail(), string(p, 4, 36), assigned == 1L,
            integer(p, 6) ?: fail(), integer(p, 7) ?: fail(), bytes(p, 8), string(p, 9), bytes(p, 10), bytes(p, 11), string(p, 12),
            repeated(p, 13, MAX_PARTITIONS).map(::partition))
    }
    fun begin(before: State): State {
        if (before.generation == Long.MAX_VALUE) fail()
        return copy(before, generation = before.generation + 1, pending = UUID.randomUUID().toString())
    }
    private fun copy(before: State, generation: Long = before.generation, pending: String? = before.pending): State = State(
        before.identity, generation, pending, before.assigned, before.timestamp, before.serving, before.requestTag,
        before.pseudonym, before.applicationToken, before.experimentToken, before.serverToken, before.partitions)

    /** Source batch: device account=-1 and exactly one authenticated account at token index0. */
    fun request(registration: Registration, state: State): ByteArray {
        if (!MessageDigest.isEqual(registration.identity, state.identity) || state.pending == null) fail()
        val common = Proto.field(4, Proto.integer(1, 4) + Proto.field(2, registration.device)) +
            (state.pseudonym?.let { Proto.field(5, it) } ?: byteArrayOf()) + Proto.integer(7, 1)
        fun application(account: Long, device: Boolean): ByteArray {
            val packageId = Proto.field(1, FolsomPromptFlagPolicy.NAMESPACE) + Proto.integer(2, registration.version) +
                Proto.field(3, Proto.integer(1, account)) + byteArrayOf(0x21) + ByteArray(8) { 0xff.toByte() } + Proto.field(5, PACKAGE)
            return Proto.field(1, packageId) + (if (device) state.partitions.fold(byteArrayOf()) { out, part ->
                bounded(out + Proto.field(3, Proto.integer(1, part.id) + Proto.field(2, part.tag)))
            } else byteArrayOf()) + (if (device) state.applicationToken?.takeIf { it.isNotEmpty() }?.let { Proto.field(4, it) }
                ?: byteArrayOf() else byteArrayOf())
        }
        return bounded(Proto.field(1, common) + Proto.field(2, application(-1, true)) + Proto.field(2, application(0, false)) +
            (state.requestTag?.let { Proto.field(3, it) } ?: byteArrayOf()) + Proto.integer(4, REASON.toLong()) + Proto.integer(5, CLIENT.toLong()))
    }

    /** Complete, bounded authenticated HTTP response; reject wrong scope and never adopt account flags. */
    fun response(registration: Registration, launched: State, current: State, raw: ByteArray): State {
        if (launched.pending == null || current.pending != launched.pending || current.generation != launched.generation ||
            !MessageDigest.isEqual(current.identity, registration.identity) || !current.encode().contentEquals(launched.encode())) fail()
        val p = checked(raw)
        if (removed(p, 6) != 0) fail() // hikb.failed_account_indices: partial auth is never our assignment proof.
        val returnedTime = integer(p, 4) ?: 0L // hikb.f has the source protobuf default when omitted.
        if (returnedTime < 0 || returnedTime != 0L && returnedTime < current.timestamp) fail()
        val timestamp = maxOf(current.timestamp, returnedTime)
        val apps = repeated(p, 1, 2)
        val scoped = apps.map { app ->
            val a = checked(app); val id = checked(bytes(a, 1, 65536) ?: fail())
            if (string(id, 1) != FolsomPromptFlagPolicy.NAMESPACE || integer(id, 2) != registration.version ||
                string(id, 5)?.let { it != PACKAGE } == true) fail()
            val account = integer(checked(bytes(id, 3, 64) ?: fail()), 1) ?: fail()
            if (account != -1L && account != 0L) fail()
            account to a
        }
        if (scoped.map { it.first }.toSet().size != scoped.size) fail()
        val device = scoped.singleOrNull { it.first == -1L }?.second
        val parts = current.partitions.associateBy { it.id }.toMutableMap()
        if (device != null) {
            val changes = repeated(device, 2, MAX_PARTITIONS)
            val ids = HashSet<Long>()
            changes.forEach { rawPart ->
                val part = checked(rawPart); val key = checked(bytes(part, 1, MAX_TOKEN + 64) ?: fail())
                val id = integer(key, 1) ?: fail()
                if (!ids.add(id)) fail()
                when (integer(part, 3) ?: 0L) {
                    0L -> {
                        // exdq ignores an absent/empty partition tag: it is not a replacement receipt.
                        if (bytes(key, 2)?.isNotEmpty() != true) return@forEach
                        val replacement = partition(rawPart)
                        if (!parts[id]?.tag.contentEquals(replacement.tag)) parts[id] = replacement
                    }
                    2L -> parts.remove(id)
                    else -> fail()
                }
            }
        }
        val pseudonym = string(p, 3)?.takeIf { it.isNotEmpty() } ?: current.pseudonym
        if (pseudonym != null && !validPseudonym(pseudonym)) fail()
        val next = State(current.identity, current.generation, null, current.assigned || device != null, timestamp,
            device?.let { integer(it, 11) } ?: timestamp,
            bytes(p, 2)?.takeIf { it.isNotEmpty() } ?: current.requestTag, pseudonym,
            if (device == null) current.applicationToken else bytes(device, 7)?.takeIf { it.isNotEmpty() },
            device?.let { bytes(it, 3) } ?: current.experimentToken,
            device?.let { string(it, 4) } ?: current.serverToken, parts.values.sortedBy { it.id })
        values(next) // A malformed consumed flag cannot poison or partly replace an earlier valid snapshot.
        next.encode()
        return next
    }
    private fun partition(raw: ByteArray): Partition {
        val p = checked(raw); if ((integer(p, 3) ?: 0L) != 0L) fail()
        val key = checked(bytes(p, 1, MAX_TOKEN + 64) ?: fail())
        val flags = repeated(p, 2, MAX_FLAGS)
        return Partition(integer(key, 1) ?: fail(), bytes(key, 2) ?: fail(), flags)
    }
    fun values(state: State): Map<String, Any> {
        if (!state.assigned) return emptyMap()
        val result = linkedMapOf<String, Any>(); var count = 0
        state.partitions.forEach { part -> part.flags.forEach { raw ->
            if (++count > MAX_FLAGS) fail()
            val f = checked(raw); val name = string(f, 1, 1024) ?: fail()
            val wanted = FolsomPromptFlagPolicy.types[name]
            if (wanted != null) {
                if (result.containsKey(name) || integer(f, 9) != wanted.toLong()) fail()
                result[name] = when (wanted) {
                    1 -> integer(f, 2) ?: 0L
                    2 -> when (integer(f, 3) ?: 0L) { 0L -> false; 1L -> true; else -> fail() }
                    else -> fail()
                }
            }
        } }
        return result
    }
    private fun validPseudonym(value: String): Boolean {
        if (value.toByteArray().size > MAX_TOKEN) return false
        val unquoted = if (value.length > 1 && value.first() == '"' && value.last() == '"') value.substring(1, value.length - 1) else value
        return unquoted.all { it.code in 33..126 && it !in "\",;\\" }
    }
    private fun bounded(raw: ByteArray): ByteArray = if (raw.size > MAX_BYTES) fail() else raw
}
