/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.loginservice

import android.accounts.Account
import android.accounts.AccountManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.microg.gms.auth.AuthManager
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

    @Test fun anImageWithoutTheStoreAsksForThatName() {
        assertFalse(AccountAuthenticator.isImageStore(context, "com.aurora.store", "com.android.vending")) // not installed
    }

    @Test fun theImagesStoreStillAsksToActAsAnyOtherApp() {
        install("com.aurora.store", system = true)
        assertFalse(AccountAuthenticator.isImageStore(context, "com.aurora.store", "com.google.android.gm"))
    }

    @Test fun theImagesStoreGetsThePlayStoresTokenWithoutConsent() {
        install("com.aurora.store", system = true)
        assertTrue(AccountAuthenticator.isImageStorePlayToken(context, "com.aurora.store", "com.android.vending",
            AccountAuthenticator.PLAY_STORE_TOKEN))
    }

    @Test fun anyOtherTokenOrCallerStillNeedsConsent() {
        install("com.aurora.store", system = true)
        assertFalse(AccountAuthenticator.isImageStorePlayToken(context, "com.aurora.store", "com.android.vending",
            "oauth2:https://www.googleapis.com/auth/gmail.readonly"))
        assertFalse(AccountAuthenticator.isImageStorePlayToken(context, "com.aurora.store", "com.google.android.gm",
            AccountAuthenticator.PLAY_STORE_TOKEN))
        install("org.example.store", system = true)
        assertFalse(AccountAuthenticator.isImageStorePlayToken(context, "org.example.store", "com.android.vending",
            AccountAuthenticator.PLAY_STORE_TOKEN))
    }

    private fun playManager(type: String, service: String = AccountAuthenticator.PLAY_STORE_TOKEN): AuthManager {
        val account = Account("owner@example.com", type)
        shadowOf(AccountManager.get(context)).addAccount(account)
        return AuthManager(context, account.name, "com.android.vending", service).apply {
            setPackageSignature("38918a453d07199354f8b19af05ec6562ced5788")
            setAccountType(type)
        }
    }

    // API 25: AuthManager.setPermitted's account-visibility call has no Robolectric implementation from API 26 on.
    @Config(sdk = [25])
    @Test fun theGrantIsRecordedOnTheRequestsOwnAccount() {
        install("com.aurora.store", system = true)
        for (type in listOf("com.google", "com.google.work")) {
            val manager = playManager(type)
            assertFalse(manager.isPermitted)
            AccountAuthenticator.grantImageStorePlayToken(context, manager, "com.aurora.store", "com.android.vending",
                AccountAuthenticator.PLAY_STORE_TOKEN)
            assertTrue(manager.isPermitted)
            assertEquals(type, manager.account.type)
        }
    }

    @Test fun noGrantForAnotherTokenOrCaller() {
        install("com.aurora.store", system = true)
        install("org.example.store", system = true)
        val other = playManager("com.google", "oauth2:https://www.googleapis.com/auth/gmail.readonly")
        AccountAuthenticator.grantImageStorePlayToken(context, other, "com.aurora.store", "com.android.vending",
            "oauth2:https://www.googleapis.com/auth/gmail.readonly")
        assertFalse(other.isPermitted)
        val stranger = playManager("com.google")
        AccountAuthenticator.grantImageStorePlayToken(context, stranger, "org.example.store", "com.android.vending",
            AccountAuthenticator.PLAY_STORE_TOKEN)
        assertFalse(stranger.isPermitted)
    }
}
