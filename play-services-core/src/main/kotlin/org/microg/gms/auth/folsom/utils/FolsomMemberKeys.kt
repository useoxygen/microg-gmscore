/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.folsom.utils

import okio.ByteString.Companion.toByteString
import org.microg.gms.auth.folsom.Keys
import org.microg.gms.auth.folsom.SecurityDomainMemberResponse
import java.security.MessageDigest

/** GMS hzxq/hzxz/hzxf, anwc.F/aa/Z. A rotation proof is never a wrapped domain key. */
internal object FolsomMemberKeys {
    private val sharedKeyHeader = "V1 shared_key".toByteArray(Charsets.UTF_8)

    fun decode(member: SecurityDomainMemberResponse, domainId: String, pair: ByteArray, known: List<Keys>): List<Keys> {
        require(pair.size == 97 && pair[32] == 4.toByte()) { "Invalid recovery key pair" }
        val public = pair.copyOfRange(32, 97)
        member.publicKey?.let { require(MessageDigest.isEqual(public, it.toByteArray())) { "Recovery member changed" } }
        val domain = member.securityDomains.singleOrNull { FolsomDomainPolicy.matches(it.name, domainId) }
            ?: throw IllegalArgumentException("Recovery domain unavailable")
        require(domain.memberKeys.size in 1..100 && domain.rotationProofs.size <= 100 && known.size <= 100) { "Invalid recovery keys" }
        val decoded = LinkedHashMap<Int, ByteArray>()
        val retained = LinkedHashMap<Int, ByteArray>()
        try {
            known.forEach { value ->
                val epoch = value.keyVersion ?: 0
                val raw = value.keyMaterial?.toByteArray() ?: throw IllegalArgumentException("Invalid recovery anchor")
                if (epoch <= 0 || raw.size !in 1..4096 || retained.containsKey(epoch)) {
                    raw.fill(0); throw IllegalArgumentException("Invalid recovery anchor")
                }
                retained[epoch] = raw
            }
            domain.memberKeys.forEach { value ->
                val epoch = value.epoch ?: 0
                val wrapped = value.wrappedKey?.toByteArray() ?: throw IllegalArgumentException("Missing wrapped recovery key")
                require(epoch > 0 && wrapped.size in 95..8192 && !decoded.containsKey(epoch)) { "Invalid recovery epoch" }
                val raw = try { SecureBox.unwrapKey(pair, wrapped, sharedKeyHeader) } finally { wrapped.fill(0) }
                decoded[epoch] = raw
                require(raw.size in 1..4096) { "Invalid recovery key" }
                retained[epoch]?.let { require(MessageDigest.isEqual(it, raw)) { "Recovery epoch changed" } }
            }
            val proofs = domain.rotationProofs.associate { value ->
                val epoch = value.epoch ?: 0
                val proof = value.rotationProof ?: throw IllegalArgumentException("Missing recovery rotation proof")
                require(epoch > 0 && proof.size in 31..8192) { "Invalid recovery rotation proof" }
                epoch to proof
            }
            require(proofs.size == domain.rotationProofs.size) { "Duplicate recovery rotation proof" }
            var previousEpoch = retained.keys.maxOrNull()
            var previousKey = previousEpoch?.let(retained::get)
            // A first member download is authenticated by its exact member's private key.
            // Once a known epoch exists, each newer epoch must extend that authenticated chain.
            decoded.toSortedMap().forEach { (epoch, raw) ->
                if (previousEpoch != null && epoch > previousEpoch!!) {
                    val proof = proofs[epoch]?.toByteArray() ?: throw IllegalArgumentException("Missing recovery rotation proof")
                    val plain = try { SecureBox.decrypt(null, previousKey!!, raw, proof) } finally { proof.fill(0) }
                    plain.fill(0) // GMS Z checks authenticated decryption, not an invented key field.
                    previousEpoch = epoch; previousKey = raw
                }
            }
            val merged = retained + decoded
            require(merged.size <= 100) { "Too many recovery epochs" }
            return merged.toSortedMap().map { (epoch, raw) -> Keys(keyVersion = epoch, keyMaterial = raw.toByteString()) }
        } finally { decoded.values.forEach { it.fill(0) }; retained.values.forEach { it.fill(0) }; public.fill(0) }
    }
}
