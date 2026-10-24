/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import com.google.common.hash.Hashing
import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom

/** google/nearby Apache-2.0 reference: connections BleAdvertisement V1 inside
 * mediums BleAdvertisement V2, plus the regular V2 GATT advertisement header.
 * No BLE data socket is advertised: the regular record supplies a real RFCOMM
 * address. A platform-redacted Bluetooth address must never be used as a peer. */
internal object ClassicBleCodec {
    private fun hash(bytes: ByteArray, size: Int) = MessageDigest.getInstance("SHA-256").digest(bytes).copyOf(size)
    private fun serviceHash(service: String) = hash(service.toByteArray(Charsets.UTF_8),3)
    fun mac(value: String): ByteArray {
        require(value.matches(Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}")))
        val result=value.split(':').map { it.toInt(16).toByte() }.toByteArray()
        require(result.any { it.toInt()!=0 } && value != "02:00:00:00:00:00" && result[0].toInt() and 1 == 0)
        return result
    }
    private fun bloom(service: String): ByteArray {
        val low=Hashing.murmur3_128().hashString(service,Charsets.UTF_8).asLong()
        val first=low.toInt(); val second=(low ushr 32).toInt(); val bytes=ByteArray(10)
        for(i in 1..5) { var combined=first+i*second; if(combined<0)combined=combined.inv(); val bit=combined%80; bytes[bit/8]=(bytes[bit/8].toInt() or (1 shl (bit%8))).toByte() }
        return bytes
    }
    fun header(service: String, advertisement: ByteArray): ByteArray {
        val random=ByteArray(16).also(SecureRandom()::nextBytes)
        val dummy=android.util.Base64.encodeToString(random,android.util.Base64.NO_WRAP)
        val first=bloom(service); val second=bloom(dummy)
        return byteArrayOf(0x41)+ByteArray(10) { (first[it].toInt() or second[it].toInt()).toByte() }+hash(hash(random,4)+advertisement,4)+byteArrayOf(0,0)
    }
    fun slots(service: String, header: ByteArray): Int {
        require(header.size==15 || header.size==17)
        require(header[0].toInt() and 0xe0 == 0x40)
        val expected=bloom(service)
        if((0..9).any { (header[it+1].toInt() and (expected[it].toInt() and 255)) != (expected[it].toInt() and 255) }) return 0
        return header[0].toInt() and 15
    }
    fun regular(service: String, id: String, info: ByteArray, pcp: Int, address: String): ByteArray {
        // Reuse validation of endpoint ID, info bound and PCP.
        ClassicDiscoveryCodec.lanName(service,id,info,pcp)
        val connection=byteArrayOf((32 or pcp).toByte())+serviceHash(service)+id.toByteArray(Charsets.US_ASCII)+byteArrayOf(info.size.toByte())+info+mac(address)+byteArrayOf(0,0)
        return wrap(service,connection,false)
    }
    fun fast(service: String, id: String, info: ByteArray, pcp: Int) = wrap(service,ClassicDiscoveryCodec.fastBle(id,info,pcp),true)
    private fun wrap(service: String, body: ByteArray, fast: Boolean): ByteArray = ByteArrayOutputStream().apply {
        DataOutputStream(this).apply { writeByte(if(fast)0x4a else 0x48); if(fast)writeByte(body.size) else { write(serviceHash(service)); writeInt(body.size) }; write(body); write(ByteArray(2).also(SecureRandom()::nextBytes)) }
    }.toByteArray()
    fun parse(service: String, bytes: ByteArray): Pair<ClassicDiscoveryCodec.Endpoint,String?> {
        require(bytes.size in 2..512)
        val input=DataInputStream(ByteArrayInputStream(bytes)); val flags=input.readUnsignedByte()
        require(flags ushr 5 in 1..2 && (flags and 28) ushr 2 in 1..2 && flags and 1 == 0)
        val fast=flags and 2 != 0
        require(input.available() >= if(fast)1 else 7)
        if(!fast)require(MessageDigest.isEqual(ByteArray(3).also(input::readFully),serviceHash(service)))
        val count=if(fast)input.readUnsignedByte() else input.readInt(); require(count in 1..256 && count<=input.available())
        val body=ByteArray(count).also(input::readFully)
        if(fast)return ClassicDiscoveryCodec.parseFastBle(body) to null
        val record=DataInputStream(ByteArrayInputStream(body)); val version=record.readUnsignedByte(); require(version ushr 5==1)
        require(MessageDigest.isEqual(ByteArray(3).also(record::readFully),serviceHash(service)))
        val id=ByteArray(4).also(record::readFully).toString(Charsets.US_ASCII)
        val size=record.readUnsignedByte(); require(size<=131 && size+6<=record.available())
        val info=ByteArray(size).also(record::readFully); val address=ByteArray(6).also(record::readFully).joinToString(":") { "%02X".format(it.toInt() and 255) }
        val pcp=version and 31; ClassicDiscoveryCodec.lanName(service,id,info,pcp)
        return ClassicDiscoveryCodec.Endpoint(id,info,pcp) to address.takeIf { runCatching { mac(it) }.isSuccess }
    }
}
