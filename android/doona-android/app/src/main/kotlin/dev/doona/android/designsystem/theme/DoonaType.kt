package dev.doona.android.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.withStyle
import dev.doona.android.designsystem.tokens.Inter
import dev.doona.android.designsystem.tokens.InterDisplay
import dev.doona.android.designsystem.tokens.NotoSansTc
import dev.doona.android.designsystem.tokens.TypeScale
import dev.doona.android.designsystem.tokens.TypeStep

/**
 * Type roles. Latin is set in Inter, its display cut for figures and titles; every run of CJK in Noto Sans TC, which
 * [scripted] applies to a string. The sample's roles keep its exact styles; the old components' roles keep U1's sizes
 * and weights on the new families.
 */
@Immutable
class DoonaType {
    val hero = TypeScale.hero.style(tabular = true)
    val heroSmall = TypeScale.heroSmall.style(tabular = true)
    val detailFigure = TypeScale.detailFigure.style(tabular = true)
    val detailUnit = TypeScale.detailUnit.style()
    val caption = TypeScale.caption.style()
    val sub = TypeScale.sub.style(tabular = true)
    val rowTitle = TypeScale.rowTitle.style()

    /** A figure in a row's title size: a trailing value or a legend's rate. */
    val rowValue = TypeScale.rowTitle.style(tabular = true)
    val rowSub = TypeScale.rowSub.style()
    val action = TypeScale.action.style()
    val segment = TypeScale.segment.style()
    val field = TypeScale.field.style()
    val tileCaption = TypeScale.tileCaption.style()
    val tileValue = TypeScale.tileValue.style(tabular = true)
    val tileUnit = TypeScale.tileUnit.style()
    val cardValue = TypeScale.cardValue.style(tabular = true)
    val cardUnit = TypeScale.cardUnit.style()
    val pill = TypeScale.pill.style(tabular = true)
    val section = TypeScale.section.style()
    val statLabel = TypeScale.statLabel.style()
    val stat = TypeScale.stat.style(tabular = true)
    val largeTitle = TypeScale.largeTitle.style()
    val barTitle = TypeScale.barTitle.style()
    val navLabel = TypeScale.navLabel.style()
    val flagCode = TypeScale.flagCode.style()
    val search = TypeScale.search.style()
    val cardTitle = TypeScale.cardTitle.style()
    val seeAll = TypeScale.seeAll.style()
    val settingValue = TypeScale.settingValue.style()

    // The old components' roles: U1's steps, their line box centred and untrimmed as before, so their layout keeps its size.

    val pageTitle = TypeScale.h1.legacy()

    /** A block's title: a dialog's, an empty state's, a group card's. */
    val blockTitle = TypeScale.h3.legacy()

    /** Labels, buttons and page links. */
    val label = TypeScale.label.legacy()
    val body = TypeScale.body.legacy()

    /** Node name. */
    val bodyStrong = TypeScale.bodyStrong.legacy()

    /** Body in tabular figures: segment labels and counts that change in place keep their width. */
    val bodyTabular = TypeScale.body.legacy(tabular = true)

    /** Latency: the node name's weight in tabular figures. */
    val latency = TypeScale.bodyStrong.legacy(tabular = true)

    /** Metric heads, field labels, node sub-line. */
    val footnote = TypeScale.footnote.legacy()
    val badge = TypeScale.footnoteMedium.legacy()

    /** A field's error line: the footnote in the medium weight, so that the line carries the error where the tone is body text. */
    val fieldError = TypeScale.footnoteMedium.legacy()

    /** An old metric tile's value. */
    val metricValue = TypeScale.big.legacy(tabular = true)

    /** The old hub bar's tab label, and the current tab's. */
    val tabLabel = TypeScale.footnoteMedium.legacy()
    val tabLabelCurrent = TypeScale.footnoteSemibold.legacy()
    val axis = TypeScale.tick.legacy()

    // Tabular figures only where figures line up or change in place: Inter's tnum also widens the hyphen of a name.
    private fun TypeStep.style(tabular: Boolean = false) = TextStyle(
        fontFamily = if (display) InterDisplay else Inter,
        fontWeight = weight,
        fontSize = size,
        lineHeight = line,
        fontFeatureSettings = if (tabular) TypeScale.TABULAR else null,
    )

    private fun TypeStep.legacy(tabular: Boolean = false) =
        style(tabular).copy(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))
}

/** [text] with every run of CJK set in Noto Sans TC; the rest keeps the style's Inter. */
fun scripted(text: String): AnnotatedString = buildAnnotatedString {
    var start = 0
    while (start < text.length) {
        val cjk = text[start].isCjk()
        var end = start + 1
        while (end < text.length && (text[end].isCjk() == cjk || text[end] == ' ')) end++
        if (cjk) withStyle(SpanStyle(fontFamily = NotoSansTc)) { append(text, start, end) } else append(text, start, end)
        start = end
    }
}

/** A text field's input style: its input cannot carry spans cheaply, so it stays in Noto Sans TC, which has Latin too. */
fun TextStyle.forInput(): TextStyle = copy(fontFamily = NotoSansTc)

private fun Char.isCjk() = code >= CJK_START

/** CJK Radicals Supplement: everything from here up is set in Noto. */
private const val CJK_START = 0x2E80
