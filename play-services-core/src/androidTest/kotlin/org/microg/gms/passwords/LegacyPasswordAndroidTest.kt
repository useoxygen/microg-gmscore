/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.net.Uri
import android.os.Build
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.auth.api.credentials.Credential
import com.google.android.gms.auth.api.credentials.internal.DeleteRequest
import com.google.android.gms.auth.api.credentials.internal.SaveRequest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.Base64
import java.security.MessageDigest
import android.content.pm.PackageManager

/** Local synthetic tickets only: never launches an activity, selects an account or sends an RPC. */
@RunWith(AndroidJUnit4::class)
class LegacyPasswordAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun legacyCallerFindsItsPaddedFacetWithoutAcceptingAnotherCertificate() {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val identity = "legacy-canonical-facet-fixture/" + UUID.randomUUID()
        val vault = DevicePasswordVault(context, identity)
        val certificate = MessageDigest.getInstance("SHA-256").digest(context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners.single().toByteArray())
        val request = LegacyPasswordRequest(context.packageName, context.applicationInfo.uid, certificate,
            LegacyPasswordRequests.GET, null, null, null)
        val paddedRealm = "android://${Base64.getUrlEncoder().encodeToString(certificate)}@${context.packageName}/"
        val data = PasswordData.create(paddedRealm, paddedRealm, "fixture-user", "fixture-secret", 1234L)
        try {
            vault.upsert(Proto.field(1, data.encode()))
            val matcher = PasswordAffiliationMatcher(context, vault)
            val listed = vault.list().singleOrNull { matcher.canFill(it.data, request.realm) }
            assertNotNull("Equivalent certificate encodings must match", listed)
            assertTrue(matcher.canFill(listed!!.data, request.realm))
            val wrongCertificate = certificate.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            val wrong = LegacyPasswordRequest(context.packageName, context.applicationInfo.uid, wrongCertificate,
                LegacyPasswordRequests.GET, null, null, null)
            assertFalse(matcher.canFill(listed.data, wrong.realm))
        } finally { vault.store.clear() }
    }

    @Test fun officialSaveAndDeleteParcelFieldOneRoundTripsWithoutDiagnosticValues() {
        val credential = Credential.Builder("synthetic-user").setPassword("synthetic-secret").setName("Synthetic").build()
        val parcel = Parcel.obtain()
        try {
            SaveRequest(credential).writeToParcel(parcel, 0); parcel.setDataPosition(0)
            val saved = SaveRequest.CREATOR.createFromParcel(parcel).credential
            assertEquals(credential, saved)
            parcel.setDataSize(0); parcel.setDataPosition(0)
            DeleteRequest(credential).writeToParcel(parcel, 0); parcel.setDataPosition(0)
            assertEquals(credential, DeleteRequest.CREATOR.createFromParcel(parcel).credential)
            for (description in listOf(credential.toString(), SaveRequest(credential).toString(), DeleteRequest(credential).toString())) {
                assertFalse(description.contains("synthetic-secret")); assertFalse(description.contains("synthetic-user"))
            }
        } finally { parcel.recycle() }
    }

    @Test fun encryptedLegacyResolutionIsCallerBoundOneUseAndCannotBroadenAppFacet() {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val identity = "legacy-password-fixture/" + UUID.randomUUID()
        val store = AndroidVaultStore(context, identity)
        val requests = LegacyPasswordRequests(context, identity)
        val marker = "synthetic-only-" + UUID.randomUUID()
        fun issue(): Uri {
            requests.issue(context.packageName, context.applicationInfo.uid, LegacyPasswordRequests.SAVE,
                Credential.Builder("fixture-user").setPassword(marker).build())
            return store.locked { Uri.parse("microg-legacy-password:/request/" + it.load().chromeLocalData.keys.single()) }
        }
        try {
            val first = issue()
            for (file in context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("password-vaults.db") }) {
                assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(marker))
            }
            assertThrows(SecurityException::class.java) { requests.consume(first, "other.synthetic.invalid") }
            assertThrows(SecurityException::class.java) { requests.consume(first, context.packageName) }
            val second = issue()
            assertThrows(SecurityException::class.java) { requests.consume(second.buildUpon().appendQueryParameter("realm", "https://other.invalid/").build(), context.packageName) }
            val accepted = requests.consume(second, context.packageName)
            assertEquals(marker, accepted.password)
            assertTrue(accepted.realm.startsWith("android://"))
            assertTrue(accepted.realm.endsWith("@${context.packageName}/"))
            assertThrows(SecurityException::class.java) { requests.consume(second, context.packageName) }
            assertFalse(accepted.toString().contains(marker))
            val third = issue()
            store.locked { session ->
                val state = session.load(); val key = state.chromeLocalData.keys.single()
                state.chromeLocalData[key] = Proto.decode(state.chromeLocalData.getValue(key)).replace(3, ByteArray(32)).encode()
                session.save(state)
            }
            assertThrows(SecurityException::class.java) { requests.consume(third, context.packageName) }
        } finally { store.clear() }
    }
}
