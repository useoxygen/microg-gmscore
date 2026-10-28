/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.loginservice

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Run 6 (CYC-700): microG asked the owner before the image's own store could act as the Play Store. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ImageStoreOverrideTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun install(name: String, system: Boolean) = shadowOf(context.packageManager).installPackage(PackageInfo().apply {
        packageName = name
        applicationInfo = ApplicationInfo().apply { packageName = name; if (system) flags = ApplicationInfo.FLAG_SYSTEM }
    })

    @Test fun theImagesStoreActsAsThePlayStoreWithoutAsking() {
        install("com.aurora.store", system = true)
        assertTrue(AccountAuthenticator.isImageStore(context, "com.aurora.store", "com.android.vending"))
    }

    @Test fun aSideloadedCopyOrAnotherAppStillAsks() {
        install("com.aurora.store", system = false)
        assertFalse(AccountAuthenticator.isImageStore(context, "com.aurora.store", "com.android.vending"))
        install("org.example.store", system = true)
        assertFalse(AccountAuthenticator.isImageStore(context, "org.example.store", "com.android.vending"))
        assertFalse(AccountAuthenticator.isImageStore(context, "org.example.missing", "com.android.vending"))
    }

    @Test fun theImagesStoreStillAsksToActAsAnyOtherApp() {
        install("com.aurora.store", system = true)
        assertFalse(AccountAuthenticator.isImageStore(context, "com.aurora.store", "com.google.android.gm"))
    }
}
