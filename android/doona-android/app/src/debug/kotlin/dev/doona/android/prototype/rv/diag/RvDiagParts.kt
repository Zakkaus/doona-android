package dev.doona.android.prototype.rv.diag

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvChevron
import dev.doona.android.designsystem.rv.RvGlass
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.RvText

/**
 * A diagnostic page: a pushed page with the back disc, the large title, an optional [caption] of what it holds and a
 * [control] (its filters), then [content]. A [field] puts a capsule (a search) in the bar, as 連線's.
 */
@Composable
internal fun RvDiagPage(
    title: String,
    caption: String? = null,
    control: (@Composable () -> Unit)? = null,
    field: (@Composable (RvGlass, Modifier) -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    RvScrollPage(title, insets, onBack = {}, field = field, bottomAction = bottomAction) { titleBlock ->
        RvPageHeader(title, titleBlock, insets, caption = caption?.let { { RvCaption(it) } }, control = control)
        content()
    }
}

/** A section's title on the page, on the gutter. */
@Composable
internal fun RvDiagSection(title: String) = RvSectionTitle(title, Modifier.padding(horizontal = RvSpace.gutter))

/** A card of facts, one key-stats row each. */
@Composable
internal fun RvFactsCard(facts: List<RvFact>) {
    RvCard { facts.forEach { RvStatRow(it.label.text(), it.value.text(), valueKeepWords = false) } }
}

/** A row that opens another page: its glyph, its label, an optional value under it, and the chevron. */
@Composable
internal fun RvDiagNavRow(@DrawableRes icon: Int, title: String, subtitle: String? = null, onClick: () -> Unit = {}) {
    RvRow({ RvIconDisc(icon) }, title, subtitle, onClick = onClick, trailing = { RvChevron() })
}

/** A row's value on its title's line, in the row's value type; [trouble] in the error hue. */
@Composable
internal fun RvEndValue(text: String, trouble: Boolean = false) {
    RvText(text, Rv.type.rowValue, color = if (trouble) Rv.colors.error else Rv.colors.text, align = TextAlign.End)
}

/** A row's second value on its subtitle's line, in secondary text. */
@Composable
internal fun RvEndSub(text: String) {
    RvText(text, Rv.type.rowSub, color = Rv.colors.textSecondary, align = TextAlign.End)
}

/** A count with the thousands grouped, as both of the samples' languages write it. */
internal fun rvCount(n: Long): String = String.format(java.util.Locale.ROOT, "%,d", n)

internal const val RV_TCP = "TCP"
internal const val RV_UDP = "UDP"
