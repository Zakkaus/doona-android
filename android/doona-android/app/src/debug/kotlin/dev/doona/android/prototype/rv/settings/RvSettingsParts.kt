package dev.doona.android.prototype.rv.settings

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.doona.android.R
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvGlyph
import dev.doona.android.prototype.rv.rvPress
import dev.doona.android.prototype.rv.scripted

/** Text in a role; CJK runs in Noto, the rest in Inter. [maxLines] defaults to one, ending in an ellipsis. */
@Composable
internal fun RvSettingsText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Rv.colors.text,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = 1,
) {
    BasicText(scripted(text), modifier, style.copy(color = color, textAlign = align), overflow = TextOverflow.Ellipsis, maxLines = maxLines)
}

/** A grouped card, 20 dp round: rows run edge to edge, with no divider, border or shadow. */
@Composable
internal fun RvSettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(RvSettingsSize.cardRadius)).background(Rv.colors.card), content = content)
}

/** The title of a group of cards, as Revolut's Accessibility page: 24 dp above, 12 dp between it and its card. */
@Composable
internal fun RvSettingsSection(title: String, modifier: Modifier = Modifier) {
    val pad = Modifier.padding(top = RvSettingsSpace.sectionAbove, bottom = RvSettingsSpace.sectionBelow)
    RvSettingsText(title, RvSettingsType.section, modifier.then(pad).semantics { heading() })
}

/** A group's explanation under its card, in secondary text. */
@Composable
internal fun RvSettingsFooter(text: String, modifier: Modifier = Modifier) {
    val pad = Modifier.padding(start = RvSettingsSpace.footerInset, end = RvSettingsSpace.footerInset, top = RvSettingsSpace.footerAbove)
    RvSettingsText(text, RvSettingsType.footer, modifier.then(pad), color = Rv.colors.textSecondary, maxLines = FOOTER_LINES)
}

/**
 * A row of a card: a 24 dp glyph, a title over an optional subtitle, and trailing content. The glyph and the trailing
 * control centre on the first line of text, which is where Revolut's rows keep them when the subtitle wraps. The row's
 * touch behaviour comes in [modifier], outside the padding, so the whole row is the target.
 */
@Composable
internal fun RvSettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val min = if (subtitle == null) RvSettingsSize.rowOneLine else RvSettingsSize.rowTwoLine
    Row(
        modifier.fillMaxWidth().heightIn(min = min).padding(horizontal = RvSettingsSpace.rowInset, vertical = RvSettingsSpace.rowPad),
        verticalAlignment = Alignment.Top,
    ) {
        icon?.let {
            FirstLine { RvGlyph(it, RvSettingsSize.glyph, Rv.colors.text) }
            Spacer(Modifier.width(RvSettingsSpace.glyphGap))
        }
        Column(Modifier.weight(1f)) {
            RvSettingsText(title, RvSettingsType.rowTitle, maxLines = TITLE_LINES)
            subtitle?.let { RvSettingsText(it, RvSettingsType.rowSub, color = Rv.colors.textSecondary, maxLines = SUBTITLE_LINES) }
        }
        trailing?.let {
            Spacer(Modifier.width(RvSettingsSpace.trailingGap))
            FirstLine(it)
        }
    }
}

@Composable
private fun FirstLine(content: @Composable () -> Unit) {
    Box(Modifier.height(RvSettingsSize.titleLine), contentAlignment = Alignment.Center) { content() }
}

/** A row that opens something: an optional [value] in secondary text, then a chevron. */
@Composable
internal fun RvSettingsNavRow(title: String, @DrawableRes icon: Int, onClick: () -> Unit, value: String? = null) {
    RvSettingsRow(title, Modifier.rvPress(RectangleShape, Role.Button, onClick), icon) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            value?.let {
                RvSettingsText(it, RvSettingsType.value, color = Rv.colors.textSecondary)
                Spacer(Modifier.width(RvSettingsSpace.valueGap))
            }
            RvGlyph(R.drawable.rv_sym_chevron_right, RvSettingsSize.chevron, Rv.colors.reference)
        }
    }
}

