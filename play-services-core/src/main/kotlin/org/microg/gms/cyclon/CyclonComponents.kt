/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.google.android.gms.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The building blocks of Cyclon Services' own Compose pages, in the look of Android's Settings on Cyclon OS (Settings'
 * "Words" look, packages/apps/Settings res/values/cyclon_words.xml and values-v36): Settings' solid page, flat rows 28dp from the
 * screen's edges with a faint line only between two rows of a group, muted 13sp section labels, a sub-page head of
 * Settings' thin back mark and the 20dp title in one row, Settings' ink pill switches and pill buttons. Inter Tight
 * (the system family cyclon-words) throughout. Use inside [CyclonMaterialTheme] with settingsLook on.
 */

/** Where words start on a page: Settings' edge inset (settingslib_cyclon_row_padding) for rows, lines and labels. */
val CyclonTextInset = 28.dp
/** A text field's edge on a page: the same inset as the rows, as Settings' search field. */
val CyclonFieldInset = CyclonTextInset
private val PillShape = RoundedCornerShape(percent = 50)

/** Where words start here: [CyclonTextInset] on a page, less in a dialog that pads itself. */
val LocalCyclonInset = staticCompositionLocalOf { CyclonTextInset }

/** The day/night tokens of [CyclonMaterialTheme], under Core's names; [line] is the faint line between rows. */
class CyclonPalette(val paper: Color, val ink: Color, val muted: Color, val line: Color, val mid: Color, val cell: Color,
                    val highlight: Color, val onHighlight: Color, val error: Color)

/**
 * Settings' Words colours (cyclon_words.xml, kit.html .light/.dark .ph): [page] under everything, [fill] and [fill2]
 * for grey pills and tracks, [line] between rows, [sheet] for dialogs. Settings uses ink where Android uses the
 * highlight (switches, radios, progress).
 */
class CyclonPage internal constructor(
    val dark: Boolean, val page: Color, val ink: Color, val onInk: Color, val muted: Color, val faint: Color, val fill: Color,
    val fill2: Color, val line: Color, val sheet: Color, val red: Color,
) {
    /** Dialogs are Settings' sheet. */
    val dialog get() = sheet
}

private val NightPage = CyclonPage(dark = true, page = Color(0xFF0B0B0D), ink = Color(0xFFF2F2F2), onInk = Color.Black,
    muted = Color(0xFF9D9D9E), faint = Color(0xFF59595A), fill = Color(0xFF1F1F20), fill2 = Color(0xFF2D2D2F),
    line = Color(0xFF232325), sheet = Color(0xFF17171B), red = Color(0xFFFF6B5E))

private val DayPage = CyclonPage(dark = false, page = Color(0xFFF5F4F0), ink = Color(0xFF151515), onInk = Color.White,
    muted = Color(0xFF5C5C5C), faint = Color(0xFFA3A3A3), fill = Color(0xFFE9E8E4), fill2 = Color(0xFFDFDEDA),
    line = Color(0xFFDFDEDA), sheet = Color(0xFFFCFCFA), red = Color(0xFFD7392B))

internal fun cyclonPage(dark: Boolean) = if (dark) NightPage else DayPage

object Cyclon {
    val palette: CyclonPalette @Composable get() {
        val c = MaterialTheme.colorScheme
        val g = page
        return CyclonPalette(paper = c.surface, ink = c.onSurface, muted = c.onSurfaceVariant, line = g.line, mid = g.faint,
            cell = g.fill2, highlight = c.primary, onHighlight = c.onPrimary, error = c.error)
    }
    val page: CyclonPage @Composable get() = cyclonPage(MaterialTheme.colorScheme.surface.luminance() < .5f)
}

/** Settings' page colour, also behind the system bars (targetSdk 29 keeps them out of the window). */
@Composable
fun CyclonSettingsPage(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val page = Cyclon.page
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        (context as? Activity)?.window?.let { it.statusBarColor = page.page.toArgb(); it.navigationBarColor = page.page.toArgb() }
    }
    Box(modifier.background(page.page), content = content)
}

/**
 * One group of rows, as a Settings category: flat on the page, a faint line between two consecutive rows, inset like
 * the row text, none above the first or under the last. Each direct child is a row; a row and the actions under it go
 * in one Column.
 */
