package dev.doona.android.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme

/** A decorative glyph in [tint]; the control or text that holds it says what it means. */
@Composable
fun DoonaIcon(@DrawableRes icon: Int, tint: Color, modifier: Modifier = Modifier, size: Dp = DoonaTheme.size.icon) {
    Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** Twemoji flag ahead of a node name (doona puts the flag glyph first, `src/ui/flags.css`). */
@Composable
fun DoonaFlag(@DrawableRes flag: Int, label: String, modifier: Modifier = Modifier) {
    val painter = painterResource(flag)
    val ratio = painter.intrinsicSize.width / painter.intrinsicSize.height
    val edge = DoonaTheme.colors.flagEdge
    Image(
        painter,
        contentDescription = label,
        modifier = modifier
            .height(DoonaTheme.size.flagHeight)
            .aspectRatio(ratio)
            .then(if (edge != null) Modifier.border(DoonaTheme.size.flagEdge, edge, DoonaTheme.shape.flag) else Modifier),
    )
}

/**
 * How far the drawn shape of an icon sits inside its box, as a share of the box, from the side it starts and the side it
 * ends: measured from the drawables, and held to what is drawn by IconInkTest. A layout that must put the shape and not
 * the box on a line (the ends of the top bar) reads it here.
 */
internal object DoonaIconInk {
    private class Ink(val start: Float, val end: Float)

    private val table = mapOf(
        R.drawable.ic_arrow_back to Ink(0.125f, 0.1375f),
        R.drawable.ic_search to Ink(0.05f, 0.047f),
        R.drawable.ic_edit to Ink(0.05f, 0.051f),
        R.drawable.ic_add_circle to Ink(0.075f, 0.075f),
        R.drawable.ic_link to Ink(0.0625f, 0.0625f),
        R.drawable.ic_more_vertical to Ink(0.425f, 0.425f),
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
 * Disclosure chevron, drawn to the edges of its box. Spectrum's chevron sits in a 20-unit grid with 3.5 units of
 * padding at the sides and 7 above and below, which left a picker's chevron 2.7 dp off centre and a card's 6 dp inside
 * its edge. The box is cut to the glyph's ink, so a chevron can sit flush on a content edge or a padding. [size] is
 * the grid's size: the glyph scales with it as an icon of that size would.
 */
@Composable
fun DoonaChevron(
    direction: ChevronDirection,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = if (direction == ChevronDirection.Down) DoonaTheme.size.iconInline else DoonaTheme.size.iconSmall,
) {
    val down = direction == ChevronDirection.Down
    // Ink of ChevronDown in grid units: x 3.55 to 16.5, y 6.93 to 14.0. Pointing right it is turned by -90 degrees
    // around the centre of the grid, so x' = y and y' = grid - x.
    val left = if (down) CHEVRON_LEFT else CHEVRON_TOP
    val top = if (down) CHEVRON_TOP else CHEVRON_GRID - CHEVRON_RIGHT
    val width = if (down) CHEVRON_RIGHT - CHEVRON_LEFT else CHEVRON_BOTTOM - CHEVRON_TOP
    val height = if (down) CHEVRON_BOTTOM - CHEVRON_TOP else CHEVRON_RIGHT - CHEVRON_LEFT
    // One grid unit in dp.
    val unit = size / CHEVRON_GRID
    Box(modifier.layoutRole(LayoutRole.Chevron).size(unit * width, unit * height).clipToBounds()) {
        Icon(
            painterResource(if (down) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).size(size).offset(unit * -left, unit * -top),
        )
    }
}

private const val CHEVRON_GRID = 20f
private const val CHEVRON_LEFT = 3.55f
private const val CHEVRON_RIGHT = 16.5f
private const val CHEVRON_TOP = 6.93f
private const val CHEVRON_BOTTOM = 14f
