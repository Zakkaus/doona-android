package dev.doona.android.prototype.rv

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import dev.doona.android.R
import dev.doona.android.prototype.rv.data.RvAmount
import dev.doona.android.prototype.rv.data.RvPlace
import kotlinx.coroutines.delay
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One line of text in a role; CJK runs in Noto, the rest in Inter. Cut short, it ends in an ellipsis right after the last
 * word that fits: the platform keeps the space after that word before its ellipsis (`IPLC …`), so the line is laid out
 * once more without it. The shorter text stays while the width does, and the whole text returns when the line gets more
 * room. [maxLines] lets it run to more lines, the last ending in the platform's ellipsis, and [overflow] may put the
 * ellipsis in the middle of a single line instead.
 */
@Composable
internal fun RvText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Rv.colors.text,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    var cut by remember(text) { mutableStateOf<RvCut?>(null) }
    BasicText(
        scripted(cut?.text ?: text),
        modifier,
        style.copy(color = color, textAlign = align),
        onTextLayout = { layout ->
            // The cut at a word is for one line ending in an ellipsis; any other line is the platform's.
            val kept = cut
            when {
                maxLines != 1 || overflow != TextOverflow.Ellipsis -> Unit
                kept == null -> cut = rvCut(text, layout)
                layout.layoutInput.constraints.maxWidth > kept.width -> cut = null
            }
        },
        overflow = overflow,
        maxLines = maxLines,
    )
}

/** A line cut at a word for [width] px. */
private class RvCut(val width: Int, val text: String)

/** [text] up to the last word that fits and an ellipsis, when the platform's cut of [layout] left a space before its own. */
private fun rvCut(text: String, layout: TextLayoutResult): RvCut? {
    if (!layout.isLineEllipsized(0)) return null
    val end = layout.getLineEnd(0, visibleEnd = true).coerceAtMost(text.length)
    val kept = text.substring(0, end).trimEnd()
    val atSpace = kept.length < end || text.getOrNull(end)?.isWhitespace() == true
    return if (atSpace && kept.isNotEmpty()) RvCut(layout.layoutInput.constraints.maxWidth, kept + ELLIPSIS) else null
}

private const val ELLIPSIS = "\u2026"

/**
 * The one press feedback: the content colour's state layer, clipped to the visible [shape], up in [RvMotion.PRESS_MS].
 * [onLongClick], when given, fires on a long press with combinedClickable's own haptic tick; [RvLongPressTiming] sets
 * when that is.
 */