@Composable
fun CyclonGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val line = Cyclon.page.line
    var rules by remember { mutableStateOf(emptyList<Int>()) }
    Layout(content = { CompositionLocalProvider(LocalCyclonInset provides CyclonTextInset, content = content) },
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp).drawBehind {
            val start = CyclonTextInset.toPx()
            rules.forEach { y -> drawRect(line, Offset(start, y.toFloat()), Size(size.width - 2 * start, 1.dp.toPx())) }
        }) { measurables, constraints ->
        val rows = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        layout(constraints.maxWidth, constraints.constrainHeight(rows.sumOf { it.height })) {
            var y = 0
            val tops = ArrayList<Int>()
            rows.forEach { row ->
                if (row.height > 0 && y > 0) tops += y
                row.placeRelative(0, y); y += row.height
            }
            if (tops != rules) rules = tops
        }
    }
}

/** Cyclon's voice for small status lines and glyphs: the page's font, muted unless said otherwise. */
@Composable
fun CyclonVoice(text: String, modifier: Modifier = Modifier, color: Color = Cyclon.palette.muted, size: Int = 13) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall.copy(fontSize = size.sp, lineHeight = (size + 6).sp), color = color)
}

/** A group's label, as Settings' section label: 13sp regular in the muted colour where the row text starts. */
@Composable
fun CyclonSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().padding(start = CyclonTextInset, end = CyclonTextInset, top = 24.dp, bottom = 6.dp)
        .semantics { heading() }, style = MaterialTheme.typography.bodySmall, color = Cyclon.palette.muted)
}

/** The faint line between rows, for dialogs and rows outside a group; groups draw their own. */
@Composable
fun CyclonHairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Cyclon.palette.line))
}

/** Muted explanatory text between groups, under a group label or a page title. */
@Composable
fun CyclonNote(text: String, modifier: Modifier = Modifier, color: Color = Cyclon.palette.muted, inset: Dp = LocalCyclonInset.current) {
    Text(text, modifier.padding(horizontal = inset, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = color)
}

// Settings' row type (TextAppearance.SettingsLib.PreferenceTitle/Summary): an 18sp regular title, a 13sp muted summary.
private val RowTitle @Composable get() = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 24.sp,
    fontWeight = FontWeight.Normal, letterSpacing = (-0.01).em)
private val RowSummary @Composable get() = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.em)

/**
 * One row, as a Settings preference: at least 54dp, optional [leading] (an icon), [title], an optional muted [summary]
 * and [trailing] (a switch, an action). The whole row is the click target when [onClick] is set. Text wraps in full by
 * default; [divider] draws the faint line under it where no group does (a dialog's list).
 */
@Composable
fun CyclonRow(
    title: String, summary: String? = null, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null, trailing: (@Composable RowScope.() -> Unit)? = null, divider: Boolean = false,
    summaryLines: Int = Int.MAX_VALUE, titleColor: Color = Cyclon.palette.ink, inset: Dp = LocalCyclonInset.current,
) {
    val p = Cyclon.palette
    Column(modifier.fillMaxWidth()) {
        Row(
            (if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
                .fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = inset, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(title, style = RowTitle, color = if (enabled) titleColor else p.muted)
                summary?.let { Text(it, style = RowSummary, color = p.muted, maxLines = summaryLines, overflow = TextOverflow.Ellipsis) }
            }
            trailing?.invoke(this)
        }
        if (divider) CyclonHairline(Modifier.padding(horizontal = inset))
    }
}

/** A row whose switch is the row: tapping anywhere toggles it. */
@Composable
fun CyclonSwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true,
                    inset: Dp = LocalCyclonInset.current, divider: Boolean = false) {
    CyclonRow(title, summary, Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        enabled = enabled, inset = inset, divider = divider, trailing = { CyclonSwitch(checked, null, enabled) })
}

/**
 * Settings' pill switch (color-v36/settingslib_expressive_color_switch_*): an ink track with an on-ink thumb when on,
 * a fill2 track with an ink thumb when off, no outline, the thumb its full size in both states and without a glyph.
 */
@Composable
fun CyclonSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    val g = Cyclon.page
    Switch(checked, onChange, enabled = enabled, thumbContent = {}, colors = SwitchDefaults.colors(
        checkedTrackColor = g.ink, checkedThumbColor = g.onInk, checkedBorderColor = Color.Transparent,
        uncheckedTrackColor = g.fill2, uncheckedThumbColor = g.ink, uncheckedBorderColor = Color.Transparent,
        disabledCheckedTrackColor = g.ink.copy(alpha = .38f), disabledCheckedThumbColor = g.onInk,
        disabledCheckedBorderColor = Color.Transparent, disabledUncheckedTrackColor = g.fill2.copy(alpha = .38f),
        disabledUncheckedThumbColor = g.ink.copy(alpha = .38f), disabledUncheckedBorderColor = Color.Transparent,
    ))
}

