package dev.doona.android.prototype.rv

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.Indication
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import androidx.core.graphics.drawable.toBitmap
import dev.doona.android.R
import dev.doona.android.prototype.rv.data.RvAmount
import dev.doona.android.prototype.rv.data.RvPlace
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The one press feedback: the content colour's state layer, clipped to the visible [shape], up in [RvMotion.PRESS_MS].
 * [onLongClick], when given, fires on a long press with combinedClickable's own haptic tick; [RvLongPressTiming] sets
 * when that is.
 */
@Composable
internal fun Modifier.rvPress(shape: Shape, role: Role, onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    clip(shape).rvClick(rvPressIndication(), role, onClick, onLongClick)

/** A row's press: [rvPress] with Revolut's row highlight ([rvRowIndication]) instead of the whole row's layer. */
@Composable
internal fun Modifier.rvRowPress(role: Role, onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    rvClick(rvRowIndication(), role, onClick, onLongClick)

private fun Modifier.rvClick(indication: Indication, role: Role, onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    if (onLongClick == null) {
        clickable(interactionSource = null, indication = indication, role = role, onClick = onClick)
    } else {
        combinedClickable(interactionSource = null, indication = indication, role = role, onLongClick = onLongClick, onClick = onClick)
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
 * [clear]. [live] moves it (a tab's fade, P2's stretch, the connected pulse, a pan to a new node). A new [dots] colour
 * turns in over [RvMotion.HERO_FADE_MS], as a theme change cross-fades, and not at once; at animator scale 0 at once.
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
    val ink = animateColorAsState(dots, Rv.motion.tween(RvMotion.HERO_FADE_MS, LinearEasing), label = "dots")
    val moving = live.tinted { ink.value }
    Box(modifier.fillMaxWidth().background(Rv.colors.page).rvDotMap(focus, dots, focusTop, clear, statusBar, moving)) {
        // The marker's pulse, an ambient loop: its own layer, voting the normal rate, so a frame of it redraws the ring
        // alone and not the page it lies on.
        if (focus != null) {
            Spacer(Modifier.matchParentSize().preferredFrameRate(FrameRateCategory.Normal).graphicsLayer {}.rvDotPulse(focus, dots, focusTop, moving))
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

/**
 * A round action: a disc with a glyph and its label under it on one line, in [labelStyle], the size its row gives every
 * label ([rvActionRowFit]), and still shrinking to fit its column alone when it has no row; the line keeps its full
 * height, so nothing under it moves. Only the disc takes the press ([rvDiscPress]). [busy] swaps the glyph for [RvBusy];
 * that swap and a new [label] fade through ([RvFadeThrough]).
 */
@Composable
internal fun RvAction(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    labelStyle: TextStyle = Rv.type.action,
) {
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.rvDiscPress(Modifier.size(RvSize.actionDisc).clip(CircleShape).background(Rv.colors.discOnHeader), onClick), contentAlignment = Alignment.Center) {
            // While [busy] (connecting) the disc shows a busy bar instead of its glyph, the one fading through the other.
            RvFadeThrough(busy, "busy") { working ->
                if (working) RvBusy(Modifier.width(RvSize.busy)) else RvGlyph(icon, RvSize.actionGlyph, Rv.colors.text)
            }
        }
        Spacer(Modifier.height(RvSpace.discToLabel))
        // A new word fades through the old one, with the glyph when both change at once.
        RvFadeThrough(label, "label", alignment = Alignment.TopCenter) { word ->
            val line = Modifier.padding(horizontal = RvSpace.actionLabelInset).rvFullLine(word, Rv.type.action)
            RvText(word, labelStyle, line, align = TextAlign.Center, autoSize = rvShrinkToFit(labelStyle))
        }
    }
}

/** The one label size of a row of round actions whose columns are [column] wide ([rvRowFit]). */
@Composable
internal fun rvActionRowFit(labels: List<String>, column: Dp): TextStyle = rvRowFit(labels, Rv.type.action, column - RvSpace.actionLabelInset * 2)

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
            caption?.let { RvControlLine(it) }
        }
    }
}

/** The line under a control in a card saying what the choice does ([RvChoiceLine]), [RvSpace.controlToCaption] under it. */
@Composable
internal fun RvControlLine(text: String, modifier: Modifier = Modifier) = RvChoiceLine(text, modifier.padding(top = RvSpace.controlToCaption))

/**
 * A line saying what a choice does (a mode, what a rule's match covers), in secondary text. It wraps as long as it needs
 * rather than cut the reason or the domain it names (`依規則分流，其餘流量經主節點`, `只比對 gemini.google.com`).
 */
@Composable
internal fun RvChoiceLine(text: String, modifier: Modifier = Modifier) {
    RvText(text, Rv.type.rowSub, modifier, color = Rv.colors.textSecondary, maxLines = Int.MAX_VALUE)
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

    /** A composite block of the page's column (a pager of tiles, a card and the message under it), measured as one card. */
    const val BLOCK = "rv-block"
    const val ROW = "rv-row"
    const val SEGMENTED = "rv-segmented"
}

/**
 * A row's leading disc: a glyph on the raised fill. A detail page's header takes it at [RvSize.headerDisc]; the glyph
 * keeps the row's proportion to its disc unless [glyph] says otherwise (a notice's, [RvSize.noticeGlyph]).
 */
@Composable
internal fun RvIconDisc(@DrawableRes icon: Int, color: Color = Rv.colors.text, size: Dp = RvSize.rowDisc, glyph: Dp = RvSize.rowGlyph * (size / RvSize.rowDisc)) {
    Box(Modifier.size(size).clip(CircleShape).background(Rv.colors.discOnCard), contentAlignment = Alignment.Center) {
        RvGlyph(icon, glyph, color)
    }
}

/**
 * A row's leading disc for an app: the app's own launcher icon, from the package manager, filling the disc as a flag
 * does; an app the phone does not have (or does not let doona see) gets the apps glyph on the raised fill instead.
 * [label] (the app's name) is for a screen reader, where the row does not name the app. A detail page's header takes
 * it at [RvSize.headerDisc].
 */
@Composable
internal fun RvAppDisc(packageName: String, label: String? = null, size: Dp = RvSize.rowDisc) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon = remember(packageName, px) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(px, px).asImageBitmap() }.getOrNull()
    }
    val named = label?.let { Modifier.semantics { contentDescription = it } } ?: Modifier
    if (icon == null) return Box(named) { RvIconDisc(R.drawable.rv_sym_apps, size = size) }
    Image(icon, contentDescription = label, Modifier.size(size).clip(CircleShape).background(Rv.colors.discOnCard))
}

