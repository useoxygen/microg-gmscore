/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

/** Expected relations are literal source-shaped cases, not computed by the production PSL helper. */
class PasswordPublicSuffixTest {
    private fun data(realm: String = "https://login.example.com/", origin: String = realm + "form") =
        PasswordData.create(realm, origin, "synthetic-user", "synthetic-value", 1234)
    private fun psl(first: String, second: String) = assertEquals(WebPasswordMatch.PUBLIC_SUFFIX, PasswordPublicSuffix.match(first, second))
    private fun none(first: String, second: String) = assertEquals(WebPasswordMatch.NONE, PasswordPublicSuffix.match(first, second))

    @Test fun htmlSiblingOriginsUseTheSameSchemeAndEffectivePort() {
        psl("https://example.com/", "https://m.example.com/")
        psl("https://www.example.com/", "https://m.example.com:443/")
        psl("http://www.example.com:8080/", "http://m.example.com:8080/")
        psl("https://a.example.co.uk/", "https://b.example.co.uk/")
        assertEquals(WebPasswordMatch.EXACT, PasswordPublicSuffix.match("https://example.com/", "https://example.com:443/"))
        assertTrue(PasswordPublicSuffix.canFill(data(), "https://m.example.com/"))
    }
    @Test fun differentSchemesPortsAndRegistryDomainsDoNotMatch() {
        none("http://example.com/", "https://m.example.com/")
        none("https://example.com:444/", "https://m.example.com/")
        none("https://example.com/", "https://wwwexample.com/")
        none("https://example.org/", "https://m.example.com/")
        none("https://example.com.evil.org/", "https://m.example.com/")
    }
    @Test fun privateRegistriesAndPslWildcardsNeverJoinIndependentTenants() {
        psl("https://login.first.appspot.com/", "https://www.first.appspot.com/")
        none("https://first.appspot.com/", "https://second.appspot.com/")
        none("https://first.github.io/", "https://second.github.io/")
        none("https://a.foo.ck/", "https://b.foo.ck/") // *.ck makes foo.ck a public suffix.
        psl("https://www.ck/", "https://sub.www.ck/") // !www.ck is the literal PSL exception.
    }
    @Test fun googlePslOnlyConnectsAccountsAndMyaccountWithTheirActualSubdomains() {
        psl("https://accounts.google.com/", "https://myaccount.google.com/")
        psl("https://a.accounts.google.com/", "https://b.myaccount.google.com/")
        for (other in listOf("https://maps.google.com/", "https://google.com/", "https://accounts.google.com.evil.com/"))
            none("https://accounts.google.com/", other)
        none("https://accounts.google.com/", "https://myaccount.google.com:444/")
        assertEquals(WebPasswordMatch.EXACT, PasswordPublicSuffix.match("https://maps.google.com/", "https://maps.google.com/"))
    }
    @Test fun unsupportedAndMalformedAuthoritiesCannotBecomePslRelations() {
        for (realm in listOf("android://AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=@com.example/", "federation://example.com/google.com",
            "https://example.com/path", "https://user@example.com/", "https://example.com/?q=x", "https://example.com/#x", "https://"))
            none(realm, "https://m.example.com/")
        none("http://localhost/", "http://a.localhost/")
        none("https://127.0.0.1/", "https://127.0.0.2/")
        none("https://[::1]/", "https://[::2]/")
    }
    @Test fun fillRequiresHtmlOriginConsistencyAndUsableUnblockedCredential() {
        val valid = data()
        for (scheme in listOf(1L, 2L, 3L, 4L, 99L)) assertFalse(PasswordPublicSuffix.canFill(
            PasswordData.decode(Proto.decode(valid.encode()).replaceInteger(1, scheme).encode()), "https://m.example.com/"))
        assertFalse(PasswordPublicSuffix.canFill(data(origin = "https://attacker.org/form"), "https://m.example.com/"))
        assertFalse(PasswordPublicSuffix.canFill(valid.withBlocked(true), "https://m.example.com/"))
        assertFalse(PasswordPublicSuffix.canFill(valid.withPassword("", 1235), "https://m.example.com/"))
    }
    @Test fun chromePslRowsAreGroupedWithoutInventingCredentialSharingAndPreserveExactPayload() {
        val chrome = Proto.field(1, data().encode()) + Proto.field(3, Proto.field(50, byteArrayOf(7, 8)))
        val requested = AffiliationFacet.parse("https://m.example.com/")
        val row = Proto.decode(ChromeAffiliatedPassword.affiliated(chrome, requested, null)!!)
        assertArrayEquals(chrome, row.one(1)); assertEquals(0L, row.integer(3)); assertEquals(1L, row.integer(4))
        assertNull(row.integer(5)) // There is no invented PSL flag; Chrome computes kPSL from the realms.
        val nonHtml = Proto.field(1, Proto.decode(data().encode()).replaceInteger(1, 2).encode())
        assertNull(ChromeAffiliatedPassword.affiliated(nonHtml, requested, null))
    }
    @Test fun independentlyVerifiedAffiliationCanCoexistWithPslButIsNeverRequiredToEnableIt() {
        val chrome = Proto.field(1, data().encode()); val requested = AffiliationFacet.parse("https://m.example.com/")
        assertEquals(1L, Proto.decode(ChromeAffiliatedPassword.affiliated(chrome, requested, null, verifiedSharing = true)!!).integer(3))
        assertNull(ChromeAffiliatedPassword.affiliated(chrome, AffiliationFacet.parse("https://other.com/"), null))
    }
}
