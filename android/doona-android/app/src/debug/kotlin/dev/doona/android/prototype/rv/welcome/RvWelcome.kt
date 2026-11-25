package dev.doona.android.prototype.rv.welcome

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvDots
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
 * carries the mark, the large title and one line where 活動 has its hero; then a card of four rows, what sets doona apart
 * from other Clash clients (its own core, TCP and UDP chosen apart, Score, speed), as 設定's rows without a chevron. The
 * page itself asks for nothing.
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
                Image(painterResource(R.drawable.ic_duck), contentDescription = null, Modifier.size(RvSize.emptyGlyph))
                Spacer(Modifier.height(RvSpace.rowGap))
                RvText(stringResource(R.string.rv_welcome_title), Rv.type.largeTitle, Modifier.semantics { heading() })
            }
            Spacer(Modifier.height(RvSpace.subGap))
            RvText(
                stringResource(R.string.rv_welcome_line),
                Rv.type.rowSub,
                Modifier.padding(horizontal = RvSpace.gutter + RvSpace.rowPadding),
                color = Rv.colors.textSecondary,
                align = TextAlign.Center,
                maxLines = LINE_LINES,
            )
            Spacer(Modifier.height(RvSpace.actionsToCard))
        }
        RvGutter {
            RvCard {
                Point(R.drawable.rv_sym_memory, R.string.rv_welcome_core, R.string.rv_welcome_core_sub)
                Point(R.drawable.rv_sym_account_tree, R.string.rv_welcome_split, R.string.rv_welcome_split_sub)
                Point(R.drawable.rv_sym_bolt, R.string.rv_welcome_score, R.string.rv_welcome_score_sub)
                Point(R.drawable.rv_sym_speed, R.string.rv_welcome_fast, R.string.rv_welcome_fast_sub)
            }
        }
    }
}

/**
 * One of what sets doona apart, as 設定's rows without a chevron: the glyph its subject has elsewhere (honk's chip as on
 * 關於, the template's branch for a choice split in two, Score's bolt as its policy kind, the gauge for speed).
 */
@Composable
private fun Point(@DrawableRes glyph: Int, @StringRes title: Int, @StringRes subtitle: Int) {
    RvRow({ RvIconDisc(glyph) }, stringResource(title), stringResource(subtitle))
}

/** The line under the title wraps to three lines at most. */
private const val LINE_LINES = 3