/**
 * Revolut's 'See all' row at a list card's foot (measured §7): [text] centred, 19 sp bold in ink, in 56 dp. Ink, as
 * Revolut sets its 'see all' (revolut-ux3 §1): no action in a card is a coloured text link (the owner, 2026-11-23).
 */
@Composable
internal fun RvSeeAll(text: String, onClick: () -> Unit) {
    val shape = remember { RoundedCornerShape(bottomStart = RvSize.cardRadius, bottomEnd = RvSize.cardRadius) }
    Box(
        Modifier.fillMaxWidth().height(RvSize.seeAll).rvPress(shape, Role.Button, onClick).padding(horizontal = RvSpace.rowPadding),
        contentAlignment = Alignment.Center,
    ) {
        RvText(text, Rv.type.seeAll, color = Rv.colors.text, align = TextAlign.Center)
    }
}

/** A thin pill bar, #13's goal progress: [fraction] of it in the positive colour on the raised track. */
@Composable
internal fun RvProgress(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(RvSize.progress).clip(CircleShape).background(Rv.colors.track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(Rv.colors.positive))
    }
}

/**
 * A row of Revolut's Key stats card (measured §11): [label] 19 sp medium in secondary ink, [value] 19 sp at the end, in a
 * 58 dp row. A fact is read whole: when the two do not fit one line with [RvSpace.trailingGap] between them (a host:port,
 * a group and its node at a large font size), the value goes under the label on its edge and wraps, and the row grows as
 * a two-line row does. Nothing of either is cut.
 */
