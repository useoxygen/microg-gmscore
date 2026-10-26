/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class DirectAffiliationProofsTest {
    private val site = AffiliationFacet.parse("https://example.invalid")
    private val app = AffiliationFacet.parse("android://AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8@com.example.fixture")
    private class Store : AffiliationCacheStore {
        var value: ByteArray? = null
        override fun read() = value?.copyOf()
        override fun write(value: ByteArray) { this.value = value.copyOf() }
    }

    @Test fun accountBoundProofsReopenWithAnExactOriginCertificateAndHardExpiry() {
        val store = Store()
        var now = 1000L
        var checks = 0
        assertTrue(DirectAffiliationProofs(store) { now }.allows(site, app) { checks++; true })
        assertTrue(DirectAffiliationProofs(store) { now + 1 }.allows(site, app) { error("Fresh proof must be reused") })
        assertFalse(DirectAffiliationProofs(Store()) { now }.allows(site, app) { false })
        val anotherCert = AffiliationFacet.parse(app.id.replace("AAEC", "BAEC"))
        assertFalse(DirectAffiliationProofs(store) { now }.allows(site, anotherCert) { checks++; false })
        now += DirectAffiliationProofs.POSITIVE_AGE_MILLIS
        assertFalse(DirectAffiliationProofs(store) { now }.allows(site, app) { checks++; false })
        assertEquals(3, checks)
    }

    @Test fun aCrashBeforeVerificationLeavesADenialInsteadOfAnAuthorityGrant() {
        val store = Store()
        var now = 1000L
        assertThrows(IllegalStateException::class.java) {
            DirectAffiliationProofs(store) { now }.allows(site, app) { error("fixture process death") }
        }
        assertFalse(DirectAffiliationProofs(store) { now }.allows(site, app) { error("Retry fence must survive") })
        now += DirectAffiliationProofs.NEGATIVE_AGE_MILLIS
        assertTrue(DirectAffiliationProofs(store) { now }.allows(site, app) { true })
    }

    @Test fun rollbackAndInsecureOrDifferentFacetsCannotReuseADirectGrant() {
        val store = Store()
        DirectAffiliationProofs(store) { 1000L }.allows(site, app) { true }
        assertFalse(DirectAffiliationProofs(store) { 999L }.allows(site, app) { false })
        assertFalse(DirectAffiliationProofs(store) { 1001L }.allows(AffiliationFacet.parse("http://example.invalid"), app) { error("HTTP cannot delegate") })
        assertFalse(DirectAffiliationProofs(store) { 1001L }.allows(AffiliationFacet.parse("https://example.invalid:8443"), app) { false })
    }

    @Test fun futureVersionsDuplicateProofsAndForgedLongLifetimesFailClosed() {
        val store = Store()
        DirectAffiliationProofs(store) { 1000L }.allows(site, app) { true }
        val valid = Proto.decode(store.value!!)
        store.value = valid.replaceInteger(1, 2).encode()
        assertThrows(ProtocolException::class.java) { DirectAffiliationProofs(store) { 1001L }.allows(site, app) { true } }
        store.value = valid.encode() + Proto.field(2, valid.one(2))
        assertThrows(ProtocolException::class.java) { DirectAffiliationProofs(store) { 1001L }.allows(site, app) { true } }
        val forged = Proto.decode(valid.one(2)).replaceInteger(5, 1001L + DirectAffiliationProofs.POSITIVE_AGE_MILLIS).encode()
        store.value = Proto.integer(1, 1) + Proto.field(2, forged)
        assertThrows(ProtocolException::class.java) { DirectAffiliationProofs(store) { 1001L }.allows(site, app) { true } }
    }
}
