package dev.doona.android.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.flags.flagResource
import dev.doona.android.icons.R as IconsR

/** A decorative glyph in [tint]; the control or text that holds it says what it means. */
@Composable
fun DoonaIcon(@DrawableRes icon: Int, tint: Color, modifier: Modifier = Modifier, size: Dp = DoonaTheme.size.icon) {
    Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/**
 * The circle flag of [code] ahead of a node name: an ISO 3166-1 alpha-2 code, or another code circle-flags files a flag
 * under, in any case. A code the set lacks gets a neutral disc with its first two letters, so a row has no gap where a
 * flag should be. [label] names the flag for a screen reader.
 */
@Composable
fun DoonaFlag(code: String, label: String, modifier: Modifier = Modifier) {
    val flag = flagResource(code)
    val edge = DoonaTheme.colors.flagRing
    val disc = modifier
        .size(DoonaTheme.size.flag)
        .border(DoonaTheme.size.flagEdge, edge, DoonaTheme.shape.flag)
    if (flag != null) {
        Image(painterResource(flag), contentDescription = label, modifier = disc)
        return
    }
    // The letters keep their size at any font scale: they must fit the disc, which does not grow.
    val letters = with(LocalDensity.current) { DoonaTheme.size.flagLetters.toSp() }
    Box(disc.background(DoonaTheme.colors.flagFallback, DoonaTheme.shape.flag).semantics { contentDescription = label }, Alignment.Center) {
        DoonaText(
            code.take(2).uppercase(),
            DoonaTheme.type.badge.copy(fontSize = letters, lineHeight = letters),
            color = DoonaTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

/**
 * How far the drawn shape of an icon sits inside its box, as a share of the box, from the side it starts and the side it
 * ends: measured from the drawables, and held to what is drawn by IconInkTest. A layout that must put the shape and not
 * the box on a line (the ends of the top bar) reads it here.
 */
internal object DoonaIconInk {
    private class Ink(val start: Float, val end: Float)

    private val table = mapOf(
        IconsR.drawable.sym_arrow_back to Ink(0.178f, 0.158f),
        IconsR.drawable.sym_search to Ink(0.117f, 0.137f),
        IconsR.drawable.sym_edit to Ink(0.1165f, 0.1163f),
        IconsR.drawable.sym_add_circle to Ink(0.075f, 0.075f),
        IconsR.drawable.sym_link to Ink(0.075f, 0.075f),
        IconsR.drawable.sym_more_vert to Ink(0.413f, 0.413f),
        R.drawable.ic_duck to Ink(0.089f, 0.089f),
    )

    /** The icons the table covers. */
    val icons: Set<Int> get() = table.keys

    /** Distance from the start edge of an icon drawn in a box of [box] to where its shape starts. */
    fun start(@DrawableRes icon: Int, box: Dp): Dp = box * ink(icon).start

    /** Distance from where its shape ends to the end edge of the box. */
    fun end(@DrawableRes icon: Int, box: Dp): Dp = box * ink(icon).end

    private fun ink(icon: Int) = checkNotNull(table[icon]) { "no ink measured for icon $icon: add it to DoonaIconInk" }
}

enum class ChevronDirection { Down, Right }

/**
 * Disclosure chevron, drawn to the edges of its box. A symbol sits in a 960-unit grid with a wide margin: the down
 * chevron's ink is 457 by 273 units, the right one's 273 by 457, each off centre. Drawn whole they left a picker's
 * chevron off centre and a card's inside its edge. The box is cut to the glyph's ink, so a chevron can sit flush on a
 * content edge or a padding. [size] is the grid's size: the glyph scales with it as an icon of that size would.
 */
@Composable
fun DoonaChevron(
    direction: ChevronDirection,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = if (direction == ChevronDirection.Down) DoonaTheme.size.inlineGlyph else DoonaTheme.size.iconSmall,
) {
    val down = direction == ChevronDirection.Down
    val ink = if (down) CHEVRON_DOWN_INK else CHEVRON_RIGHT_INK
    // One grid unit in dp.
    val unit = size / CHEVRON_GRID
    Box(modifier.layoutRole(LayoutRole.Chevron).size(unit * ink.width, unit * ink.height).clipToBounds()) {
        Icon(
            painterResource(if (down) IconsR.drawable.sym_keyboard_arrow_down else IconsR.drawable.sym_chevron_right),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).size(size).offset(unit * -ink.left, unit * -ink.top),
        )
    }
}

/** Where a glyph's shape lies in the 960-unit grid of its symbol. */
private class GlyphInk(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

private const val CHEVRON_GRID = 960f

// keyboard_arrow_down and chevron_right, measured from the drawables (ChevronInkTest holds them to what is drawn).
private val CHEVRON_DOWN_INK = GlyphInk(251.5f, 331.5f, 708.5f, 604.5f)
private val CHEVRON_RIGHT_INK = GlyphInk(331.5f, 251.5f, 604.5f, 708.5f)
