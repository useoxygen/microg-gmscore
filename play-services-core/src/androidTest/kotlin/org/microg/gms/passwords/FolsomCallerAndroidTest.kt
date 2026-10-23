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
    @Test fun unsupportedResetAndEnrollmentCallsFailWithoutAdvertisingRecoveryCapabilities() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = object : LifecycleOwner {
            private val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val identity = FolsomAndroidAdmission.identity(context, context.packageName)!!
        val admission = FolsomCallerAdmission(identity, "chromesync", Process.myUid())
        val api = KeyRetrievalServiceImpl(context, owner.lifecycle, "chromesync", "synthetic-fixture", false, context.packageName, admission, null)
        assertEquals(setOf("key_retrieval", "get_domain_state"), FOLSOM_SUPPORTED_FEATURES.map { it.name }.toSet())
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
    }
}
