package dev.doona.android.prototype.rv

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import dev.doona.android.R
import dev.doona.android.designsystem.components.Tone
import dev.doona.android.designsystem.components.latencyTone

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
 * The screen's one tinted area: the dot map of the world on the neutral page behind the header content, centred on
 * [focus] at [focusTop] from the header's top.
 */
@Composable
internal fun RvHeader(focus: RvPlace?, focusTop: Dp, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Rv.colors.page).rvDotMap(focus, Rv.colors.dots, focusTop),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** The hero figure: [whole] large, [part] (decimals or a unit) small on the same baseline, as Revolut sets a balance. */
@Composable
internal fun RvHero(whole: String, part: String, modifier: Modifier = Modifier) {
    val type = Rv.type
    val text = buildAnnotatedString {
        append(whole)
        withStyle(type.heroSmall.toSpanStyle()) { append(part) }
    }
    BasicText(text, modifier, type.hero.copy(color = Rv.colors.text), maxLines = 1)
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

/** A node's flag filling the row's disc, or a globe when its name names no country; [selected] adds #13's check. */
@Composable
internal fun RvFlagDisc(place: RvPlace?, selected: Boolean) {
    Box(Modifier.size(RvSize.rowDisc)) {
        if (place == null) {
            RvIconDisc(R.drawable.rv_ic_globe, Rv.colors.textSecondary)
        } else {
            val edge = Rv.colors.flagEdge?.let { Modifier.border(Dp.Hairline, it, CircleShape) } ?: Modifier
            Image(painterResource(place.flag), contentDescription = null, Modifier.fillMaxSize().clip(CircleShape).then(edge), contentScale = ContentScale.Crop)
        }
        if (selected) {
            val ring = Modifier.size(RvSize.badge).clip(CircleShape).background(Rv.colors.card).padding(RvSize.badgeRing)
            Box(
                Modifier.align(Alignment.BottomEnd).offset(RvSize.badgeOverhang, RvSize.badgeOverhang).then(ring).clip(CircleShape).background(Rv.colors.positive),
                contentAlignment = Alignment.Center,
            ) { RvGlyph(R.drawable.rv_ic_check, RvSize.badgeGlyph, Rv.colors.onPositive) }
        }
    }
}

/** A node's latency over its quality word, the word in the quality's fixed hue. */
@Composable
internal fun RvLatency(ms: Int?) {
    val tone = latencyTone(ms)
    RvTrailingValue(ms?.let { stringResource(R.string.latency_ms, it) } ?: RV_NO_VALUE, stringResource(tone.quality), Rv.colors.quality(tone))
}

/** A thin pill bar, #13's goal progress: [fraction] of it in the positive colour on the raised track. */
@Composable
internal fun RvProgress(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(RvSize.progress).clip(CircleShape).background(Rv.colors.track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(Rv.colors.positive))
    }
}

/** A screen's top bar: the back glyph's edge sits on the gutter; its touch target reaches past it. */
@Composable
internal fun RvBackBar(onBack: () -> Unit) {
    val back = stringResource(R.string.ui_back)
    Box(Modifier.height(RvSize.topBar).padding(start = RvSpace.gutter - (RvSize.touch - RvSize.navGlyph) / 2), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier.size(RvSize.touch).rvPress(CircleShape, Role.Button, onBack).semantics { contentDescription = back },
            contentAlignment = Alignment.Center,
        ) { RvGlyph(R.drawable.rv_ic_back, RvSize.navGlyph, Rv.colors.text) }
    }
}

/**
 * A range under a chart, Revolut's chip: the selected one raised off the page, the rest plain secondary text, and one the
 * build does not offer fainter still.
 */
@Composable
internal fun RvRangePill(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = Rv.colors
    val color = when {
        selected -> colors.text
        enabled -> colors.textSecondary
        else -> colors.textSecondary.copy(alpha = RvAlpha.DISABLED)
    }
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .height(RvChartSize.range)
            .then(if (selected) Modifier.rvRaised(CircleShape) else Modifier.clip(CircleShape))
            .selectable(selected, interactionSource = null, indication = ripple(color = colors.stateLayer), enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(horizontal = RvChartSize.rangePad),
        contentAlignment = Alignment.Center,
    ) { RvText(label, Rv.type.action, color = color) }
}

