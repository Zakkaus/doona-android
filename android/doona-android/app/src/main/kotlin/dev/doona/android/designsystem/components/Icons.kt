package dev.doona.android.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.icons.R as IconsR

/** A decorative glyph in [tint]; the control or text that holds it says what it means. */
@Composable
fun DoonaIcon(@DrawableRes icon: Int, tint: Color, modifier: Modifier = Modifier, size: Dp = DoonaTheme.size.icon) {
    Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = modifier.size(size))
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

/**
 * The one chevron (measured §7; S5-06, X-07): ink of 6.7 × 12.2 dp drawn in a 2 dp stroke with round ends, centred in a
 * 24 dp box, in [color]: the control grey by default, a header's or a title's colour where it follows one.
 */
@Composable
fun DoonaChevron(modifier: Modifier = Modifier, color: Color = DoonaTheme.colors.control) {
    val chevron = DoonaTheme.size
    Canvas(modifier.layoutRole(LayoutRole.Mark).size(chevron.chevronBox)) {
        val w = chevron.chevronWidth.toPx()
        val h = chevron.chevronHeight.toPx()
        val stroke = chevron.chevronStroke.toPx()
        val left = (size.width - w) / 2 + stroke / 2
        val right = (size.width + w) / 2 - stroke / 2
        val top = (size.height - h) / 2 + stroke / 2
        val bottom = (size.height + h) / 2 - stroke / 2
        val middle = size.height / 2
        val path = Path().apply {
            moveTo(left, top)
            lineTo(right, middle)
            lineTo(left, bottom)
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * The down arrow of a picker, drawn to the edges of its box. A symbol sits in a 960-unit grid with a wide margin: the
 * arrow's ink is 457 by 273 units, off centre. Drawn whole it left a picker's arrow off centre, so the box is cut to the
 * glyph's ink and the arrow sits flush on a content edge or a padding. [size] is the grid's size: the glyph scales with
 * it as an icon of that size would.
 */
@Composable
internal fun DropDownGlyph(tint: Color, modifier: Modifier = Modifier, size: Dp = DoonaTheme.size.inlineGlyph) {
    val ink = CHEVRON_DOWN_INK
    // One grid unit in dp.
    val unit = size / CHEVRON_GRID
    Box(modifier.layoutRole(LayoutRole.Chevron).size(unit * ink.width, unit * ink.height).clipToBounds()) {
        Icon(
            painterResource(IconsR.drawable.sym_keyboard_arrow_down),
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

// keyboard_arrow_down, measured from the drawable (ChevronInkTest holds it to what is drawn).
private val CHEVRON_DOWN_INK = GlyphInk(251.5f, 331.5f, 708.5f, 604.5f)
