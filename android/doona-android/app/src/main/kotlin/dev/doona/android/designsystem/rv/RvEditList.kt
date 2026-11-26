package dev.doona.android.designsystem.rv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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

// The add and remove controls of an editable list (owner 2026-11-26: 「要加要減的按鈕我都要看到」).

/**
 * Where a list shows its remove control; the owner chooses one (review question, 2026-11-26).
 * [Editing]: the bar's 編輯 turns on edit mode, which puts [RvRemoveDisc] at each row's start (A).
 * [Trailing]: [RvRemoveButton] stands at each row's end at all times (B).
 * Both end the list's card with [RvAddRow].
 */
internal enum class RvRemoveStyle { Editing, Trailing }

/**
 * A page's list in style A ([RvRemoveStyle.Editing]): the bar's 編輯 runs [onEdit] with true and 完成 with false; while
 * [editing] each row that can go starts with [RvRemoveDisc], whose sheet runs [onRemove] with the row's id once
 * confirmed, and [onAdd] is the [RvAddRow] that ends the list. A page given none is its approved self.
 */
@Immutable
internal class RvListEdit(
    val editing: Boolean,
    val onEdit: (Boolean) -> Unit = {},
    val onAdd: () -> Unit = {},
    val onRemove: (String) -> Unit = {},
)

/** A: the filled remove disc at a row's start in edit mode, in the scheme's one red, its minus cut in the card's colour. */
@Composable
internal fun RvRemoveDisc(description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(RvSize.touch)
            .testTag(RvEditPart.REMOVE)
            .rvPress(CircleShape, Role.Button, onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(RvSize.removeDisc).testTag(RvEditPart.REMOVE_DISC).clip(CircleShape).background(Rv.colors.error), contentAlignment = Alignment.Center) {
            RvGlyph(R.drawable.rv_sym_remove, RvSize.removeGlyph, Rv.colors.card)
        }
    }
}

/**
 * B: the remove control at a row's end, always shown: the red minus on the card's disc. Its 48 dp target overhangs the
 * row's end padding by its slack, so the disc's edge sits on the card's grid.
 */
@Composable
internal fun RvRemoveButton(description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .rvTouchOverhang(start = false)
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
 * A row's start in a list with remove controls: with [remove] in style A (shown in edit mode) the remove disc, then the
 * row's own [leading] 16 dp after its ink; the disc's 48 dp target overhangs the row's padding by its slack, so the ink
 * starts on the card's grid. A row that cannot be removed keeps the disc's place empty, so the card's text stays in one
 * column (LAYOUT-RULES.md R7). Otherwise it is [leading] alone.
 */
@Composable
internal fun RvRemovableLeading(remove: RvRowRemove?, description: String, leading: @Composable () -> Unit) {
    if (remove?.style != RvRemoveStyle.Editing) return leading()
    Row(verticalAlignment = Alignment.CenterVertically) {
        RvRemoveStart(remove, description)
        leading()
    }
}

/**
 * The start of a row in style A's edit mode, before its leading visual or, on a row with none (a settings row), its
 * text: the remove disc and the gap after its ink, or on a row that cannot be removed the disc's place left empty.
 * Nothing outside edit mode.
 */
@Composable
internal fun RvRemoveStart(remove: RvRowRemove?, description: String) {
    if (remove?.style != RvRemoveStyle.Editing) return
    val onRemove = remove.onRemove
    if (onRemove != null) {
        RvRemoveDisc(description, onRemove, Modifier.rvTouchOverhang(start = true))
        Spacer(Modifier.width(RvSpace.rowGap - RvEditGap.slack))
    } else {
        Spacer(Modifier.size(RvSize.removeDisc).testTag(RvEditPart.REMOVE_DISC))
        Spacer(Modifier.width(RvSpace.rowGap))
    }
}

/**
 * A row's trailing slot in a list with remove controls: [RvRemoveButton] when [remove] is style B, its place left empty
 * on a row that cannot be removed (so the line ends stay in one column), else none.
 */
internal fun rvRemoveTrailing(remove: RvRowRemove?, description: String): (@Composable () -> Unit)? {
    if (remove?.style != RvRemoveStyle.Trailing) return null
    val onRemove = remove.onRemove ?: return { Spacer(Modifier.width(RvSize.touch - RvEditGap.slack)) }
    return { RvRemoveButton(description, onRemove) }
}

/**
 * A row's remove control in an editable list: where it stands ([RvRemoveStyle]) and what it does; a null [onRemove]
 * marks a row the list keeps (the rules' last row), which shows no control but keeps its place.
 */
internal class RvRowRemove(val style: RvRemoveStyle, val onRemove: (() -> Unit)?)

/**
 * The last row of an editable list's card in style A's edit mode (owner 2026-11-26: the add row is the same row as the
 * rows above it): a list row of their height, insets and title, whose start is theirs too. The accent add disc stands
 * where the red remove disc stands, as large, and the leading visual's place after it stays empty, so the title starts
 * on the card's text column; nothing at its end. A tap opens the add form.
 */
@Composable
internal fun RvAddRow(label: String, onClick: () -> Unit) {
    RvRow({ RvAddStart() }, label, null, Modifier.testTag(RvEditPart.ADD), onClick = onClick)
}

/** [RvAddRow]'s start: the add disc in the remove disc's place, then the leading visual's place, empty. */
@Composable
private fun RvAddStart() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.height(RvSize.rowDisc), contentAlignment = Alignment.Center) {
            Box(Modifier.size(RvSize.removeDisc).testTag(RvEditPart.ADD_DISC).clip(CircleShape).background(Rv.colors.positive), contentAlignment = Alignment.Center) {
                RvGlyph(R.drawable.rv_sym_add, RvSize.removeGlyph, Rv.colors.card)
            }
        }
        Spacer(Modifier.width(RvSpace.rowGap))
        Spacer(Modifier.size(RvSize.rowDisc).testTag(RvEditPart.ADD_PLACE))
    }
}

/**
 * A remove disc's 48 dp target, overhanging the row's padding by its slack on the [start] or the end, so the 24 dp
 * disc's edge sits on the row's padding: it reports its width less that slack, and the leading disc's height, centred,
 * so a row's leading top stays the title's top (LAYOUT-RULES.md R9).
 */
private fun Modifier.rvTouchOverhang(start: Boolean): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val slack = RvEditGap.slack.roundToPx()
    val height = RvSize.rowDisc.roundToPx()
    layout(placeable.width - slack, height) { placeable.placeRelative(if (start) -slack else 0, (height - placeable.height) / 2) }
}

/** The slack between [RvSize.touch] and [RvSize.removeDisc] on one side. */
internal object RvEditGap {
    val slack = (RvSize.touch - RvSize.removeDisc) / 2
}

internal object RvEditPart {
    const val REMOVE = "rv-remove"
    const val ADD = "rv-add"

    /**
     * The 24 dp disc inside [REMOVE]'s 48 dp target, or its empty place on a row that cannot be removed, which the row
     * rules gate measures (LAYOUT-RULES.md R5, R16).
     */
    const val REMOVE_DISC = "rv-remove-disc"

    /** [RvAddRow]'s 24 dp add disc, in the remove disc's place, and the empty place of the leading visual after it. */
    const val ADD_DISC = "rv-add-disc"
    const val ADD_PLACE = "rv-add-place"
}