/** A raised control's fill in [shape]: a surface over a soft shadow in light, a lighter container in dark. */
@Composable
private fun Modifier.rvRaised(shape: Shape): Modifier {
    val colors = Rv.colors
    val lift = if (colors.raisedShadow) Modifier.shadow(RvChartSize.rangeShadow, shape) else Modifier
    return then(lift).clip(shape).background(colors.raised)
}

/**
 * Revolut's segmented control (Annual / Quarterly, Stocks / ETFs): a pill track in a container colour across the
 * width, [options] in equal parts, the [selected] one on a raised surface.
 */
@Composable
internal fun RvSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(RvChartSize.segment)
            .clip(CircleShape)
            .background(Rv.colors.segmentTrack)
            .padding(RvChartSize.segmentInset),
    ) {
        options.forEachIndexed { i, label ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (i == selected) Modifier.rvRaised(CircleShape) else Modifier.clip(CircleShape))
                    .selectable(i == selected, interactionSource = null, indication = ripple(color = Rv.colors.stateLayer), role = Role.Tab) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { RvText(label, Rv.type.action, color = if (i == selected) Rv.colors.text else Rv.colors.textSecondary) }
        }
    }
}

/** A round icon button on a container colour, as after Revolut's range chips; [label] is for the screen reader. */
@Composable
internal fun RvRoundButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .size(RvChartSize.roundButton)
            .clip(CircleShape)
            .background(Rv.colors.roundButton)
            .rvPress(CircleShape, Role.Button, onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { RvGlyph(icon, RvChartSize.roundGlyph, Rv.colors.text) }
}

/** A row of Revolut's Key stats card: [label] in secondary ink at the start, [value] at the end. */
@Composable
internal fun RvStatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(RvSize.rowOneLine).padding(horizontal = RvSpace.rowPadding), verticalAlignment = Alignment.CenterVertically) {
        RvText(label, Rv.type.caption, Modifier.weight(1f), color = Rv.colors.textSecondary)
        RvText(value, Rv.type.stat, align = TextAlign.End)
    }
}

/** A destination of the bottom navigation. */
internal class RvNavItem(@DrawableRes val icon: Int, @StringRes val label: Int)

/** The bottom navigation on the page: a glyph over a label each, the current one in the text colour, the rest grey. */
@Composable
internal fun RvBottomNav(items: List<RvNavItem>, selected: Int, onSelect: (Int) -> Unit, insets: WindowInsets) {
    Column(Modifier.fillMaxWidth().background(Rv.colors.page)) {
        Row(Modifier.fillMaxWidth().height(RvSize.navBar)) {
            items.forEachIndexed { i, item ->
                val color = if (i == selected) Rv.colors.text else Rv.colors.navIdle
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(i == selected, interactionSource = null, indication = ripple(color = Rv.colors.stateLayer), role = Role.Tab) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    RvGlyph(item.icon, RvSize.navGlyph, color)
                    Spacer(Modifier.height(RvSpace.tight))
                    RvText(stringResource(item.label), Rv.type.navLabel, color = color, align = TextAlign.Center)
                }
            }
        }
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

/** A trailing value over a short line under it, both ending at the row's end, as Revolut's amount and change. */
@Composable
internal fun RvTrailingValue(value: String, note: String?, noteColor: Color) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(RvSpace.tight / 2)) {
        RvText(value, Rv.type.rowValue, align = TextAlign.End)
        note?.let { RvText(it, Rv.type.rowSub, color = noteColor, align = TextAlign.End) }
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

private val Tone.quality: Int
    get() = when (this) {
        Tone.Ok -> R.string.latency_good
        Tone.Warn -> R.string.latency_fair
        Tone.Err -> R.string.latency_slow
        Tone.Neutral -> R.string.latency_untested
    }
