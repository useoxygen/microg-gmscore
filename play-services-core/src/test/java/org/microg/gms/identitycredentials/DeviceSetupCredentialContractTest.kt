/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.identitycredentials

import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import com.google.android.gms.common.Feature
import com.google.android.gms.common.api.ApiMetadata
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.ApiExceptionUtil
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import com.google.android.gms.identitycredentials.ExportCredentialsToDeviceSetupRequest
import com.google.android.gms.identitycredentials.GetCredentialTransferCapabilitiesRequest
import com.google.android.gms.identitycredentials.ImportCredentialsForDeviceSetupRequest
import com.google.android.gms.tasks.TaskCompletionSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.common.GmsService
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DeviceSetupCredentialContractTest {
    private fun withService(action: (IBinder, Array<Feature>) -> Unit) {
        val controller = Robolectric.buildService(IdentityCredentialApiService::class.java).create()
        try {
            var info: ConnectionInfo? = null
            var service: IBinder? = null
            controller.get().handleServiceRequest(object : IGmsCallbacks.Stub() {
                override fun onPostInitComplete(status: Int, binder: IBinder?, params: Bundle?) = fail("Expected connection info")
                override fun onAccountValidationComplete(status: Int, params: Bundle?) = fail("Unexpected account validation")
                override fun onPostInitCompleteWithConnectionInfo(status: Int, binder: IBinder?, connection: ConnectionInfo?) {
                    assertEquals(0, status)
                    assertNotNull(binder)
                    service = binder
                    info = connection
                }
            }, GetServiceRequest(352).apply { packageName = "com.google.android.apps.restore" }, GmsService.IDENTITY_CREDENTIALS)
            action(requireNotNull(service), requireNotNull(info).features)
        } finally {
            controller.destroy()
        }
    }

    @Test fun brokerAdvertisesTheDeviceSetupWireVersionsRequestedBySwitch() = withService { _, features ->
        val versions = features.associate { it.name to it.version }
        assertEquals(1L, versions["GET_CREDENTIAL"])
        listOf("IMPORT_CREDENTIALS_FOR_DEVICE_SETUP", "EXPORT_CREDENTIALS_TO_DEVICE_SETUP", "GET_CREDENTIAL_TRANSFER_CAPABILITIES").forEach {
            assertEquals(3L, versions[it])
        }
    }

    @Test fun switchRolledOutFeaturesReachImmediateFailuresWithoutMissingFeatureResolution() = withService { service, features ->
        val uri = Uri.parse("content://synthetic.credentials/private-transfer")
        // AIDL offsets 11/12/13 are wire transactions 12/13/14. Switch ijq/jdw
        // and its callback stub use those same numbers, not the zero-based offsets.
        val requests = listOf<Triple<Feature, Int, Parcelable>>(
            Triple(Feature("IMPORT_CREDENTIALS_FOR_DEVICE_SETUP", 3, true), 12, ImportCredentialsForDeviceSetupRequest("{}", uri, Bundle())),
            Triple(Feature("EXPORT_CREDENTIALS_TO_DEVICE_SETUP", 3, true), 13, ExportCredentialsToDeviceSetupRequest(uri, Bundle())),
            Triple(Feature("GET_CREDENTIAL_TRANSFER_CAPABILITIES", 3, true), 14, GetCredentialTransferCapabilitiesRequest(Bundle()))
        )
        requests.forEach { (required, transaction, request) ->
            // Switch jed/jdc/iww auto-resolve these rolled-out features. ivv.r/x checks
            // the broker's name/version BEFORE dispatch, otherwise queues 5s/120s timers.
            assertTrue("Switch would auto-resolve missing ${required.name}@${required.version}",
                features.any { it.name == required.name && it.version >= required.version })
            var calls = 0
            val completion = TaskCompletionSource<Parcelable?>()
            val callback = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    data.enforceInterface("com.google.android.gms.identitycredentials.internal.IIdentityCredentialCallbacks")
                    assertEquals(transaction, code)
                    assertEquals(0, flags)
                    assertEquals(1, data.readInt())
                    val status = Status.CREATOR.createFromParcel(data)
                    assertEquals(CommonStatusCodes.API_NOT_CONNECTED, status.statusCode)
                    assertFalse(status.hasResolution())
                    assertEquals(0, data.readInt()) // No fabricated transfer result or inventory.
                    assertEquals(0, data.dataAvail()) // ApiMetadata.SKIP omits its presence word too.
                    // Switch jea/jeb/jec complete failed tasks through imp.aE, without retry.
                    completion.setException(ApiExceptionUtil.fromStatus(status))
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
                assertTrue(service.transact(transaction, data, reply, 0))
                reply.readException()
                assertEquals(0, reply.dataAvail())
                assertEquals(1, calls)
                // No looper advancement or feature-resolution delay is needed to finish.
                assertTrue(completion.task.isComplete)
                assertFalse(completion.task.isSuccessful)
                assertEquals(CommonStatusCodes.API_NOT_CONNECTED, (completion.task.exception as ApiException).statusCode)
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }
}