@Composable
internal fun Modifier.rvPress(shape: Shape, role: Role, onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    if (onLongClick == null) {
        clip(shape).clickable(interactionSource = null, indication = rvPressIndication(), role = role, onClick = onClick)
    } else {
        clip(shape).combinedClickable(interactionSource = null, indication = rvPressIndication(), role = role, onLongClick = onLongClick, onClick = onClick)
    }

/**
 * A round disc's press, as Revolut's discs take one (measured: they lighten and grow slightly while held): the disc
 * grows by [RvMotion.DISC_GROW] and lightens toward [RvColors.discLit], both in [RvMotion.PRESS_MS], and goes back on
 * release; at animator scale 0 at once. [fill] is the disc's size and fill (a background, or the controls' glass), drawn
 * inside the growing layer under the lightening. The scale is the layer's and both read the press in the draw phase,
 * so a frame lays nothing out and allocates nothing. Rows and pills keep [rvPressIndication].
 */
@Composable
internal fun Modifier.rvDiscPress(fill: Modifier, onClick: () -> Unit): Modifier {
    val motion = Rv.motion
    val lit = Rv.colors.discLit
    val source = remember { MutableInteractionSource() }
    val press = remember { Animatable(0f) }
    LaunchedEffect(source, motion) { source.rvFollowPress { press.animateTo(it, motion.tween(RvMotion.PRESS_MS, LinearEasing)) } }
    return graphicsLayer {
        val grow = 1f + RvMotion.DISC_GROW * press.value
        scaleX = grow
        scaleY = grow
    }.then(fill)
        .drawBehind { if (press.value > 0f) drawCircle(lit, alpha = press.value) }
        .clip(CircleShape)
        .clickable(source, indication = null, role = Role.Button, onClick = onClick)
}

@Composable
internal fun RvGlyph(@DrawableRes icon: Int, size: Dp, color: Color, modifier: Modifier = Modifier) {
    Image(painterResource(icon), contentDescription = null, modifier.size(size), colorFilter = ColorFilter.tint(color))
}

/**
 * The screen's one tinted area: the dot map of the world in [dots] on the neutral page behind the header content,
 * centred on [focus] at [focusTop] from the header's top, none above [statusBar], and faint behind the hero's text at
 * [clear]. [live] moves it (a tab's fade, P2's stretch, the connected pulse, a pan to a new node).
 */
@Composable
internal fun RvHeader(
    focus: RvPlace?,
    focusTop: Dp,
    statusBar: Dp,
    dots: Color,
    clear: Rect?,
    modifier: Modifier = Modifier,
    live: RvDotMotion = RvDotMotion(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth().background(Rv.colors.page).rvDotMap(focus, dots, focusTop, clear, statusBar, live)) {
        // The marker's pulse, an ambient loop: its own layer, voting the normal rate, so a frame of it redraws the ring
        // alone and not the page it lies on.
        if (focus != null) {
            Spacer(Modifier.matchParentSize().preferredFrameRate(FrameRateCategory.Normal).graphicsLayer {}.rvDotPulse(focus, dots, focusTop, live))
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/**
 * The hero figure as Revolut sets a balance (measured §5): the integer of [amount] at 44 sp, its decimals and unit at
 * 32 sp on the same baseline.
 */
@Composable
internal fun RvHero(amount: RvAmount, modifier: Modifier = Modifier) {
    val (whole, decimals) = rvSplitDecimals(amount.number, LocalConfiguration.current.locales[0])
    RvHero(whole, decimals + amount.unit, modifier)
}

/** The hero figure: [whole] at 44 sp, [part] (decimals and unit) at 32 sp on the same baseline. */
@Composable
internal fun RvHero(whole: String, part: String, modifier: Modifier = Modifier) {
    val type = Rv.type
    val text = buildAnnotatedString {
        append(whole)
        withStyle(type.heroSmall.toSpanStyle()) { append(part) }
    }
    BasicText(text, modifier, type.hero.copy(color = Rv.colors.text), maxLines = 1)
}

/** A figure split at its decimal separator: the integer, and the separator with the decimals (or nothing). */
internal fun rvSplitDecimals(figure: String, locale: Locale): Pair<String, String> {
    val cut = figure.indexOf(DecimalFormatSymbols.getInstance(locale).decimalSeparator)
    return if (cut < 0) figure to "" else figure.substring(0, cut) to figure.substring(cut)
}

/** A round action: a disc with a glyph and its label under it; only the disc takes the press ([rvDiscPress]). [busy] swaps the glyph for [RvBusy]. */
@Composable
internal fun RvAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, busy: Boolean = false) {
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.rvDiscPress(Modifier.size(RvSize.actionDisc).clip(CircleShape).background(Rv.colors.discOnHeader), onClick), contentAlignment = Alignment.Center) {
            // While [busy] (connecting) the disc shows a busy bar instead of its glyph.
            Crossfade(busy, animationSpec = Rv.motion.tween(RvMotion.FADE_MS), label = "busy") { working ->
                if (working) RvBusy(Modifier.width(RvSize.busy)) else RvGlyph(icon, RvSize.actionGlyph, Rv.colors.text)
            }
        }
        Spacer(Modifier.height(RvSpace.discToLabel))
        RvText(label, Rv.type.action, align = TextAlign.Center)
    }
}

/** A grouped card: rows run edge to edge inside its rounded fill; no border, no shadow. */
@Composable
internal fun RvCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().testTag(RvPart.CARD).clip(RoundedCornerShape(RvSize.cardRadius)).background(Rv.colors.card), content = content)
}

/**
 * A control in a card, as Revolut sets its segmented control (measured §8: inset 16 dp in a card): [control] on the row
 * padding, 16 dp under the card's top, then the optional [caption] saying what the choice does, in secondary text on the
 * card's grid, and the card's padding at its foot.
 */
@Composable
internal fun RvControlCard(caption: String?, modifier: Modifier = Modifier, control: @Composable () -> Unit) {
    RvCard(modifier) {
        Column(Modifier.padding(RvSpace.rowPadding)) {
            control()
            caption?.let { RvText(it, Rv.type.rowSub, Modifier.padding(top = RvSpace.controlToCaption), color = Rv.colors.textSecondary) }
        }
    }
}

