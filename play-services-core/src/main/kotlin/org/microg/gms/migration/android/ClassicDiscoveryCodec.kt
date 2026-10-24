/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Public Nearby V1 discovery encodings. No peer-selected names become local paths. */
internal object ClassicDiscoveryCodec {
    data class Endpoint(val id: String, val info: ByteArray, val pcp: Int)
    private fun hash(service: String) = MessageDigest.getInstance("SHA-256").digest(service.toByteArray(Charsets.UTF_8))
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun decode(value: String): ByteArray { require(value.length <= 512); return Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP) }
    fun endpointId(): String { val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"; val random = SecureRandom(); return (1..4).map { chars[random.nextInt(chars.length)] }.joinToString("") }
    fun serviceType(service: String): String { require(service.isNotBlank() && service.length <= 256); return "_" + hash(service).take(6).joinToString("") { "%02X".format(it.toInt() and 255) } + "._tcp." }
    fun bluetoothUuid(service: String): UUID = UUID.nameUUIDFromBytes(service.toByteArray(Charsets.UTF_8))
    private fun validate(id: String, info: ByteArray, pcp: Int) { require(id.length == 4 && id.all { it.code in 33..126 }); require(pcp in 1..3 && info.size <= 131) }
    fun lanName(service: String, id: String, info: ByteArray, pcp: Int): String { validate(id, info, pcp); return encode(byteArrayOf((32 or pcp).toByte()) + id.toByteArray(Charsets.US_ASCII) + hash(service).copyOf(3)) }
    fun lanInfo(info: ByteArray): String { require(info.size <= 131); return encode(info) }
    fun parseLan(service: String, name: String, txt: ByteArray?): Endpoint {
        val bytes = decode(name); require(bytes.size in 8..64 && bytes[0].toInt() ushr 5 == 1)
        require(MessageDigest.isEqual(hash(service).copyOf(3), bytes.copyOfRange(5, 8)))
        val id = bytes.copyOfRange(1, 5).toString(Charsets.US_ASCII)
        val info = txt?.toString(Charsets.US_ASCII)?.let(::decode) ?: byteArrayOf()
        val pcp = bytes[0].toInt() and 31; validate(id, info, pcp); return Endpoint(id, info, pcp)
    }
    fun fastBle(id: String, info: ByteArray, pcp: Int): ByteArray { validate(id, info, pcp); require(info.size <= 17); return byteArrayOf((32 or pcp).toByte()) + id.toByteArray(Charsets.US_ASCII) + byteArrayOf(info.size.toByte()) + info }
    fun parseFastBle(bytes: ByteArray): Endpoint { require(bytes.size in 6..23 && bytes[0].toInt() ushr 5 == 1); val size = bytes[5].toInt() and 255; require(size <= 17 && bytes.size == size + 6); val id = bytes.copyOfRange(1,5).toString(Charsets.US_ASCII); val info = bytes.copyOfRange(6,bytes.size); val pcp = bytes[0].toInt() and 31; validate(id,info,pcp); return Endpoint(id,info,pcp) }
}
