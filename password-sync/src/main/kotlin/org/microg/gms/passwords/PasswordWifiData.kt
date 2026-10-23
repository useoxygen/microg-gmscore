/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/** Published WiFiConfigurationSpecifics / pinned ipcj. Only WEP and PSK have a compatible consumer. */
class PasswordWifiNetwork private constructor(private val value: Proto) {
    val hexSsid: String get() = value.string(1)
    val security: Int get() = value.integer(2)!!.toInt()
    val passphrase: String get() = value.string(3)
    val metered: Int get() = (value.integer(6) ?: 0).toInt()
    val lastConnectedWindowsMillis: Long get() = value.integer(9) ?: 0
    fun encode(): ByteArray = value.encode()
    fun tag(): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(
        Proto.field(662827, byteArrayOf()) + "$hexSsid<||>${if (security == WEP) "wep" else "psk"}".toByteArray(Charsets.UTF_8)))
    // dbpd compares SSID, security, passphrase and metered override. Timestamps aren't an edit.
    fun sameConfiguration(other: PasswordWifiNetwork): Boolean = hexSsid == other.hexSsid && security == other.security &&
        comparableKey() == other.comparableKey() && metered == other.metered
    fun digest(): ByteArray = MessageDigest.getInstance("SHA-256").digest(Proto.field(1, hexSsid) +
        Proto.integer(2, security.toLong()) + Proto.field(3, comparableKey()) + Proto.integer(6, metered.toLong()))
    private fun comparableKey(): String = if (isRawKey(passphrase, security)) passphrase.uppercase(Locale.US) else passphrase
    fun withConfiguration(other: PasswordWifiNetwork): PasswordWifiNetwork {
        if (tag() != other.tag()) throw ConflictException()
        return decode(value.replace(3, other.passphrase.toByteArray(Charsets.UTF_8)).replaceInteger(6, other.metered.toLong())
            .replaceInteger(9, other.lastConnectedWindowsMillis).encode())
    }
    override fun toString(): String = "PasswordWifiNetwork[redacted]"
    companion object {
        const val WEP = 2
        const val PSK = 3
        fun decode(raw: ByteArray): PasswordWifiNetwork = PasswordWifiNetwork(Proto.decode(raw)).also {
            if (!Regex("[0-9A-F]{2,64}").matches(it.hexSsid) || it.hexSsid.length % 2 != 0 ||
                it.security !in setOf(WEP, PSK) || it.passphrase.isEmpty() || it.passphrase == "*" ||
                it.passphrase.toByteArray(Charsets.UTF_8).size > 1024 || it.passphrase.any { c -> c == '\u0000' } ||
                it.metered !in 0..3 || it.lastConnectedWindowsMillis < 0) throw ProtocolException()
        }
        fun create(hexSsid: String, security: Int, passphrase: String, metered: Int = 3, lastConnectedWindowsMillis: Long = 0): PasswordWifiNetwork =
            decode(Proto.field(1, hexSsid.uppercase(Locale.US)) + Proto.integer(2, security.toLong()) + Proto.field(3, passphrase) +
                Proto.integer(6, metered.toLong()) + Proto.integer(9, lastConnectedWindowsMillis))
        /** Framework values are quoted strings or unquoted hex; never uppercase a quoted password. */
        fun frameworkValue(value: String): String = if (value.length >= 2 && value.startsWith('"') && value.endsWith('"'))
            value.substring(1, value.length - 1) else value.uppercase(Locale.US)
        fun frameworkKey(network: PasswordWifiNetwork): String {
            val value = network.passphrase
            val raw = isRawKey(value, network.security)
            return if (raw) value else "\"$value\""
        }
        fun frameworkMetered(value: Int): Int = when (value) { 1 -> 2; 2 -> 1; else -> 0 }
        fun syncedMetered(value: Int): Int = when (value) { 1 -> 2; 2 -> 1; else -> 3 }
        private fun isRawKey(value: String, security: Int): Boolean = value.matches(Regex("[0-9A-Fa-f]+")) &&
            (if (security == PSK) value.length == 64 else value.length in setOf(10, 26, 58))
    }
}

object PasswordWifiResource {
    fun read(resource: ByteArray, keys: VaultKeys): PasswordWifiNetwork {
        val record = Proto.decode(resource)
        val plain = keys.decrypt(record.one(6))
        return try {
            val result = PasswordWifiNetwork.decode(Proto.decode(plain).one(662827))
            if (record.string(2) != result.tag()) throw ProtocolException()
            result
        } finally { plain.fill(0) }
    }
    fun write(network: PasswordWifiNetwork, keys: VaultKeys, base: ByteArray? = null): ByteArray {
        val record = base?.let(Proto::decode) ?: Proto.decode(Proto.field(2, network.tag()))
        if (record.string(2) != network.tag()) throw ConflictException()
        return record.replace(6, keys.encrypt(Proto.field(662827, network.encode()))).encode()
    }
}

/** Pinned iakg/iakh list, iajb create, ialb update, iaji delete. IDs are opaque. */
class PasswordWifiApi(private val transport: PasswordTransport) {
    fun snapshot(parent: String): List<ByteArray> {
        PasswordApi.checkedParent(parent)
        val records = ArrayList<ByteArray>()
        val pages = HashSet<String>()
        var page: ByteArray? = null
        var size = 0L
        repeat(100) {
            val response = Proto.decode(transport.invoke(PasswordMethod.LIST_WIFI, Proto.field(1, parent) +
                (page?.let { Proto.field(3, it) } ?: byteArrayOf())))
            response.bytes(1).forEach { raw ->
                checkedId(Proto.decode(raw).string(1), parent)
                size += raw.size
                if (records.size >= 10_000 || size > Proto.MAX_BYTES / 2) throw ProtocolException()
                records.add(raw)
            }
            page = response.optional(2)?.takeIf { it.isNotEmpty() }
            if (page == null) {
                if (records.map { Proto.decode(it).string(2) }.toSet().size != records.size ||
                    records.map { Proto.decode(it).string(1) }.toSet().size != records.size) throw ProtocolException()
                return records
            }
            if (!pages.add(Base64.getEncoder().encodeToString(page!!))) throw ProtocolException()
        }
        throw ProtocolException()
    }
    fun create(parent: String, resource: ByteArray, token: ByteArray): ByteArray = transport.invoke(PasswordMethod.CREATE_WIFI,
        Proto.field(1, PasswordApi.checkedParent(parent)) + Proto.field(2, resource) + Proto.field(4, token))
    fun update(resource: ByteArray, token: ByteArray): ByteArray = transport.invoke(PasswordMethod.UPDATE_WIFI,
        Proto.field(1, checkedId(Proto.decode(resource).string(1))) + Proto.field(2, resource) + Proto.field(4, token))
    fun delete(resource: ByteArray, token: ByteArray) {
        val record = Proto.decode(resource)
        transport.invoke(PasswordMethod.DELETE_WIFI, Proto.field(1, checkedId(record.string(1))) + Proto.field(4, token))
    }
    companion object {
        fun checkedId(value: String, parent: String? = null): String = value.also {
            if (it.isEmpty() || it.length > 4096 || it.any(Char::isISOControl) ||
                parent != null && it.startsWith("users/") && !it.startsWith("$parent/")) throw ProtocolException()
        }
    }
}