@Composable
internal fun RvStatRow(label: String, value: String) {
    // Laid out once more when the value moves under the label, as RvText's cut is: its lines then start on the edge.
    var stacked by remember(label, value) { mutableStateOf(false) }
    Layout(
        {
            RvText(label, Rv.type.statLabel, color = Rv.colors.textSecondary, maxLines = RV_ROW_TITLE_LINES)
            RvText(value, Rv.type.stat, align = if (stacked) TextAlign.Start else TextAlign.End, maxLines = Int.MAX_VALUE)
        },
        Modifier.fillMaxWidth().padding(horizontal = RvSpace.rowPadding),
    ) { (labelPart, valuePart), constraints ->
        val width = constraints.maxWidth
        val oneLine = labelPart.maxIntrinsicWidth(Constraints.Infinity) + RvSpace.trailingGap.roundToPx() + valuePart.maxIntrinsicWidth(Constraints.Infinity) <= width
        if (stacked == oneLine) stacked = !oneLine
        val free = Constraints(maxWidth = width)
        val labelLine = labelPart.measure(free)
        val valueLine = valuePart.measure(free)
        if (oneLine) {
            val height = max(RvSize.rowOneLine.roundToPx(), max(labelLine.height, valueLine.height))
            layout(width, height) {
                labelLine.place(0, Alignment.CenterVertically.align(labelLine.height, height))
                valueLine.place(width - valueLine.width, Alignment.CenterVertically.align(valueLine.height, height))
            }
        } else {
            val text = labelLine.height + valueLine.height
            val height = max(RvSize.rowTwoLine.roundToPx(), text + RvSpace.rowTextInset.roundToPx() * 2)
            val top = Alignment.CenterVertically.align(text, height)
            layout(width, height) {
                labelLine.place(0, top)
                valueLine.place(0, top + labelLine.height)
            }
        }
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
    val press = onClick?.let { Modifier.rvPress(RoundedCornerShape(RvSize.highlightRadius), Role.Button, it) } ?: Modifier
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
    val press = onClick?.let { Modifier.rvPress(RoundedCornerShape(RvSize.highlightRadius), Role.Button, it) } ?: Modifier
    val above = if (first) Modifier else Modifier.padding(top = RvSpace.sectionAbove)
    Row(modifier.then(above).padding(bottom = RvSpace.sectionBelow).testTag(RvPart.SECTION).then(press), verticalAlignment = Alignment.CenterVertically) {
        RvText(title, Rv.type.section, Modifier.semantics { heading() })
        if (onClick != null) RvChevron(color = Rv.colors.text)
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
 * What a banner says, by kind; the kind alone picks the glyph, so every banner of a kind looks the same. [Error]: an
 * action failed or a part stopped (a failed switch, save, export, import or update; the core stopped; every node failed).
 * [Warning]: something outside doona stops traffic (no network, another app's VPN). [Info]: nothing is broken now, the
 * banner offers something (the log of an earlier crash). The glyphs are the log levels' on 回報問題.
 */
internal enum class RvNoticeKind(@DrawableRes val glyph: Int) {
    Error(R.drawable.rv_sym_error),
    Warning(R.drawable.rv_nodes_ic_warning),
    Info(R.drawable.rv_sym_info),
}

/** One banner: its [kind], the [title] over its [reason], and the one [action] that [onAction] runs. */
@Immutable
internal class RvNotice(val kind: RvNoticeKind, val title: String, val reason: String, val action: String, val onAction: () -> Unit)

/**
 * The one banner of the RV pages, as Revolut's notice card with a button (revolut-ux3 §3): 16 dp padding on every side;
 * a row's 40 dp disc with its kind's glyph in ink, its top on the title's line; the title over its reason (wrapping, in
 * secondary ink) 16 dp after the disc; then, [RvSpace.noticeToAction] under the text, the one action as the full-width
 * secondary pill. No hue anywhere: Revolut's notice is neutral, and the owner ruled out a red glyph over a teal action
 * (2026-11-23). A screen reader announces it when it appears. A page places it with [RvBannerSlot] (or [RvGutter]'s
 * `banner`), never by itself.
 */
@Composable
internal fun RvBanner(notice: RvNotice, modifier: Modifier = Modifier) {
    RvCard(modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.fillMaxWidth().padding(RvSpace.rowPadding), verticalArrangement = Arrangement.spacedBy(RvSpace.noticeToAction)) {
            Row(Modifier.fillMaxWidth()) {
                RvIconDisc(notice.kind.glyph, glyph = RvSize.noticeGlyph)
                Spacer(Modifier.width(RvSpace.rowGap))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RvSpace.tight / 2)) {
                    RvText(notice.title, Rv.type.rowTitle, maxLines = BANNER_TITLE_LINES)
                    RvText(notice.reason, Rv.type.rowSub, color = Rv.colors.textSecondary, maxLines = BANNER_REASON_LINES)
                }
            }
            RvSecondaryButton(notice.action, notice.onAction)
        }
    }
}

/**
 * Where a page's banner sits, the same on every page: first in the page's body, right under its header (活動's hero
 * and actions, a large title, the tab's header, the picker's title and caption), so it takes the place of the first
 * card, on the gutter, [RvSpace.cardGap] over what follows. Nothing without a [banner].
 */
@Composable
internal fun RvBannerSlot(banner: RvNotice?) {
    if (banner != null) RvBanner(banner, Modifier.padding(start = RvSpace.gutter, end = RvSpace.gutter, bottom = RvSpace.cardGap))
}

/**
 * An empty state where a section is not set up yet (活動 with no subscription), as Revolut's product that is not opened
 * (revolut-ux3 §4): no glyph, no disc and no card, the [title] over one [line], centred, then the one [action] as the
 * full-width primary pill on the caller's gutter. The title keeps the row title's step: Revolut's 44 sp is the hero's,
 * not a notice's. For data that is empty inside a card, [RvEmptyCaption].
 */
@Composable
internal fun RvEmptyState(title: String, line: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = RvSpace.chartGap), horizontalAlignment = Alignment.CenterHorizontally) {
        RvText(title, Rv.type.rowTitle, align = TextAlign.Center, maxLines = BANNER_TITLE_LINES)
        Spacer(Modifier.height(RvSpace.emptyLine))
        RvText(line, Rv.type.rowSub, color = Rv.colors.textSecondary, align = TextAlign.Center, maxLines = BANNER_REASON_LINES)
        Spacer(Modifier.height(RvSpace.chartGap))
        RvPrimaryButton(action, onAction)
    }
}

