/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
@file:Suppress("DEPRECATION")

package org.microg.gms.cyclon

import android.content.Context
import android.content.res.Configuration
import android.webkit.WebSettings
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * Google's sign-in and consent pages in microG's WebViews follow the system dark theme.
 * microG targets SDK 29, where WebView only reports prefers-color-scheme: dark (and only falls
 * back to darkening the page itself) when force dark is on.
 */
fun WebSettings.followSystemDarkTheme(context: Context) {
    val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    if (!night || !WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) return
    WebSettingsCompat.setForceDark(this, WebSettingsCompat.FORCE_DARK_ON)
    if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
        WebSettingsCompat.setForceDarkStrategy(this, WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING)
    }
}
