/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.folsom.Keys
import org.microg.gms.auth.folsom.SecurityDomainMemberResponse
import org.microg.gms.auth.folsom.utils.FolsomMemberKeys
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class FolsomMemberKeysTest {
    // Independent source-shaped SecureBox fixture: both P-256 scalars are one,
    // so ECDH's x-coordinate is the published P-256 generator x-coordinate.
    private val gx = hex("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296")
    private val public = byteArrayOf(4) + gx + hex("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")
    private val pair = ByteArray(32).also { it[31] = 1 } + public
    private val old = ByteArray(32) { (it + 1).toByte() }
    private val fresh = ByteArray(32) { (it + 65).toByte() }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hmac(key: ByteArray, value: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(value)
    }
    private fun box(plain: ByteArray, header: ByteArray, shared: ByteArray? = null): ByteArray {
        val salt = "SECUREBOX".toByteArray() + byteArrayOf(2, 0)
        val info = (if (shared == null) "P256" else "SHARED") + " HKDF-SHA-256 AES-128-GCM"
        val prk = hmac(salt, shared ?: gx)
        val aes = hmac(prk, info.toByteArray() + byteArrayOf(1)).copyOf(16)
        val nonce = ByteArray(12) { (it + 3).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        return byteArrayOf(2, 0) + (if (shared == null) public else byteArrayOf()) + nonce + cipher.doFinal(plain)
    }
    private fun resource(keys: List<Pair<Int, ByteArray>>, proofs: List<Pair<Int, ByteArray>> = emptyList(),
        domain: String = "hw_protected", aad: String = "V1 shared_key"): SecurityDomainMemberResponse {
        val shared = keys.fold(byteArrayOf()) { raw, (epoch, key) -> raw + Proto.field(3,
            Proto.integer(1, epoch.toLong()) + Proto.field(2, box(key, aad.toByteArray())) +
                Proto.field(3, ByteArray(32) { 9 })) } // membership proof is not ciphertext
        val rotations = proofs.fold(byteArrayOf()) { raw, (epoch, proof) -> raw + Proto.field(4,
            Proto.integer(1, epoch.toLong()) + Proto.field(2, proof)) }
        return SecurityDomainMemberResponse.ADAPTER.decode(Proto.field(1, "users/me/members/fixture") +
            Proto.field(2, public) + Proto.field(3, Proto.field(1, "users/me/securitydomains/$domain") + shared + rotations))
    }
    @Test fun genuineMemberFieldTwoAndAuthenticatedHeaderDecryptWithoutTreatingProofsAsKeys() {
        val response = resource(listOf(7 to old), listOf(8 to box(byteArrayOf(0), fresh, old)))
        assertArrayEquals(public, response.publicKey!!.toByteArray())
        val result = FolsomMemberKeys.decode(response, "hw_protected", pair, emptyList())
        assertEquals(listOf(7), result.map { it.keyVersion })
        assertArrayEquals(old, result.single().keyMaterial!!.toByteArray())
        assertThrows(Exception::class.java) { FolsomMemberKeys.decode(resource(listOf(7 to old), aad = ""), "hw_protected", pair, emptyList()) }
        assertThrows(Exception::class.java) { FolsomMemberKeys.decode(response, "chromesync", pair, emptyList()) }
    }
    @Test fun rotationRequiresTheExactAuthenticatedPreviousKeyAndWholeChain() {
        val known = listOf(Keys(keyVersion = 7, keyMaterial = old.toByteString()))
        val proof = box(byteArrayOf(0), fresh, old)
        val response = resource(listOf(7 to old, 8 to fresh), listOf(8 to proof))
        assertEquals(listOf(7, 8), FolsomMemberKeys.decode(response, "hw_protected", pair, known).map { it.keyVersion })
        assertThrows(Exception::class.java) { FolsomMemberKeys.decode(resource(listOf(8 to fresh)), "hw_protected", pair, known) }
        assertThrows(Exception::class.java) { FolsomMemberKeys.decode(resource(listOf(8 to fresh), listOf(8 to proof.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })), "hw_protected", pair, known) }
        assertThrows(Exception::class.java) { FolsomMemberKeys.decode(response, "hw_protected", pair,
            listOf(Keys(keyVersion = 7, keyMaterial = fresh.toByteString()))) }
        assertThrows(Exception::class.java) { FolsomMemberKeys.decode(resource(listOf(7 to old, 7 to fresh)), "hw_protected", pair, known) }
        val third = ByteArray(32) { 3 }
        assertThrows(Exception::class.java) { FolsomMemberKeys.decode(resource(listOf(8 to fresh, 9 to third), listOf(8 to proof)), "hw_protected", pair, known) }
        assertThrows(Exception::class.java) { FolsomMemberKeys.decode(resource(listOf(8 to fresh), listOf(8 to proof, 8 to proof)), "hw_protected", pair, known) }
        assertArrayEquals(old, known.single().keyMaterial!!.toByteArray())
    }
}
