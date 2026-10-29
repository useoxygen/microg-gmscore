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
 * The building blocks of Cyclon Services' own Compose pages, in the launcher's Chat only look (cyclon simulator
 * words.css, "Chat only: the Glass sheet" and "AI settings on glass"): a page of Home's deep violet (soft grey by day),
 * what it holds on islands of one glass 10dp from the screen's edges with the words 32dp from them, sentence-case group
 * labels above the islands, a large title under a round back button, pills for actions, the owner's highlight only for
 * what is on or selected. No blur: the glass is a still gradient with a light top edge. Use inside
 * [CyclonMaterialTheme] with glass on.
 */

/** Where words start on a page: the title, group labels, notes and the words inside islands. */
val CyclonTextInset = 32.dp
/** A text field's edge on a page, so its words (16dp inside) start at [CyclonTextInset]. */
val CyclonFieldInset = CyclonTextInset - 16.dp
private val IslandMargin = 10.dp
private val IslandPadding = CyclonTextInset - IslandMargin
private val IslandShape = RoundedCornerShape(26.dp)
private val PillShape = RoundedCornerShape(percent = 50)
private val GlassShadow = Color(0xFF1E1E3C)

/** Where words start here: [CyclonTextInset] on the page, an island's padding inside a [CyclonGroup]. */
val LocalCyclonInset = staticCompositionLocalOf { CyclonTextInset }

/** The day/night tokens of [CyclonMaterialTheme], under Core's names; [line] is the glass's rule. */
class CyclonPalette(val paper: Color, val ink: Color, val muted: Color, val line: Color, val mid: Color, val cell: Color,
                    val highlight: Color, val onHighlight: Color, val error: Color)

/**
 * Chat only's glass (words.css --gs-*) over a flat page instead of the wallpaper it tints, so night islands are a
 * deeper tint than the launcher's to still read as islands, and day islands near-white with the light shadow.
 */
class CyclonGlass internal constructor(
    val dark: Boolean, val pageTop: Color, val pageBottom: Color, val island: Brush, val rim: Color, val edge: Color,
    val pill: Brush, val pillRim: Color, val pillEdge: Color, val rule: Color, val dialog: Color,
)

private val NightGlass = CyclonGlass(dark = true, pageTop = Color(0xFF221839), pageBottom = Color(0xFF191130),
    island = Brush.verticalGradient(listOf(Color(10, 6, 28, 118), Color(8, 4, 22, 148))),
    rim = Color.White.copy(alpha = .14f), edge = Color.White.copy(alpha = .06f),
    pill = Brush.verticalGradient(listOf(Color.White.copy(alpha = .17f), Color.White.copy(alpha = .09f))),
    pillRim = Color.White.copy(alpha = .24f), pillEdge = Color.White.copy(alpha = .1f),
    rule = Color.White.copy(alpha = .14f), dialog = Color(0xFF1B1231))

private val DayGlass = CyclonGlass(dark = false, pageTop = Color(0xFFECECF1), pageBottom = Color(0xFFF4F4F7),
    island = SolidColor(Color.White), rim = Color.White, edge = Color.White, pill = SolidColor(Color.White),
    pillRim = Color.White, pillEdge = Color.White, rule = Color(30, 30, 60, 26), dialog = Color(0xFFFBFAF8))

internal fun cyclonGlass(dark: Boolean) = if (dark) NightGlass else DayGlass

object Cyclon {
    val palette: CyclonPalette @Composable get() {
        val c = MaterialTheme.colorScheme
        val g = glass
        // On violet glass Core's greys vanish: rings, outlines and cells are white at low alpha instead.
        return CyclonPalette(paper = c.surface, ink = c.onSurface, muted = c.onSurfaceVariant, line = g.rule,
            mid = if (g.dark) Color.White.copy(alpha = .3f) else colorResource(R.color.cyclon_mid),
            cell = if (g.dark) Color.White.copy(alpha = .1f) else c.surfaceVariant, highlight = c.primary,
            onHighlight = c.onPrimary, error = c.error)
    }
    val glass: CyclonGlass @Composable get() = cyclonGlass(MaterialTheme.colorScheme.surface.luminance() < .5f)
}

/** Lays [shape] in the glass: an island's tint, or a pill's sheen; the top edge catches the light, day keeps a shadow. */
fun Modifier.cyclonGlass(glass: CyclonGlass, shape: Shape, pill: Boolean = false): Modifier =
    (if (glass.dark) this else shadow(if (pill) 3.dp else 6.dp, shape, ambientColor = GlassShadow, spotColor = GlassShadow))
        .clip(shape).background(if (pill) glass.pill else glass.island, shape)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val rim = Brush.verticalGradient(listOf(if (pill) glass.pillRim else glass.rim, if (pill) glass.pillEdge else glass.edge),
                startY = 0f, endY = minOf(size.height / 2, 24.dp.toPx()))
            // Twice the width, half of it clipped away: a 1dp edge inside the shape.
            val stroke = Stroke(2.dp.toPx())
            onDrawBehind { drawOutline(outline, rim, style = stroke) }
        }

