/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import com.google.android.gms.R

/**
 * Compose counterpart of Theme.Cyclon: paper/black surfaces and ink text from the day/night
 * cyclon_* colours, the owner's highlight as primary for on/selected states and progress only.
 * Text uses the system sans-serif (Manrope on Cyclon OS).
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
    MaterialTheme(colorScheme = colors, content = content)
}

/** Primary button: filled ink with paper text. */
@Composable
fun cyclonPrimaryButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.onSurface,
    contentColor = MaterialTheme.colorScheme.surface
)
