package dev.doona.android.prototype.rv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp

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
internal fun RvGlyph(@DrawableRes icon: Int, size: Dp, color: Color, modifier: Modifier = Modifier, rotation: Float = 0f) {
    Image(painterResource(icon), contentDescription = null, modifier.size(size).rotate(rotation), colorFilter = ColorFilter.tint(color))
}

/** The screen's one tinted area: the scheme's tint fading into the page behind the header content. */
@Composable
internal fun RvHeader(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(Rv.colors.headerBrush), horizontalAlignment = Alignment.CenterHorizontally, content = content)
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
internal fun RvAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, iconRotation: Float = 0f) {
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(RvSize.actionDisc)
                .clip(CircleShape)
                .background(Rv.colors.discOnHeader)
                .rvPress(CircleShape, Role.Button, onClick),
            contentAlignment = Alignment.Center,
        ) {
            RvGlyph(icon, RvSize.actionGlyph, Rv.colors.text, rotation = iconRotation)
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

/** A row of a grouped card: an icon disc, a title over an optional subtitle, and trailing content at the end. */
@Composable
internal fun RvRow(
    @DrawableRes icon: Int,
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
        Box(Modifier.size(RvSize.rowDisc).clip(CircleShape).background(Rv.colors.discOnCard), contentAlignment = Alignment.Center) {
            RvGlyph(icon, RvSize.rowGlyph, Rv.colors.text)
        }
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
