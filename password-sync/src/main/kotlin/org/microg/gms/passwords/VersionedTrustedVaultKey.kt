/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

/** A recovered epoch, never an epoch inferred from the position of a raw unlock key. */
class VersionedTrustedVaultKey(val epoch: Int, material: ByteArray) {
    private val raw = material.copyOf()
    private var destroyed = false
    init { if (epoch <= 0 || raw.size !in 16..4096) throw ProtocolException() }
    /** The caller owns, and must clear, this copy. */
    val material: ByteArray get() { if (destroyed) throw VaultLockedException(); return raw.copyOf() }
    fun copy(): VersionedTrustedVaultKey {
        if (destroyed) throw VaultLockedException()
        return VersionedTrustedVaultKey(epoch, raw)
    }
    fun encode(): ByteArray {
        if (destroyed) throw VaultLockedException()
        return Proto.integer(1, epoch.toLong()) + Proto.field(2, raw)
    }
    fun destroy() { destroyed = true; raw.fill(0) }
    override fun toString() = "VersionedTrustedVaultKey[redacted]"
    companion object {
        fun decode(bytes: ByteArray): VersionedTrustedVaultKey {
            if (bytes.size > 4128) throw ProtocolException()
            val value = Proto.decode(bytes)
            val epoch = value.integer(1)?.takeIf { it in 1..Int.MAX_VALUE.toLong() } ?: throw ProtocolException()
            val raw = value.one(2)
            return try { VersionedTrustedVaultKey(epoch.toInt(), raw) } finally { raw.fill(0) }
        }
    }
}

/** Account-bound AEAD storage contains this additional, explicit vault-birthday binding. */
class VersionedTrustedVaultKeys(val parent: String, keys: List<VersionedTrustedVaultKey>) {
    private val retained: List<VersionedTrustedVaultKey>
    init {
        PasswordApi.checkedParent(parent)
        if (keys.size !in 1..100 || keys.map { it.epoch }.toSet().size != keys.size) throw ProtocolException()
        retained = keys.map { it.copy() }
    }
    /** Independent copies, suitable for a single owner-authorized enrollment operation. */
    val keys: List<VersionedTrustedVaultKey> get() = retained.map { it.copy() }
    fun encode(): ByteArray = Proto.integer(1, 1) + Proto.field(2, parent) +
        retained.fold(byteArrayOf()) { out, key -> out + Proto.field(3, key.encode()) }
    fun destroy() { retained.forEach { it.destroy() } }
    override fun toString() = "VersionedTrustedVaultKeys[redacted]"
    companion object {
        fun decode(bytes: ByteArray): VersionedTrustedVaultKeys {
            if (bytes.size > 420 * 1024) throw ProtocolException()
            val value = Proto.decode(bytes)
            if (value.integer(1) != 1L) throw ProtocolException()
            val encoded = value.bytes(3)
            if (encoded.size !in 1..100) throw ProtocolException()
            val keys = ArrayList<VersionedTrustedVaultKey>()
            return try {
                encoded.forEach { keys.add(VersionedTrustedVaultKey.decode(it)) }
                VersionedTrustedVaultKeys(value.string(2), keys)
            } finally { keys.forEach { it.destroy() } }
        }
    }
}
