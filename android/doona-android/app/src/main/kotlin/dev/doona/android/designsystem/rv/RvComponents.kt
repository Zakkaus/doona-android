package dev.doona.android.designsystem.rv

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.Indication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import dev.doona.android.R
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.max

/**
 * The one press feedback: the content colour's state layer, clipped to the visible [shape], up in [RvMotion.PRESS_MS].
 * [onLongClick], when given, fires on a long press with combinedClickable's own haptic tick; [RvLongPressTiming] sets
 * when that is.
 */
@Composable
internal fun Modifier.rvPress(shape: Shape, role: Role, onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    clip(shape).rvClick(rvPressIndication(shape), role, onClick, onLongClick)

/** A row's press: [rvPress] with Revolut's row highlight ([rvRowIndication]) instead of the whole row's layer. */
@Composable
internal fun Modifier.rvRowPress(role: Role, onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    rvClick(rvRowIndication(), role, onClick, onLongClick)

/** A radio row's choice: the whole row selects it, with [rvRowPress]'s highlight; while not [enabled] it takes no press. */
@Composable
internal fun Modifier.rvRowSelect(selected: Boolean, onClick: () -> Unit, enabled: Boolean = true): Modifier =
    selectable(selected, enabled = enabled, interactionSource = null, indication = rvRowIndication(), role = Role.RadioButton, onClick = onClick)

/** A switch row: the whole row toggles [value], with [rvRowPress]'s highlight. */
@Composable
internal fun Modifier.rvRowToggle(value: Boolean, onValueChange: (Boolean) -> Unit): Modifier =
    toggleable(value, interactionSource = null, indication = rvRowIndication(), role = Role.Switch, onValueChange = onValueChange)

/** A choice drawn as a tile or disc: clipped to [shape], it selects with [rvPress]'s layer; while not [enabled] it takes no press. */
@Composable
internal fun Modifier.rvPressSelect(shape: Shape, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true): Modifier =
    clip(shape).selectable(selected, interactionSource = null, indication = rvPressIndication(shape), enabled = enabled, role = Role.RadioButton, onClick = onClick)

/** One of a set of page dots or tabs that picks its page, with no press layer of its own, only the focus outline. */
@Composable
internal fun Modifier.rvTabSelect(selected: Boolean, onClick: () -> Unit): Modifier = selectable(selected, interactionSource = null, indication = rvFocusIndication(CircleShape), role = Role.Tab, onClick = onClick)

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
internal fun Modifier.rvDiscPress(fill: Modifier, onClick: () -> Unit, enabled: Boolean = true): Modifier {
    val lit = Rv.colors.discLit
    val source = remember { MutableInteractionSource() }
    val press = rvPressLevel(source)
    return graphicsLayer {
        val grow = 1f + RvMotion.DISC_GROW * press.value
        scaleX = grow
        scaleY = grow
    }.then(fill)
        .drawBehind { if (press.value > 0f) drawCircle(lit, alpha = press.value) }
        .clip(CircleShape)
        .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .indication(source, rvFocusIndication(CircleShape))
}

@Composable
internal fun RvGlyph(@DrawableRes icon: Int, size: Dp, color: Color, modifier: Modifier = Modifier) {
    Image(painterResource(icon), contentDescription = null, modifier.size(size).testTag(RvRowPart.GLYPH), colorFilter = ColorFilter.tint(color))
}

/** [RvGlyph] whose [size] is read as the glyph is measured, so a size that moves every frame composes nothing. */
@Composable
internal fun RvGlyph(@DrawableRes icon: Int, size: () -> Dp, color: Color, modifier: Modifier = Modifier) {
    val fit = Modifier.layout { measurable, constraints ->
        val edge = size().roundToPx()
        val w = edge.coerceIn(constraints.minWidth, constraints.maxWidth)
        val h = edge.coerceIn(constraints.minHeight, constraints.maxHeight)
        val placeable = measurable.measure(Constraints.fixed(w, h))
        layout(w, h) { placeable.placeRelative(0, 0) }
    }
    Image(painterResource(icon), contentDescription = null, modifier.then(fit).testTag(RvRowPart.GLYPH), colorFilter = ColorFilter.tint(color))
}

/**
 * The screen's one tinted area: the dot map of the world in [dots] on the neutral page behind the header content,
 * centred on [focus] at [focusTop] from the header's top, none above [statusBar], and faint behind the hero's text at
 * [clear]. [live] moves it (a tab's fade, P2's stretch, the connected pulse, a pan to a new node). A new [dots] colour
 * turns in over [RvMotion.HERO_FADE_MS], as a theme change cross-fades, and not at once; at animator scale 0 at once.
 */
@Composable
internal fun RvHeader(
    focus: RvSpot?,
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
    val roll = rvRollText()
    BasicText(text, modifier.then(roll.modifier), type.hero.copy(color = Rv.colors.text), onTextLayout = roll::laid, maxLines = 1)
}

/** A figure split at its decimal separator: the integer, and the separator with the decimals (or nothing). */
internal fun rvSplitDecimals(figure: String, locale: Locale): Pair<String, String> {
    val cut = figure.indexOf(DecimalFormatSymbols.getInstance(locale).decimalSeparator)
    return if (cut < 0) figure to "" else figure.substring(0, cut) to figure.substring(cut)
}

/**
 * A round action: a disc with a glyph and its label under it on one line, in [labelStyle], the size its row gives every
 * label ([rvActionRowFit]), and still shrinking to fit its column alone when it has no row; the line keeps its full
 * height, so nothing under it moves. Only the disc takes the press ([rvDiscPress]), and none while not [enabled]. [busy]
 * swaps the glyph for [RvBusy]; that swap and a new [label] fade through ([RvFadeThrough]). A screen reader finds one
 * button: the label names it, it clicks as the disc does, and it reads as disabled with the disc. It hears only the
 * current [label], the one the click acts on: the word fading out is drawn, not read. A short [label] that keeps a
 * row at its size (Mode, Updates) names the button by its full wording, [description].
 */
@Composable
internal fun RvAction(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    labelStyle: TextStyle = Rv.type.action,
    enabled: Boolean = true,
    description: String = label,
) {
    val button = modifier.semantics(mergeDescendants = true) {
        role = Role.Button
        if (description != label) contentDescription = description
        onClick {
            if (enabled) onClick()
            enabled
        }
        if (!enabled) disabled()
    }
    Column(button, horizontalAlignment = Alignment.CenterHorizontally) {
        // The disc's own click node would be a second, nameless button: a screen reader does not see it, and finds the
        // column above, which carries its action.
        val disc = Modifier.rvDiscPress(Modifier.size(RvSize.actionDisc).clip(CircleShape).background(Rv.colors.discOnHeader), onClick, enabled)
            .semantics { hideFromAccessibility() }
        Box(disc, contentAlignment = Alignment.Center) {
            // While [busy] (connecting) the disc shows a busy bar instead of its glyph, the one fading through the other.
            RvFadeThrough(busy, "busy") { working ->
                if (working) RvBusy(Modifier.width(RvSize.busy)) else RvGlyph(icon, RvSize.actionGlyph, Rv.colors.text)
            }
        }
        Spacer(Modifier.height(RvSpace.discToLabel))
        // A new word fades through the old one, with the glyph when both change at once.
        RvFadeThrough(label, "label", alignment = Alignment.TopCenter) { word ->
            val line = Modifier.padding(horizontal = RvSpace.actionLabelInset).rvFullLine(word, Rv.type.action)
                .then(if (word == label) Modifier else Modifier.clearAndSetSemantics {})
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
    Column(modifier.fillMaxWidth().testTag(RvPart.CARD).clip(RoundedCornerShape(Rv.cardRadius)).background(Rv.colors.card), content = content)
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
 * rather than cut the reason or the domain it names (`依規則分流，其餘流量經主節點`, `只比對 gemini.google.com`);
 * [keepWords] false for a line that names one ([RvText]'s).
 */
@Composable
internal fun RvChoiceLine(text: String, modifier: Modifier = Modifier, keepWords: Boolean = true) {
    RvText(text, Rv.type.rowSub, modifier, color = Rv.colors.textSecondary, maxLines = Int.MAX_VALUE, keepWords = keepWords)
}

/** Test tags of the library's layout parts, which `RvLayoutTest` measures on every page against these tokens. */
internal object RvPart {
    const val MARK = "rv-mark"
    const val TITLE = "rv-title"
    const val CAPTION = "rv-caption"
    const val CONTROL = "rv-control"

    /** An in-page search field ([RvSearchSlot]). */
    const val SEARCH = "rv-search"

    /** A text field with its label and message ([RvTextField]). */
    const val FIELD = "rv-field"
    const val CARD = "rv-card"
    const val SECTION = "rv-section"
    const val FOOTER = "rv-footer"

    /** A composite block of the page's column (a pager of tiles, a card and the message under it), measured as one card. */
    const val BLOCK = "rv-block"
    const val ROW = "rv-row"

    /** A key stats row ([RvStatRow]) and its skeleton ([RvSkeletonStatRow]). */
    const val STAT = "rv-stat"

    /** A settings row's leading disc, held on the title's first line as its end is. */
    const val DISC = "rv-disc"

    /** A busy bar standing in for a row's end while a test runs. */
    const val BUSY = "rv-busy"
    const val SEGMENTED = "rv-segmented"

    /** A skeleton's line of text, its block (a chart's or a bar's place) and its disc, each in the bounds of the part it stands for. */
    const val SKELETON_LINE = "rv-skeleton-line"
    const val SKELETON_BLOCK = "rv-skeleton-block"
    const val SKELETON_DISC = "rv-skeleton-disc"

    /** What a skeleton's block stands for: a chart's frame, a progress track, a range chip, the world map. */
    const val FRAME = "rv-frame"

    /** A line of text set with a glyph before it ([RvInline]), measured as one line. */
    const val TEXT_LINE = "rv-text-line"
}

/**
 * A row's leading disc: a glyph on the raised fill (a header takes [RvSize.headerDisc]; [glyph] keeps the row's proportion
 * unless it says otherwise, a notice's [RvSize.noticeGlyph]); the [current] node's carries the flag's check badge.
 */
@Composable
internal fun RvIconDisc(@DrawableRes icon: Int, color: Color = Rv.colors.text, size: Dp = RvSize.rowDisc, glyph: Dp = RvSize.rowGlyph * (size / RvSize.rowDisc), current: Boolean = false) {
    Box(Modifier.size(size).testTag(RvRowPart.ICON_DISC)) {
        Box(Modifier.matchParentSize().clip(CircleShape).background(Rv.colors.discOnCard), contentAlignment = Alignment.Center) {
            RvGlyph(icon, glyph, color)
        }
        if (current) RvCurrentBadge(Rv.colors.card, Modifier.align(Alignment.BottomEnd).offset(RvSize.badgeOverhang, RvSize.badgeOverhang))
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
    val context = LocalContext.current.applicationContext
    val px = with(LocalDensity.current) { size.roundToPx() }
    // Read off the main thread and kept in AppIcons' cache; until it is there, and for a package that cannot be read, the placeholder.
    val icon by produceState(AppIcons.cached(packageName, px), packageName, px) { value = AppIcons.load(context, packageName, px) }
    val named = label?.let { Modifier.semantics { contentDescription = it } } ?: Modifier
    val loaded = icon ?: return Box(named.testTag(RvRowPart.APP_DISC)) { RvIconDisc(R.drawable.rv_sym_apps, size = size) }
    Image(loaded, contentDescription = label, Modifier.size(size).testTag(RvRowPart.APP_DISC).clip(CircleShape).background(Rv.colors.discOnCard))
}

/**
 * A row of a list where several can be checked (依 App's apps): [RvRow] with its [leading] disc, [title] and [subtitle],
 * and a check at its end that the whole row toggles, as a switch row of 設定 toggles its switch. Not [enabled], the row
 * takes no press: no layer, no toggle, and a screen reader hears it disabled. [titleKeepWords] and [subtitleKeepWords]
 * are [RvRow]'s (false for an app's label and package).
 */
@Composable
internal fun RvCheckRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    titleKeepWords: Boolean = true,
    subtitleKeepWords: Boolean = true,
) {
    val toggle = Modifier.toggleable(value = checked, enabled = enabled, interactionSource = null, indication = rvRowIndication(), role = Role.Checkbox, onValueChange = onChange)
    RvRow(leading, title, subtitle, modifier.then(toggle), titleKeepWords = titleKeepWords, subtitleKeepWords = subtitleKeepWords, endFirstLine = true) { Check(checked) }
}

/**
 * [RvCheckRow]'s check in [RvRadio]'s box: off, the radio's ring in [RvColors.control]; on, the check glyph of 已選's
 * chip in the radio's accent, its disc grown to the ring's size, so both states are one size.
 */
@Composable
private fun Check(checked: Boolean) {
    val mark = painterResource(R.drawable.rv_nodes_ic_check_circle)
    val on = Rv.colors.positive
    val ring = Rv.colors.control
    Canvas(Modifier.size(RvControlSize.radio).clearAndSetSemantics { testTag = RvRowPart.CHECK }) {
        if (checked) {
            val box = size.minDimension / CHECK_DISC
            translate((size.width - box) / 2, (size.height - box) / 2) { with(mark) { draw(Size(box, box), colorFilter = ColorFilter.tint(on)) } }
        } else {
            val stroke = RvControlSize.radioStroke.toPx()
            drawCircle(ring, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
        }
    }
}

/** The share of its box that the `check_circle` glyph's disc fills: 816 of Material Symbols' 960 units. */
private const val CHECK_DISC = 816f / 960f

/**
 * Revolut's 'See all' row at a list card's foot (measured §7): [text] centred, 19 sp bold in ink, in 56 dp. Ink, as
 * Revolut sets its 'see all' (revolut-ux3 §1): no action in a card is a coloured text link (the owner, 2026-11-23).
 * Not [shown], it shrinks and fades out over [RvMotion.ITEM_MS].
 */
@Composable
internal fun RvSeeAll(text: String, shown: Boolean = true, onClick: () -> Unit) {
    val radius = Rv.cardRadius
    val shape = remember(radius) { RoundedCornerShape(bottomStart = radius, bottomEnd = radius) }
    // Tapped, it gives way as the rows it opens come in ([RvItems]), so the card's height moves as one.
    RvItems(if (shown) SEE_ALL else emptyList(), { it }) {
        Box(
            Modifier.fillMaxWidth().height(RvSize.seeAll).rvPress(shape, Role.Button, onClick).padding(horizontal = RvSpace.rowPadding),
            contentAlignment = Alignment.Center,
        ) {
            RvText(text, Rv.type.seeAll, color = Rv.colors.text, align = TextAlign.Center)
        }
    }
}

private val SEE_ALL = listOf(Unit)

/**
 * A row of Revolut's Key stats card (measured §11): [label] 19 sp medium in secondary ink, [value] 19 sp at the end, in a
 * 58 dp row. A fact is read whole: when the two do not fit one line with [RvSpace.trailingGap] between them (a host:port,
 * a group and its node at a large font size), the value goes under the label on its edge and wraps, and the row grows as
 * a two-line row does. Nothing of either is cut. [valueKeepWords] false for a value that is a name or an identifier
 * ([RvText]'s `keepWords`: a host, a node's or a subscription's name). [spoken] is what a screen reader says for the
 * whole row, label included, when the row does not show all of it, such as the direction a figure is for
 * (q-a11y-wording); without it the label and the value are read. A row merged with a description of its own would
 * still be read part by part, so a spoken row stands in for its parts.
 */
@Composable
internal fun RvStatRow(label: String, value: String, valueKeepWords: Boolean = true, spoken: String? = null) {
    // Laid out once more when the value moves under the label, as RvText's cut is: its lines then start on the edge.
    var stacked by remember(label, value) { mutableStateOf(false) }
    Layout(
        {
            RvText(label, Rv.type.statLabel, color = Rv.colors.textSecondary, maxLines = RV_ROW_TITLE_LINES)
            RvText(value, Rv.type.stat, align = if (stacked) TextAlign.Start else TextAlign.End, maxLines = Int.MAX_VALUE, keepWords = valueKeepWords)
        },
        // One stop either way: the label and the value, or what [spoken] says in their place.
        (if (spoken == null) Modifier.semantics(mergeDescendants = true) {} else Modifier.clearAndSetSemantics { contentDescription = spoken })
            .testTag(RvPart.STAT)
            .fillMaxWidth()
            .padding(horizontal = RvSpace.rowPadding),
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

/** A small glyph before a label in the same colour: the header's sub line, which wraps within the row when [maxLines] allows it. */
@Composable
internal fun RvInline(@DrawableRes icon: Int, text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = 1, description: String? = null) {
    // The glyph is decorative, so it says nothing; [description] names what the line counts when the text alone does not.
    val spoken = if (description == null) Modifier else Modifier.semantics { contentDescription = description }
    Row(modifier.testTag(RvPart.TEXT_LINE), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RvSpace.tight)) {
        RvGlyph(icon, RvSize.inlineGlyph, color)
        RvText(text, style, Modifier.weight(1f, fill = false).then(spoken), color = color, maxLines = maxLines)
    }
}

/**
 * The one footnote: what a group of cards means, under its last card in secondary text at the row subtitle's step,
 * [RvSpace.footnoteAbove] under it, its box [RvSpace.footnoteStart] in from the card's start edge and
 * [RvSpace.footnoteEnd] in from its end (LAYOUT-RULES.md R20, owner q-s1125c-footgap), three lines at most unless
 * [whole]: [inset] is where the cards' edge is, in from its parent, less whatever a column it sits in already insets
 * ([LocalRvColumnInset], which [RvSettingsColumn] sets), so a page passes none. A footnote that must never be cut (a
 * privacy statement) is [whole] and grows to every line it needs. 13/18 is for chart captions and legends only. Pages
 * draw no footnote of their own (`RvFootnoteGateTest`).
 */
@Composable
internal fun RvFootnote(text: String, modifier: Modifier = Modifier, inset: Dp = RvSpace.gutter - LocalRvColumnInset.current, whole: Boolean = false) {
    val pad = Modifier.padding(start = inset + RvSpace.footnoteStart, top = RvSpace.footnoteAbove, end = inset + RvSpace.footnoteEnd).fillMaxWidth().testTag(RvPart.FOOTER)
    RvText(text, Rv.type.rowSub, modifier.then(pad), color = Rv.colors.textSecondary, maxLines = if (whole) Int.MAX_VALUE else FOOTNOTE_LINES)
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

/** A footnote wraps to three lines at most. */
private const val FOOTNOTE_LINES = 3
