/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The building blocks of Core's owner screens (Cyclon android/Core OwnerTheme.kt, design draft 4) for Cyclon Services'
 * own Compose pages, so Passwords looks like Assistant access: paper, ink text, hairline rows instead of cards, Space
 * Mono for section labels and status, the owner's highlight only for what is on or selected, the primary action filled
 * with ink. Use inside [CyclonMaterialTheme].
 */

internal val CyclonSpaceMono = FontFamily(Font(R.font.cyclon_space_mono_regular), Font(R.font.cyclon_space_mono_bold, FontWeight.Bold))

/** The day/night tokens of [CyclonMaterialTheme], under Core's names. */
class CyclonPalette(val paper: Color, val ink: Color, val muted: Color, val line: Color, val mid: Color, val cell: Color,
                    val highlight: Color, val onHighlight: Color, val error: Color)

object Cyclon {
    val palette: CyclonPalette @Composable get() {
        val c = MaterialTheme.colorScheme
        return CyclonPalette(paper = c.surface, ink = c.onSurface, muted = c.onSurfaceVariant, line = c.outlineVariant,
            mid = colorResource(R.color.cyclon_mid), cell = c.surfaceVariant, highlight = c.primary, onHighlight = c.onPrimary,
            error = c.error)
    }
}

/** Cyclon's voice: Space Mono, small, muted unless said otherwise. Section labels and status lines. */
@Composable
fun CyclonVoice(text: String, modifier: Modifier = Modifier, color: Color = Cyclon.palette.muted, size: Int = 13) {
    Text(text, modifier, style = TextStyle(fontFamily = CyclonSpaceMono, fontSize = size.sp, lineHeight = (size + 6).sp, color = color))
}

/** A section's label above its rows, with the hairline that starts the section. */
@Composable
fun CyclonSectionLabel(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 28.dp)) {
        CyclonVoice(text.lowercase(), Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { heading() })
        CyclonHairline()
    }
}

@Composable
fun CyclonHairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Cyclon.palette.line))
}

/** Muted explanatory text under a section label or a page title. */
@Composable
fun CyclonNote(text: String, modifier: Modifier = Modifier, color: Color = Cyclon.palette.muted) {
    Text(text, modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = color)
}

/**
 * One hairline row: optional [leading] (an icon), [title], an optional muted [summary] and [trailing] (a switch, a
 * chevron, an action). The whole row is the click target when [onClick] is set. Text wraps in full by default.
 */
@Composable
fun CyclonRow(
    title: String, summary: String? = null, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null, trailing: (@Composable RowScope.() -> Unit)? = null, divider: Boolean = true,
    summaryLines: Int = Int.MAX_VALUE, titleColor: Color = Cyclon.palette.ink, inset: Dp = 20.dp,
) {
    val p = Cyclon.palette
    Column(modifier.fillMaxWidth()) {
        Row(
            (if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
                .fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = inset, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) titleColor else p.muted)
                summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = summaryLines, overflow = TextOverflow.Ellipsis) }
            }
            trailing?.invoke(this)
        }
        if (divider) CyclonHairline(Modifier.padding(start = inset))
    }
}

/** A row whose switch is the row: tapping anywhere toggles it. On is the highlight. */
@Composable
fun CyclonSwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true,
                    inset: Dp = 20.dp, divider: Boolean = true) {
    CyclonRow(title, summary, Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        enabled = enabled, inset = inset, divider = divider, trailing = { CyclonSwitch(checked, null, enabled) })
}

@Composable
fun CyclonSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    val p = Cyclon.palette
    Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(
        checkedTrackColor = p.highlight, checkedThumbColor = p.onHighlight, checkedBorderColor = p.highlight,
        uncheckedTrackColor = p.paper, uncheckedThumbColor = p.mid, uncheckedBorderColor = p.mid,
    ))
}

/** One choice of a set: a ring that fills with the highlight when selected, a title and an explanation. */
@Composable
fun CyclonChoiceRow(title: String, explanation: String?, selected: Boolean, enabled: Boolean = true, inset: Dp = 20.dp,
                    onSelect: () -> Unit) {
    val p = Cyclon.palette
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
        .padding(horizontal = inset, vertical = 12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val ring = if (selected) p.highlight else p.mid
        Box(Modifier.padding(top = 2.dp).size(20.dp).clip(CircleShape).border(BorderStroke(1.5.dp, ring), CircleShape),
            contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(p.highlight))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) p.ink else p.muted)
            explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.muted) }
        }
    }
}

