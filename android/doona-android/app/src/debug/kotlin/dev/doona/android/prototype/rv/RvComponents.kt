package dev.doona.android.prototype.rv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import dev.doona.android.R
import dev.doona.android.prototype.rv.data.RvAmount
import dev.doona.android.prototype.rv.data.RvPlace
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Text in a role, one line unless [maxLines] says more, ending in an ellipsis; CJK runs in Noto, the rest in Inter. */
@Composable
internal fun RvText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Rv.colors.text,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = 1,
) {
    BasicText(scripted(text), modifier, style.copy(color = color, textAlign = align), overflow = TextOverflow.Ellipsis, maxLines = maxLines)
}

/** The one press feedback: the content colour's state layer, clipped to the visible [shape]. */
@Composable
internal fun Modifier.rvPress(shape: Shape, role: Role, onClick: () -> Unit): Modifier =
    clip(shape).clickable(interactionSource = null, indication = ripple(color = Rv.colors.stateLayer), role = role, onClick = onClick)

@Composable
internal fun RvGlyph(@DrawableRes icon: Int, size: Dp, color: Color, modifier: Modifier = Modifier) {
    Image(painterResource(icon), contentDescription = null, modifier.size(size), colorFilter = ColorFilter.tint(color))
}

/**
 * The screen's one tinted area: the dot map of the world in [dots] on the neutral page behind the header content,
 * centred on [focus] at [focusTop] from the header's top, none above [statusBar], and faint behind the hero's text at
 * [clear].
 */
@Composable
internal fun RvHeader(
    focus: RvPlace?,
    focusTop: Dp,
    statusBar: Dp,
    dots: Color,
    clear: Rect?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().background(Rv.colors.page).rvDotMap(focus, dots, focusTop, clear, statusBar),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
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

/** A round action: a disc with a glyph and its label under it; only the disc lights up when pressed. */
@Composable
internal fun RvAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(RvSize.actionDisc)
                .clip(CircleShape)
                .background(Rv.colors.discOnHeader)
                .rvPress(CircleShape, Role.Button, onClick),
            contentAlignment = Alignment.Center,
        ) {
            RvGlyph(icon, RvSize.actionGlyph, Rv.colors.text)
        }
        Spacer(Modifier.height(RvSpace.discToLabel))
        RvText(label, Rv.type.action, align = TextAlign.Center)
    }
}

/** A grouped card: rows run edge to edge inside its rounded fill; no border, no shadow. */
@Composable
internal fun RvCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(RvSize.cardRadius)).background(Rv.colors.card), content = content)
}

/**
 * A row of a grouped card: a disc at the start, a title over an optional subtitle, and trailing content at the end.
 *
 * [titleEnd] and [subtitleEnd] put a value at the end of the title's line and of the subtitle's line instead of one
 * trailing column beside both: each line then shares its width only with its own end. The start keeps its width up to
 * [TITLE_SHARE] of the line, the end takes what it needs of the rest, and the start gets what is left, so a long name
 * (`Hong Kong 02 IPLC`) and a long subtitle (`自動測速 · 48 個節點`) both fit where one trailing column would squeeze the
 * text column to its widest end. [trailing] (a chevron) still follows after both lines.
 */
@Composable
internal fun RvRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    titleEnd: (@Composable () -> Unit)? = null,
    subtitleEnd: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    // The card clips the layer of its first and last row to its corners.
    val press = onClick?.let { Modifier.rvPress(RectangleShape, Role.Button, it) } ?: Modifier
    val lineWise = titleEnd != null || subtitleEnd != null
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (subtitle == null) RvSize.rowOneLine else RvSize.rowTwoLine)
            .then(press)
            .padding(horizontal = RvSpace.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(RvSpace.rowGap))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RvSpace.tight / 2)) {
            if (lineWise) {
                RowLine(titleEnd) { RvText(title, Rv.type.rowTitle) }
                subtitle?.let { RowLine(subtitleEnd) { RvText(it, Rv.type.rowSub, color = Rv.colors.textSecondary) } }
            } else {
                RvText(title, Rv.type.rowTitle)
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
 */
@Composable
internal fun RvSectionTitle(title: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val press = onClick?.let { Modifier.rvPress(RoundedCornerShape(RvSpace.tight), Role.Button, it) } ?: Modifier
    Row(modifier.padding(top = RvSpace.sectionAbove, bottom = RvSpace.sectionBelow).then(press), verticalAlignment = Alignment.CenterVertically) {
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

/** A node's latency as a trailing value (19 sp semibold) over its [RvDelta] (17 sp); a test that timed out is a dash. */
@Composable
internal fun RvLatencyChange(latencyMs: Int?, changeMs: Int?) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(RvSpace.tight / 2)) {
        RvText(latencyMs?.let { stringResource(R.string.latency_ms, it) } ?: RV_NO_VALUE, Rv.type.rowValue, align = TextAlign.End)
        changeMs?.let { RvDelta(it) }
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
