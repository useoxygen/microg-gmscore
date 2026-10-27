/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.aang

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.safeparcel.SafeParcelReader as Reader
import com.google.android.gms.common.internal.safeparcel.SafeParcelWriter as Writer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.auth.AuthManager
import org.microg.gms.auth.AuthResponse
import org.microg.gms.auth.aang.AangParcelables.*
import org.microg.gms.auth.capabilities.CapabilityState
import org.microg.gms.auth.capabilities.CapabilityStore
import org.microg.gms.auth.capabilities.UserDataKeys
import org.microg.gms.auth.capabilities.proto.Capabilities
import org.microg.gms.auth.capabilities.proto.Capability
import org.microg.gms.auth.capabilities.proto.CapabilityStatus
import org.microg.gms.auth.capabilities.proto.CapabilityType
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccountManager

// Robolectric's base AccountManager shadow has no account-visibility implementation.
@Implements(AccountManager::class)
class AangShadowAccountManager : ShadowAccountManager() {
    private val visibility = mutableMapOf<Pair<Account, String>, Int>()

    @Implementation
    fun setAccountVisibility(account: Account, packageName: String, value: Int): Boolean {
        visibility[account to packageName] = value
        return true
    }

    @Implementation
    fun getAccountVisibility(account: Account, packageName: String): Int =
        visibility[account to packageName] ?: AccountManager.VISIBILITY_UNDEFINED
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], shadows = [AangShadowAccountManager::class])
class GoogleAuthAangServiceTest {
    private lateinit var context: Context
    private lateinit var am: AccountManager
    private val caller = "com.google.android.apps.restore"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        am = AccountManager.get(context)
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = caller
            applicationInfo = ApplicationInfo().apply { packageName = caller; uid = Binder.getCallingUid() }
            signatures = arrayOf(Signature(ByteArray(32) { (it + 1).toByte() }))
        })
    }

    private fun account(name: String = "owner@example.test", id: String = "gaia-1"): Account {
        val account = Account(name, "com.google")
        assertTrue(am.addAccountExplicitly(account, "master-token-fixture", null))
        am.setUserData(account, UserDataKeys.GOOGLE_USER_ID, id)
        am.setAccountVisibility(account, caller, AccountManager.VISIBILITY_VISIBLE)
        return account
    }

    private fun Parcel.safe(write: Parcel.() -> Unit) {
        val start = Writer.writeObjectHeader(this)
        write()
        Writer.finishObjectHeader(this, start)
    }

    private fun Parcel.googleAccount(account: Account, id: String = "gaia-1") = safe {
        Writer.write(this, 1, id, false)
        Writer.write(this, 2, account.type, false)
        Writer.write(this, 3, account.name, false)
    }

    private fun Parcel.accountField(account: Account, id: String = "gaia-1") {
        // A SafeParcel nested Parcelable has no nullable marker inside its field.
        writeInt(0xffff0001.toInt()); val sizeAt = dataPosition(); writeInt(0)
        val start = dataPosition(); googleAccount(account, id); val end = dataPosition()
        setDataPosition(sizeAt); writeInt(end - start); setDataPosition(end)
    }

    private fun call(code: Int, backend: AangBackend = AangAccountBackend(context, caller),
                     uid: Int = Binder.getCallingUid(), present: Boolean = true, request: Parcel.() -> Unit): Parcel {
        val output = Parcel.obtain()
        var received = false
        val callback = object : Binder() {
            override fun onTransact(codeReceived: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(AANG_CALLBACK)
                assertEquals(if (code == 4) 3 else code, codeReceived)
                assertEquals(IBinder.FLAG_ONEWAY, flags)
                output.appendFrom(data, data.dataPosition(), data.dataAvail())
                output.setDataPosition(0)
                received = true
                return true
            }
        }
        val binder = GoogleAuthAangBinder(uid, backend, authorize = {}, execute = { it() })
        val input = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            input.writeInterfaceToken(AANG_DESCRIPTOR)
            input.writeStrongBinder(callback)
            input.writeInt(if (present) 1 else 0)
            input.request()
            assertTrue(binder.transact(code, input, reply, 0))
            reply.readException()
            assertTrue(received)
            return output
        } catch (e: Throwable) { output.recycle(); throw e }
        finally { input.recycle(); reply.recycle() }
    }

    private fun Parcel.status(): Status {
        assertEquals(1, readInt())
        return Status.CREATOR.createFromParcel(this)
    }

    @Test fun accountsTransactionReturnsActualZeroOneAndTwoAccountLists() {
        for (count in 0..2) {
            if (count > 0) account("owner$count@example.test", "gaia-$count")
            val result = call(1) { safe { Writer.write(this, 1, "com.google", false); Writer.write(this, 4, false); Writer.write(this, 5, false) } }
            try {
                assertEquals(0, result.status().statusCode)
                assertEquals(1, result.readInt())
                val end = Reader.readObjectHeader(result)
                val field = Reader.readHeader(result)
                assertEquals(1, Reader.getFieldId(field))
                val accounts = Reader.readParcelableList(result, field, GoogleAccount.CREATOR)
                assertEquals(count, accounts.size)
                accounts.forEachIndexed { index, account ->
                    assertEquals("gaia-${index + 1}", account.gaiaId)
                    assertEquals("com.google", account.accountType)
                    assertEquals("owner${index + 1}@example.test", account.accountName)
                }
                assertEquals(end, result.dataPosition())
                assertEquals(0, result.dataAvail())
            } finally { result.recycle() }
        }
    }

    @Test fun hiddenAccountsStayHiddenAndMissingIdsAreAnError() {
        val account = account()
        am.setAccountVisibility(account, caller, AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE)
        call(1) { safe { Writer.write(this, 1, "com.google", false) } }.useParcel {
            assertEquals(0, status().statusCode); assertEquals(1, readInt())
            assertTrue(GetAccountsResponse.CREATOR.createFromParcel(this).accounts.isEmpty())
        }
        am.setAccountVisibility(account, caller, AccountManager.VISIBILITY_VISIBLE)
        am.setUserData(account, UserDataKeys.GOOGLE_USER_ID, null)
        call(1) { safe { Writer.write(this, 1, "com.google", false) } }.useParcel {
            assertEquals(8, status().statusCode); assertEquals(0, readInt()); assertEquals(0, dataAvail())
        }
    }

    @Test fun restrictedAndTransientListingsFailExplicitly() {
        for (field in listOf(4, 5)) call(1) {
            safe { Writer.write(this, 1, "com.google", false); Writer.write(this, field, true) }
        }.useParcel {
            assertEquals(17, status().statusCode); assertEquals(0, readInt()); assertEquals(0, dataAvail())
        }
    }

    @Test fun tokenTransactionUsesTheExistingSignatureAndScopeBoundTokenCache() {
        val account = account()
        val scope = "oauth2:https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile"
        AuthManager(context, account.name, caller, scope).apply {
            setPermitted(true); setAuthToken("fixture-access-token"); setExpiry(System.currentTimeMillis() / 1000 + 3600)
        }
        call(2) { safe { accountField(account); Writer.writeStringList(this, 3, listOf(scope.removePrefix("oauth2:")), false) } }.useParcel {
            assertEquals(0, status().statusCode); assertEquals(1, readInt())
            val end = Reader.readObjectHeader(this)
            var token: String? = null; var expiry: Long? = null; var scopes: List<String>? = null
            while (dataPosition() < end) {
                val field = Reader.readHeader(this)
                when (Reader.getFieldId(field)) {
                    1 -> token = Reader.readString(this, field)
                    2 -> {
                        // Independently inspect metadata IDs 1(Long)/2(String list).
                        val metadata = Reader.readParcelable(this, field, TokenMetadata.CREATOR)
                        expiry = metadata.expiry; scopes = metadata.grantedScopes
                    }
                    else -> fail("Unexpected token response field")
                }
            }
            assertEquals("fixture-access-token", token)
            assertTrue(expiry!! > System.currentTimeMillis() / 1000)
            assertEquals(scope.removePrefix("oauth2:").split(' '), scopes)
            assertEquals(0, dataAvail())
        }
    }

    @Test fun tokenScopeMappingAndDeniedConsentNeverInventAToken() {
        val account = account()
        listOf(3 to "oauth2:email profile", 4 to "weblogin:email profile", 5 to "email profile", 6 to "audience:server:client_id:email profile").forEach { (field, expected) ->
            var requested = false
            val backend = AangAccountBackend(context, caller) { actualAccount, actualScope ->
                assertEquals(account, actualAccount); assertEquals(expected, actualScope)
                requested = true; AuthResponse()
            }
            call(2, backend) { safe { accountField(account); Writer.writeStringList(this, field, listOf("email profile"), false) } }.useParcel {
                val status = status()
                assertEquals(4, status.statusCode)
                assertNotNull(status.resolution)
                assertEquals(Binder.getCallingUid(), shadowOf(status.resolution).savedIntent.getIntExtra(AccountManager.KEY_CALLER_UID, 0))
                assertEquals(0, readInt()); assertEquals(0, dataAvail())
                assertTrue(requested)
            }
        }
    }

    @Test fun spoofedGaiaIdAndClientOverrideCannotReachTokenBackend() {
        val account = account()
        val backend = AangAccountBackend(context, caller) { _, _ -> fail("Unauthorized token backend call"); AuthResponse() }
        call(2, backend) { safe { accountField(account, "someone-else"); Writer.writeStringList(this, 3, listOf("email"), false) } }.useParcel {
            assertEquals(5, status().statusCode); assertEquals(0, readInt())
        }
        call(2, backend) { safe { accountField(account); Writer.writeStringList(this, 3, listOf("email"), false); Writer.write(this, 11, "another.package", false) } }.useParcel {
            assertEquals(10, status().statusCode); assertEquals(0, readInt())
        }
    }

    @Test fun capabilitiesReturnOnlyCachedAllowedDeniedAndVisibleState() {
        val account = account()
        CapabilityStore.writeMerged(am, account, CapabilityState(setOf("allowed", "private"), setOf("denied"), emptySet(),
            mapOf("private" to listOf("another.package")), emptyMap()), emptyList())
        for ((cap, expected) in listOf("allowed" to 1, "denied" to 2, "private" to 4, "unknown" to -1)) {
            call(4) { safe { accountField(account); Writer.writeStringList(this, 2, listOf(cap), false) } }.useParcel {
                assertEquals(if (expected == -1) 17 else 0, status().statusCode)
                assertEquals(expected, readInt()); assertEquals(0, dataAvail())
            }
        }
    }

    @Test fun accountCapabilityFilterDoesNotExposeDeniedAccounts() {
        val account = account()
        CapabilityStore.writeMerged(am, account, CapabilityState(emptySet(), setOf("blocked"), emptySet(), emptyMap(), emptyMap()), emptyList())
        call(1) { safe { Writer.write(this, 1, "com.google", false); Writer.writeStringList(this, 2, listOf("blocked"), false) } }.useParcel {
            assertEquals(0, status().statusCode); assertEquals(1, readInt())
            assertTrue(GetAccountsResponse.CREATOR.createFromParcel(this).accounts.isEmpty())
        }
    }

    @Test fun legacyCacheAndUnknownServerStatusesCannotEstablishGrants() {
        val account = account()
        am.setUserData(account, UserDataKeys.CAPABILITIES_VERSION, "1")
        am.setUserData(account, UserDataKeys.ENABLED_CAPS, "was-unknown")
        assertTrue(CapabilityStore.read(am, account).enabled.isEmpty())
        call(4) { safe { accountField(account); Writer.writeStringList(this, 2, listOf("was-unknown"), false) } }.useParcel {
            assertEquals(17, status().statusCode); assertEquals(-1, readInt())
        }

        fun store(status: CapabilityStatus?) {
            val state = CapabilityStore.decode(Capabilities(entries = listOf(
                Capability(name = "real", type = CapabilityType.TYPE_DEFAULT, status = status)
            )))
            CapabilityStore.writeMerged(am, account, state, emptyList())
        }
        for (unknown in listOf(CapabilityStatus.STATUS_UNKNOWN, null)) {
            store(CapabilityStatus.STATUS_ALLOWED)
            assertEquals(setOf("real"), CapabilityStore.read(am, account).enabled)
            store(unknown)
            assertTrue(CapabilityStore.read(am, account).enabled.isEmpty())
            call(4) { safe { accountField(account); Writer.writeStringList(this, 2, listOf("real"), false) } }.useParcel {
                assertEquals(17, status().statusCode); assertEquals(-1, readInt())
            }
        }
        // Preserve the real GMS field's representation; provenance is a separate local key.
        assertEquals("1", am.getUserData(account, UserDataKeys.CAPABILITIES_VERSION))
        assertEquals("2", am.getUserData(account, UserDataKeys.CACHE_FORMAT))
    }

    @Test fun nullRequestsReturnErrorsOnEverySupportedCallback() {
        for (code in listOf(1, 2, 4)) call(code, present = false) {}.useParcel {
            assertEquals(10, status().statusCode)
            assertEquals(if (code == 4) -1 else 0, readInt())
            assertEquals(0, dataAvail())
        }
    }

    @Test fun clientDeathDuringCallbackDoesNotEscapeTheDispatchedOperation() {
        val binder = GoogleAuthAangBinder(Binder.getCallingUid(), AangAccountBackend(context, caller), {}, { it() })
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                throw android.os.DeadObjectException()
            }
        }
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(AANG_DESCRIPTOR); data.writeStrongBinder(callback); data.writeInt(0)
            assertTrue(binder.transact(1, data, reply, 0)); reply.readException()
        } finally { data.recycle(); reply.recycle() }
    }

    @Test fun unsupportedTokenOptionsReturnErrorsWithCorrectCallbacks() {
        val account = account()
        for (field in listOf(7, 9, 10, 11, 13)) call(2) {
            safe {
                accountField(account); Writer.writeStringList(this, 3, listOf("email"), false)
                when (field) {
                    7 -> Writer.write(this, 7, 1)
                    9 -> Writer.write(this, 9, true)
                    10 -> Writer.write(this, 10, byteArrayOf(0), false)
                    11 -> Writer.write(this, 11, "other", false)
                    13 -> Writer.write(this, 13, android.net.Network.fromNetworkHandle((1L shl 32) or 0xcafed00dL), 0, false)
                }
            }
        }.useParcel { assertFalse(status().isSuccess); assertEquals(0, readInt()) }
    }

    @Test fun brokerAdmissionRejectsWrongUidAndUntrustedCertificate() {
        assertThrows(SecurityException::class.java) { authorizeAangCaller(context, "missing.package") }
        assertThrows(SecurityException::class.java) { authorizeAangCaller(context, caller) }
        assertThrows(Exception::class.java) {
            call(1, uid = Binder.getCallingUid() + 1) { safe { Writer.write(this, 1, "com.google", false) } }
        }
    }

    @Test fun onlyGoogleAuthFeatureIsAdvertisedAndNewerOperationsStayUnavailable() {
        assertEquals(listOf("google_auth_api" to 1L), AANG_FEATURES.map { it.name to it.version })
        val binder = GoogleAuthAangBinder(Binder.getCallingUid(), AangAccountBackend(context, caller), {}, { it() })
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(AANG_DESCRIPTOR)
            assertFalse(binder.transact(6, data, reply, 0)) // SyncAccountState is tx6, never tx4.
        } finally { data.recycle(); reply.recycle() }
    }

    private fun Parcel.useParcel(action: Parcel.() -> Unit) { try { action() } finally { recycle() } }
}
