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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import dev.doona.android.R
import dev.doona.android.prototype.rv.data.RvAmount
import dev.doona.android.prototype.rv.data.RvPlace
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/** One line of text in a role; CJK runs in Noto, the rest in Inter. */
@Composable
internal fun RvText(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Rv.colors.text, align: TextAlign = TextAlign.Start) {
    BasicText(scripted(text), modifier, style.copy(color = color, textAlign = align), overflow = TextOverflow.Ellipsis, maxLines = 1)
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

/** A row of a grouped card: a disc at the start, a title over an optional subtitle, and trailing content at the end. */
@Composable
internal fun RvRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    // The card clips the layer of its first and last row to its corners.
    val press = onClick?.let { Modifier.rvPress(RectangleShape, Role.Button, it) } ?: Modifier
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
            RvText(title, Rv.type.rowTitle)
            subtitle?.let { RvText(it, Rv.type.rowSub, color = Rv.colors.textSecondary) }
        }
        trailing?.let {
            Spacer(Modifier.width(RvSpace.trailingGap))
            it()
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

@Composable
internal fun RvGutter(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter), verticalArrangement = Arrangement.spacedBy(RvSpace.cardGap), content = content)
}

internal const val RV_NO_VALUE = "—"

/** An equilateral triangle is this share of its width high. */
private const val TRIANGLE_HEIGHT = 0.866f