/** One choice of a set: a ring that fills with ink when selected, as Settings' radios, a title and an explanation. */
@Composable
fun CyclonChoiceRow(title: String, explanation: String?, selected: Boolean, enabled: Boolean = true, inset: Dp = LocalCyclonInset.current,
                    onSelect: () -> Unit) {
    val p = Cyclon.palette
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
        .heightIn(min = 54.dp).padding(horizontal = inset, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val ring = if (selected) p.highlight else p.mid
        Box(Modifier.size(20.dp).clip(CircleShape).border(BorderStroke(1.5.dp, ring), CircleShape),
            contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(p.highlight))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = RowTitle, color = if (enabled) p.ink else p.muted)
            explanation?.let { Text(it, style = RowSummary, color = p.muted) }
        }
    }
}

// Settings' buttons (.btn): 54dp pills with 17sp medium labels.
private val ButtonLabel @Composable get() = MaterialTheme.typography.labelLarge.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)

/** The primary action: Settings' ink pill with on-ink text. */
@Composable
fun CyclonInkButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = Cyclon.palette
    Box(modifier.clip(PillShape).background(if (enabled) p.ink else p.cell)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 54.dp).padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = ButtonLabel, color = if (enabled) p.paper else p.muted)
    }
}

/** A secondary action: Settings' grey pill (fill2) with ink text and no outline. */
@Composable
fun CyclonSecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = Cyclon.palette
    Box(modifier.clip(PillShape).background(Cyclon.page.fill2)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 54.dp).padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = ButtonLabel, color = if (enabled) p.ink else p.muted)
    }
}

/** A quiet action, as Settings' dialog buttons: 16sp medium ink text only ([color] for a destructive one). */
@Composable
fun CyclonTextAction(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Cyclon.palette.ink,
                     onClick: () -> Unit) {
    Box(modifier.clip(PillShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 48.dp)
        .padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
            color = if (enabled) color else Cyclon.palette.mid)
    }
}

/** Settings' rows show no mark for opening a page: kept so callers need not change, it draws nothing. */
@Composable
fun CyclonChevron() = Unit

/**
 * A page's head, as a Settings sub-page: Settings' thin back mark (read as [backLabel]) and the title in one 56dp row,
 * 20dp medium as Settings' collapsed title, after an optional [leading] (an icon), optional [trailing] at the end (a
 * count), then an optional muted [status] under it (the account a page is about). Titles wrap rather than truncate.
 */
@Composable
fun CyclonTopBar(title: String, backLabel: String, onBack: () -> Unit, status: String? = null,
                 leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val p = Cyclon.palette
    // dp, not sp, as Settings' page titles: they don't grow with the font scale.
    val style = with(LocalDensity.current) {
        MaterialTheme.typography.titleLarge.copy(fontSize = 20.dp.toSp(), lineHeight = 26.dp.toSp(), fontWeight = FontWeight.Medium,
            letterSpacing = (-0.02).em)
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        // Settings' toolbar: the 40dp back target 23dp from the edge, the title at 84dp.
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 23.dp, end = CyclonTextInset),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).semantics { contentDescription = backLabel }
                .clickable(role = Role.Button, onClick = onBack), contentAlignment = Alignment.Center) {
                CyclonBackMark(p.muted)
            }
            Spacer(Modifier.size(21.dp))
            leading?.let { Box(Modifier.padding(end = 12.dp)) { it() } }
            Text(title, Modifier.weight(1f).padding(vertical = 8.dp).semantics { heading() }, style = style, color = p.ink)
            trailing?.let { Box(Modifier.padding(start = 12.dp)) { it() } }
        }
        status?.let {
            Text(it, Modifier.padding(start = CyclonTextInset, end = CyclonTextInset, top = 4.dp),
                style = MaterialTheme.typography.bodyMedium, color = p.muted)
        }
    }
}

