package dev.doona.android.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSettingsCard
import dev.doona.android.designsystem.rv.RvSettingsSpace
import dev.doona.android.designsystem.rv.RvSettingsSwitchRow
import dev.doona.android.designsystem.rv.RvSpace

/**
 * 實驗室 (owner, 2026-11-25 15:45: experimental options go on a page of their own in 設定), as sample s1125-lab-page
 * draws it: the large title, a card of switch rows, and a footnote that the options may change or go away. Its one item
 * is 效能模式 ([performance], turned by [onPerformance]), which the core reads when it starts, so its subtitle says it
 * takes effect the next time the app opens (owner, 2026-11-25 15:20).
 */
@Composable
internal fun RvLabScreen(performance: Boolean, onPerformance: (Boolean) -> Unit, onBack: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_settings_lab)
    RvScrollPage(title, insets, onBack = onBack, end = RvPageEnd.Inset(RvSettingsSpace.bottom)) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSettingsSpace.margin)) {
            RvSettingsCard {
                RvSettingsSwitchRow(
                    stringResource(R.string.rv_lab_performance),
                    R.drawable.rv_sym_speed,
                    performance,
                    onPerformance,
                    stringResource(R.string.rv_lab_performance_sub),
                )
            }
            RvFootnote(stringResource(R.string.rv_lab_footnote), inset = RvSpace.none)
        }
    }
}
