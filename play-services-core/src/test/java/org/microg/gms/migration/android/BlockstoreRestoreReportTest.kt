/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.api.internal.IStatusCallback
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.auth.blockstore.BlobstoreServiceImpl
import org.microg.gms.auth.blockstore.BlockStoreImpl
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class BlockstoreRestoreReportTest {
    @Test fun unsupportedRestoreReportingCompletesExactlyOnceWithAnError() {
        val owner = object : LifecycleOwner { override val lifecycle: Lifecycle = LifecycleRegistry(this) }
        val service = BlobstoreServiceImpl(BlockStoreImpl(RuntimeEnvironment.getApplication(), "com.google.android.apps.restore"), owner.lifecycle)
        var count = 0
        var result: Status? = null
        service.reportAppRestore(object : IStatusCallback.Stub() {
            override fun onResult(status: Status) { count++; result = status }
        }, listOf("com.example.synthetic"), 1, null)
        assertEquals(1, count); assertEquals(17, result?.statusCode)
        service.reportAppRestore(null, emptyList(), 1, null)
    }
}
