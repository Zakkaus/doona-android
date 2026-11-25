package dev.doona.android.prototype.rv.settings

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.settings.RV_DEBUG_HOURS
import dev.doona.android.feature.settings.RvLogLevel

/**
 * 日誌等級, a page of 設定, in DNS's language: the large title, then one card of the four levels as radio rows, 資訊
 * over 預設, and one footer line: 除錯 returns to 資訊 by itself after [RV_DEBUG_HOURS]. No row counts the time down
 * (the owner asked whether that state is needed; the footer says it once).
 */
@Composable
internal fun RvLogLevelScreen(level: RvLogLevel, onSelect: (RvLogLevel) -> Unit, onBack: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_settings_log_level)
    RvScrollPage(title, insets, onBack = onBack, end = RvPageEnd.Inset(RvSettingsSpace.bottom)) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSettingsSpace.margin)) {
            RvSettingsRadioCard {
                RvLogLevel.entries.forEach {
                    RvSettingsRadioRow(stringResource(it.label), it == level, { onSelect(it) }, subtitle = if (it == RvLogLevel.Info) stringResource(R.string.rv_log_default) else null)
                }
            }
            RvFootnote(pluralStringResource(R.plurals.rv_log_debug_footer, RV_DEBUG_HOURS, RV_DEBUG_HOURS), inset = RvSpace.gutter - RvSettingsSpace.margin)
        }
    }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvLogLevelScreenPreview() {
    val dark = isSystemInDarkTheme()
    var level by remember { mutableStateOf(RvLogLevel.Info) }
    RvTheme(rvSystemScheme(dark), dark) { RvLogLevelScreen(level, { level = it }, onBack = {}) }
}
