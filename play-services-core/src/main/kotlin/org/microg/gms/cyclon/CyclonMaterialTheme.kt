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
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.google.android.gms.R

/**
 * Compose counterpart of Theme.Cyclon: paper/black surfaces and ink text from the day/night
 * cyclon_* colours, the owner's highlight as primary for on/selected states and progress only.
 * Text uses the bundled Manrope with Core's type scale (OwnerTheme.kt), as Theme.CyclonServices
 * does for the XML screens. Content without a Surface (Cyclon Services screens draw their own
 * paper) still gets ink, and cards and fields have 8dp corners. With [settingsLook] (pages built of
 * CyclonComponents, the password pages) everything takes Android Settings' Words look instead:
 * its page, ink, muted and line colours, ink where the highlight was, Inter Tight (the system
 * family cyclon-words), and dialogs on its sheet with 30dp corners and a 22sp medium title.
 */
@Composable
fun CyclonMaterialTheme(settingsLook: Boolean = false, content: @Composable () -> Unit) {
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
    if (settingsLook) {
        val w = cyclonPage(isSystemInDarkTheme())
        val settings = scheme.copy(
            primary = w.ink, onPrimary = w.onInk, primaryContainer = w.fill2, onPrimaryContainer = w.ink, inversePrimary = w.ink,
            secondary = w.ink, onSecondary = w.onInk, secondaryContainer = w.fill2, onSecondaryContainer = w.ink,
            tertiary = w.ink, onTertiary = w.onInk, tertiaryContainer = w.fill2, onTertiaryContainer = w.ink,
            background = w.page, onBackground = w.ink, surface = w.page, onSurface = w.ink,
            surfaceVariant = w.fill2, onSurfaceVariant = w.muted, surfaceTint = w.page,
            inverseSurface = w.ink, inverseOnSurface = w.page, error = w.red,
            outline = w.faint, outlineVariant = w.line,
            surfaceBright = w.page, surfaceDim = w.page, surfaceContainerLowest = w.page,
            surfaceContainerLow = w.page, surfaceContainer = w.page, surfaceContainerHigh = w.sheet,
            surfaceContainerHighest = w.fill2
        )
        MaterialTheme(colorScheme = settings, shapes = CyclonShapes.copy(extraLarge = RoundedCornerShape(30.dp)),
            typography = SettingsTypography) {
            CompositionLocalProvider(LocalContentColor provides w.ink, content = content)
        }
        return
    }
    MaterialTheme(colorScheme = colors, shapes = CyclonShapes, typography = CyclonTypography) {
        CompositionLocalProvider(LocalContentColor provides ink, content = content)
    }
}

// Inter Tight, as Settings names it (the system family cyclon-words, vendor/cyclon android/branding/fonts); a phone
// without it falls back to the system's sans-serif.
@OptIn(ExperimentalTextApi::class)
private val CyclonWords = FontFamily(
    Font(DeviceFontFamilyName("cyclon-words"), FontWeight.Normal),
    Font(DeviceFontFamilyName("cyclon-words"), FontWeight.Medium),
    Font(DeviceFontFamilyName("cyclon-words"), FontWeight.SemiBold),
)

/**
 * Settings' type in the Words look (values-v36/cyclon_words.xml): Inter Tight throughout, medium titles and labels,
 * a 22sp medium dialog title tracked -0.02em, 13sp section labels and summaries.
 */
private val SettingsTypography = Typography().run {
    fun TextStyle.w() = copy(fontFamily = CyclonWords)
    Typography(
        displayLarge = displayLarge.w(), displayMedium = displayMedium.w(), displaySmall = displaySmall.w(),
        headlineLarge = headlineLarge.w(), headlineMedium = headlineMedium.w(),
        headlineSmall = headlineSmall.w().copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.02).em),
        titleLarge = titleLarge.w().copy(fontWeight = FontWeight.Medium), titleMedium = titleMedium.w().copy(fontWeight = FontWeight.Medium),
        titleSmall = titleSmall.w(), bodyLarge = bodyLarge.w().copy(fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.01).em),
        bodyMedium = bodyMedium.w().copy(fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = 0.em),
        bodySmall = bodySmall.w().copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.em),
        labelLarge = labelLarge.w().copy(fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 0.em),
        labelMedium = labelMedium.w(), labelSmall = labelSmall.w(),
    )
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
