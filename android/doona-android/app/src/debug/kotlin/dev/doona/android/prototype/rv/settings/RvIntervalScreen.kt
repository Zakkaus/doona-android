package dev.doona.android.prototype.rv.settings

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.prototype.rv.RvChevron
import dev.doona.android.prototype.rv.RvLargeTitle
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.rvRowPress
import dev.doona.android.prototype.rv.rvSystemScheme

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
internal fun RvIntervalScreen(selected: Int, onSelect: (Int) -> Unit, onBack: () -> Unit, onCustom: () -> Unit = NOT_WIRED) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_settings_update_interval)
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
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
        Spacer(Modifier.height(RvSettingsSpace.bottom))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvIntervalScreenPreview() {
    val dark = isSystemInDarkTheme()
    var hours by remember { mutableIntStateOf(RvData.UPDATE_HOURS) }
    RvTheme(rvSystemScheme(dark), dark) { RvIntervalScreen(hours, { hours = it }, onBack = {}) }
}
