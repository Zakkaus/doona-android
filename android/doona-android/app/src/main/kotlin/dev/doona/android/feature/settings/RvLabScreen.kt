package dev.doona.android.feature.settings

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvBannerSlot
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSettingsCard
import dev.doona.android.designsystem.rv.RvSettingsColumn
import dev.doona.android.designsystem.rv.RvSettingsSpace
import dev.doona.android.designsystem.rv.RvSettingsSwitchRow

/**
 * 實驗室 (owner, 2026-11-25 15:45: experimental options go on a page of their own in 設定), as sample s1125-lab-page
 * draws it: the large title, a card of switch rows, and a footnote that the options may change or go away. Its one item
 * is 效能模式 ([performance], turned by [onPerformance]), which the core reads when it starts (owner, 2026-11-25 15:20);
 * its subtitle gives the cost and the gain. Once the switch differs from what the running app started with, [restart]
 * is set and the library's Info banner over the card says it takes effect after a restart, with 重開 App, which runs it
 * (owner, 2026-11-26 02:35).
 */
@Composable
internal fun RvLabScreen(performance: Boolean, onPerformance: (Boolean) -> Unit, onBack: () -> Unit, restart: (() -> Unit)? = null) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_settings_lab)
    RvScrollPage(title, insets, onBack = onBack, end = RvPageEnd.Inset(RvSettingsSpace.bottom)) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvBannerSlot(restart?.let { restartNotice(it) })
        RvSettingsColumn {
            RvSettingsCard {
                RvSettingsSwitchRow(
                    stringResource(R.string.rv_lab_performance),
                    R.drawable.rv_sym_speed,
                    performance,
                    onPerformance,
                    stringResource(R.string.rv_lab_performance_sub),
                )
            }
            RvFootnote(stringResource(R.string.rv_lab_footnote))
        }
    }
}

/** The banner after 效能模式 turns: it takes effect after a restart, and 重開 App runs [onRestart]. */
@Composable
private fun restartNotice(onRestart: () -> Unit) =
    RvNotice(RvNoticeKind.Info, stringResource(R.string.rv_lab_restart_hint), null, stringResource(R.string.rv_lab_restart), onRestart)
