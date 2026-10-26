/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

/** Durable refresh obligations. A hint is acknowledged only by a complete domain snapshot. */
class SyncInvalidations(
    val generation: Long = 0,
    val passwords: Long = 0,
    val preferences: Long = 0,
    val passwordsHandled: Long = 0,
    val preferencesHandled: Long = 0,
    val directPasswordGeneration: Long = 0,
    val receivedMillis: Long = 0,
    private val deliveryId: String? = null,
    private val digest: ByteArray? = null
) {
    init {
        if (generation < 0 || passwords !in 0..generation || preferences !in 0..generation ||
            passwordsHandled !in 0..passwords || preferencesHandled !in 0..preferences ||
            directPasswordGeneration !in 0..passwords || receivedMillis < 0 ||
            (deliveryId == null) != (digest == null) ||
            (deliveryId != null && (deliveryId.isEmpty() || deliveryId.length > 128)) ||
            (digest != null && digest.size != 32)) throw ProtocolException()
    }
    val pending get() = passwordsHandled < passwords || preferencesHandled < preferences

    fun receive(id: String, types: Set<Int>, payloadDigest: ByteArray, nowMillis: Long): SyncInvalidations {
        if (id.isEmpty() || id.length > 128 || payloadDigest.size != 32 || nowMillis < 0 ||
            types.size > 128 || types.any { it <= 0 }) throw ProtocolException()
        val password = SyncDeviceInfo.PASSWORDS in types || SyncDeviceInfo.NIGORI in types
        val preference = SyncDeviceInfo.PREFERENCES in types || SyncDeviceInfo.NIGORI in types
        // The WorkManager ID is stable across retry, unlike a new process instance. Distinct
        // deliveries may legitimately have identical type-only payloads and must not be dropped.
        if (id == deliveryId || (!password && !preference)) return this
        if (generation == Long.MAX_VALUE) throw ProtocolException()
        val next = generation + 1
        return SyncInvalidations(next, if (password) next else passwords, if (preference) next else preferences,
            passwordsHandled, preferencesHandled, if (SyncDeviceInfo.PASSWORDS in types) next else directPasswordGeneration,
            nowMillis, id, payloadDigest.copyOf())
    }
    fun acknowledgePasswords() = SyncInvalidations(generation, passwords, preferences, passwords, preferencesHandled,
        directPasswordGeneration, receivedMillis, deliveryId, digest)
    fun acknowledgePreferences() = SyncInvalidations(generation, passwords, preferences, passwordsHandled, preferences,
        directPasswordGeneration, receivedMillis, deliveryId, digest)
    fun encode(): ByteArray = Proto.integer(1, generation) + Proto.integer(2, passwords) + Proto.integer(3, preferences) +
        Proto.integer(4, passwordsHandled) + Proto.integer(5, preferencesHandled) + Proto.integer(6, directPasswordGeneration) +
        Proto.integer(7, receivedMillis) + (deliveryId?.let { Proto.field(8, it) } ?: byteArrayOf()) +
        (digest?.let { Proto.field(9, it) } ?: byteArrayOf())
    override fun toString(): String = "SyncInvalidations[redacted]"
    companion object {
        fun decode(raw: ByteArray): SyncInvalidations = Proto.decode(raw).let {
            SyncInvalidations(it.integer(1) ?: 0, it.integer(2) ?: 0, it.integer(3) ?: 0, it.integer(4) ?: 0,
                it.integer(5) ?: 0, it.integer(6) ?: 0, it.integer(7) ?: 0, it.optionalString(8), it.optional(9))
        }
    }
}
