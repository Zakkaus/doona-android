package dev.doona.android.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.scripted

/**
 * A card (X-02, X-03): a 20 dp rounded fill in the card colour, one step off the page, with no border and no shadow; the
 * page is its only frame. Rows run edge to edge inside it and set their own 16 dp inset, so by default it has no
 * padding: a card of free content passes [contentPadding] and its [verticalArrangement]. With [onClick] the whole card
 * is the button.
 */
@Composable
fun DoonaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = DoonaCardDefaults.flush,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = DoonaTheme.shape.card
    Column(
        modifier
            .layoutRole(if (contentPadding == DoonaCardDefaults.flush) LayoutRole.ListCard else LayoutRole.Card)
            .fillMaxWidth()
            .clip(shape)
            .background(DoonaTheme.colors.card)
            .then(onClick?.let { Modifier.press(shape, Role.Button, it) } ?: Modifier)
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/**
 * A card's in-card header (measured §7 `listSubheader`), for a card that is a widget on a home or overview page (X-05):
 * 19 sp medium secondary text in a 48 dp row, then an optional [tag], and, only when it opens something ([onClick]),
 * a chevron in the header's colour; then the whole row is the button.
 */
@Composable
fun DoonaCardHeader(title: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, tag: String? = null) {
    val press = onClick?.let { Modifier.press(DoonaTheme.shape.card, Role.Button, it) } ?: Modifier
    Row(
        modifier
            .layoutRole(LayoutRole.Heading)
            .padding(top = DoonaTheme.spacing.headerInset)
            .fillMaxWidth()
            .height(DoonaTheme.size.headerRow)
            .then(press)
            .padding(horizontal = DoonaTheme.spacing.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineText(title, DoonaTheme.type.cardTitle, Modifier.weight(1f, fill = false).semantics { heading() }, color = DoonaTheme.colors.textSecondary)
        tag?.let { DoonaTag(it, Modifier.padding(start = DoonaTheme.spacing.subGap)) }
        if (onClick != null) DoonaChevron(color = DoonaTheme.colors.textSecondary)
    }
}

/**
 * An outside section title (measured §7, §13.4), for a run of list cards on a settings or detail page (X-05): 20 sp
 * bold, 24 dp under what is above it and 12 dp above its card; with [onClick], a chevron in the title's colour, and the
 * title opens it.
 */
@Composable
fun DoonaSectionTitle(title: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val press = onClick?.let { Modifier.press(RoundedCornerShape(DoonaTheme.spacing.tight), Role.Button, it) } ?: Modifier
    Row(
        modifier.layoutRole(LayoutRole.Heading).padding(top = DoonaTheme.spacing.sectionAbove, bottom = DoonaTheme.spacing.sectionBelow).then(press),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineText(title, DoonaTheme.type.section, Modifier.semantics { heading() })
        if (onClick != null) DoonaChevron(color = DoonaTheme.colors.text)
    }
}

internal object DoonaCardDefaults {
    val flush = PaddingValues()

    /** The old components' card: free content 16 dp in, 12 dp apart. */
    val inset get() = DoonaTheme.spacing.cardInset
    val gap get() = DoonaTheme.spacing.block
}

/** The old components' card of free content: [DoonaCard] with their inset and gap. */
@Composable
internal fun InsetCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) =
    DoonaCard(modifier, onClick, PaddingValues(DoonaCardDefaults.inset), Arrangement.spacedBy(DoonaCardDefaults.gap), content)

/** Control card (§7): icon and 14/500 label, controls at the end of the same row. */
@Composable
fun DoonaControlCard(
    @DrawableRes icon: Int,
    label: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit,
) {
    InsetCard(modifier) {
        Row(
            Modifier.layoutRole(LayoutRole.Row, mergeDescendants = true).heightIn(min = DoonaRowDefaults.minHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DoonaIcon(icon, DoonaTheme.colors.textSecondary)
            Spacer(Modifier.width(DoonaTheme.spacing.item))
            Text(scripted(label), style = DoonaTheme.type.label, color = DoonaTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(DoonaTheme.spacing.block))
            trailing()
        }
    }
}

/** Metric tiles side by side, as high as the highest. */
@Composable
fun DoonaTileRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.block),
        content = content,
    )
}

/** `.rp-tile-head`: the 12/16 caption of a metric tile, in the secondary colour. */
@Composable
fun DoonaTileCaption(text: String, modifier: Modifier = Modifier) {
    DoonaText(text, modifier = modifier, style = DoonaTheme.type.footnote, color = DoonaTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * Dashboard tile (§7): a head of icon and [caption] at control height, then the [value]. With [onClick] the whole
 * tile is the touch target, so the head keeps doona's control height.
 */
@Composable
fun DoonaMetricTile(
    @DrawableRes icon: Int,
    caption: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    value: @Composable () -> Unit,
) {
    InsetCard(modifier, onClick) {
        Row(Modifier.heightIn(min = DoonaTheme.size.control), verticalAlignment = Alignment.CenterVertically) {
            DoonaIcon(icon, DoonaTheme.colors.textSecondary)
            DoonaHGap(DoonaTheme.spacing.item)
            caption()
        }
        value()
    }
}