/** Test tags of the library's layout parts, which `RvLayoutTest` measures on every page against these tokens. */
internal object RvPart {
    const val MARK = "rv-mark"
    const val TITLE = "rv-title"
    const val CAPTION = "rv-caption"
    const val CONTROL = "rv-control"
    const val CARD = "rv-card"
    const val SECTION = "rv-section"
    const val FOOTER = "rv-footer"

    /** A composite block of the page's column (a pager of tiles and its dots), measured as one card. */
    const val BLOCK = "rv-block"
    const val ROW = "rv-row"
    const val SEGMENTED = "rv-segmented"
}

/**
 * A row of a grouped card: a disc at the start, a title over an optional subtitle, and trailing content at the end.
 *
 * [titleEnd] and [subtitleEnd] put a value at the end of the title's line and of the subtitle's line instead of one
 * trailing column beside both: each line then shares its width only with its own end. The start keeps its width up to
 * [TITLE_SHARE] of the line, the end takes what it needs of the rest, and the start gets what is left, so a long name
 * (`Hong Kong 02 IPLC`) and a long subtitle (`自動測速 · 48 個節點`) both fit where one trailing column would squeeze the
 * text column to its widest end. [trailing] (a chevron) still follows after both lines.
 *
 * The title takes one line and ends in an ellipsis; [titleLines] lets it wrap (a host name on its own page), and
 * [titleOverflow] can cut it in the middle instead, which keeps both ends of a host:port in view.
 */
@Composable
internal fun RvRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    titleEnd: (@Composable () -> Unit)? = null,
    subtitleEnd: (@Composable () -> Unit)? = null,
    titleLines: Int = 1,
    titleOverflow: TextOverflow = TextOverflow.Ellipsis,
    trailing: (@Composable () -> Unit)? = null,
) {
    // The card clips the layer of its first and last row to its corners.
    val press = onClick?.let { Modifier.rvPress(RectangleShape, Role.Button, it, onLongClick) } ?: Modifier
    val lineWise = titleEnd != null || subtitleEnd != null
    Row(
        modifier
            .fillMaxWidth()
            .testTag(RvPart.ROW)
            .heightIn(min = if (subtitle == null) RvSize.rowOneLine else RvSize.rowTwoLine)
            .then(press)
            .padding(horizontal = RvSpace.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(RvSpace.rowGap))
        Column(Modifier.weight(1f).padding(vertical = RvSpace.rowTextInset), verticalArrangement = Arrangement.spacedBy(RvSpace.tight / 2)) {
            if (lineWise) {
                RowLine(titleEnd) { RvText(title, Rv.type.rowTitle, maxLines = titleLines, overflow = titleOverflow) }
                subtitle?.let { RowLine(subtitleEnd) { RvText(it, Rv.type.rowSub, color = Rv.colors.textSecondary) } }
            } else {
                RvText(title, Rv.type.rowTitle, maxLines = titleLines, overflow = titleOverflow)
                subtitle?.let { RvText(it, Rv.type.rowSub, color = Rv.colors.textSecondary) }
            }
        }
        trailing?.let {
            Spacer(Modifier.width(RvSpace.trailingGap))
            it()
        }
    }
}

/** The share of a line a row's title keeps, if it needs it, before the end of the line takes the rest. */
private const val TITLE_SHARE = 0.4f

/**
 * One line of a line-wise [RvRow]: [start] keeps its own width up to [TITLE_SHARE] of the line, [end] takes what it
 * needs of the rest, and [start] then gets what the end leaves; either ends in an ellipsis only when both are long.
 */
@Composable
private fun RowLine(end: (@Composable () -> Unit)?, start: @Composable () -> Unit) {
    Layout({
        start()
        end?.invoke()
    }) { parts, constraints ->
        val width = constraints.maxWidth
        val gap = if (parts.size > 1) RvSpace.trailingGap.roundToPx() else 0
        val keep = min(parts[0].maxIntrinsicWidth(constraints.maxHeight), (width * TITLE_SHARE).toInt())
        val endPart = parts.getOrNull(1)?.measure(Constraints(maxWidth = max(0, width - keep - gap)))
        val endWidth = endPart?.width ?: 0
        val startPart = parts[0].measure(Constraints(maxWidth = max(0, width - endWidth - gap)))
        val height = max(startPart.height, endPart?.height ?: 0)
        layout(width, height) {
            startPart.place(0, (height - startPart.height) / 2)
            endPart?.let { it.place(width - it.width, (height - it.height) / 2) }
        }
    }
}

