/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.Base64

class PasswordAssetLinksTest {
    private val site = AffiliationFacet.parse("https://example.invalid")
    private val app = AffiliationFacet.parse("android://AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8@com.example.fixture")
    private val digest = Base64.getUrlDecoder().decode(app.id.removePrefix("android://").substringBefore('@'))
        .joinToString(":") { "%02X".format(it.toInt() and 255) }
    private fun statement(target: JSONObject, relation: String = DigitalAssetLinks.RELATION) = JSONObject()
        .put("relation", JSONArray().put(relation)).put("target", target)
    private fun websiteTarget(url: String = site.id) = JSONObject().put("namespace", "web").put("site", url)
    private fun appTarget(fingerprint: String = digest, name: String = "com.example.fixture") = JSONObject()
        .put("namespace", "android_app").put("package_name", name).put("sha256_cert_fingerprints", JSONArray().put(fingerprint))
    private fun statements(vararg values: JSONObject): ByteArray = values.fold(JSONArray()) { out, item -> out.put(item) }.toString().toByteArray()
    private val endpoint = site.id + "/.well-known/assetlinks.json"

    @Test fun reciprocalCredentialStatementsRequireTheExactCertificateAndWebOrigin() {
        val website = statements(statement(websiteTarget()), statement(appTarget()))
        val application = statements(statement(websiteTarget()))
        val verifier = DigitalAssetLinks { url -> assertEquals(endpoint, url); website }
        assertTrue(verifier.allows(site, app, application))
        assertFalse(verifier.allows(site, AffiliationFacet.parse(app.id.replace("AAEC", "BAEC")), application))
        assertFalse(verifier.allows(AffiliationFacet.parse("http://example.invalid"), app, application))
        assertFalse(verifier.allows(site, app, statements(statement(websiteTarget("https://example.invalid:8443")))))
        assertFalse(verifier.allows(site, app, statements(statement(websiteTarget("https://sub.example.invalid")))))
    }

    @Test fun appLinkPermissionAppNamesAndOneSidedDeclarationsNeverGrantCredentialAuthority() {
        val application = statements(statement(websiteTarget()))
        for (website in listOf(
            statements(statement(websiteTarget()), statement(appTarget(), "delegate_permission/common.handle_all_urls")),
            statements(statement(websiteTarget()), statement(appTarget(name = "com.example.other"))),
            statements(statement(websiteTarget()), statement(appTarget(fingerprint = digest.lowercase()))),
            statements(statement(appTarget())))) {
            assertFalse(DigitalAssetLinks { website }.allows(site, app, application))
        }
        assertFalse(DigitalAssetLinks { statements(statement(websiteTarget()), statement(appTarget())) }
            .allows(site, app, statements(statement(websiteTarget(), "delegate_permission/common.handle_all_urls"))))
    }

    @Test fun anAppThatDoesNotDeclareTheExactStoredOriginCausesNoWebsiteEgress() {
        var fetches = 0
        val verifier = DigitalAssetLinks { fetches++; error("Unrelated saved sites must not be contacted") }
        assertFalse(verifier.allows(site, app, statements(statement(websiteTarget("https://other.invalid")))))
        assertFalse(verifier.allows(site, app, statements(statement(websiteTarget(), "delegate_permission/common.handle_all_urls"))))
        assertFalse(verifier.allows(site, app, "[]".toByteArray()))
        assertEquals(0, fetches)
    }

    @Test fun delegatedIncludesAreBoundedAndDoNotChangeTheSourceWebOrigin() {
        val delegated = "https://static.example.invalid/delegation.json"
        val include = statements(JSONObject().put("include", delegated))
        val website = statements(statement(websiteTarget()), statement(appTarget()))
        val verifier = DigitalAssetLinks { url -> when (url) { endpoint -> include; delegated -> website; else -> error("Unexpected URL") } }
        assertTrue(verifier.allows(site, app, statements(JSONObject().put("include", endpoint))))
        val cycling = DigitalAssetLinks { statements(JSONObject().put("include", endpoint)) }
        assertFalse(cycling.allows(site, app, statements(statement(websiteTarget()))))
        val downgrade = DigitalAssetLinks { statements(JSONObject().put("include", "http://example.invalid/links.json")) }
        assertFalse(downgrade.allows(site, app, statements(statement(websiteTarget()))))
        var fetches = 0
        val excessive = DigitalAssetLinks {
            fetches++
            statements(JSONObject().put("include", "https://static.example.invalid/$fetches.json"))
        }
        assertFalse(excessive.allows(site, app, statements(statement(websiteTarget())))); assertTrue(fetches <= 11)
    }

    @Test fun malformedOversizedAndUnavailableDocumentsFailClosed() {
        val appStatements = statements(statement(websiteTarget()))
        for (raw in listOf("{}".toByteArray(), "[] trailing".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28),
            ByteArray(DigitalAssetLinks.MAX_DOCUMENT_BYTES + 1))) {
            assertFalse(DigitalAssetLinks { raw }.allows(site, app, appStatements))
        }
        assertFalse(DigitalAssetLinks { throw IllegalStateException("fixture network outage") }.allows(site, app, appStatements))
    }

    @Test fun directProofTransportRefusesPrivateLoopbackLinkLocalAndCarrierAddresses() {
        for (value in listOf("127.0.0.1", "10.0.0.1", "192.168.1.1", "169.254.1.1", "100.64.0.1", "0.0.0.0", "224.0.0.1", "::1", "fc00::1", "fe80::1")) {
            assertFalse(value, PasswordAssetLinks.publicAddress(InetAddress.getByName(value)))
        }
        assertTrue(PasswordAssetLinks.publicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(PasswordAssetLinks.publicAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }
}
