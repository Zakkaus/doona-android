package dev.doona.android.designsystem.rv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.doona.android.R

// The add and remove controls of an editable list (owner 2026-11-26: 「要加要減的按鈕我都要看到」; on the right, 13:53).

/**
 * A page's editable list: each row that can go ends with [RvRemoveButton], whose sheet runs [onRemove] with the row's id
 * once confirmed, and [onAdd] is the [RvAddRow] that ends the list. A page given none is its approved self.
 */
@Immutable
internal class RvListEdit(val onAdd: () -> Unit, val onRemove: (String) -> Unit)

/**
 * The remove control at a row's end: the red minus on the card's disc. Its 48 dp target overhangs the row's end padding
 * by its slack, so the disc's edge sits on the card's grid.
 */
@Composable
internal fun RvRemoveButton(description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .rvTouchOverhang()
            .size(RvSize.touch)
            .testTag(RvEditPart.REMOVE)
            .rvPress(CircleShape, Role.Button, onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(RvSize.removeDisc).testTag(RvEditPart.REMOVE_DISC).clip(CircleShape).background(Rv.colors.discOnCard), contentAlignment = Alignment.Center) {
            RvGlyph(R.drawable.rv_sym_remove, RvSize.removeGlyph, Rv.colors.error)
        }
    }
}

/**
 * A row's trailing slot in an editable list: [RvRemoveButton], or on a row the list keeps (a null [RvRowRemove.onRemove])
 * the control's place left empty, so the line ends stay in one column; none outside an editable list.
 */
internal fun rvRemoveTrailing(remove: RvRowRemove?, description: String): (@Composable () -> Unit)? {
    remove ?: return null
    val onRemove = remove.onRemove ?: return { Spacer(Modifier.width(RvSize.touch - RvEditGap.slack)) }
    return { RvRemoveButton(description, onRemove) }
}

/** A row's remove control in an editable list; a null [onRemove] marks a row the list keeps (the rules' last row). */
internal class RvRowRemove(val onRemove: (() -> Unit)?)

/**
 * Every 新增 X entry (owner 2026-11-26: one add component for list cards and empty states): a list row like its siblings,
 * the plus on the card's disc in the leading visual's place, the label, and the chevron; a tap opens the add form. A
 * [subtitle] says what adding takes, under the label.
 */
@Composable
internal fun RvAddRow(label: String, onClick: () -> Unit, subtitle: String? = null) {
    RvRow({ RvIconDisc(R.drawable.rv_sym_add) }, label, subtitle, Modifier.testTag(RvEditPart.ADD), onClick = onClick) { RvChevron() }
}

/**
 * [RvAddRow] in a card of settings rows: the settings row, with the plus on the card's disc when the rows above lead with
 * one ([disc]: the DNS lists) and none when they do not (a configuration's files), so the label stands on the rows' text
 * column (LAYOUT-RULES.md R7), and the chevron.
 */
@Composable
internal fun RvSettingsAddRow(label: String, onClick: () -> Unit, disc: Boolean) {
    RvSettingsRow(label, Modifier.testTag(RvEditPart.ADD).rvRowPress(Role.Button, onClick), if (disc) R.drawable.rv_sym_add else null) { RvNavEnd(null) }
}

/**
 * The remove control's 48 dp target, overhanging the row's end padding by its slack, so the 24 dp disc's edge sits on
 * the row's padding: it reports its width less that slack, and the leading disc's height, centred, so the row's height
 * stays its own (LAYOUT-RULES.md R9).
 */
private fun Modifier.rvTouchOverhang(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val slack = RvEditGap.slack.roundToPx()
    val height = RvSize.rowDisc.roundToPx()
    layout(placeable.width - slack, height) { placeable.placeRelative(0, (height - placeable.height) / 2) }
}

/** The slack between [RvSize.touch] and [RvSize.removeDisc] on one side. */
internal object RvEditGap {
    val slack = (RvSize.touch - RvSize.removeDisc) / 2
}

internal object RvEditPart {
    const val REMOVE = "rv-remove"
    const val ADD = "rv-add"

    /** The 24 dp disc inside [REMOVE]'s 48 dp target, which the row rules gate measures (LAYOUT-RULES.md R5, R16). */
    const val REMOVE_DISC = "rv-remove-disc"
}
