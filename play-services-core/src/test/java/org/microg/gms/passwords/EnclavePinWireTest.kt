/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import com.upokecenter.cbor.CBORObject
import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.auth.folsom.JoinSecurityDomainRequest
import org.microg.gms.auth.folsom.vault.VaultUpdateRequest
import org.microg.gms.auth.folsom.utils.SecureBox
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.sec.SECNamedCurves
import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.CertificateFactory

/** Independent CBOR library + generated SDK adapters + real SecureBox, with source-shaped enclave output. No live-server claim. */
class EnclavePinWireTest {
    private fun pair(n: Int) = ByteArray(31) + byteArrayOf(n.toByte()) + SECNamedCurves.getByName("secp256r1").g.multiply(BigInteger.valueOf(n.toLong())).normalize().getEncoded(false)
    private fun cbor(value: Any): ByteArray = CBORObject.FromObject(value).EncodeToBytes()
    @Test fun sourceSetPinResponseProducesCanonicalSdkRequestsExactCertSequenceAndRecoverableMemberKey() {
        val domainKey = ByteArray(32) { (it + 1).toByte() }; val hashedPin = ByteArray(32) { 7 }; val claim = ByteArray(32) { 8 }
        val salt = ByteArray(16) { 9 }; val counter = ByteArray(8) { 10 }; val handle = byteArrayOf(3) + ByteArray(16) { 11 }
        val application = pair(3).copyOfRange(32, 97); val cohort = EnclaveNoise.publicKey(pair(2).copyOfRange(32, 97)).encoded
        val certs = listOf("endpoint.pem", "root.pem").map { name -> javaClass.getResourceAsStream("/gpm-pin/$name")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it).encoded } }
        val metadata = Proto.field(2, claim) + Proto.integer(4, 1) + Proto.integer(5, 1) + Proto.integer(6, 16384) + Proto.field(7, salt)
        // Field names/types are independently pinned to Chrome145 BuildPINAndSecurityDomainSecretWrappingEnclaveRequest and ParseVaultAndMemberResponse.
        val commands = cbor(listOf(mapOf("cmd" to "recovery_key_store/wrap_pin_and_secret", "pin_hash" to hashedPin, "pin_claim_key" to claim,
            "cert_xml" to byteArrayOf(1), "sig_xml" to byteArrayOf(2), "wrapped_secret" to byteArrayOf(3))))
        val attempt = EnclavePinAttempt("source-shaped-id", "synthetic-gaia", 7, ByteArray(32), byteArrayOf(), commands, metadata,
            byteArrayOf(), byteArrayOf(), EnclavePinPhase.PIN_LAUNCHED)
        val wrappedPin = GpmPinCryptography.sealMetadata(cbor(mapOf(1 to hashedPin, 3 to claim, 4 to counter, 5 to handle.copyOfRange(1, 17), 6 to 10, 7 to cohort)), domainKey)
        val wrappedSds = SecureBox.wrapKey(application, domainKey)
        val response = cbor(listOf(mapOf("ok" to mapOf("wrapped_pin" to wrappedPin, "wrapped_sds" to wrappedSds,
            "member_proof" to EnrollmentWire.proof(domainKey, application), "wrapped" to mapOf(
                "cohort_public_key" to cohort, "encrypted_recovery_key" to ByteArray(80) { 12 }, "vault_handle" to handle,
                "counter_id" to counter, "app_public_key" to application, "wrapped_app_private_key" to ByteArray(125) { 13 },
                "wrapped_wrapping_key" to ByteArray(64) { 14 }, "max_attempts" to 10, "certs_in_path" to certs, "serial" to 10)))))
        val prepared = EnclavePinWire.finishPin(attempt, response, 42, domainKey)
        assertEquals(EnclavePinPhase.VAULT_PREPARED, prepared.phase)
        assertArrayEquals(prepared.update, VaultUpdateRequest.ADAPTER.decode(prepared.update).encode())
        assertArrayEquals(prepared.join, JoinSecurityDomainRequest.ADAPTER.decode(prepared.join).encode())
        val vault = Proto.decode(Proto.decode(prepared.update).one(3)); val params = Proto.decode(vault.one(2))
        assertArrayEquals(cohort, params.one(1)); assertArrayEquals(counter, params.one(2)); assertArrayEquals(handle, params.one(4)); assertEquals(10L, params.integer(3))
        val protection = Proto.decode(vault.one(3)); assertEquals(1L, protection.integer(1)); assertEquals(3L, protection.integer(4))
        assertEquals(16384L, protection.integer(6)); assertArrayEquals(salt, protection.one(5))
        val path = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(protection.one(7)))
        // Chrome concatenates leaf->issuer inside ASN.1 SEQUENCE; Java PkiPath reversal would change the source bytes.
        assertEquals(2, path.size()); assertArrayEquals(certs[0], path.getObjectAt(0).toASN1Primitive().encoded)
        assertArrayEquals(certs[1], path.getObjectAt(1).toASN1Primitive().encoded)
        val join = Proto.decode(prepared.join); assertNull(join.optional(5)); assertEquals("source-shaped-id", join.string(6))
        assertArrayEquals(domainKey, SecureBox.unwrapKey(pair(3), Proto.decode(join.one(3)).one(2)))
        val pin = Proto.decode(GpmPinRenewalWire.metadata(join.one(2)))
        assertEquals(1L, pin.integer(5)); assertArrayEquals(wrappedPin, pin.one(1)); assertArrayEquals(claim, pin.one(2))
        assertTrue(prepared.commands.isEmpty()); assertTrue(prepared.pinMetadata.isEmpty())
    }
    @Test fun independentCborReaderSeesOnlyTruthfullyRegisteredIdentityAndExactDeviceDigest() {
        val spki = EnclaveNoise.publicKey(pair(2).copyOfRange(32, 97)).encoded
        val commands = CBORObject.DecodeFromBytes(EnclavePinWire.register(spki, "sw"))
        assertEquals(2, commands.size()); val register = commands[0]
        assertEquals("device/register", register["cmd"].AsString()); assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(spki), register["device_id"].GetByteString())
        assertEquals(1, register["pub_keys"].size()); assertArrayEquals(spki, register["pub_keys"]["sw"].GetByteString())
        assertNull(register["uv_key_pending"]); assertEquals("keys/genpair", commands[1]["cmd"].AsString())
        assertEquals("security domain member key", commands[1]["purpose"].AsString())
        assertThrows(Exception::class.java) { EnclavePinWire.register(spki, "uv") }
    }
}