/** A row's leading disc: a glyph on the raised fill. */
@Composable
internal fun RvIconDisc(@DrawableRes icon: Int, color: Color = Rv.colors.text) {
    Box(Modifier.size(RvSize.rowDisc).clip(CircleShape).background(Rv.colors.discOnCard), contentAlignment = Alignment.Center) {
        RvGlyph(icon, RvSize.rowGlyph, color)
    }
}

/**
 * A row's leading disc for an app: the app's own launcher icon, from the package manager, filling the disc as a flag
 * does; an app the phone does not have (or does not let doona see) gets the apps glyph on the raised fill instead.
 * [label] (the app's name) is for a screen reader, where the row does not name the app.
 */
@Composable
internal fun RvAppDisc(packageName: String, label: String? = null) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { RvSize.rowDisc.roundToPx() }
    val icon = remember(packageName, px) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(px, px).asImageBitmap() }.getOrNull()
    }
    val named = label?.let { Modifier.semantics { contentDescription = it } } ?: Modifier
    if (icon == null) return Box(named) { RvIconDisc(R.drawable.rv_sym_apps) }
    Image(icon, contentDescription = label, Modifier.size(RvSize.rowDisc).clip(CircleShape).background(Rv.colors.discOnCard))
}

/** Revolut's 'See all' row at a list card's foot (measured §7): [text] centred, 19 sp bold in the accent, in 56 dp. */
@Composable
internal fun RvSeeAll(text: String, onClick: () -> Unit) {
    val shape = remember { RoundedCornerShape(bottomStart = RvSize.cardRadius, bottomEnd = RvSize.cardRadius) }
    Box(
        Modifier.fillMaxWidth().height(RvSize.seeAll).rvPress(shape, Role.Button, onClick).padding(horizontal = RvSpace.rowPadding),
        contentAlignment = Alignment.Center,
    ) {
        RvText(text, Rv.type.seeAll, color = Rv.colors.positive, align = TextAlign.Center)
    }
}

/** A thin pill bar, #13's goal progress: [fraction] of it in the positive colour on the raised track. */
@Composable
internal fun RvProgress(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(RvSize.progress).clip(CircleShape).background(Rv.colors.track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(Rv.colors.positive))
    }
}

/** A row of Revolut's Key stats card (measured §11): [label] 19 sp medium in secondary ink, [value] 19 sp at the end. */
@Composable
internal fun RvStatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(RvSize.rowOneLine).padding(horizontal = RvSpace.rowPadding), verticalAlignment = Alignment.CenterVertically) {
        RvText(label, Rv.type.statLabel, Modifier.weight(1f), color = Rv.colors.textSecondary)
        RvText(value, Rv.type.stat, align = TextAlign.End)
    }
}

