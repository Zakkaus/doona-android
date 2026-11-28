package dev.doona.android.designsystem.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.doona.android.designsystem.theme.DoonaTheme
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * The hero figure as Revolut sets a balance (measured §5; A04): [whole], the integer, at 44 sp bold, and [part], its
 * decimals and unit, at 32 sp on the same baseline. [splitDecimals] cuts a formatted figure in two.
 */
@Composable
fun DoonaHero(whole: String, part: String, modifier: Modifier = Modifier) {
    val type = DoonaTheme.type
    val text = buildAnnotatedString {
        append(whole)
        withStyle(type.heroSmall.toSpanStyle()) { append(part) }
    }
    BasicText(text, modifier.layoutRole(LayoutRole.Figure), type.hero.copy(color = DoonaTheme.colors.text), maxLines = 1)
}

/**
 * A figure in [style], its [unit] in [unitStyle] on the same baseline (a tile's value, a card's total, the detail
 * figure): Latin only, as figures and units are, in the text colour.
 */
@Composable
fun DoonaFigureText(whole: String, unit: String, style: TextStyle, unitStyle: TextStyle, modifier: Modifier = Modifier) {
    val text = buildAnnotatedString {
        append(whole)
        withStyle(unitStyle.toSpanStyle()) { append(unit) }
    }
    BasicText(text, modifier.layoutRole(LayoutRole.Figure), style = style.copy(color = DoonaTheme.colors.text), maxLines = 1)
}
