/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.spec.X509EncodedKeySpec
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.jce.provider.BouncyCastleProvider

/** Independently implemented Chromium145 server attestation: AMD Milan -> SEV-SNP -> DICE -> leaf -> Noise hash. */
object EnclaveAttestation {
    fun verify(raw: ByteArray, handshakeHash: ByteArray) {
        if (handshakeHash.size != 32) throw ProtocolException()
        val values = EnclaveCbor.map(EnclaveCbor.decode(raw))
        try {
            val certRaw = EnclaveCbor.bytes(values[4L], 1..65536)
            val stream = ByteArrayInputStream(certRaw)
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(stream) as X509Certificate
            if (stream.available() != 0 || !cert.encoded.contentEquals(certRaw)) throw ProtocolException()
            val issuer = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(EnclaveNoise.hex(MILAN_SPKI)))
            // Android Conscrypt exposes digest-specific PSS names. Use the already bundled provider for this exact contract.
            val pss = Signature.getInstance("RSASSA-PSS", BouncyCastleProvider())
            pss.initVerify(issuer)
            pss.setParameter(PSSParameterSpec("SHA-384", "MGF1", MGF1ParameterSpec.SHA384, 48, 1))
            pss.update(cert.tbsCertificate)
            if (!pss.verify(cert.signature)) throw ProtocolException()
            val evidence = Proto.decode(EnclaveCbor.bytes(values[3L]))
            val root = Proto.decode(evidence.one(1))
            if (root.integer(1) != 1L || evidence.bytes(2).size != 1) throw ProtocolException()
            val report = root.one(2)
            if (report.size != 0x4a0) throw ProtocolException()
            val extensions = listOf(1 to 0x180, 2 to 0x181, 3 to 0x186, 8 to 0x187)
            extensions.forEach { (oid, offset) ->
                val bytes = extension(cert, "1.3.6.1.4.1.3704.1.3.$oid")
                val number = ASN1Integer.getInstance(ASN1Primitive.fromByteArray(bytes)).positiveValue
                if (number != BigInteger.valueOf((report[offset].toInt() and 255).toLong())) throw ProtocolException()
            }
            if (!extension(cert, "1.3.6.1.4.1.3704.1.4").contentEquals(report.copyOfRange(0x1a0, 0x1e0))) throw ProtocolException()
            val vcek = cert.publicKey as? ECPublicKey ?: throw ProtocolException()
            if (vcek.params.curve.field.fieldSize != 384) throw ProtocolException()
            val reportSignature = der(report.copyOfRange(0x2a0, 0x2d0).reversedArray(), report.copyOfRange(0x2e8, 0x318).reversedArray())
            verifySignature(vcek, "SHA384withECDSA", report.copyOfRange(0, 0x2a0), reportSignature)
            val rootCose = root.one(3)
            if (!EnclaveNoise.sha(rootCose).contentEquals(report.copyOfRange(0x50, 0x70)) ||
                report.copyOfRange(0x70, 0x90).any { it != 0.toByte() }) throw ProtocolException()
            val rootKey = coseKey(rootCose)
            val kernel = cwt(Proto.decode(evidence.bytes(2).single()).one(1), rootKey)
            val application = cwt(Proto.decode(evidence.one(3)).one(2), kernel)
            val leaf = EnclaveCbor.bytes(values[1L], 65..65)
            verifySignature(application, "SHA256withECDSA", "Leaf dice signature\u0000".toByteArray() + leaf,
                concatSignature(EnclaveCbor.bytes(values[2L], 64..64)))
            verifySignature(EnclaveNoise.publicKey(leaf), "SHA256withECDSA", "Handshake signature\u0000".toByteArray() + handshakeHash,
                EnclaveCbor.bytes(values[0L], 8..80))
        } finally { EnclaveCbor.destroy(values) }
    }
    private fun extension(cert: X509Certificate, oid: String): ByteArray {
        val raw = cert.getExtensionValue(oid) ?: throw ProtocolException()
        return ASN1OctetString.getInstance(ASN1Primitive.fromByteArray(raw)).octets
    }
    private fun coseKey(raw: ByteArray): PublicKey {
        val key = EnclaveCbor.map(EnclaveCbor.decode(raw))
        try {
            // ExtractFromCOSEKey receives ES256 out of band; COSE alg is not required on the DICE public key.
            if (key[1L] != 2L || key[3L]?.let { it != -7L } == true || key[-1L] != 1L) throw ProtocolException()
            return EnclaveNoise.publicKey(byteArrayOf(4) + EnclaveCbor.bytes(key[-2L], 32..32) + EnclaveCbor.bytes(key[-3L], 32..32))
        } finally { EnclaveCbor.destroy(key) }
    }
    private fun cwt(raw: ByteArray, issuer: PublicKey): PublicKey {
        val value = EnclaveCbor.decode(raw) as? List<*> ?: throw ProtocolException()
        try {
            if (value.size != 4) throw ProtocolException()
            val protected = EnclaveCbor.bytes(value[0]); val body = EnclaveCbor.bytes(value[2]); val signature = EnclaveCbor.bytes(value[3], 64..64)
            val headers = EnclaveCbor.map(EnclaveCbor.decode(protected))
            if (headers[1L] != -7L) throw ProtocolException()
            val signed = EnclaveCbor.encode(listOf("Signature1", protected, byteArrayOf(), body))
            verifySignature(issuer, "SHA256withECDSA", signed, concatSignature(signature))
            val claims = EnclaveCbor.map(EnclaveCbor.decode(body))
            return try { coseKey(EnclaveCbor.bytes(claims[-4670552L])) } finally { EnclaveCbor.destroy(claims) }
        } finally { EnclaveCbor.destroy(value) }
    }
    private fun verifySignature(key: PublicKey, algorithm: String, raw: ByteArray, signature: ByteArray) {
        if (!Signature.getInstance(algorithm).run { initVerify(key); update(raw); verify(signature) }) throw ProtocolException()
    }
    private fun concatSignature(raw: ByteArray): ByteArray = der(raw.copyOfRange(0, 32), raw.copyOfRange(32, 64))
    private fun der(r: ByteArray, s: ByteArray): ByteArray = DERSequence(ASN1EncodableVector().apply {
        add(ASN1Integer(BigInteger(1, r))); add(ASN1Integer(BigInteger(1, s))) }).encoded
    // Public AMD Milan ASK SPKI from Chromium145 attestation.cc. It is not account or device material.
    private const val MILAN_SPKI = "30820222300d06092a864886f70d01010105000382020f003082020a02820201009d4d9daeb3537db84d4089657fe5b6cbe44e09b4b321dd5a2997edd93f738d940ece319c725d7b8b598829697b353701d15617b77271652cce663b232cd54010dd8c1a3f5389e74bf907b02995f4266404b988e6f962a4b0bb7181d2e9f44ec464dc0d0ea575af4a913f9b41f0e5a4c906c874b7aee1a0b3ee3fd2975164072b5ebfdb1b146cededcc278f38bd9bb9e8aac93eb91541a77f889f7e503dd723f187e51269c704dbee5032612c224c5bc28e8cfebef8f85bb378828ad25c00d12d5b8a93345a0a5b708795b7120a34ccf0ab0d6d4c7703c7a4e4454b8d9587d69b7d1374dfa51e97c9f40a9d8ea497968420fc1d5b778561aa8214fac8a3da504fa5ae0d23f82426096d99de28a21b663cd7909b773501b7d84ba46089816fd482926f7e7d2e4b64583da23cee6cc5f7f8d901a125c8ece3ef9c73318eea7d9b0e6c7ba41457b1aaa420364cdca9a259ae43e7006b157b26a1c4f1d97da567fe6376ab6fef628850b016de25025270e43d024d14c2d58e585850c10ab03fbc69e64e7a86f02fa38d4012edc8e347cab838f8720e62513e7682fd91b9b8fd0ad33e86a0eff7b9e9fdc2ab210ae1de80b4e939e1c42512ab405af83d523054e074f9e6cee45828c8ec38de7c850f950a043c4407804aa3fd2e0222872d1bef80b6ce45b53c280448b35128a848e617c52dae64165768548f0ceac2f1c57a2f2b3f130203010001"
}
