package dev.doona.android.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import dev.doona.android.designsystem.tokens.TypeScale
import dev.doona.android.designsystem.tokens.TypeStep

/** Type roles (§3) on doona's face. */
@Immutable
class DoonaType(private val family: FontFamily) {
    private fun style(step: TypeStep) = TextStyle(
        fontFamily = family,
        fontSize = step.size,
        lineHeight = step.line,
        fontWeight = FontWeight(step.weight),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

    /** Tabular figures: values that change in place keep their width. Declared first: the roles below use it. */
    private val tabular = TypeScale.TABULAR

    val pageTitle = style(TypeScale.h1)
    val cardTitle = style(TypeScale.h3)

    /** Labels, buttons and page links. */
    val label = style(TypeScale.label)
    val body = style(TypeScale.body)

    /** Node name. */
    val bodyStrong = style(TypeScale.bodyStrong)

    /** Body in tabular figures: segment labels and counts that change in place keep their width. */
    val bodyTabular = body.copy(fontFeatureSettings = tabular)

    /** Latency: the node name's weight in tabular figures. */
    val latency = bodyStrong.copy(fontFeatureSettings = tabular)

    /** Metric heads, field labels, node sub-line. */
    val caption = style(TypeScale.caption)
    val badge = style(TypeScale.captionMedium)

    /** A field's error line: the caption in the medium weight, so that the line carries the error where the tone is body text. */
    val fieldError = style(TypeScale.captionMedium)
    val tileValue = style(TypeScale.big).copy(fontFeatureSettings = tabular)
    val navLabel = style(TypeScale.captionMedium)
    val navLabelCurrent = style(TypeScale.captionSemibold)
    val axis = style(TypeScale.tick)
}
