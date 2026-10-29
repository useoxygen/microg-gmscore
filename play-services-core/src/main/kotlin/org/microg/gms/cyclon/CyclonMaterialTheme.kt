/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.R

/**
 * Compose counterpart of Theme.Cyclon: paper/black surfaces and ink text from the day/night
 * cyclon_* colours, the owner's highlight as primary for on/selected states and progress only.
 * Text uses the bundled Manrope with Core's type scale (OwnerTheme.kt), as Theme.CyclonServices
 * does for the XML screens. Content without a Surface (Cyclon Services screens draw their own
 * paper) still gets ink, and cards and fields have 8dp corners.
 */
@Composable
fun CyclonMaterialTheme(content: @Composable () -> Unit) {
    val paper = colorResource(R.color.cyclon_paper)
    val ink = colorResource(R.color.cyclon_ink)
    val muted = colorResource(R.color.cyclon_muted)
    val line = colorResource(R.color.cyclon_line)
    val cell = colorResource(R.color.cyclon_panel)
    val raised = colorResource(R.color.cyclon_raised)
    val highlight = colorResource(R.color.cyclon_highlight)
    val error = colorResource(R.color.cyclon_error)
    val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    val colors = scheme.copy(
        primary = highlight, onPrimary = paper, primaryContainer = cell, onPrimaryContainer = ink,
        inversePrimary = highlight,
        secondary = ink, onSecondary = paper, secondaryContainer = cell, onSecondaryContainer = ink,
        tertiary = highlight, onTertiary = paper, tertiaryContainer = cell, onTertiaryContainer = ink,
        background = paper, onBackground = ink, surface = paper, onSurface = ink,
        surfaceVariant = cell, onSurfaceVariant = muted, surfaceTint = paper,
        inverseSurface = ink, inverseOnSurface = paper, error = error,
        outline = line, outlineVariant = line,
        surfaceBright = paper, surfaceDim = paper, surfaceContainerLowest = paper,
        surfaceContainerLow = paper, surfaceContainer = paper, surfaceContainerHigh = raised,
        surfaceContainerHighest = cell
    )
    MaterialTheme(colorScheme = colors, shapes = CyclonShapes, typography = CyclonTypography) {
        CompositionLocalProvider(LocalContentColor provides ink, content = content)
    }
}

// Manrope is variable and its default axis is 200: each weight names its axis value, as Core's family does.
@OptIn(ExperimentalTextApi::class)
private val CyclonManrope = FontFamily(
    Font(R.font.cyclon_manrope, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.cyclon_manrope, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.cyclon_manrope, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

/** Core's owner-screen type scale: Manrope throughout, semibold headlines, titles and labels. */
private val CyclonTypography = Typography().run {
    fun TextStyle.m() = copy(fontFamily = CyclonManrope)
    Typography(
        displayLarge = displayLarge.m(), displayMedium = displayMedium.m(), displaySmall = displaySmall.m(),
        headlineLarge = headlineLarge.m(), headlineMedium = headlineMedium.m(),
        headlineSmall = headlineSmall.m().copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.m().copy(fontWeight = FontWeight.SemiBold), titleMedium = titleMedium.m().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.m(), bodyLarge = bodyLarge.m().copy(fontSize = 16.sp, lineHeight = 22.sp),
        bodyMedium = bodyMedium.m().copy(fontSize = 14.sp, lineHeight = 20.sp), bodySmall = bodySmall.m().copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = labelLarge.m().copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp), labelMedium = labelMedium.m(), labelSmall = labelSmall.m(),
    )
}

private val CyclonShapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(8.dp))
private val CyclonButtonShape = RoundedCornerShape(8.dp)

/** Primary button: filled ink with paper text. */
@Composable
fun cyclonPrimaryButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.onSurface,
    contentColor = MaterialTheme.colorScheme.surface
)

/** Text button: ink text, 8dp corners. */
@Composable
fun CyclonTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                     content: @Composable RowScope.() -> Unit) = TextButton(onClick, modifier, enabled,
    shape = CyclonButtonShape, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    content = content)

/** Outlined button: ink text on a hairline, 8dp corners. */
@Composable
fun CyclonOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                         content: @Composable RowScope.() -> Unit) = OutlinedButton(onClick, modifier, enabled,
    shape = CyclonButtonShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    content = content)
