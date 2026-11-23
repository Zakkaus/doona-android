package dev.doona.android.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.DoonaUsage

/**
 * A row of a list card (measured §7): [leading] (a 40 dp flag or [DoonaIconDisc]) 16 dp from the card's edge and 16 dp
 * before the text, [title] over an optional [subtitle], and [trailing] content ending 16 dp from the edge (A05, N12,
 * X-08). One line is 58 dp, two lines 82 dp (S04). With [onClick] the whole row is the button; the card clips the press
 * of its first and last row to its corners. A screen reader reads the row as one line.
 */
@Composable
fun DoonaListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val size = DoonaTheme.size
    val space = DoonaTheme.spacing
    val press = onClick?.let { Modifier.press(RectangleShape, Role.Button, it) } ?: Modifier
    Row(
        modifier
            .layoutRole(LayoutRole.ListRow, mergeDescendants = true)
            .fillMaxWidth()
            .heightIn(min = if (subtitle == null) size.rowOneLine else size.rowTwoLine)
            .then(press)
            .padding(horizontal = space.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            it()
            Spacer(Modifier.width(space.rowGap))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(space.tight / 2)) {
            LineText(title, DoonaTheme.type.rowTitle)
            subtitle?.let { LineText(it, DoonaTheme.type.rowSub, color = DoonaTheme.colors.textSecondary) }
        }
        trailing?.let {
            Spacer(Modifier.width(space.trailingGap))
            it()
        }
    }
}

/** A row's leading disc: [icon] in [color] on the fill one step above the card. */
@Composable
fun DoonaIconDisc(@DrawableRes icon: Int, modifier: Modifier = Modifier, color: Color = DoonaTheme.colors.text) {
    Box(modifier.size(DoonaTheme.size.rowDisc).clip(CircleShape).background(DoonaTheme.colors.discOnCard), contentAlignment = Alignment.Center) {
        DoonaGlyph(icon, DoonaTheme.size.rowGlyph, color)
    }
}

/** A row's trailing value (19 sp semibold, tabular) at the end, over an optional [second] line such as a [DoonaDelta]. */
@Composable
fun DoonaTrailingValue(value: String, modifier: Modifier = Modifier, second: (@Composable () -> Unit)? = null) {
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.tight / 2)) {
        LineText(value, DoonaTheme.type.rowValue, align = TextAlign.End)
        second?.invoke()
    }
}

/** Which way a [DoonaDelta] went. */
enum class DeltaDirection { Up, Down, None }

/**
 * A change since the value before, as Revolut writes a delta (measured §7): a filled 8 dp triangle pointing its
 * [direction], then [text], both in [tone]'s hue. No change ([DeltaDirection.None]) has no triangle and shows [text]
 * (a dash, say) in the secondary ink.
 */
@Composable
fun DoonaDelta(text: String, direction: DeltaDirection, tone: Tone, modifier: Modifier = Modifier) {
    val colors = DoonaTheme.colors
    if (direction == DeltaDirection.None) return LineText(text, DoonaTheme.type.rowSub, modifier, color = colors.textSecondary)
    val color = toneColor(colors, tone)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DoonaUsage.deltaGap)) {
        Canvas(Modifier.size(DoonaUsage.delta)) {
            val h = size.width * DoonaRowDefaults.TRIANGLE_HEIGHT
            val top = (size.height - h) / 2
            val path = Path().apply {
                if (direction == DeltaDirection.Up) {
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
        LineText(text, DoonaTheme.type.rowSub, color = color)
    }
}

/** Revolut's 'See all' row at a list card's foot (measured §7): [text] centred, 19 sp bold in the accent, in 56 dp. */
@Composable
fun DoonaSeeAll(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val radius = DoonaTheme.size.cardRadius
    val shape = RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
    Box(
        modifier
            .layoutRole(LayoutRole.ListRow)
            .fillMaxWidth()
            .heightIn(min = DoonaTheme.size.seeAll)
            .press(shape, Role.Button, onClick)
            .padding(horizontal = DoonaTheme.spacing.rowPadding),
        contentAlignment = Alignment.Center,
    ) {
        LineText(text, DoonaTheme.type.seeAll, color = DoonaTheme.colors.positive, align = TextAlign.Center)
    }
}

/** A row of Revolut's Key stats card (measured §11): [label] 19 sp medium in secondary ink, [value] 19 sp at the end. */
@Composable
fun DoonaStatRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .layoutRole(LayoutRole.ListRow, mergeDescendants = true)
            .fillMaxWidth()
            .heightIn(min = DoonaTheme.size.rowOneLine)
            .padding(horizontal = DoonaTheme.spacing.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineText(label, DoonaTheme.type.statLabel, Modifier.weight(1f), color = DoonaTheme.colors.textSecondary)
        LineText(value, DoonaTheme.type.stat, align = TextAlign.End)
    }
}

internal object DoonaRowDefaults {
    /** The old components' row minimum: the touch target. */
    val minHeight get() = DoonaTheme.size.touch

    /** An equilateral triangle is this share of its width high. */
    const val TRIANGLE_HEIGHT = 0.866f
}