/** The one chevron (measured §7): 6.7 x 12.2 dp of 2 dp stroke with round ends in a 24 dp box, in [color]. */
@Composable
internal fun RvChevron(modifier: Modifier = Modifier, color: Color = Rv.colors.control) {
    Canvas(modifier.size(RvChevronSize.box)) {
        val w = RvChevronSize.width.toPx()
        val h = RvChevronSize.height.toPx()
        val stroke = RvChevronSize.stroke.toPx()
        val left = (size.width - w) / 2 + stroke / 2
        val right = (size.width + w) / 2 - stroke / 2
        val top = (size.height - h) / 2 + stroke / 2
        val bottom = (size.height + h) / 2 - stroke / 2
        val path = Path().apply {
            moveTo(left, top)
            lineTo(right, size.height / 2)
            lineTo(left, bottom)
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * A card's in-card header (measured §7 `listSubheader`), for a card that is a widget on a home or overview page: 19 sp
 * medium secondary text in a 48 dp row, then an optional [tag] chip, and, only when it opens something, a
 * chevron in the header's colour; then the whole row is the button.
 */
@Composable
internal fun RvCardHeader(title: String, onClick: (() -> Unit)? = null, tag: String? = null) {
    val press = onClick?.let { Modifier.rvPress(RoundedCornerShape(RvSize.cardRadius), Role.Button, it) } ?: Modifier
    Row(
        Modifier
            .padding(top = RvSpace.headerInset)
            .fillMaxWidth()
            .height(RvHeaderSize.row)
            .then(press)
            .padding(horizontal = RvSpace.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RvText(title, Rv.type.cardTitle, Modifier.weight(1f, fill = false).semantics { heading() }, color = Rv.colors.textSecondary)
        tag?.let { RvTag(it, Modifier.padding(start = RvSpace.subGap)) }
        if (onClick != null) RvChevron(color = Rv.colors.textSecondary)
    }
}

/**
 * An outside section title (measured §7, §13.4), for a run of list cards on a settings or detail page: 20 sp bold, 24 dp
 * under what is above it and 12 dp above its card; with [onClick], a chevron in the title's colour, and the title opens it.
 * The [first] one under a [RvPageHeader] adds nothing above itself: the header's gap is already there.
 */
@Composable
internal fun RvSectionTitle(title: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, first: Boolean = false) {
    val press = onClick?.let { Modifier.rvPress(RoundedCornerShape(RvSpace.tight), Role.Button, it) } ?: Modifier
    val above = if (first) Modifier else Modifier.padding(top = RvSpace.sectionAbove)
    Row(modifier.then(above).padding(bottom = RvSpace.sectionBelow).testTag(RvPart.SECTION).then(press), verticalAlignment = Alignment.CenterVertically) {
        RvText(title, Rv.type.section, Modifier.semantics { heading() })
        if (onClick != null) RvChevron(color = Rv.colors.text)
    }
}

/**
 * A latency's change since the test before, as Revolut writes a delta (measured §7): a filled 8 dp triangle pointing its
 * way, then the amount, in one hue: the good hue when it fell, the slow hue when it rose. No change is a dash.
 */
@Composable
internal fun RvDelta(changeMs: Int) {
    // No change is not a measurement, so it shows a dash and no triangle.
    if (changeMs == 0) return RvText(RV_NO_VALUE, Rv.type.rowSub, color = Rv.colors.textSecondary)
    val color = if (changeMs < 0) Rv.colors.good else Rv.colors.slow
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RvHomeSize.deltaGap)) {
        Canvas(Modifier.size(RvHomeSize.delta)) {
            val h = size.width * TRIANGLE_HEIGHT
            val top = (size.height - h) / 2
            val path = Path().apply {
                if (changeMs > 0) {
                    moveTo(size.width / 2, top)
                    lineTo(size.width, top + h)
                    lineTo(0f, top + h)
                } else {
                    moveTo(0f, top)
                    lineTo(size.width, top)
                    lineTo(size.width / 2, top + h)
                }
                close()
            }
            drawPath(path, color)
        }
        RvText(stringResource(R.string.latency_ms, abs(changeMs)), Rv.type.rowSub, color = color)
    }
}

/** Whether a latency test of every node is running: every [RvLatencyChange] shows [RvBusy] until its result arrives. */
internal val LocalRvTesting = compositionLocalOf { false }

/** The nodes a test of their own is running for (a row menu's 測試延遲): only their rows show [RvBusy]. */
internal val LocalRvTestingNodes = compositionLocalOf { emptySet<String>() }

/**
 * A node's latency as a trailing value (19 sp semibold, as [RvLatencyText] writes it) over its change since the test
 * before ([RvDelta], 17 sp). While a test runs ([LocalRvTesting], or one of
 * [node]'s own in [LocalRvTestingNodes]) a busy bar stands in for both; when it ends the result fades in [order] ×
 * [RvMotion.STAGGER_MS] after the list's first, so the results run down the list, and any later change cross-fades.
 */
@Composable
internal fun RvLatencyChange(latencyMs: Int?, changeMs: Int?, timedOut: Boolean = false, order: Int = 0, node: String? = null) {
    val motion = Rv.motion
    val testing = LocalRvTesting.current || node in LocalRvTestingNodes.current
    var waiting by remember { mutableStateOf(testing) }
    // Only while what it shows lags the test: a row at rest launches nothing, and a list has one per row.
    if (waiting != testing) {
        LaunchedEffect(testing) {
            if (!testing && !motion.instant) delay(order.toLong() * RvMotion.STAGGER_MS)
            waiting = testing
        }
    }
    Crossfade(if (waiting) null else Triple(latencyMs, changeMs, timedOut), animationSpec = motion.tween(RvMotion.FADE_MS), label = "latency") { shown ->
        if (shown == null) {
            RvBusy(Modifier.width(RvSize.busy))
        } else {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(RvSpace.tight / 2)) {
                RvLatencyText(shown.first, shown.third)
                shown.second?.let { RvDelta(it) }
            }
        }
    }
}

/**
 * A node's last test as a row's trailing value: its latency; 逾時 in the slow colour of a latency that went up, when the
 * test timed out; a dash when there is nothing to show (never tested, or no node).
 */
@Composable
internal fun RvLatencyText(latencyMs: Int?, timedOut: Boolean) {
    when {
        latencyMs != null -> RvText(stringResource(R.string.latency_ms, latencyMs), Rv.type.rowValue, align = TextAlign.End)
        timedOut -> RvText(stringResource(R.string.rv_nodes_timed_out), Rv.type.rowValue, color = Rv.colors.slow, align = TextAlign.End)
        else -> RvText(RV_NO_VALUE, Rv.type.rowValue, align = TextAlign.End)
    }
}

/** A small glyph before a label in the same colour: the header's sub line. */
@Composable
internal fun RvInline(@DrawableRes icon: Int, text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RvSpace.tight)) {
        RvGlyph(icon, RvSize.inlineGlyph, color)
        RvText(text, style, color = color)
    }
}

