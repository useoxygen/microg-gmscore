/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.os.IBinder
import android.os.Parcel
import android.os.Process
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertThrows
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.auth.folsom.FolsomCallerAdmission
import org.microg.gms.auth.folsom.FolsomCallerIdentity
import org.microg.gms.auth.folsom.KeyRetrievalServiceImpl
import org.microg.gms.auth.folsom.FolsomAndroidAdmission
import org.microg.gms.auth.folsom.FOLSOM_SUPPORTED_FEATURES
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.api.internal.IStatusCallback
import com.google.android.gms.auth.folsom.internal.IKeyRetrievalConsentCallback
import com.google.android.gms.auth.folsom.internal.IRecoveryResultCallback
import com.google.android.gms.auth.folsom.internal.IByteArrayCallback
import com.google.android.gms.auth.folsom.RecoveryResult
import com.google.android.gms.common.api.ApiMetadata

/** Inert Binder guard fixture: no account lookup, key read, activity or network request. */
@RunWith(AndroidJUnit4::class)
class FolsomCallerAndroidTest {
    @Test fun everyTransactionRejectsAHandleBoundToAnotherUidBeforeDispatch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = object : LifecycleOwner {
            private val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val otherUid = Process.myUid() + 37
        val admission = FolsomCallerAdmission(FolsomCallerIdentity(otherUid, "com.android.chrome", "1".repeat(64), true), "chromesync", Process.myUid())
        val binder = KeyRetrievalServiceImpl(context, owner.lifecycle, "chromesync", "synthetic-fixture", false, "com.android.chrome", admission, null)
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            assertThrows(SecurityException::class.java) { binder.onTransact(IBinder.INTERFACE_TRANSACTION, data, reply, 0) }
            assertThrows(SecurityException::class.java) { binder.onTransact(5, data, reply, 0) }
        } finally { data.recycle(); reply.recycle() }
    }
    @Test fun unavailableRecoveryCallsAndMalformedLifecycleRequestsCannotReportSuccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = object : LifecycleOwner {
            private val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val identity = FolsomAndroidAdmission.identity(context, context.packageName)!!
        val admission = FolsomCallerAdmission(identity, "chromesync", Process.myUid())
        val api = KeyRetrievalServiceImpl(context, owner.lifecycle, "chromesync", "synthetic-fixture", false, context.packageName, admission, null)
        assertEquals(setOf("key_retrieval", "get_domain_state", "reset_security_domain", "lskf_consent", "start_recovery", "generate_open_vault_request", "list_recovered_security_domains"), FOLSOM_SUPPORTED_FEATURES.map { it.name }.toSet())
        assertEquals(2L, FOLSOM_SUPPORTED_FEATURES.single { it.name == "reset_security_domain" }.version)
        assertEquals(1L, FOLSOM_SUPPORTED_FEATURES.single { it.name == "lskf_consent" }.version)
        assertEquals(3L, FOLSOM_SUPPORTED_FEATURES.single { it.name == "start_recovery" }.version)
        assertEquals(1L, FOLSOM_SUPPORTED_FEATURES.single { it.name == "generate_open_vault_request" }.version)
        assertEquals(1L, FOLSOM_SUPPORTED_FEATURES.single { it.name == "list_recovered_security_domains" }.version)
        val operations: List<(IStatusCallback) -> Unit> = listOf(
            { api.resetSecurityDomain(it, null, null) },
            { api.joinSecurityDomain(it, null, null, 0, null) },
            { api.addGaiaPasswordMember(it, null, null) },
            { api.createPrfMemberOperation(it, null, null, null, null) },
            { api.addRecoveryContactToDependentKeychainOperation(it, null, null, null) },
            { api.createRetrievalPacketOperation(it, null, null, null, null) },
            { api.setClaimantKeyOperation(it, null, null, null, null) }
        )
        operations.forEach { operation ->
            var result: Status? = null
            operation(object : IStatusCallback.Stub() { override fun onResult(status: Status?) { result = status } })
            assertNotNull(result); assertFalse(result!!.isSuccess)
        }
        var consent = true
        var result: Status? = null
        val callback = object : IKeyRetrievalConsentCallback.Stub() {
            override fun onResult(status: Status?, allowed: Boolean) { result = status; consent = allowed }
        }
        api.setConsent(callback, null, false, null)
        assertNotNull(result); assertFalse(result!!.isSuccess); assertFalse(consent)
        var recoveryCompletions = 0
        api.startRecoveryOperation(object : IRecoveryResultCallback.Stub() {
            override fun onResult(status: Status?, recovery: RecoveryResult?) {
                recoveryCompletions++; assertFalse(status!!.isSuccess); assertEquals(13, recovery!!.result)
            }
        }, null, null)
        assertEquals(1, recoveryCompletions)
        var generatedCompletions = 0
        api.generateOpenVaultRequestOperation(object : IByteArrayCallback.Stub() {
            override fun onResult(status: Status?, bytes: ByteArray?, metadata: ApiMetadata?) {
                generatedCompletions++; assertFalse(status!!.isSuccess); assertTrue(bytes!!.isEmpty())
            }
        }, null, null)
        assertEquals(1, generatedCompletions)
    }
}
