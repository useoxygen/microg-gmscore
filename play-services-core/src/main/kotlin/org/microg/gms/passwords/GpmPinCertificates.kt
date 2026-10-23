/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.CertPathBuilder
import java.security.cert.CertStore
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.cert.X509CertSelector
import java.security.interfaces.ECPublicKey
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.SAXException

/** hzgp/hzgn: endpoint PKIX paths against the actual OS Google root, after OS XML signature verification. */
internal object GpmPinCertificates {
    fun parse(xml: ByteArray, root: X509Certificate): List<GpmPinCertificate> {
        if (xml.size !in 1..256 * 1024 || xml.any { (it.toInt() and 255) !in 32..126 && it !in byteArrayOf(9, 10, 13) }) throw ProtocolException()
        val ascii = String(xml, Charsets.US_ASCII)
        if (Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(ascii)) throw ProtocolException()
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false; isExpandEntityReferences = false }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXException("External reference refused") }
        val element = builder.parse(ByteArrayInputStream(xml)).documentElement
        fun children(parent: Element, tag: String): List<Element> = (0 until parent.childNodes.length).mapNotNull {
            (parent.childNodes.item(it) as? Element)?.takeIf { node -> node.tagName == tag }
        }
        fun path(group: String, leaf: String): List<Element> = children(element, group).singleOrNull()?.let { children(it, leaf) } ?: emptyList()
        val serial = path("metadata", "serial").singleOrNull()?.textContent?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: throw ProtocolException()
        if (path("metadata", "refresh-interval").singleOrNull()?.textContent?.trim()?.toLongOrNull()?.let { it >= 0 } != true) throw ProtocolException()
        val certificates = CertificateFactory.getInstance("X.509")
        fun cert(node: Element): X509Certificate {
            val raw = Base64.getDecoder().decode(node.textContent.replace(Regex("\\s+"), ""))
            if (raw.size !in 1..65536) throw ProtocolException()
            return certificates.generateCertificate(ByteArrayInputStream(raw)) as? X509Certificate ?: throw ProtocolException()
        }
        val intermediates = path("intermediates", "cert").also { if (it.size > 128) throw ProtocolException() }.map(::cert)
        val endpoints = path("endpoints", "cert").also { if (it.isEmpty() || it.size > 128) throw ProtocolException() }.map(::cert)
        return endpoints.map { leaf ->
            val public = leaf.publicKey as? ECPublicKey ?: throw ProtocolException()
            if (public.params.curve.field.fieldSize != 256) throw ProtocolException()
            val selector = X509CertSelector().apply { certificate = leaf }
            val params = PKIXBuilderParameters(setOf(TrustAnchor(root, null)), selector).apply {
                isRevocationEnabled = false
                addCertStore(CertStore.getInstance("Collection", CollectionCertStoreParameters(intermediates + listOf(leaf))))
            }
            val path = CertPathBuilder.getInstance("PKIX").build(params).certPath
            GpmPinCertificate(serial, public.encoded, path.getEncoded("PkiPath"))
        }
    }
}
