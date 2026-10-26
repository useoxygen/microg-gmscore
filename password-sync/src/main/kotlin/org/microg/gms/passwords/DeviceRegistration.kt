/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest
import java.security.SecureRandom

/** SyncEntities device information; field positions are independently derived from the pinned contract. */
class SyncDeviceInfo(val model: String, val manufacturer: String, val softwareVersion: String,
    val formFactor: Int = 1, val pushToken: String? = null,
    val interestedTypes: Set<Int> = setOf(PASSWORDS, PREFERENCES, NIGORI)) {
    init {
        if (listOf(model, manufacturer, softwareVersion).any { it.isEmpty() || it.length > 1024 || it.any(Char::isISOControl) } ||
            formFactor !in 1..5 || (pushToken?.length ?: 0) > 16_384 || interestedTypes.size > 128 ||
            interestedTypes.any { it <= 0 }) throw ProtocolException()
    }
    fun encode(): ByteArray = Proto.field(1, model) + Proto.field(2, Proto.field(1, softwareVersion)) +
        Proto.field(3, model) + Proto.field(4, manufacturer) + Proto.integer(5, formFactor.toLong()) +
        Proto.field(6, Proto.integer(1, REFRESH_SECONDS)) +
        (pushToken?.takeIf { it.isNotEmpty() }?.let { token ->
            Proto.field(7, token) + Proto.field(8, interestedTypes.sorted().fold(byteArrayOf()) { out, type -> out + Proto.varint(type.toLong()) })
        } ?: byteArrayOf()) + Proto.integer(9, 1)
    override fun toString(): String = "SyncDeviceInfo[redacted]"
    companion object {
        const val PASSWORDS = 45873
        const val PREFERENCES = 163425
        const val NIGORI = 47745
        const val REFRESH_SECONDS = 7 * 24 * 60 * 60L
    }
}

/** Kept inside the account's AEAD state, including the nonce before the first network attempt. */
class DeviceRegistration(val parent: String, val nonce: ByteArray, val token: ByteArray? = null,
    val cacheGuid: String? = null, val info: ByteArray? = null, val updatedMillis: Long = 0) {
    init {
        PasswordApi.checkedParent(parent)
        if (nonce.size != 16 || (token != null && (token.isEmpty() || token.size > 16_384)) ||
            (cacheGuid?.length ?: 0) > 1024 || (token == null) != (cacheGuid == null) ||
            (token != null && (cacheGuid.isNullOrEmpty() || info == null)) || updatedMillis < 0) throw ProtocolException()
    }
    fun encode(): ByteArray = Proto.field(1, parent) + Proto.field(2, nonce) +
        (token?.let { Proto.field(3, it) } ?: byteArrayOf()) +
        (cacheGuid?.let { Proto.field(4, it) } ?: byteArrayOf()) +
        (info?.let { Proto.field(5, it) } ?: byteArrayOf()) + Proto.integer(6, updatedMillis)
    override fun toString(): String = "DeviceRegistration[redacted]"
    companion object {
        fun decode(raw: ByteArray): DeviceRegistration = Proto.decode(raw).let {
            DeviceRegistration(it.string(1), it.one(2), it.optional(3), it.optionalString(4), it.optional(5), it.integer(6) ?: 0)
        }
    }
}

fun interface DeviceTokenProvider { fun token(session: VaultSession, state: VaultState, parent: String): ByteArray }

/** Invoked while the existing account lock is held, so registration and outbox saves share one state. */
class DeviceRegistrationSync(private val transport: PasswordTransport, private val info: () -> SyncDeviceInfo,
    private val now: () -> Long = System::currentTimeMillis,
    private val nonce: () -> ByteArray = { ByteArray(16).also { SecureRandom().nextBytes(it) } }) : DeviceTokenProvider {
    override fun token(session: VaultSession, state: VaultState, parent: String): ByteArray {
        PasswordApi.checkedParent(parent)
        var registered = state.deviceRegistration?.takeIf { it.parent == parent }
        if (registered == null) {
            registered = DeviceRegistration(parent, nonce())
            state.deviceRegistration = registered
            state.invalidations = SyncInvalidations()
            session.save(state)
        }
        val deviceInfo = info().encode()
        val time = now()
        if (time < 0) throw ProtocolException()
        if (registered.token != null && registered.info != null && MessageDigest.isEqual(registered.info, deviceInfo) &&
            time >= registered.updatedMillis && time - registered.updatedMillis < SyncDeviceInfo.REFRESH_SECONDS * 1000)
            return registered.token.copyOf()
        val response = if (registered.token == null) {
            // Google persists and reuses this exact nonce until a successful create response.
            transport.invoke(PasswordMethod.CREATE_DEVICE, Proto.field(1, deviceInfo) + Proto.field(2, parent) + Proto.field(4, registered.nonce))
        } else {
            transport.invoke(PasswordMethod.UPDATE_DEVICE, Proto.field(1, registered.token) + Proto.field(2, deviceInfo))
        }
        val result = Proto.decode(response)
        val token = result.one(1)
        val replacement = DeviceRegistration(parent, registered.nonce, token, result.string(2), deviceInfo, time)
        if (registered.cacheGuid != null && registered.cacheGuid != replacement.cacheGuid) state.invalidations = SyncInvalidations()
        state.deviceRegistration = replacement
        session.save(state)
        return token.copyOf()
    }
    override fun toString(): String = "DeviceRegistrationSync[redacted]"
}

/** Push invalidations are hints: account routing is by the server's cache GUID, never an intent account name. */
class PasswordInvalidation private constructor(val cacheGuid: String, val types: Set<Int>, val digest: ByteArray) {
    fun appliesTo(registration: DeviceRegistration?): Boolean = registration?.cacheGuid == cacheGuid &&
        types.any { it in setOf(SyncDeviceInfo.PASSWORDS, SyncDeviceInfo.PREFERENCES, SyncDeviceInfo.NIGORI) }
    override fun toString(): String = "PasswordInvalidation[redacted]"
    companion object {
        const val SENDER = "361488507004"
        fun decode(sender: String?, cacheGuid: String?, payload: ByteArray): PasswordInvalidation {
            if (sender != SENDER || cacheGuid.isNullOrEmpty() || cacheGuid.length > 1024 || payload.size > 64 * 1024) throw ProtocolException()
            val values = Proto.decode(payload).bytes(1)
            if (values.isEmpty() || values.size > 128) throw ProtocolException()
            val types = values.map { raw ->
                val type = Proto.decode(raw).integer(1) ?: throw ProtocolException()
                if (type !in 1..Int.MAX_VALUE) throw ProtocolException()
                type.toInt()
            }.toSet()
            return PasswordInvalidation(cacheGuid, types, MessageDigest.getInstance("SHA-256").digest(
                Proto.field(1, cacheGuid) + Proto.field(2, payload)))
        }
    }
}
