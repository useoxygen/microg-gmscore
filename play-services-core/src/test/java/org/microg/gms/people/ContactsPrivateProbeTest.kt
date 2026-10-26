/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.accounts.Account
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.AbstractThreadedSyncAdapter
import android.content.SyncResult
import android.content.pm.ProviderInfo
import android.os.Bundle
import android.database.Cursor
import android.net.Uri
import android.content.ContentResolver
import android.content.ContextWrapper
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.auth.AuthManager
import org.microg.gms.auth.AuthResponse
import org.microg.gms.common.HttpFormClient
import org.robolectric.shadows.ShadowLog
import java.net.HttpURLConnection
import java.net.URL
import org.microg.gms.people.sync.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ContactsPrivateProbeTest {
    @Test fun privateChecksCannotEnableOrReconfigureContactsSync() {
        val context = RuntimeEnvironment.getApplication()
        val account = Account("synthetic@invalid", "com.google")
        val prefs = context.getSharedPreferences("contacts-sync", 0)
        var providerTouches = 0
        fun forbidden(): Nothing { providerTouches++; error("Probe must not access ContactsProvider") }
        ShadowContentResolver.registerProviderInternal("com.android.contacts", object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor = forbidden()
            override fun getType(uri: Uri): String = forbidden()
            override fun insert(uri: Uri, values: ContentValues?): Uri = forbidden()
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = forbidden()
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = forbidden()
        })
        for (mode in listOf(SyncMode.OFF, SyncMode.TWO_WAY)) {
            prefs.edit().clear().putString("com.google:synthetic@invalid:mode", mode.name).putLong("sentinel", 7).commit()
            val before = prefs.all.toMap()
            ContentResolver.setIsSyncable(account, "com.android.contacts", if (mode == SyncMode.OFF) 0 else 1)
            ContentResolver.setSyncAutomatically(account, "com.android.contacts", mode != SyncMode.OFF)
            val periodic = ContentResolver.getPeriodicSyncs(account, "com.android.contacts").toList()
            val requests = ShadowContentResolver.getStatus(account, "com.android.contacts").syncRequests
            var authCalls = 0; var getCalls = 0
            // Do not create the Compose screen. Exercise the real check entry
            // point with only authorization and GET capabilities substituted.
            val activity = Robolectric.buildActivity(ContactsSyncActivity::class.java).get()
            activity.privateProbeAuthorization = { received, _ ->
                assertEquals(account, received); authCalls++
                ProbeGrant(if (mode == SyncMode.OFF) "synthetic-token" else null, null)
            }
            activity.privateProbeGet = { _, url -> getCalls++; assertEquals(PrivatePeopleProbe.url(ContactSyncService.privatePeopleContext), url); HttpReply(200, "{}") }
            activity.checkPrivateAccess(account)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(6, authCalls); assertEquals(if (mode == SyncMode.OFF) 6 else 0, getCalls)
            assertEquals(requests, ShadowContentResolver.getStatus(account, "com.android.contacts").syncRequests)
            assertEquals(0, providerTouches)
            assertEquals(before, prefs.all)
            assertEquals(if (mode == SyncMode.OFF) 0 else 1, ContentResolver.getIsSyncable(account, "com.android.contacts"))
            assertEquals(mode != SyncMode.OFF, ContentResolver.getSyncAutomatically(account, "com.android.contacts"))
            assertEquals(periodic, ContentResolver.getPeriodicSyncs(account, "com.android.contacts"))
        }
    }
    @Test fun productionScopesAndAdvertisedContextUseThePrivateBackend() {
        assertEquals(PrivatePeopleScope.READONLY.scope, ContactSyncService.scope(SyncMode.DOWNLOAD))
        assertEquals(PrivatePeopleContext.fromGmsVersionCode(org.microg.gms.common.Constants.GMS_VERSION_CODE).version,
            ContactSyncService.privatePeopleContext.version)
        assertEquals(PrivatePeopleScope.READWRITE.scope, ContactSyncService.scope(SyncMode.TWO_WAY))
        assertThrows(IllegalArgumentException::class.java) { ContactSyncService.scope(SyncMode.OFF) }
    }
    @Test fun rejectedContextStopsBothModesBeforeAuthAndProviderWithoutRetryFailures() {
        val context = RuntimeEnvironment.getApplication()
        val account = Account("synthetic@invalid", "com.google")
        val prefs = ContactSyncPreferences(context, account)
        val rawPrefs = context.getSharedPreferences("contacts-sync", 0)
        var touches = 0
        fun forbidden(): Nothing { touches++; error("Guard must precede provider access") }
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor = forbidden()
            override fun getType(uri: Uri): String = forbidden()
            override fun insert(uri: Uri, values: ContentValues?): Uri = forbidden()
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = forbidden()
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = forbidden()
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = "com.android.contacts" })
        ShadowContentResolver.registerProviderInternal("com.android.contacts", provider)
        val service = Robolectric.buildService(ContactSyncService::class.java).create().get()
        val getter = ContactSyncService::class.java.getDeclaredMethod("getAdapter").apply { isAccessible = true }
        val adapter = getter.invoke(service) as AbstractThreadedSyncAdapter
        context.contentResolver.acquireContentProviderClient("com.android.contacts")!!.use { client ->
            rawPrefs.edit().putString("com.google:synthetic@invalid:mode", SyncMode.TWO_WAY.name).commit()
            prefs.blockedClientContext = ContactSyncService.privatePeopleContext.binding
            repeat(4) { attempt ->
                rawPrefs.edit().putString("com.google:synthetic@invalid:mode", if (attempt % 2 == 0) SyncMode.DOWNLOAD.name else SyncMode.TWO_WAY.name).commit()
                val result = SyncResult()
                adapter.onPerformSync(account, Bundle(), "com.android.contacts", client, result)
                assertEquals("needs_update", prefs.status)
                assertEquals(0L, result.stats.numIoExceptions); assertEquals(0L, result.stats.numAuthExceptions)
            }
            assertEquals(0, touches)
            prefs.refresh(); assertNull(prefs.blockedClientContext)
        }
    }
    @Test fun accessCheckFactoryCannotMutateEvenWithAToken() {
        val api = ContactSyncService.privateContactsApi("synthetic-token")
        assertThrows(UploadDisabled::class.java) { api.create(org.json.JSONObject()) }
        assertThrows(UploadDisabled::class.java) { api.writePhoto("people/fsa_17", byteArrayOf(1)) }
    }
    @Test fun privateOwnershipFloorsAreDurableAndIndependentOfLegacyFloors() {
        val context = RuntimeEnvironment.getApplication()
        val account = Account("synthetic-floors@invalid", "com.google")
        val prefs = ContactSyncPreferences(context, account)
        // Customers follow Google: the account's own new rows are adopted (floor 0).
        ContactMigrationPrivacy(context).developerControlsEnabled = false
        prefs.initializePrivateFloors(23, 42)
        assertEquals(0L, prefs.privateAdoptionFloor); assertEquals(0L, prefs.privateGroupAdoptionFloor)
        // The developer fixture controls keep the first recorded floors, independent of legacy ones.
        ContactMigrationPrivacy(context).developerControlsEnabled = true
        prefs.initializePrivateFloors(100, 200)
        val restarted = ContactSyncPreferences(context, account)
        assertEquals(23L, restarted.privateAdoptionFloor); assertEquals(42L, restarted.privateGroupAdoptionFloor)
        assertNull(restarted.groupAdoptionFloor)
        ContactMigrationPrivacy(context).developerControlsEnabled = false
    }
    @Test fun quietAuthParserNeverLogsUnknownFieldsOrMalformedValues() {
        ShadowLog.clear()
        val connection = object : HttpURLConnection(URL("https://unused.invalid")) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getHeaderFields(): Map<String, List<String>> = emptyMap()
            override fun getResponseCode() = 200
        }
        val parser = HttpFormClient::class.java.getDeclaredMethod("parseResponse", Class::class.java,
            HttpURLConnection::class.java, String::class.java, Boolean::class.javaPrimitiveType)
        parser.isAccessible = true
        val response = parser.invoke(null, AuthResponse::class.java, connection,
            "Auth=secret-token\nUnknown=secret-account\nExpiry=secret-malformed\nsecret-bare", true) as AuthResponse
        assertEquals("secret-token", response.auth)
        assertFalse(ShadowLog.getLogs().any { it.msg.contains("secret") || it.throwable?.toString()?.contains("secret") == true })
    }
    @Test fun alternateIdentityIsSelfPrivateProbeOnlyAndDoesNotPersistTokens() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getPackageName() = "com.google.android.gms"
        }
        for (pkg in listOf("com.google.android.gms", "untrusted.app")) {
            for (service in listOf("oauth2:" + PrivatePeopleScope.READONLY.scope, "oauth2:https://www.googleapis.com/auth/contacts")) {
                val manager = object : AuthManager(context, "synthetic@invalid", pkg, service) {
                    override fun setAuthToken(service: String?, token: String?) { error("Probe must not store tokens") }
                    override fun setUserData(key: String?, value: String?) { error("Probe must not store account data") }
                }
                manager.configureContactsProbe(true, true)
                val allowed = pkg == "com.google.android.gms" && PrivatePeopleProbe.isPrivateService(service)
                assertEquals(allowed, manager.isContactsProbe)
                assertEquals(allowed, manager.isGmsApp)
                assertEquals(allowed, manager.forceRefreshToken)
                if (allowed) manager.storeResponse(AuthResponse().apply { auth = "synthetic-token"; expiry = 123; accountId = "synthetic" })
            }
        }
    }
}
