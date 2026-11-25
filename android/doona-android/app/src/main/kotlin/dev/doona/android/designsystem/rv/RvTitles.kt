package dev.doona.android.designsystem.rv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Constraints
import kotlin.math.min

/** The one chevron (measured §7): 6.7 x 12.2 dp of 2 dp stroke with round ends in a 24 dp box, in [color]. */
@Composable
internal fun RvChevron(modifier: Modifier = Modifier, color: Color = Rv.colors.control) {
    Canvas(modifier.size(RvChevronSize.box).testTag(RvRowPart.CHEVRON)) {
        val w = RvChevronSize.width.toPx()
        val h = RvChevronSize.height.toPx()
        val stroke = RvChevronSize.stroke.toPx()
        val left = (size.width - w) / 2 + stroke / 2
        val right = (size.width + w) / 2 - stroke / 2
        val top = (size.height - h) / 2 + stroke / 2
        val bottom = (size.height + h) / 2 - stroke / 2
        val path = Path().apply {
            moveTo(left, top)
            lineTo(right, size.height / 2)
            lineTo(left, bottom)
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * A card's in-card header (measured §7 `listSubheader`), for a card that is a widget on a home or overview page: 19 sp
 * medium secondary text in a 48 dp row, then an optional [tag] chip, and, only when it opens something, a
 * chevron in the header's colour; then the whole row is the button. The title keeps one line, [titleLines] lets one that
 * is a name to find (a file's path) wrap, and the row then grows past 48 dp; [keepWords] false keeps such a name's
 * breaks the platform's ([RvText]'s). A one-line title never gives up width to the tag: the tag sits after it when there
 * is room, placed as before, and moves under it when there is not (統計's narrow 已用流量 with 全部訂閱, owner s1125g).
 */
@Composable
internal fun RvCardHeader(title: String, onClick: (() -> Unit)? = null, tag: String? = null, titleLines: Int = 1, keepWords: Boolean = true) {
    val press = onClick?.let { Modifier.rvPress(RoundedCornerShape(Rv.highlightRadius), Role.Button, it) } ?: Modifier
    Layout(
        content = {
            RvText(title, Rv.type.cardTitle, Modifier.semantics { heading() }, color = Rv.colors.textSecondary, maxLines = titleLines, keepWords = keepWords)
            tag?.let { RvTag(it, Modifier.layoutId(HEADER_TAG)) }
            if (onClick != null) RvChevron(Modifier.layoutId(HEADER_CHEVRON), color = Rv.colors.textSecondary)
        },
        modifier = Modifier
            .padding(top = RvSpace.headerInset)
            .fillMaxWidth()
            .heightIn(min = RvHeaderSize.row)
            .then(press)
            .padding(horizontal = RvSpace.rowPadding),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val gap = RvSpace.subGap.roundToPx()
        val chevron = measurables.firstOrNull { it.layoutId == HEADER_CHEVRON }?.measure(loose)
        val tagBox = measurables.firstOrNull { it.layoutId == HEADER_TAG }?.measure(loose)
        val text = measurables.first { it.layoutId == null }
        val chevronWidth = chevron?.width ?: 0
        val beside = tagBox?.let { it.width + gap } ?: 0
        // A one-line title that would have to give up width to the tag keeps it; the tag goes under it.
        val under = tagBox != null && titleLines == 1 && text.maxIntrinsicWidth(Constraints.Infinity) + beside + chevronWidth > constraints.maxWidth
        val titleRoom = constraints.maxWidth - chevronWidth - if (under) 0 else beside
        val label = text.measure(loose.copy(maxWidth = titleRoom.coerceAtLeast(0)))
        if (!under) {
            // As a row: each part centred in the header's height, the tag and the chevron right after the title.
            val height = maxOf(constraints.minHeight, label.height, tagBox?.height ?: 0, chevron?.height ?: 0).coerceAtMost(constraints.maxHeight)
            layout(constraints.maxWidth, height) {
                label.place(0, Alignment.CenterVertically.align(label.height, height))
                tagBox?.place(label.width + gap, Alignment.CenterVertically.align(tagBox.height, height))
                chevron?.place(label.width + beside, Alignment.CenterVertically.align(chevron.height, height))
            }
        } else {
            val stack = label.height + RvSpace.tight.roundToPx() + tagBox!!.height
            val height = maxOf(constraints.minHeight, stack, chevron?.height ?: 0).coerceAtMost(constraints.maxHeight)
            val top = Alignment.CenterVertically.align(stack, height)
            layout(constraints.maxWidth, height) {
                label.place(0, top)
                tagBox.place(0, top + stack - tagBox.height)
                chevron?.place(maxOf(label.width, tagBox.width) + gap, Alignment.CenterVertically.align(chevron.height, height))
            }
        }
    }
}

private const val HEADER_TAG = "tag"
private const val HEADER_CHEVRON = "chevron"

/**
 * An outside section title (measured §7, §13.4), for a run of list cards on a settings or detail page: 20 sp bold, 24 dp
 * under what is above it and 12 dp above its card; with [onClick], a chevron in the title's colour, and the title opens it.
 * The [first] one under a [RvPageHeader] adds nothing above itself: the header's gap is already there.
 */
@Composable
internal fun RvSectionTitle(title: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, first: Boolean = false) {
    val press = onClick?.let { Modifier.rvPress(RoundedCornerShape(Rv.highlightRadius), Role.Button, it) } ?: Modifier
    val above = if (first) Modifier else Modifier.padding(top = RvSpace.sectionAbove)
    Row(modifier.then(above).padding(bottom = RvSpace.sectionBelow).testTag(RvPart.SECTION).then(press), verticalAlignment = Alignment.CenterVertically) {
        RvText(title, Rv.type.section, Modifier.semantics { heading() })
        if (onClick != null) RvChevron(color = Rv.colors.text)
    }
}
