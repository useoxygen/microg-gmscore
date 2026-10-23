/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class AffiliationsTest {
    private val web = AffiliationFacet.parse("https://example.invalid")
    private val other = AffiliationFacet.parse("https://other.invalid")
    private val app = AffiliationFacet.parse("android://AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8@com.Example_app")
    private fun facet(value: AffiliationFacet, extra: ByteArray = byteArrayOf()) = Proto.field(1, value.id) + extra
    private fun group(vararg values: AffiliationFacet) = values.fold(byteArrayOf()) { out, value -> out + Proto.field(1, facet(value)) }

    @Test fun displayGroupingDoesNotAuthorizeCredentialSharing() {
        val raw = Proto.field(1, group(web)) + Proto.field(2, group(web, app))
        val response = AffiliationResponse.decode(raw, listOf(web))
        assertEquals(setOf(web), response.credentialSharingFacets(web))
        assertEquals(setOf(web, app), response.groupedFacets(web).map { it.facet }.toSet())
        assertTrue(response.credentialSharingFacets(app).isEmpty())
    }

    @Test fun sharingRequiresTheExactCertificateFacetAndDoesNotIncludeInsecureHttp() {
        val http = AffiliationFacet.parse("http://example.invalid")
        val raw = Proto.field(1, group(web, app, http))
        val response = AffiliationResponse.decode(raw, listOf(web))
        assertEquals(setOf(web, app), response.credentialSharingFacets(web))
        assertEquals(setOf(web, app), response.credentialSharingFacets(app))
        val differentCertificate = AffiliationFacet.parse(app.id.replace("AAEC", "BAEC"))
        assertTrue(response.credentialSharingFacets(differentCertificate).isEmpty())
        assertTrue(response.credentialSharingFacets(http).isEmpty())
    }

    @Test fun aHashPrefixCollisionCannotInjectAnUnrequestedEquivalenceClass() {
        val unknown = AffiliationFacet.parse("https://unrequested.invalid")
        val response = AffiliationResponse.decode(Proto.field(1, group(web, app)) + Proto.field(1, group(other, unknown)), listOf(web))
        assertEquals(1, response.sharing.size)
        assertTrue(response.credentialSharingFacets(other).isEmpty())
        assertEquals(setOf(web, app), response.credentialSharingFacets(web))
    }

    @Test fun partiallyOverlappingClassesFailClosedForSharingAndGrouping() {
        for (field in 1..2) {
            val raw = Proto.field(field, group(web, app)) + Proto.field(field, group(app, other))
            assertThrows(ProtocolException::class.java) { AffiliationResponse.decode(raw, listOf(web)) }
        }
        val duplicate = Proto.field(1, group(web, app)) + Proto.field(1, group(app, web))
        assertEquals(1, AffiliationResponse.decode(duplicate, listOf(web)).sharing.size)
    }

    @Test fun absentFacetsGetOnlySynthesizedSingletonsAndFutureSchemesGrantNoAuthority() {
        val unknown = Proto.field(1, Proto.field(1, "future://new-platform"))
        val response = AffiliationResponse.decode(Proto.field(1, unknown), listOf(web, app))
        assertEquals(2, response.sharing.size)
        assertTrue(response.sharing.all { it.synthesized })
        assertEquals(setOf(web), response.credentialSharingFacets(web))
        assertEquals(setOf(app), response.credentialSharingFacets(app))
    }

    @Test fun brandingAndChangePasswordUrlsStayMetadataAndUnsafeLinksAreOmitted() {
        val brand = Proto.field(1, "Fixture") + Proto.field(2, "https://static.example.invalid/icon.png")
        val info = Proto.field(2, brand) + Proto.field(3, "example.invalid") +
            Proto.field(4, Proto.field(1, "https://example.invalid/change"))
        val response = AffiliationResponse.decode(Proto.field(2, Proto.field(1, facet(web, info)) + Proto.field(2, brand)), listOf(web))
        val result = response.groupedFacets(web).single()
        assertEquals("Fixture", result.branding!!.name)
        assertEquals("https://static.example.invalid/icon.png", result.branding!!.iconUrl)
        assertEquals("example.invalid", result.mainDomain)
        assertEquals("https://example.invalid/change", result.changePasswordUrl)
        assertEquals("Fixture", response.groups.single().branding!!.name)
        assertEquals(setOf(web), response.credentialSharingFacets(web))
        val unsafe = Proto.field(2, Proto.field(2, "http://unsafe.invalid/icon")) +
            Proto.field(4, Proto.field(1, "javascript:bad"))
        val filtered = AffiliationResponse.decode(Proto.field(1, Proto.field(1, facet(web, unsafe))), listOf(web))
            .sharing.single().facets.single()
        assertNull(filtered.branding!!.iconUrl); assertNull(filtered.changePasswordUrl)
        val delegated = Proto.field(2, Proto.field(2, "https://@other.invalid/icon"))
        assertNull(AffiliationResponse.decode(Proto.field(1, Proto.field(1, facet(web, delegated))), listOf(web))
            .sharing.single().facets.single().branding!!.iconUrl)
    }

    @Test fun webFacetsCanonicalizePortsAndHostsButRejectPathsOrDelegatedAuthorities() {
        assertEquals(web, AffiliationFacet.parse("HTTPS://EXAMPLE.invalid:443/"))
        assertNotEquals(web, AffiliationFacet.parse("https://example.invalid:8443"))
        assertNotEquals(web, AffiliationFacet.parse("http://example.invalid"))
        for (value in listOf("https://example.invalid/path", "https://example.invalid/?", "https://example.invalid/#",
            "https://user@example.invalid", "https://@example.invalid", "https://example.invalid\\@other.invalid", "https://example.invalid/\n")) {
            assertThrows(ProtocolException::class.java) { AffiliationFacet.parse(value) }
        }
    }

    @Test fun androidFacetsPreservePackageCaseAndNormalizeHashPaddingWithoutAcceptingExtraAuthority() {
        assertEquals(app, AffiliationFacet.parse(app.id + "/"))
        assertEquals(app, AffiliationFacet.parse(app.id.replace("=", "%3D")))
        assertNotEquals(app, AffiliationFacet.parse(app.id.replace("com.Example_app", "com.example_app")))
        for (value in listOf(app.id + "/path", app.id + ":443", app.id + "?x", app.id + "#x",
            "android://a@com.example", "android://bad+hash@com.example", "android://abcd:password@com.example")) {
            assertThrows(ProtocolException::class.java) { AffiliationFacet.parse(value) }
        }
    }

    @Test fun hashLookupContainsOnlyTheCanonicalSixteenBitPrefixAndReferenceMask() {
        val public = AffiliationFacet.parse("https://google.com")
        val encoded = AffiliationLookup.request(listOf(public))
        val request = Proto.decode(encoded)
        assertEquals(16L, request.integer(2))
        assertEquals(361413870096482304L, request.integer(3))
        assertFalse(String(encoded, Charsets.ISO_8859_1).contains("google.com"))
        val mask = Proto.decode(request.one(1))
        assertEquals("en", mask.string(2)); assertEquals(40L, mask.integer(4))
        for (field in listOf(1, 5, 6, 7, 8, 9)) assertEquals(1L, mask.integer(field))
        assertArrayEquals(encoded, AffiliationLookup.request(listOf(public, public)))
    }

    @Test fun boundedCacheReopensWithoutExtendingAuthorityAndRefusesClockRollback() {
        val raw = Proto.field(1, group(web, app))
        val snapshot = AffiliationSnapshot.create(raw, listOf(web), 1000L, 2000L)
        val restored = AffiliationSnapshot.decode(snapshot.encode())
        raw.fill(0)
        assertEquals(setOf(web, app), restored.at(1000L)!!.credentialSharingFacets(web))
        assertEquals(setOf(web, app), snapshot.at(1999L)!!.credentialSharingFacets(web))
        assertNull(restored.at(999L)); assertNull(restored.at(2000L))
        assertThrows(ProtocolException::class.java) { AffiliationSnapshot.create(byteArrayOf(), listOf(web), 0, AffiliationSnapshot.MAX_AGE_MILLIS + 1) }
        assertThrows(ProtocolException::class.java) { AffiliationSnapshot.create(byteArrayOf(), listOf(web), -1, 1000) }
        assertThrows(ProtocolException::class.java) { AffiliationSnapshot.decode(Proto.decode(snapshot.encode()).replaceInteger(1, 2).encode()) }
    }

    @Test fun malformedOrOversizedMetadataCannotBecomeAnAuthoritativeSnapshot() {
        assertThrows(ProtocolException::class.java) { AffiliationResponse.decode(ByteArray(AffiliationResponse.MAX_RESPONSE_BYTES + 1), listOf(web)) }
        assertThrows(ProtocolException::class.java) { AffiliationResponse.decode(byteArrayOf(), emptyList()) }
        assertThrows(ProtocolException::class.java) { AffiliationResponse.decode(Proto.field(1, group(web, web)), listOf(web)) }
        assertThrows(ProtocolException::class.java) { AffiliationResponse.decode(Proto.field(3, "other.invalid/path"), listOf(web)) }
        assertThrows(ProtocolException::class.java) { AffiliationLookup.request(emptyList()) }
        val longList = List(AffiliationResponse.MAX_REQUESTED_FACETS + 1) { web }
        assertThrows(ProtocolException::class.java) { AffiliationLookup.request(longList) }
    }

    @Test fun returnedMetadataCannotBeMutatedToBroadenCredentialSharing() {
        val response = AffiliationResponse.decode(Proto.field(1, group(web)), listOf(web))
        assertThrows(UnsupportedOperationException::class.java) { (response.sharing as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (response.sharing.single().facets as MutableList).clear() }
        assertEquals("AffiliationResponse[redacted]", response.toString())
        assertEquals("AffiliationFacet[redacted]", web.toString())
    }

    @Test fun chromeAffiliatedRowsHaveTheirOwnWrapperAndRetainOpaqueLocalData() {
        val data = PasswordData.create(web.id + "/", web.id + "/login", "fixture-user", "fixture-password", 1234)
        val chrome = Proto.field(1, data.encode()) + Proto.field(3, Proto.field(1, byteArrayOf(1, 2, 3)))
        val row = Proto.decode(ChromeAffiliatedPassword.exact(chrome, web)!!)
        assertArrayEquals(chrome, row.one(1))
        assertEquals(1L, row.integer(3)); assertEquals(1L, row.integer(4))
        assertNull(ChromeAffiliatedPassword.exact(chrome, other))
        val http = AffiliationFacet.parse("http://example.invalid")
        val insecure = Proto.field(1, PasswordData.create(http.id + "/", http.id + "/", "fixture", "value", 1234).encode())
        val insecureRow = Proto.decode(ChromeAffiliatedPassword.exact(insecure, http)!!)
        assertEquals(0L, insecureRow.integer(3)); assertEquals(1L, insecureRow.integer(4))
    }

    @Test fun groupingOnlyRowsCarryBrandingButCannotAuthorizeAChromeFill() {
        val branding = Proto.field(2, Proto.field(1, "Fixture app") + Proto.field(2, "https://static.example.invalid/icon"))
        val raw = Proto.field(1, group(web)) + Proto.field(2, Proto.field(1, facet(web)) + Proto.field(1, facet(app, branding)))
        val response = AffiliationResponse.decode(raw, listOf(web, app))
        val chrome = Proto.field(1, PasswordData.create(app.id + "/", "", "fixture", "value", 1234).encode()) +
            Proto.field(3, byteArrayOf(1, 2, 3))
        val row = Proto.decode(ChromeAffiliatedPassword.affiliated(chrome, web, response)!!)
        assertArrayEquals(chrome, row.one(1))
        assertEquals(0L, row.integer(3)); assertEquals(1L, row.integer(4))
        assertEquals("Fixture app", Proto.decode(row.one(2)).string(1))
        val ui = Proto.decode(ChromeAffiliatedPassword.uiInfo(chrome, response))
        assertArrayEquals(row.one(2), ui.one(2)); assertNull(ui.integer(3)); assertNull(ui.integer(4))
    }

    @Test fun pslExtensionsSeparateTenantsAndNeverGrantSharingAuthority() {
        val first = AffiliationFacet.parse("https://login.first.slack.com")
        val sameTenant = AffiliationFacet.parse("https://www.first.slack.com")
        val otherTenant = AffiliationFacet.parse("https://second.slack.com")
        val response = AffiliationResponse.decode(Proto.field(3, "slack.com"), listOf(first, sameTenant, otherTenant))
        assertEquals("first.slack.com", AffiliationDomains.mainDomain(first, response.pslExtensions))
        assertTrue(response.isGrouped(first, sameTenant)); assertFalse(response.isGrouped(first, otherTenant))
        assertEquals(setOf(first), response.credentialSharingFacets(first))
        assertEquals("example.co.uk", AffiliationDomains.mainDomain(AffiliationFacet.parse("https://login.example.co.uk")))
        assertEquals("127.0.0.1", AffiliationDomains.mainDomain(AffiliationFacet.parse("https://127.0.0.1")))
        assertNull(AffiliationDomains.mainDomain(app))
    }

    @Test fun androidCertificateFacetsRequireCanonicalSha256Digests() {
        for (value in listOf("android://YWJjZA==@com.example", "android://" + "A".repeat(44) + "@com.example",
            app.id.replace("Hh8=", "Hh9="))) {
            assertThrows(ProtocolException::class.java) { AffiliationFacet.parse(value) }
        }
    }

    @Test fun extendedSuffixesAreAppliedOneLevelAtATimeAndCannotSkipMissingAncestors() {
        val facet = AffiliationFacet.parse("https://login.team.tenant.example.com")
        assertEquals("example.com", AffiliationDomains.mainDomain(facet, setOf("tenant.example.com")))
        assertEquals("tenant.example.com", AffiliationDomains.mainDomain(facet, setOf("example.com", "team.tenant.example.com")))
        assertEquals("team.tenant.example.com", AffiliationDomains.mainDomain(facet, setOf("example.com", "tenant.example.com")))
        assertEquals("login.team.tenant.example.com", AffiliationDomains.mainDomain(facet,
            setOf("example.com", "tenant.example.com", "team.tenant.example.com")))
    }

    @Test fun relatedGroupsMergeTransitivelyWhileSharingAuthorityAndTenantBoundariesStaySeparate() {
        val first = AffiliationFacet.parse("https://a.example.com")
        val second = AffiliationFacet.parse("https://b.example.com")
        val third = AffiliationFacet.parse("https://x.example.org")
        val fourth = AffiliationFacet.parse("https://y.example.org")
        val secondApp = AffiliationFacet.parse(app.id.replace("com.example", "com.other"))
        fun brandedGroup(name: String, icon: String?, vararg entries: AffiliationFacet): ByteArray =
            group(*entries) + Proto.field(2, Proto.field(1, name) + (icon?.let { Proto.field(2, it) } ?: byteArrayOf()))
        val requested = listOf(first, app, second, third, fourth, secondApp)
        val raw = Proto.field(2, brandedGroup("First", "https://brand.example/first", first, app)) +
            Proto.field(2, brandedGroup("No icon", null, second, third)) +
            Proto.field(2, brandedGroup("Last", "https://brand.example/last", fourth, secondApp))
        val response = AffiliationResponse.decode(raw, requested)
        assertEquals(1, response.groups.size)
        assertEquals(requested.toSet(), response.groupedFacets(app).map { it.facet }.toSet())
        assertEquals("Last", response.groups.single().branding!!.name)
        assertTrue(response.isGrouped(app, secondApp))
        assertEquals(setOf(first), response.credentialSharingFacets(first))
        assertEquals(setOf(app), response.credentialSharingFacets(app))
        val isolated = AffiliationResponse.decode(raw + Proto.field(3, "example.com"), requested)
        assertEquals(2, isolated.groups.size)
        assertFalse(isolated.isGrouped(first, secondApp))
    }

    @Test fun serverMainDomainsOverrideComputedDisplayGroupingWithoutGrantingCredentialRelease() {
        val first = AffiliationFacet.parse("https://login.example.com")
        val second = AffiliationFacet.parse("https://accounts.example.org")
        val raw = listOf(first, second).fold(byteArrayOf()) { out, value -> out + Proto.field(2,
            Proto.field(1, facet(value) + Proto.field(3, "declared.example"))) }
        val response = AffiliationResponse.decode(raw, listOf(first, second))
        assertEquals(1, response.groups.size); assertTrue(response.isGrouped(first, second))
        assertEquals(setOf(first), response.credentialSharingFacets(first))
    }
}