/** A row that only shows a [value]. */
@Composable
internal fun RvSettingsValueRow(title: String, @DrawableRes icon: Int, value: String) {
    RvSettingsRow(title, icon = icon) { RvSettingsText(value, RvSettingsType.value, color = Rv.colors.textSecondary) }
}

/** A row whose whole width toggles its switch. */
@Composable
internal fun RvSettingsSwitchRow(title: String, @DrawableRes icon: Int, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    val toggle = Modifier.toggleable(
        value = checked,
        interactionSource = null,
        indication = ripple(color = Rv.colors.stateLayer),
        role = Role.Switch,
        onValueChange = onChange,
    )
    RvSettingsRow(title, toggle, icon, subtitle) { RvSwitch(checked) }
}

/** A row of a radio list: the title, and the radio at the end. */
@Composable
internal fun RvSettingsRadioRow(title: String, selected: Boolean, onSelect: () -> Unit) {
    val choose = Modifier.selectable(
        selected = selected,
        interactionSource = null,
        indication = ripple(color = Rv.colors.stateLayer),
        role = Role.RadioButton,
        onClick = onSelect,
    )
    RvSettingsRow(title, choose) { RvRadio(selected) }
}

/**
 * Revolut's switch (§13.4): a 50 × 27 dp node holding a 34 × 17 dp track and a 19 dp thumb that is taller than the track.
 * On, the thumb is the accent and the track its half-strength tint; off, a grey track under a near-white thumb. The row
 * around it owns the toggling.
 */
@Composable
internal fun RvSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    val track = when {
        checked -> colors.positive.copy(alpha = RvSettingsAlpha.SWITCH_TRACK)
        colors.dark -> colors.discOnCard
        else -> colors.reference
    }
    val thumb = when {
        checked -> colors.positive
        colors.dark -> colors.textSecondary
        else -> colors.raised
    }
    val off = (RvSettingsSize.switchWidth - RvSettingsSize.switchTrackWidth) / 2 - (RvSettingsSize.switchThumb - RvSettingsSize.switchTrackHeight) / 2
    val x by animateDpAsState(if (checked) off + RvSettingsSize.switchTrackWidth - RvSettingsSize.switchTrackHeight else off, label = "thumb")
    Box(modifier.size(RvSettingsSize.switchWidth, RvSettingsSize.switchHeight).clearAndSetSemantics {}) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(RvSettingsSize.switchTrackWidth, RvSettingsSize.switchTrackHeight)
                .clip(CircleShape)
                .background(track),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = x)
                .size(RvSettingsSize.switchThumb)
                .shadow(RvSettingsSize.switchThumbShadow, CircleShape)
                .background(thumb, CircleShape),
        )
    }
}

/** A radio: a 2 dp ring, in the accent with a dot inside once selected. */
@Composable
internal fun RvRadio(selected: Boolean, modifier: Modifier = Modifier) {
    val ring = if (selected) Rv.colors.positive else Rv.colors.textSecondary
    Canvas(modifier.size(RvSettingsSize.radio).clearAndSetSemantics {}) {
        val stroke = RvSettingsSize.radioStroke.toPx()
        drawCircle(ring, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
        if (selected) drawCircle(ring, radius = RvSettingsSize.radioDot.toPx() / 2)
    }
}

/** A disc with a [glyph] in it and a [label] under it; only the disc lights up when pressed. */
@Composable
internal fun RvSettingsAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(RvSettingsSize.actionDisc)
                .clip(CircleShape)
                .background(Rv.colors.roundButton)
                .rvPress(CircleShape, Role.Button, onClick),
            contentAlignment = Alignment.Center,
        ) { RvGlyph(icon, RvSettingsSize.actionGlyph, Rv.colors.text) }
        Spacer(Modifier.height(RvSettingsSpace.discToLabel))
        RvSettingsText(label, RvSettingsType.action, align = TextAlign.Center)
    }
}

private const val TITLE_LINES = 2
private const val SUBTITLE_LINES = 2
private const val FOOTER_LINES = 3
