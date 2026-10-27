/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.phenotype

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.common.GmsService
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PhenotypeStorageInfoContractTest {
    @Test fun brokerDoesNotAdvertiseStorageInfoWithoutSharedStorage() {
        val controller = Robolectric.buildService(PhenotypeService::class.java).create()
        try {
            var completions = 0
            controller.get().handleServiceRequest(object : IGmsCallbacks.Stub() {
                override fun onPostInitComplete(status: Int, binder: IBinder?, params: Bundle?) {
                    assertEquals(0, status)
                    assertNotNull(binder)
                    assertNull(params)
                    completions++
                }
                override fun onAccountValidationComplete(status: Int, params: Bundle?) = fail("Unexpected account validation")
                override fun onPostInitCompleteWithConnectionInfo(status: Int, binder: IBinder?, info: ConnectionInfo?) {
                    assertFalse(info?.features.orEmpty().any { it.name == "get_storage_info_api" })
                    completions++
                }
            }, GetServiceRequest(51), GmsService.PHENOTYPE)
            assertEquals(1, completions)
        } finally {
            controller.destroy()
        }
    }

    @Test fun unimplementedStorageInfoTransactionDoesNotInventAResponse() {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        var callbacks = 0
        try {
            data.writeInterfaceToken("com.google.android.gms.phenotype.internal.IPhenotypeService")
            data.writeStrongBinder(object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    callbacks++
                    return true
                }
            })
            assertFalse(PhenotypeServiceImpl("com.google.android.apps.restore").asBinder().transact(27, data, reply, 0))
            assertEquals(0, callbacks)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
