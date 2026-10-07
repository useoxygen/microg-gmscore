/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp
import com.google.android.gms.R

@Composable
@OptIn(ExperimentalTextApi::class)
internal fun CyclonTheme(content: @Composable () -> Unit) {
    val body = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) FontFamily(
        Font(R.font.cyclon_manrope, weight = FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.cyclon_manrope, weight = FontWeight.Medium,
            variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.cyclon_manrope, weight = FontWeight.SemiBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(600)))
    ) else FontFamily.SansSerif
    val heading = FontFamily(Font(R.font.cyclon_space_mono_regular))
    val paper = colorResource(R.color.cyclon_paper)
    val ink = colorResource(R.color.cyclon_ink)
    val muted = colorResource(R.color.cyclon_muted)
    val colors = lightColorScheme(
        primary = ink, onPrimary = paper, background = paper, onBackground = ink,
        surface = paper, onSurface = ink, surfaceVariant = colorResource(R.color.cyclon_panel),
        onSurfaceVariant = muted, outline = colorResource(R.color.cyclon_line),
        outlineVariant = colorResource(R.color.cyclon_line)
    )
    val defaults = Typography()
    MaterialTheme(colorScheme = colors, typography = Typography(
        headlineSmall = defaults.headlineSmall.copy(fontFamily = heading),
        titleMedium = defaults.titleMedium.copy(fontFamily = heading),
        bodyLarge = defaults.bodyLarge.copy(fontFamily = body),
        bodyMedium = defaults.bodyMedium.copy(fontFamily = body),
        labelLarge = defaults.labelLarge.copy(fontFamily = body, fontWeight = FontWeight.SemiBold)
    ), shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(8.dp))) {
        CompositionLocalProvider(LocalContentColor provides ink) {
            content()
        }
    }
}