/** The primary action: filled with ink, paper text. */
@Composable
fun CyclonInkButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = Cyclon.palette
    Box(modifier.clip(PillShape).background(if (enabled) p.ink else p.cell)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) p.paper else p.muted)
    }
}

/** A secondary action: hairline outline in mid, ink text. */
@Composable
fun CyclonLineButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = Cyclon.palette
    Box(modifier.clip(PillShape).border(BorderStroke(1.dp, p.mid), PillShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) p.ink else p.muted)
    }
}

/** A quiet action: ink text only ([color] for a destructive one). */
@Composable
fun CyclonTextAction(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Cyclon.palette.ink,
                     onClick: () -> Unit) {
    Box(modifier.clip(PillShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 48.dp)
        .padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) color else Cyclon.palette.mid)
    }
}

/** A trailing "›" for rows that open a page. */
@Composable
fun CyclonChevron() = CyclonVoice("›", color = Cyclon.palette.muted, size = 20)

/** The "‹  Back" action at the top of a page; [label] is the back target's name. */
@Composable
fun CyclonBack(label: String, onBack: () -> Unit) {
    val p = Cyclon.palette
    Row(Modifier.padding(start = 4.dp).clip(PillShape).clickable(role = Role.Button, onClick = onBack).heightIn(min = 48.dp)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("‹", style = MaterialTheme.typography.labelLarge, color = p.ink)
        Text(label, style = MaterialTheme.typography.labelLarge, color = p.ink)
    }
}

/** A page's large title (Assistant access's size) with an optional Space Mono status line under it. */
@Composable
fun CyclonTitle(title: String, status: String? = null, leading: (@Composable () -> Unit)? = null) {
    val p = Cyclon.palette
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 30.sp, lineHeight = 36.sp), color = p.ink)
            status?.let { CyclonVoice(it, Modifier.padding(top = 6.dp), color = p.ink, size = 14) }
        }
    }
}

/**
 * One row of tabs: each a selectable pill, the current one filled with ink. Replaces Material's underlined TabRow on
 * Cyclon pages; every tab keeps Tab semantics (selected state and click).
 */
@Composable
fun CyclonTabs(labels: List<String>, selected: Int, enabled: (Int) -> Boolean = { true }, onSelect: (Int) -> Unit) {
    val p = Cyclon.palette
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            val usable = enabled(index)
            Box(Modifier.weight(1f).clip(PillShape).background(if (on) p.ink else Color.Transparent)
                .border(BorderStroke(1.dp, if (on) p.ink else p.line), PillShape)
                .selectable(on, enabled = usable, role = Role.Tab, onClick = { onSelect(index) })
                .heightIn(min = 40.dp).padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, color = if (on) p.paper else if (usable) p.ink else p.muted)
            }
        }
    }
}

/**
 * An app's real icon when [packageName] is installed, drawn once off the main thread; otherwise (a website, or while
 * loading) the first letter of [label] in a cell circle, as Core's AppIcon.
 */
@Composable
fun CyclonMonogram(label: String, packageName: String? = null, size: Dp = 36.dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, packageName, px) {
        if (packageName != null) value = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also {
                    drawable.setBounds(0, 0, px, px)
                    drawable.draw(Canvas(it))
                }.asImageBitmap()
            }.getOrNull()
        }
    }
    val p = Cyclon.palette
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        val bitmap = icon
        if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(size))
        else Box(Modifier.size(size).clip(CircleShape).background(p.cell), contentAlignment = Alignment.Center) {
            Text(label.trim().take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = p.muted)
        }
    }
}

/** Text fields in ink: the outline and label turn ink when focused, mid otherwise. */
@Composable
fun cyclonFieldColors(): TextFieldColors {
    val p = Cyclon.palette
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = p.ink, unfocusedBorderColor = p.mid, focusedLabelColor = p.ink, unfocusedLabelColor = p.muted,
        cursorColor = p.ink, focusedTextColor = p.ink, unfocusedTextColor = p.ink,
    )
}

/** A thin highlight bar while something loads; keeps its 2dp when idle, so content does not jump. */
@Composable
fun CyclonProgress(busy: Boolean, modifier: Modifier = Modifier) {
    val p = Cyclon.palette
    Box(modifier.fillMaxWidth().height(2.dp)) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = p.highlight, trackColor = p.line)
    }
}

private val PillShape = RoundedCornerShape(24.dp)
