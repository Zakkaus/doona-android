package dev.doona.android.feature.stats

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.doona.android.R
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvDeltaTriangle
import dev.doona.android.designsystem.rv.RvFigure
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvSpanText
import dev.doona.android.designsystem.rv.scripted

/** A card's figure: the number in [style], its unit in [unitStyle] on the same baseline. */
@Composable
internal fun RvStatsFigure(figure: RvFigure, style: TextStyle = Rv.type.cardValue, unitStyle: TextStyle = Rv.type.cardUnit) {
    val text = buildAnnotatedString {
        append(scripted(figure.whole))
        withStyle(unitStyle.toSpanStyle()) { append(scripted(figure.unit)) }
    }
    RvSpanText(text, style.copy(color = Rv.colors.text), maxLines = 1)
}

/** Revolut's delta: a filled triangle and the change in [color], then what it compares with in secondary ink. */
@Composable
internal fun RvStatsDelta(up: Boolean, change: String, against: RvStatsAgainst, color: Color) {
    // The triangle and the hue show the direction; a screen reader is told it in words, for the period the line names.
    val spoken = stringResource(if (up) against.up else against.down, change)
    val line = stringResource(against.line)
    Row(verticalAlignment = Alignment.CenterVertically) {
        RvDeltaTriangle(up, color)
        Spacer(Modifier.width(RvSpace.tight))
        val text = buildAnnotatedString {
            withStyle(SpanStyle(color = color)) { append(change) }
            append(" ")
            append(scripted(line))
        }
        RvSpanText(text, Rv.type.tileCaption.copy(color = Rv.colors.textSecondary), Modifier.semantics { contentDescription = spoken }, maxLines = 1)
    }
}

/** A row of the tools card: its glyph, title and subtitle, and what a tap does. */
internal class RvStatsTool(@DrawableRes val icon: Int, val title: String, val subtitle: String, val onClick: (() -> Unit)?)
