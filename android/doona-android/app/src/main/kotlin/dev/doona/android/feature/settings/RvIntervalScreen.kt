package dev.doona.android.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvChevron
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSettingsRadioCard
import dev.doona.android.designsystem.rv.RvSettingsRadioRow
import dev.doona.android.designsystem.rv.RvSettingsRow
import dev.doona.android.designsystem.rv.RvSettingsSpace
import dev.doona.android.designsystem.rv.rvRowPress

/**
 * The intervals doona web offers for a subscription (`features/nodes/subscription.ts`: 1, 6, 12 and 24 hours), in
 * hours. Its 僅手動 is 設定's 自動更新 switch turned off, so the page leaves it out.
 */
internal val RV_INTERVAL_HOURS = listOf(1, 6, 12, 24)

/**
 * 更新間隔, the page 訂閱's 更新間隔 row opens, in DNS's language: the large title, then one card of radio rows, each
 * preset, and 自訂 last, which opens a field for a count of minutes or hours, as doona web's typed interval ([onCustom],
 * not wired). A [selected] value that is no preset leaves every radio empty.
 */
@Composable
internal fun RvIntervalScreen(selected: Int, onSelect: (Int) -> Unit, onBack: () -> Unit, onCustom: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_settings_update_interval)
    RvScrollPage(title, insets, onBack = onBack, end = RvPageEnd.Inset(RvSettingsSpace.bottom)) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSettingsSpace.margin)) {
            RvSettingsRadioCard {
                RV_INTERVAL_HOURS.forEach { hours ->
                    RvSettingsRadioRow(pluralStringResource(R.plurals.rv_interval_hours, hours, hours), hours == selected, { onSelect(hours) })
                }
                // As DNS's 自訂: no radio, a chevron, since it opens a field rather than choosing.
                RvSettingsRow(stringResource(R.string.rv_settings_dns_custom), Modifier.rvRowPress(Role.Button, onCustom)) { RvChevron() }
            }
        }
    }
}
