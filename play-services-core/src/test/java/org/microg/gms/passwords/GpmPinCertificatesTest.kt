/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/** Public synthetic certificate fixtures; no private signing key is stored in the repository. */
class GpmPinCertificatesTest {
    private fun certificate(name: String): X509Certificate = javaClass.getResourceAsStream("/gpm-pin/$name")!!.use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
    private fun xml(serial: String = "2", endpoint: ByteArray = certificate("endpoint.pem").encoded) =
        ("<certs><metadata><serial>$serial</serial><refresh-interval>3600</refresh-interval></metadata>" +
            "<intermediates/><endpoints><cert>${Base64.getEncoder().encodeToString(endpoint)}</cert></endpoints></certs>").toByteArray()
    @Test fun actualPkixBuildBindsEndpointToSuppliedTrustedRootAndRetainsSpkiSerialAndPath() {
        val root = certificate("root.pem"); val leaf = certificate("endpoint.pem")
        val actual = GpmPinCertificates.parse(xml(), root).single()
        assertEquals(2L, actual.serial); assertArrayEquals(leaf.publicKey.encoded, actual.publicKey)
        val reopened = CertificateFactory.getInstance("X.509").generateCertPath(actual.certPath.inputStream(), "PkiPath")
        assertEquals(listOf(leaf), reopened.certificates)
        assertThrows(Exception::class.java) { GpmPinCertificates.parse(xml(), certificate("foreign-root.pem")) }
    }
    @Test fun forgedLeafSignatureCannotBecomeATrustedEndpointDespiteWellFormedXmlAndSameIssuer() {
        val damaged = certificate("endpoint.pem").encoded.also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { GpmPinCertificates.parse(xml(endpoint = damaged), certificate("root.pem")) }
    }
    @Test fun malformedMetadataDuplicatesExternalEntitiesAndUnboundedXmlAreRefused() {
        val root = certificate("root.pem")
        for (bad in listOf(xml("-1"), xml("not-a-serial"), String(xml()).replace("<serial>2</serial>", "<serial>2</serial><serial>3</serial>").toByteArray(),
                "<!DOCTYPE certs [<!ENTITY external SYSTEM 'file:///never-read'>]><certs/>".toByteArray(),
                "<certs><metadata><serial>2</serial><refresh-interval>1</refresh-interval></metadata><endpoints/></certs>".toByteArray(),
                ByteArray(256 * 1024 + 1), String(xml()).toByteArray(Charsets.UTF_16)))
            assertThrows(Exception::class.java) { GpmPinCertificates.parse(bad, root) }
    }
}
