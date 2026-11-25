package dev.doona.android.designsystem.rv

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign

/**
 * The header row of a section that folds: a disc with [icon], the section's [title] over its [subtitle], what is left
 * ([value]) and a chevron that turns down while the section is [open]. A tap calls [onToggle] while [enabled] (a section
 * that cannot fold, as while a search lists it, answers no tap and no screen-reader expand or collapse); a long press
 * calls [onLongClick]. [busy] shows a busy bar where [value] would be. [modifier] carries the row's menu actions.
 * 節點's and a policy group's pages share it for a subscription's 訂閱 row.
 */
@Composable
internal fun RvFoldRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    value: String,
    open: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    busy: Boolean = false,
) {
    val turn by animateFloatAsState(if (open) QUARTER_TURN else 0f, Rv.motion.tween(RvMotion.MOVE_MS), label = "chevron")
    val toggle = Modifier.semantics {
        if (!enabled) return@semantics
        if (open) {
            collapse {
                onToggle()
                true
            }
        } else {
            expand {
                onToggle()
                true
            }
        }
    }
    RvRow(
        { RvIconDisc(icon) },
        title,
        subtitle,
        toggle.then(modifier),
        onClick = onToggle.takeIf { enabled },
        onLongClick = onLongClick,
        titleKeepWords = false,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Crossfade(busy, animationSpec = Rv.motion.tween(RvMotion.FADE_MS), label = "update") { updating ->
                if (updating) RvBusy(Modifier.width(RvSize.busy)) else RvText(value, Rv.type.rowValue, align = TextAlign.End)
            }
            Spacer(Modifier.width(RvSpace.trailingGap))
            RvChevron(Modifier.rotate(turn))
        }
    }
}

private const val QUARTER_TURN = 90f
