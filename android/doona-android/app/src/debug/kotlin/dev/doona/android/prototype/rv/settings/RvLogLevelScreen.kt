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
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme

/** How long 除錯 stays before the core logs at 資訊 again; the owner sets the time (brief: say 1 hour). */
internal const val RV_DEBUG_HOURS = 1

/** The sample's minutes left of 除錯 when the page opens with it on. */
internal const val RV_SAMPLE_DEBUG_LEFT = 52

/**
 * 日誌等級, a page of 設定, in DNS's language: the large title, then one card of the four levels as radio rows, 資訊
 * over 預設; while 除錯 is on, its row says how many minutes are left ([debugLeft]) before it returns to 資訊 by itself.
 * A footer says it does so after [RV_DEBUG_HOURS] and what it is for.
 */
@Composable
internal fun RvLogLevelScreen(level: RvLogLevel, debugLeft: Int, onSelect: (RvLogLevel) -> Unit, onBack: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_settings_log_level)
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSettingsSpace.margin)) {
            RvSettingsRadioCard {
                RvLogLevel.entries.forEach {
                    RvSettingsRadioRow(stringResource(it.label), it == level, { onSelect(it) }, subtitle = levelLine(it, level, debugLeft))
                }
            }
            RvFootnote(pluralStringResource(R.plurals.rv_log_debug_footer, RV_DEBUG_HOURS, RV_DEBUG_HOURS), inset = RvSpace.gutter - RvSettingsSpace.margin)
        }
        Spacer(Modifier.height(RvSettingsSpace.bottom))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

/** A level's second line: 資訊 is the default; 除錯, while it is on, counts down to 資訊. */
@Composable
private fun levelLine(row: RvLogLevel, level: RvLogLevel, debugLeft: Int): String? = when {
    row == RvLogLevel.Info -> stringResource(R.string.rv_log_default)
    row == RvLogLevel.Debug && level == RvLogLevel.Debug -> pluralStringResource(R.plurals.rv_log_debug_left, debugLeft, debugLeft)
    else -> null
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvLogLevelScreenPreview() {
    val dark = isSystemInDarkTheme()
    var level by remember { mutableStateOf(RvLogLevel.Info) }
    RvTheme(rvSystemScheme(dark), dark) { RvLogLevelScreen(level, RV_SAMPLE_DEBUG_LEFT, { level = it }, onBack = {}) }
}
