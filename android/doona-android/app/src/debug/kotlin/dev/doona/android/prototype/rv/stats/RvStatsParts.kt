package dev.doona.android.prototype.rv.stats

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvFigure
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.scripted

/** A card's figure: the number in [style], its unit in [unitStyle] on the same baseline. */
@Composable
internal fun RvStatsFigure(figure: RvFigure, style: TextStyle = Rv.type.cardValue, unitStyle: TextStyle = Rv.type.cardUnit) {
    val text = buildAnnotatedString {
        append(scripted(figure.whole))
        withStyle(unitStyle.toSpanStyle()) { append(scripted(figure.unit)) }
    }
    BasicText(text, style = style.copy(color = Rv.colors.text), maxLines = 1)
}

/** Revolut's delta: a filled triangle and the change in [color], then what it compares with in secondary ink. */
@Composable
internal fun RvStatsDelta(up: Boolean, change: String, against: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Triangle(up, color, RvStatsSize.delta)
        Spacer(Modifier.width(RvSpace.tight))
        val text = buildAnnotatedString {
            withStyle(SpanStyle(color = color)) { append(change) }
            append(" ")
            append(scripted(against))
        }
        BasicText(text, style = Rv.type.tileCaption.copy(color = Rv.colors.textSecondary), maxLines = 1)
    }
}

@Composable
private fun Triangle(up: Boolean, color: Color, side: Dp) {
    Canvas(Modifier.size(side)) {
        val (w, h) = size.width to size.height
        val path = Path().apply {
            if (up) {
                moveTo(w / 2, 0f)
                lineTo(w, h)
                lineTo(0f, h)
            } else {
                moveTo(0f, 0f)
                lineTo(w, 0f)
                lineTo(w / 2, h)
            }
            close()
        }
        drawPath(path, color)
    }
}

/** A legend entry's rounded square, Revolut's 9 dp. */
@Composable
internal fun RvStatsSwatch(color: Color) {
    Box(Modifier.size(RvStatsSize.legendSquare).clip(RoundedCornerShape(RvStatsSize.legendRadius)).background(color))
}

/** The pager's dots under the tiles: the current one larger and darker, the others in the control grey (3:1 on the page, S06). */
@Composable
internal fun RvStatsDots(count: Int, current: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            Box(Modifier.width(RvStatsSize.dotPitch), contentAlignment = Alignment.Center) {
                val size = if (i == current) RvStatsSize.dot else RvStatsSize.dotIdle
                Box(Modifier.size(size).clip(CircleShape).background(if (i == current) Rv.colors.textSecondary else Rv.colors.control))
            }
        }
    }
}

/** A row of the tools card: its glyph, title and subtitle. */
internal class RvStatsTool(@DrawableRes val icon: Int, val title: String, val subtitle: String)

/** The tag on a card whose data the v0.1 core does not report, so the sample invents it. */
internal const val RV_V02 = "v0.2"
