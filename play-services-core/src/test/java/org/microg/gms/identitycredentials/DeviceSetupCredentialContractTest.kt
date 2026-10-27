/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.identitycredentials

import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.android.gms.common.api.ApiMetadata
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import com.google.android.gms.identitycredentials.ExportCredentialsToDeviceSetupRequest
import com.google.android.gms.identitycredentials.GetCredentialTransferCapabilitiesRequest
import com.google.android.gms.identitycredentials.ImportCredentialsForDeviceSetupRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.common.GmsService
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DeviceSetupCredentialContractTest {
    @Test fun brokerDoesNotOfferUnimplementedDeviceSetupTransfer() {
        val controller = Robolectric.buildService(IdentityCredentialApiService::class.java).create()
        try {
            var info: ConnectionInfo? = null
            controller.get().handleServiceRequest(object : IGmsCallbacks.Stub() {
                override fun onPostInitComplete(status: Int, binder: IBinder?, params: Bundle?) = fail("Expected connection info")
                override fun onAccountValidationComplete(status: Int, params: Bundle?) = fail("Unexpected account validation")
                override fun onPostInitCompleteWithConnectionInfo(status: Int, binder: IBinder?, connection: ConnectionInfo?) {
                    assertEquals(0, status)
                    assertNotNull(binder)
                    info = connection
                }
            }, GetServiceRequest(352).apply { packageName = "com.google.android.apps.restore" }, GmsService.IDENTITY_CREDENTIALS)
            val features = requireNotNull(info).features.map { it.name }.toSet()
            assertTrue(features.contains("GET_CREDENTIAL"))
            assertFalse(features.contains("IMPORT_CREDENTIALS_FOR_DEVICE_SETUP"))
            assertFalse(features.contains("EXPORT_CREDENTIALS_TO_DEVICE_SETUP"))
            assertFalse(features.contains("GET_CREDENTIAL_TRANSFER_CAPABILITIES"))
        } finally {
            controller.destroy()
        }
    }

    @Test fun deviceSetupWireTransactionsReturnTheirMatchingExplicitErrorCallbacks() {
        val owner = object : LifecycleOwner { override val lifecycle: Lifecycle = LifecycleRegistry(this) }
        val service = IdentityCredentialApiServiceImpl(RuntimeEnvironment.getApplication(), "com.google.android.apps.restore", owner.lifecycle)
        val uri = Uri.parse("content://synthetic.credentials/private-transfer")
        // AIDL offsets 11/12/13 are wire transactions 12/13/14. Switch ijq/jdw
        // and its callback stub use those same numbers, not the zero-based offsets.
        val requests = listOf<Pair<Int, Parcelable>>(
            12 to ImportCredentialsForDeviceSetupRequest("{}", uri, Bundle()),
            13 to ExportCredentialsToDeviceSetupRequest(uri, Bundle()),
            14 to GetCredentialTransferCapabilitiesRequest(Bundle())
        )
        requests.forEach { (transaction, request) ->
            var calls = 0
            val callback = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    data.enforceInterface("com.google.android.gms.identitycredentials.internal.IIdentityCredentialCallbacks")
                    assertEquals(transaction, code)
                    assertEquals(0, flags)
                    assertEquals(1, data.readInt())
                    assertEquals(CommonStatusCodes.API_NOT_CONNECTED, Status.CREATOR.createFromParcel(data).statusCode)
                    assertEquals(0, data.readInt()) // No fabricated transfer result or inventory.
                    assertEquals(0, data.dataAvail()) // ApiMetadata.SKIP omits its presence word too.
                    calls++
                    requireNotNull(reply).writeNoException()
                    return true
                }
            }
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("com.google.android.gms.identitycredentials.internal.IIdentityCredentialService")
                data.writeStrongBinder(callback)
                data.writeInt(1)
                request.writeToParcel(data, 0)
                data.writeInt(1)
                ApiMetadata.DEFAULT.writeToParcel(data, 0)
                assertTrue(service.asBinder().transact(transaction, data, reply, 0))
                reply.readException()
                assertEquals(0, reply.dataAvail())
                assertEquals(1, calls)
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }
}