/**
 * An inline error banner: a card with the error's glyph on a row's disc in the error hue (the title stays in ink, as
 * Revolut's destructive row, measured §13.2), the [title] over its [reason] (wrapping, in secondary ink), and the one
 * [action] as the card's foot, in the 'See all' row's form. A screen reader announces it when it appears.
 */
@Composable
internal fun RvBanner(
    title: String,
    reason: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int = R.drawable.rv_sym_error,
) {
    RvCard(modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.fillMaxWidth().padding(start = RvSpace.rowPadding, end = RvSpace.rowPadding, top = RvSpace.rowPadding)) {
            RvIconDisc(icon, Rv.colors.error)
            Spacer(Modifier.width(RvSpace.rowGap))
            // The title centres on the disc; the reason wraps under it.
            Column(Modifier.weight(1f).padding(top = (RvSize.rowDisc - RvSize.titleLine) / 2), verticalArrangement = Arrangement.spacedBy(RvSpace.tight / 2)) {
                RvText(title, Rv.type.rowTitle, maxLines = BANNER_TITLE_LINES)
                RvText(reason, Rv.type.rowSub, color = Rv.colors.textSecondary, maxLines = BANNER_REASON_LINES)
            }
        }
        RvSeeAll(action, onAction)
    }
}

/**
 * An empty state, as Revolut's New features card (measured §13.3): a card with a 48 dp glyph of the set in secondary
 * ink, the [title] and one [line] under it, centred, and the one [action] as the primary button.
 */
@Composable
internal fun RvEmptyState(
    @DrawableRes icon: Int,
    title: String,
    line: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RvCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSpace.rowPadding, vertical = RvSpace.chartGap), horizontalAlignment = Alignment.CenterHorizontally) {
            RvGlyph(icon, RvSize.emptyGlyph, Rv.colors.textSecondary)
            Spacer(Modifier.height(RvSpace.rowGap))
            RvText(title, Rv.type.rowTitle, align = TextAlign.Center, maxLines = BANNER_TITLE_LINES)
            Spacer(Modifier.height(RvSpace.tight / 2))
            RvText(line, Rv.type.rowSub, color = Rv.colors.textSecondary, align = TextAlign.Center, maxLines = BANNER_REASON_LINES)
            Spacer(Modifier.height(RvSpace.chartGap))
            RvPrimaryButton(action, onAction)
        }
    }
}

@Composable
internal fun RvGutter(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter), verticalArrangement = Arrangement.spacedBy(RvSpace.cardGap), content = content)
}

internal const val RV_NO_VALUE = "—"

/** A banner's or an empty state's title wraps to two lines at most, and its reason or line to three. */
private const val BANNER_TITLE_LINES = 2
private const val BANNER_REASON_LINES = 3

/** An equilateral triangle is this share of its width high. */
private const val TRIANGLE_HEIGHT = 0.866f