/**
 * An empty state inside a card (連線 with the tunnel off, a search that matches nothing), as Revolut's tile with no data
 * (revolut-ux3 §4): the card holds its [title] and a 17 sp [caption] under it, both at the start on the card's 16 dp
 * padding, and no glyph, disc or action.
 */
@Composable
internal fun RvEmptyCaption(title: String, caption: String, modifier: Modifier = Modifier) {
    RvCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(RvSpace.rowPadding), verticalArrangement = Arrangement.spacedBy(RvSpace.tight / 2)) {
            RvText(title, Rv.type.rowTitle, maxLines = BANNER_TITLE_LINES)
            RvText(caption, Rv.type.rowSub, color = Rv.colors.textSecondary, maxLines = BANNER_REASON_LINES)
        }
    }
}

/**
 * The one footnote: what a group of cards means, under its last card in secondary text at the row subtitle's step,
 * [RvSpace.footnoteAbove] under it and on the gutter as the section titles, three lines at most: [inset] in from its
 * parent, less whatever a column it sits in already insets. 13/18 is for chart captions and legends only.
 */
@Composable
internal fun RvFootnote(text: String, modifier: Modifier = Modifier, inset: Dp = RvSpace.gutter) {
    val pad = Modifier.padding(start = inset, top = RvSpace.footnoteAbove, end = inset).testTag(RvPart.FOOTER)
    RvText(text, Rv.type.rowSub, modifier.then(pad), color = Rv.colors.textSecondary, maxLines = FOOTNOTE_LINES)
}

/** A page's body: its cards on the gutter, [RvSpace.cardGap] apart, under the page's [banner] in [RvBannerSlot]. */
@Composable
internal fun RvGutter(modifier: Modifier = Modifier, banner: RvNotice? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        RvBannerSlot(banner)
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter), verticalArrangement = Arrangement.spacedBy(RvSpace.cardGap), content = content)
    }
}

internal const val RV_NO_VALUE = "—"

/** A banner's or an empty state's title wraps to two lines at most, and its reason, line or caption to three. */
private const val BANNER_TITLE_LINES = 2
private const val BANNER_REASON_LINES = 3
private const val FOOTNOTE_LINES = 3
