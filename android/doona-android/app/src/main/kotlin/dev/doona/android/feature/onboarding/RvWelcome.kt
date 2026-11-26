package dev.doona.android.feature.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvDots
import dev.doona.android.designsystem.rv.RvDuckMark
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvHeader
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvText

/**
 * The first run: one page saying what doona does, and 新增訂閱. 活動's header, idle (the grey dot map, no place marked),
 * carries the mark, the large title and one line where 活動 has its hero; then a card of three rows, one strength each
 * (its own core, TCP/UDP each picking a node, 10 Gbps), as 設定's rows without a chevron. The page asks for nothing.
 */
@Composable
internal fun RvWelcome(onAdd: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val statusBar = with(LocalDensity.current) { insets.getTop(this).toDp() }
    RvScrollPage(
        stringResource(R.string.rv_welcome_title),
        insets,
        bottomAction = { RvPrimaryButton(stringResource(R.string.rv_add_subscription), onAdd) },
    ) { titleBlock ->
        RvHeader(null, statusBar + RvDots.focusTop, statusBar, Rv.colors.dotsIdle, null) {
            Spacer(Modifier.windowInsetsTopHeight(insets))
            Spacer(Modifier.height(RvSpace.headerTop))
            Column(titleBlock, horizontalAlignment = Alignment.CenterHorizontally) {
                RvDuckMark(RvSize.emptyGlyph)
                Spacer(Modifier.height(RvSpace.rowGap))
                RvText(stringResource(R.string.rv_welcome_title), Rv.type.largeTitle, Modifier.semantics { heading() })
            }
            Spacer(Modifier.height(RvSpace.subGap))
            RvCaption(stringResource(R.string.rv_welcome_line), lines = Int.MAX_VALUE)
            Spacer(Modifier.height(RvSpace.actionsToCard))
        }
        RvGutter {
            RvCard {
                RvRow({ RvIconDisc(R.drawable.rv_sym_memory) }, stringResource(R.string.rv_welcome_core), stringResource(R.string.rv_welcome_core_sub))
                RvRow({ RvIconDisc(R.drawable.rv_sym_call_split) }, stringResource(R.string.rv_welcome_split), stringResource(R.string.rv_welcome_split_sub))
                RvRow({ RvIconDisc(R.drawable.rv_sym_speed) }, stringResource(R.string.rv_welcome_speed), stringResource(R.string.rv_welcome_speed_sub))
            }
        }
    }
}
