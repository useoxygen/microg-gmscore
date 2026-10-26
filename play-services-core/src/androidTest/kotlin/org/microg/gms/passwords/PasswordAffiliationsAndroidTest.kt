/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Synthetic cached-evidence boundary checks only; no claim of live DAL or Google server acceptance. */
@RunWith(AndroidJUnit4::class)
class PasswordAffiliationsAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixtureApp(): AffiliationFacet {
        val fixture = InstrumentationRegistry.getInstrumentation().context
        val signer = fixture.packageManager.getPackageInfo(fixture.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo!!.apkContentsSigners.single()
        val hash = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()))
        return AffiliationFacet.parse("android://$hash@${fixture.packageName}")
    }
    private fun seed(vault: DevicePasswordVault, site: AffiliationFacet, app: AffiliationFacet, timestamp: Long) {
        vault.store.locked { session ->
            val state = session.load()
            DirectAffiliationProofs(object : AffiliationCacheStore {
                override fun read() = state.directAffiliationProofs
                override fun write(value: ByteArray) { state.directAffiliationProofs = value; session.save(state) }
            }) { timestamp }.allows(site, app) { true }
        }
    }

    @Test fun encryptedDirectProofReopensForOnlyTheExactInstalledCertificateAndVault() {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val identity = "password-affiliation-fixture/" + UUID.randomUUID()
        val otherIdentity = identity + "/other"
        val vault = DevicePasswordVault(context, identity)
        val other = DevicePasswordVault(context, otherIdentity)
        val site = AffiliationFacet.parse("https://" + UUID.randomUUID() + ".invalid")
        val app = fixtureApp()
        val data = PasswordData.create(site.id + "/", site.id + "/login", "synthetic", "fixture-value", 1234)
        try {
            seed(vault, site, app, System.currentTimeMillis())
            val reopened = DevicePasswordVault(context, identity)
            assertTrue(PasswordAffiliationMatcher(context, reopened).canFill(data, app.id + "/"))
            val wrongCert = AffiliationFacet.parse("android://AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=@" + app.id.substringAfter('@'))
            assertFalse(PasswordAffiliationMatcher(context, reopened).canFill(data, wrongCert.id + "/"))
            assertFalse(PasswordAffiliationMatcher(context, other).canFill(data, app.id + "/"))
            for (file in context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("password-vaults.db") }) {
                assertFalse("Facet plaintext reached the vault database", file.readBytes().toString(Charsets.ISO_8859_1).contains(site.id))
            }
        } finally { vault.store.clear(); other.store.clear() }
    }

    @Test fun expiredProofCannotResurrectSharingAndGroupingMetadataCannotReplaceProof() {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val vault = DevicePasswordVault(context, "password-affiliation-fixture/" + UUID.randomUUID())
        val site = AffiliationFacet.parse("https://example.invalid"); val app = fixtureApp()
        val data = PasswordData.create(site.id + "/", site.id + "/", "synthetic", "fixture-value", 1234)
        try {
            seed(vault, site, app, System.currentTimeMillis() - 2 * DirectAffiliationProofs.POSITIVE_AGE_MILLIS)
            assertFalse(PasswordAffiliationMatcher(context, vault).canFill(data, app.id + "/"))
            vault.store.locked { session ->
                val state = session.load()
                val group = Proto.field(1, Proto.field(1, site.id)) + Proto.field(1, Proto.field(1, app.id))
                state.affiliationCache = AffiliationSnapshot.create(Proto.field(2, group), listOf(site, app),
                    System.currentTimeMillis(), System.currentTimeMillis() + 60_000).encode()
                session.save(state)
            }
            assertFalse(PasswordAffiliationMatcher(context, vault).canFill(data, app.id + "/"))
        } finally { vault.store.clear() }
    }

    @Test fun anAffiliationGrantCannotOverrideBlockedEmptyOrInconsistentStoredPasswords() {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val vault = DevicePasswordVault(context, "password-affiliation-fixture/" + UUID.randomUUID())
        val site = AffiliationFacet.parse("https://example.invalid"); val app = fixtureApp()
        try {
            seed(vault, site, app, System.currentTimeMillis())
            val matcher = PasswordAffiliationMatcher(context, vault)
            assertFalse(matcher.canFill(PasswordData.blockedSite(site.id, 1234), app.id + "/"))
            assertFalse(matcher.canFill(PasswordData.create(site.id + "/", site.id + "/", "fixture", "", 1234), app.id + "/"))
            assertFalse(matcher.canFill(PasswordData.create(site.id + "/", "https://other.invalid/", "fixture", "value", 1234), app.id + "/"))
        } finally { vault.store.clear() }
    }
}