/** Settings' back mark (settingslib_expressive_icon_back): a thin chevron, 20dp, 1.6dp round strokes. */
@Composable
private fun CyclonBackMark(color: Color) {
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Canvas(Modifier.size(20.dp)) {
        val u = size.width / 20f
        fun x(v: Float) = if (rtl) size.width - v * u else v * u
        val path = androidx.compose.ui.graphics.Path().apply { moveTo(x(12.5f), 4f * u); lineTo(x(6.5f), 10f * u); lineTo(x(12.5f), 16f * u) }
        drawPath(path, color, style = Stroke(1.6f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/**
 * One row of tabs. Settings has none; they are its pills: a fill capsule holding a pill per tab, the current one ink,
 * where the rows start. Every tab keeps Tab semantics (selected state and click).
 */
@Composable
fun CyclonTabs(labels: List<String>, selected: Int, enabled: (Int) -> Boolean = { true }, onSelect: (Int) -> Unit) {
    val p = Cyclon.palette
    Row(Modifier.fillMaxWidth().padding(horizontal = CyclonTextInset, vertical = 8.dp).clip(PillShape).background(Cyclon.page.fill)
        .padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            val usable = enabled(index)
            Box(Modifier.weight(1f).clip(PillShape).background(if (on) p.ink else Color.Transparent)
                .selectable(on, enabled = usable, role = Role.Tab, onClick = { onSelect(index) })
                .heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = 2,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = if (on) p.paper else if (usable) p.ink else p.muted)
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
    // Keyed, so a row reused for another app or a website starts from the letter again, never the old icon.
    var icon by remember(packageName, px) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName, px) {
        if (packageName != null) icon = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also {
                    drawable.setBounds(0, 0, px, px)
                    drawable.draw(android.graphics.Canvas(it))
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

/**
 * The search field of a list page, as Settings' search card: unfilled, outlined with the faint line, half its height
 * round; one line, a search glyph and [placeholder] in place of a floating label.
 */
@Composable
fun CyclonSearchField(query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val p = Cyclon.palette
    OutlinedTextField(query, onQuery, modifier, singleLine = true, shape = PillShape,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.ink, unfocusedBorderColor = Cyclon.page.fill2,
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            cursorColor = p.ink, focusedTextColor = p.ink, unfocusedTextColor = p.ink, unfocusedPlaceholderColor = p.muted,
            focusedPlaceholderColor = p.muted),
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = p.muted) })
}

/**
 * A round 48dp icon action beside a field: [filled] with ink for the primary one, Settings' grey (fill2) otherwise.
 * [label] is what TalkBack reads, as the icon has no text.
 */
@Composable
fun CyclonIconAction(icon: ImageVector, label: String, filled: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val p = Cyclon.palette
    Box(Modifier.size(48.dp).then(if (filled) Modifier.clip(CircleShape).background(if (enabled) p.ink else p.cell)
            else Modifier.clip(CircleShape).background(Cyclon.page.fill2))
        .semantics { contentDescription = label }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = if (!enabled) p.muted else if (filled) p.paper else p.ink)
    }
}

/** A phone outline in muted ink, 24dp: "this device" where an account would show its monogram. */
@Composable
fun CyclonDeviceGlyph() {
    val color = Cyclon.palette.muted
    Canvas(Modifier.size(24.dp)) {
        val stroke = 1.5.dp.toPx()
        val w = 12.dp.toPx(); val h = 19.dp.toPx()
        val left = (size.width - w) / 2; val top = (size.height - h) / 2
        drawRoundRect(color, Offset(left, top), Size(w, h),
            CornerRadius(2.dp.toPx()), style = Stroke(stroke))
        val y = top + h - 3.dp.toPx()
        drawLine(color, Offset(size.width / 2 - 2.dp.toPx(), y),
            Offset(size.width / 2 + 2.dp.toPx(), y), stroke)
    }
}

/** Text fields in ink: the outline and label turn ink when focused, Settings' faint grey otherwise. */
@Composable
fun cyclonFieldColors(): TextFieldColors {
    val p = Cyclon.palette
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = p.ink, unfocusedBorderColor = p.mid, focusedLabelColor = p.ink, unfocusedLabelColor = p.muted,
        cursorColor = p.ink, focusedTextColor = p.ink, unfocusedTextColor = p.ink,
    )
}

/** A thin ink bar while something loads, as Settings' progress; keeps its 2dp when idle, so content does not jump. */
@Composable
fun CyclonProgress(busy: Boolean, modifier: Modifier = Modifier) {
    val p = Cyclon.palette
    Box(modifier.fillMaxWidth().height(2.dp)) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = p.highlight, trackColor = p.line)
    }
}