/**
 * The page under the islands: Home's violet by night, soft grey by day, also behind the system bars (targetSdk 29
 * keeps them out of the window, so they take the page's ends).
 */
@Composable
fun CyclonGlassPage(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val glass = Cyclon.glass
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        (context as? Activity)?.window?.let { it.statusBarColor = glass.pageTop.toArgb(); it.navigationBarColor = glass.pageBottom.toArgb() }
    }
    Box(modifier.background(Brush.verticalGradient(listOf(glass.pageTop, glass.pageBottom))), content = content)
}

/**
 * One island: a group's rows on the glass, a faint rule between consecutive rows. Each direct child is a row; a row
 * and the actions under it go in one Column.
 */
@Composable
fun CyclonGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val glass = Cyclon.glass
    var rules by remember { mutableStateOf(emptyList<Int>()) }
    Layout(content = { CompositionLocalProvider(LocalCyclonInset provides IslandPadding, content = content) },
        modifier = modifier.fillMaxWidth().padding(horizontal = IslandMargin, vertical = 6.dp).cyclonGlass(glass, IslandShape)
            .padding(vertical = 2.dp).drawBehind {
                val start = IslandPadding.toPx()
                rules.forEach { y -> drawRect(glass.rule, Offset(start, y.toFloat()), Size(size.width - 2 * start, 1.dp.toPx())) }
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

/** A group's label above its island: sentence case at the page's text inset, the ink a little faded. */
@Composable
fun CyclonSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().padding(start = CyclonTextInset, end = CyclonTextInset, top = 20.dp, bottom = 4.dp)
        .semantics { heading() }, style = MaterialTheme.typography.bodySmall, color = Cyclon.palette.ink.copy(alpha = .72f))
}

/** The glass's rule, for dialogs; islands draw their own between rows. */
@Composable
fun CyclonHairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Cyclon.palette.line))
}

/** Muted explanatory text between islands, under a group label or a page title. */
@Composable
fun CyclonNote(text: String, modifier: Modifier = Modifier, color: Color = Cyclon.palette.muted, inset: Dp = LocalCyclonInset.current) {
    Text(text, modifier.padding(horizontal = inset, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = color)
}

private val RowTitle @Composable get() = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 23.sp)
private val RowSummary @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 19.sp)

/**
 * One row of an island: optional [leading] (an icon), [title], an optional muted [summary] and [trailing] (a switch, a
 * chevron, an action). The whole row is the click target when [onClick] is set. Text wraps in full by default;
 * [divider] draws a rule under it where no island does (a dialog's list).
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
                .fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = inset, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(title, style = RowTitle, color = if (enabled) titleColor else p.muted)
                summary?.let { Text(it, style = RowSummary, color = p.muted, maxLines = summaryLines, overflow = TextOverflow.Ellipsis) }
            }
            trailing?.invoke(this)
        }
        if (divider) CyclonHairline(Modifier.padding(start = inset))
    }
}

/** A row whose switch is the row: tapping anywhere toggles it. On is the highlight. */
@Composable
fun CyclonSwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true,
                    inset: Dp = LocalCyclonInset.current, divider: Boolean = false) {
    CyclonRow(title, summary, Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        enabled = enabled, inset = inset, divider = divider, trailing = { CyclonSwitch(checked, null, enabled) })
}

@Composable
fun CyclonSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    val p = Cyclon.palette
    Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(
        checkedTrackColor = p.highlight, checkedThumbColor = p.onHighlight, checkedBorderColor = p.highlight,
        uncheckedTrackColor = Color.Transparent, uncheckedThumbColor = p.mid, uncheckedBorderColor = p.mid,
    ))
}

/** One choice of a set: a ring that fills with the highlight when selected, a title and an explanation. */
@Composable
fun CyclonChoiceRow(title: String, explanation: String?, selected: Boolean, enabled: Boolean = true, inset: Dp = LocalCyclonInset.current,
                    onSelect: () -> Unit) {
    val p = Cyclon.palette
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
        .heightIn(min = 52.dp).padding(horizontal = inset, vertical = 12.dp), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val ring = if (selected) p.highlight else p.mid
        Box(Modifier.padding(top = 2.dp).size(20.dp).clip(CircleShape).border(BorderStroke(1.5.dp, ring), CircleShape),
            contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(p.highlight))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = RowTitle, color = if (enabled) p.ink else p.muted)
            explanation?.let { Text(it, style = RowSummary, color = p.muted) }
        }
    }
}

