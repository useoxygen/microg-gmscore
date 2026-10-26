/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest

/** SDS MemberKey field3 is HMAC-SHA256(shared key, member public key), not a page-success flag. */
object PasswordEncryptionKeyProof {
    fun verify(domain: ByteArray, members: List<ByteArray>, delivered: List<VersionedTrustedVaultKey>, known: List<VersionedTrustedVaultKey>) {
        val epoch = EnrollmentWire.currentEpoch(domain)
        if (delivered.isEmpty() || delivered.size > 100 || delivered.map { it.epoch }.toSet().size != delivered.size ||
            delivered.maxOf { it.epoch } != epoch || members.size > 10_000 || known.size > 100 ||
            known.map { it.epoch }.toSet().size != known.size || known.any { it.epoch > epoch }) throw ProtocolException()
        for (key in delivered) {
            val material = key.material
            try {
                known.singleOrNull { it.epoch == key.epoch }?.let { old ->
                    val raw = old.material
                    try { if (!MessageDigest.isEqual(raw, material)) throw ConflictException() } finally { raw.fill(0) }
                }
                val proved = members.any { encoded ->
                    val member = Proto.decode(encoded)
                    val public = member.optional(2) ?: return@any false
                    if (public.size != 65 || public[0] != 4.toByte() || member.string(1) != EnrollmentWire.memberName(public)) return@any false
                    val membership = member.bytes(3).map(Proto::decode).singleOrNull { it.string(1) == EnrollmentWire.DOMAIN }
                        ?: return@any false
                    val matching = membership.bytes(3).map(Proto::decode).filter { it.integer(1) == key.epoch.toLong() }
                    if (matching.size != 1) return@any false
                    val proof = matching.single().optional(3) ?: return@any false
                    val expected = EnrollmentWire.proof(material, public)
                    try { MessageDigest.isEqual(proof, expected) } finally { expected.fill(0) }
                }
                if (!proved) throw VaultLockedException()
            } finally { material.fill(0) }
        }
    }
}
