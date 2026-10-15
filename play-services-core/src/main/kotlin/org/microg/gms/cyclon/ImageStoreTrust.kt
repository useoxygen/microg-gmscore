/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */

package org.microg.gms.cyclon

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.microg.gms.common.Constants

/**
 * The image's store, Cyclon's Aurora fork preinstalled in /product, asks for Play tokens as the Play Store for the
 * Google account the owner signed in to it with. microG asks the owner once per account and Aurora signer to allow
 * that, and a background renewal cannot show the prompt: Android only posts a notification, and Aurora reads the
 * missing token as an expired sign-in. Updates to a system app must keep its signer, so a preinstalled
 * com.aurora.store is the image's own build. It gets the Play scope as the Play Store without the prompt, and
 * nothing else.
 */
object ImageStoreTrust {
    const val STORE_PACKAGE = "com.aurora.store"
    const val PLAY_SCOPE = "oauth2:https://www.googleapis.com/auth/googleplay"

    @JvmStatic
    fun allowsPlayOverride(context: Context, requestingPackage: String, overridePackage: String,
                           overrideCertSha1: String, authTokenType: String?): Boolean {
        if (requestingPackage != STORE_PACKAGE || overridePackage != Constants.VENDING_PACKAGE_NAME) return false
        if (authTokenType != PLAY_SCOPE || !overrideCertSha1.equals(Constants.GMS_PACKAGE_SIGNATURE_SHA1, ignoreCase = true)) return false
        val info = try {
            context.packageManager.getApplicationInfo(requestingPackage, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        return info.flags and ApplicationInfo.FLAG_SYSTEM != 0
    }
}