private val ButtonLabel @Composable get() = MaterialTheme.typography.labelLarge.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)

/** The primary action: a pill filled with ink, paper text. */
@Composable
fun CyclonInkButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = Cyclon.palette
    Box(modifier.clip(PillShape).background(if (enabled) p.ink else p.cell)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 50.dp).padding(horizontal = 24.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = ButtonLabel, color = if (enabled) p.paper else p.muted)
    }
}

/** A secondary action: a pill of the glass's sheen, ink text. */
@Composable
fun CyclonGlassButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = Cyclon.palette
    Box(modifier.cyclonGlass(Cyclon.glass, PillShape, pill = true)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 50.dp).padding(horizontal = 24.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = ButtonLabel, color = if (enabled) p.ink else p.muted)
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

/**
 * A page's head: a round glass back button (read as [backLabel]) with optional [trailing] across from it (a count),
 * then the title as the page's heading in [titleSize], after an optional [leading] (an icon), and an optional muted
 * [status] under it (the account a page is about). Titles wrap rather than truncate.
 */
@Composable
fun CyclonTopBar(title: String, backLabel: String, onBack: () -> Unit, status: String? = null, titleSize: Int = 34,
                 leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val p = Cyclon.palette
    val style = MaterialTheme.typography.headlineLarge.copy(fontSize = titleSize.sp, lineHeight = 1.1.em, fontWeight = FontWeight.Medium,
        letterSpacing = (-0.035).em)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = IslandMargin, end = CyclonTextInset), verticalAlignment = Alignment.CenterVertically) {
            // 46dp as Chat only's; Compose still takes touches over 48dp.
            Box(Modifier.size(46.dp).cyclonGlass(Cyclon.glass, CircleShape).semantics { contentDescription = backLabel }
                .clickable(role = Role.Button, onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, Modifier.size(28.dp), tint = p.ink)
            }
            Spacer(Modifier.weight(1f))
            trailing?.invoke()
        }
        // An icon lines up with the title's first line, also when a long title wraps.
        val firstLine = with(LocalDensity.current) { (titleSize * 1.1f).sp.toDp() }
        Row(Modifier.fillMaxWidth().padding(start = CyclonTextInset, end = CyclonTextInset, top = 18.dp)) {
            leading?.let { Box(Modifier.height(firstLine).padding(end = 12.dp), contentAlignment = Alignment.Center) { it() } }
            Text(title, Modifier.weight(1f).semantics { heading() }, style = style, color = p.ink)
        }
        status?.let {
            Text(it, Modifier.padding(start = CyclonTextInset, end = CyclonTextInset, top = 6.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp), color = p.muted)
        }
    }
}

/**
 * One row of tabs: a capsule of the glass holding a pill per tab, the current one filled with ink. Replaces Material's
 * underlined TabRow on Cyclon pages; every tab keeps Tab semantics (selected state and click).
 */
@Composable
fun CyclonTabs(labels: List<String>, selected: Int, enabled: (Int) -> Boolean = { true }, onSelect: (Int) -> Unit) {
    val p = Cyclon.palette
    Row(Modifier.fillMaxWidth().padding(horizontal = IslandMargin, vertical = 6.dp).cyclonGlass(Cyclon.glass, PillShape)
        .padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            val usable = enabled(index)
            Box(Modifier.weight(1f).clip(PillShape).background(if (on) p.ink else Color.Transparent)
                .selectable(on, enabled = usable, role = Role.Tab, onClick = { onSelect(index) })
                .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
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

/** The search field of a list page: a glass pill, one line, a search glyph and [placeholder] in place of a floating label. */
@Composable
fun CyclonSearchField(query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val p = Cyclon.palette
    OutlinedTextField(query, onQuery, modifier.cyclonGlass(Cyclon.glass, PillShape), singleLine = true, shape = PillShape,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.ink, unfocusedBorderColor = Color.Transparent,
            cursorColor = p.ink, focusedTextColor = p.ink, unfocusedTextColor = p.ink),
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = p.muted) })
}

/**
 * A round 48dp icon action beside a field: [filled] with ink for the primary one, the glass's sheen otherwise. [label]
 * is what TalkBack reads, as the icon has no text.
 */
@Composable
fun CyclonIconAction(icon: ImageVector, label: String, filled: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val p = Cyclon.palette
    Box(Modifier.size(48.dp).then(if (filled) Modifier.clip(CircleShape).background(if (enabled) p.ink else p.cell)
            else Modifier.cyclonGlass(Cyclon.glass, CircleShape, pill = true))
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

